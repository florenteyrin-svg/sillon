import crypto from 'node:crypto'
import fs from 'node:fs'
import fsp from 'node:fs/promises'
import path from 'node:path'

import { readAppleCreds, readSettings } from './settingsStore.mjs'
import { getArtistMusicVideos, getMusicVideo, searchCatalog } from './appleApi.mjs'
import { spawnAmdp, writeAmdpConfig } from './amdpRunner.mjs'
import { fitFileName, sanitizeSegment } from './libraryMatchKey.mjs'

// Clips (perso) : telechargement des clips Apple Music dans
// <musique>/Mes Clips/<Artiste>/<Titre>.mp4, avec la vignette <Titre>.jpg a
// cote (Jellyfin la prend comme image). Une file a part de celle des albums,
// un clip a la fois. amdp a besoin de mp4decrypt (installe dans le Dockerfile)
// et du media-user-token.

const MUSIC_ROOT = process.env.AMDL_MUSIC_PATH || '/music'
export const CLIPS_FOLDER = process.env.AMDL_CLIPS_FOLDER || 'Mes Clips'
// Sur le meme disque que la musique : un clip 4K fait vite 1 Go.
const STAGING_ROOT = path.join(MUSIC_ROOT, '.amdl-tmp')
const MAX_JOBS_KEPT = 60
const STALL_MS = 10 * 60 * 1000

export function clipsRoot() {
  return path.join(MUSIC_ROOT, CLIPS_FOLDER)
}

function artworkFrom(attrs, maxWidth = 1920) {
  const a = attrs?.artwork
  if (!a?.url) return null
  let w = Number(a.width) || 1280
  let h = Number(a.height) || 720
  if (w > maxWidth) {
    h = Math.round((h * maxWidth) / w)
    w = maxWidth
  }
  return a.url.replace('{w}', String(w)).replace('{h}', String(h)).replace('{f}', 'jpg')
}

export function clipPaths(artistName, name) {
  const dir = path.join(clipsRoot(), sanitizeSegment(artistName || 'Artiste inconnu'))
  const base = fitFileName(`${sanitizeSegment(name || 'Clip')}.mp4`).replace(/\.mp4$/, '')
  return { dir, video: path.join(dir, `${base}.mp4`), thumb: path.join(dir, `${base}.jpg`) }
}

export function normalizeMusicVideo(raw) {
  const at = raw?.attributes || {}
  const artistName = at.artistName || ''
  const name = at.name || ''
  return {
    id: String(raw?.id || ''),
    name,
    artistName,
    artistId: raw?.relationships?.artists?.data?.[0]?.id || null,
    albumName: at.albumName || null,
    durationMs: at.durationInMillis || null,
    releaseDate: at.releaseDate || null,
    year: at.releaseDate ? String(at.releaseDate).slice(0, 4) : null,
    has4K: Boolean(at.has4K),
    hasHDR: Boolean(at.hasHDR),
    contentRating: at.contentRating || null,
    artwork: artworkFrom(at, 640),
    artworkLarge: artworkFrom(at, 1920),
    url: at.url || null,
    inLibrary: Boolean(name) && fs.existsSync(clipPaths(artistName, name).video),
  }
}

function withJobState(clip) {
  const job = [...state.jobs.values()].find(
    (j) => j.clipId === clip.id && (j.status === 'queued' || j.status === 'running'),
  )
  return job ? { ...clip, job: publicJob(job) } : clip
}

// --- Recherche ------------------------------------------------------------

export async function searchClips(term) {
  const settings = await readSettings()
  const json = await searchCatalog({
    storefront: settings.storefront || 'us',
    term,
    types: 'music-videos,artists',
    limit: 25,
    language: settings.language || 'en-US',
  })
  const clips = (json?.results?.['music-videos']?.data || []).map(normalizeMusicVideo).map(withJobState)
  const artists = (json?.results?.artists?.data || []).slice(0, 8).map((a) => ({
    id: String(a.id),
    name: a.attributes?.name || '',
    artwork: a.attributes?.artwork?.url
      ? a.attributes.artwork.url.replace('{w}', '300').replace('{h}', '300').replace('{f}', 'jpg')
      : null,
  }))
  return { clips, artists }
}

export async function artistClips(artistId) {
  const settings = await readSettings()
  const storefront = settings.storefront || 'us'
  const language = settings.language || 'en-US'
  const out = []
  for (let offset = 0, page = 0; page < 5; page += 1) {
    const json = await getArtistMusicVideos({ storefront, id: artistId, language, offset })
    const data = json?.data || []
    out.push(...data)
    if (!json?.next || data.length === 0) break
    offset += data.length
  }
  const clips = out.map(normalizeMusicVideo).map(withJobState)
  clips.sort((a, b) => String(b.releaseDate || '').localeCompare(String(a.releaseDate || '')))
  return { artistName: clips[0]?.artistName || null, clips }
}

// --- File de telechargement ---------------------------------------------

const state = {
  jobs: new Map(), // id -> job
  queue: [],
  running: null,
}

function publicJob(j) {
  const { abort, ...rest } = j
  return rest
}

export function listClipJobs() {
  return [...state.jobs.values()].sort((a, b) => b.createdAt - a.createdAt).map(publicJob)
}

function update(job, patch) {
  Object.assign(job, patch, { updatedAt: Date.now() })
}

function prune() {
  const done = [...state.jobs.values()]
    .filter((j) => j.status === 'done' || j.status === 'failed')
    .sort((a, b) => b.createdAt - a.createdAt)
  for (const j of done.slice(MAX_JOBS_KEPT)) state.jobs.delete(j.id)
}

export async function enqueueClip(clipId) {
  const id = String(clipId || '').trim()
  if (!/^\d+$/.test(id)) {
    const e = new Error('identifiant de clip invalide')
    e.statusCode = 400
    throw e
  }
  for (const j of state.jobs.values()) {
    if (j.clipId === id && (j.status === 'queued' || j.status === 'running')) return publicJob(j)
  }
  const creds = await readAppleCreds()
  if (!creds.mediaUserToken) {
    const e = new Error('Les clips demandent un media-user-token (Réglages)')
    e.statusCode = 400
    throw e
  }
  const settings = await readSettings()
  const storefront = settings.storefront || 'us'
  const raw = await getMusicVideo({ storefront, id, language: settings.language || 'en-US' })
  const clip = normalizeMusicVideo(raw?.data?.[0])
  if (!clip.id) {
    const e = new Error('clip introuvable')
    e.statusCode = 404
    throw e
  }
  if (clip.inLibrary) {
    const e = new Error('Déjà dans Mes Clips')
    e.statusCode = 409
    throw e
  }
  const job = {
    id: crypto.randomUUID(),
    clipId: id,
    name: clip.name,
    artistName: clip.artistName,
    artwork: clip.artwork,
    artworkLarge: clip.artworkLarge,
    storefront,
    status: 'queued',
    progress: 0,
    message: 'En attente',
    error: null,
    file: null,
    createdAt: Date.now(),
    updatedAt: Date.now(),
  }
  state.jobs.set(job.id, job)
  state.queue.push(job.id)
  prune()
  setImmediate(tick)
  return publicJob(job)
}

export function cancelClipJob(jobId) {
  const job = state.jobs.get(jobId)
  if (!job) return false
  if (job.status === 'queued') {
    state.queue = state.queue.filter((x) => x !== jobId)
    update(job, { status: 'failed', error: 'Annulé', message: 'Annulé' })
    return true
  }
  if (job.status === 'running') {
    job.cancelled = true
    job.abort?.abort()
    return true
  }
  return false
}

export function clearFinishedClipJobs() {
  for (const j of [...state.jobs.values()]) {
    if (j.status === 'done' || j.status === 'failed') state.jobs.delete(j.id)
  }
}

function tick() {
  if (state.running) return
  const nextId = state.queue.shift()
  if (!nextId) return
  const job = state.jobs.get(nextId)
  if (!job || job.status !== 'queued') return setImmediate(tick)
  state.running = job.id
  runClip(job)
    .catch((err) => {
      console.error(`[clips] ${job.artistName} - ${job.name}:`, err.message)
      update(job, {
        status: 'failed',
        error: job.cancelled ? 'Annulé' : err.message,
        message: job.cancelled ? 'Annulé' : `Échec : ${err.message}`,
      })
    })
    .finally(() => {
      delete job.abort
      state.running = null
      setImmediate(tick)
    })
}

async function findVideos(dir, out = []) {
  const entries = await fsp.readdir(dir, { withFileTypes: true }).catch(() => [])
  for (const e of entries) {
    const abs = path.join(dir, e.name)
    if (e.isDirectory()) await findVideos(abs, out)
    else if (/\.(mp4|m4v)$/i.test(e.name)) {
      const st = await fsp.stat(abs).catch(() => null)
      if (st) out.push({ abs, size: st.size })
    }
  }
  return out
}

async function moveFile(from, to) {
  try {
    await fsp.rename(from, to)
  } catch (err) {
    if (err.code !== 'EXDEV') throw err
    await fsp.copyFile(from, to)
    await fsp.rm(from, { force: true })
  }
}

async function saveThumb(url, dest) {
  if (!url) return
  try {
    const res = await fetch(url, { signal: AbortSignal.timeout(30_000) })
    if (!res.ok) return
    await fsp.writeFile(dest, Buffer.from(await res.arrayBuffer()))
  } catch {
    /* la vignette est un bonus */
  }
}

async function runClip(job) {
  update(job, { status: 'running', message: 'Préparation', progress: 0 })
  const settings = await readSettings()
  const creds = await readAppleCreds()
  const staging = path.join(STAGING_ROOT, `clip-${job.id}`)
  await fsp.mkdir(staging, { recursive: true })
  const ctl = new AbortController()
  job.abort = ctl
  try {
    await writeAmdpConfig({ settings, mediaUserToken: creds.mediaUserToken, stagingRoot: staging })
    const url = `https://music.apple.com/${encodeURIComponent(job.storefront)}/music-video/_/${encodeURIComponent(job.clipId)}`
    update(job, { message: 'Téléchargement depuis Apple Music' })

    const tail = []
    let lastLineAt = Date.now()
    const watchdog = setInterval(() => {
      if (Date.now() - lastLineAt > STALL_MS) {
        job.stalled = true
        ctl.abort()
      }
    }, 15_000)
    let result
    try {
      const { waitExit } = spawnAmdp({
        args: [url],
        cwd: staging,
        signal: ctl.signal,
        onLine: ({ line }) => {
          lastLineAt = Date.now()
          tail.push(line)
          if (tail.length > 30) tail.shift()
          const pct = /(\d{1,3}(?:\.\d+)?)\s?%/.exec(line)
          if (pct) {
            const p = Math.max(0, Math.min(99, Math.round(Number(pct[1]))))
            if (p !== job.progress) update(job, { progress: p })
          }
          if (/decrypt/i.test(line)) update(job, { message: 'Déchiffrement' })
          else if (/mux|MP4Box/i.test(line)) update(job, { message: 'Assemblage' })
        },
      })
      result = await waitExit
    } catch (err) {
      if (err.name === 'AbortError' || ctl.signal.aborted) {
        throw new Error(job.stalled ? 'bloqué (aucune sortie depuis 10 min)' : 'Annulé')
      }
      throw err
    } finally {
      clearInterval(watchdog)
    }
    if (job.cancelled) throw new Error('Annulé')

    const videos = (await findVideos(staging)).sort((a, b) => b.size - a.size)
    if (result?.code !== 0 || videos.length === 0) {
      const hint = tail.filter((l) => /error|fail|not found|mp4decrypt|token|unavailable/i.test(l)).slice(-2).join(' | ')
      console.error(`[clips] amdp exit ${result?.code} :\n${tail.join('\n')}`)
      throw new Error(hint || `aucun fichier vidéo (code ${result?.code})`)
    }

    update(job, { message: 'Rangement dans Mes Clips', progress: 99 })
    const dest = clipPaths(job.artistName, job.name)
    await fsp.mkdir(dest.dir, { recursive: true, mode: 0o775 })
    await moveFile(videos[0].abs, dest.video)
    await fsp.chmod(dest.video, 0o664).catch(() => {})
    await saveThumb(job.artworkLarge, dest.thumb)
    console.log(`[clips] ajouté : ${path.relative(MUSIC_ROOT, dest.video)}`)
    update(job, {
      status: 'done',
      progress: 100,
      message: 'Dans Mes Clips',
      file: path.relative(MUSIC_ROOT, dest.video),
    })
  } finally {
    await fsp.rm(staging, { recursive: true, force: true }).catch(() => {})
  }
}
