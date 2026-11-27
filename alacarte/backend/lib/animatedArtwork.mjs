import fsp from 'node:fs/promises'
import path from 'node:path'
import { spawn } from 'node:child_process'

import { emitEvent } from './eventBus.mjs'
import { readAudioIdentityTags, readAudioMetaTags } from './audioTags.mjs'
import { readSettings } from './settingsStore.mjs'
import { getAlbum, getAlbumsByUpc, searchCatalog } from './appleApi.mjs'
import { withAppleRetry } from './appleWait.mjs'
import { normalizeForMatchKey } from './libraryMatchKey.mjs'
import { DISCOVERY_FOLDER, PLAYLISTS_FOLDER } from './folderLayout.mjs'

// Pochettes animees (perso) : quand Apple propose une pochette animee pour un
// album, elle est enregistree dans <dossier de l'album>/cover/animated.mp4
// (video muette en boucle, H.264 si possible). Le dossier cover contient un
// fichier .ignore pour que Jellyfin ne le scanne pas.
//
// - apres chaque telechargement (album, titre, completion d'album)
// - pour la bibliotheque existante : bouton dans Reglages > Maintenance
//
// AMDL_ANIMATED_ARTWORK=0 desactive tout.

const MUSIC_ROOT = process.env.AMDL_MUSIC_PATH || '/music'
const CONFIG_DIR = process.env.AMDL_CONFIG_DIR || '/config'
const MISSES_FILE = path.join(CONFIG_DIR, 'animated-artwork-misses.json')
const MISS_TTL_MS = 30 * 24 * 60 * 60 * 1000
const FFMPEG_TIMEOUT_MS = 5 * 60 * 1000
const PROGRESS_MIN_INTERVAL_MS = 400

export const COVER_FOLDER = 'cover'
export const ANIMATED_FILE = 'animated.mp4'

export function animatedArtworkEnabled() {
  return !/^(0|false|no|off)$/i.test(String(process.env.AMDL_ANIMATED_ARTWORK ?? '1'))
}

export function animatedArtworkPath(albumDir) {
  return path.join(albumDir, COVER_FOLDER, ANIMATED_FILE)
}

async function exists(p) {
  return fsp.stat(p).then(() => true, () => false)
}

// URL de la playlist HLS de la pochette animee carree, ou null.
export function squareVideoUrlFromAlbum(rawAlbum) {
  const ev = rawAlbum?.attributes?.editorialVideo
  if (!ev) return null
  return (
    ev.motionDetailSquare?.video ||
    ev.motionSquareVideo1x1?.video ||
    null
  )
}

// Choisit la meilleure variante de la playlist HLS : H.264 en priorite (lu
// partout), puis la plus haute definition / le plus gros debit.
export function pickVariant(masterText, masterUrl) {
  const lines = String(masterText || '').split(/\r?\n/)
  const variants = []
  for (let i = 0; i < lines.length; i += 1) {
    const line = lines[i].trim()
    if (!line.startsWith('#EXT-X-STREAM-INF:')) continue
    const attrs = line.slice('#EXT-X-STREAM-INF:'.length)
    let uri = null
    for (let j = i + 1; j < lines.length; j += 1) {
      const next = lines[j].trim()
      if (!next) continue
      if (!next.startsWith('#')) uri = next
      break
    }
    if (!uri) continue
    const bandwidth = Number(/(?:^|,)BANDWIDTH=(\d+)/.exec(attrs)?.[1] || 0)
    const codecs = /CODECS="([^"]*)"/.exec(attrs)?.[1] || ''
    const res = /RESOLUTION=(\d+)x(\d+)/.exec(attrs)
    const pixels = res ? Number(res[1]) * Number(res[2]) : 0
    variants.push({
      url: new URL(uri, masterUrl).toString(),
      bandwidth,
      pixels,
      avc: /avc1/i.test(codecs),
    })
  }
  if (variants.length === 0) return masterUrl // deja une playlist simple
  variants.sort(
    (a, b) =>
      Number(b.avc) - Number(a.avc) || b.pixels - a.pixels || b.bandwidth - a.bandwidth,
  )
  return variants[0].url
}

function runFfmpeg(args, timeoutMs = FFMPEG_TIMEOUT_MS) {
  return new Promise((resolve, reject) => {
    const proc = spawn('ffmpeg', args, { stdio: ['ignore', 'ignore', 'pipe'] })
    let stderr = ''
    let timedOut = false
    const timer = setTimeout(() => {
      timedOut = true
      proc.kill('SIGKILL')
    }, timeoutMs)
    proc.stderr.on('data', (c) => {
      stderr = (stderr + c.toString()).slice(-2000)
    })
    proc.on('error', (err) => {
      clearTimeout(timer)
      reject(err)
    })
    proc.on('close', (code) => {
      clearTimeout(timer)
      if (code === 0) resolve()
      else if (timedOut) reject(new Error('ffmpeg timed out'))
      else reject(new Error(`ffmpeg exit ${code}: ${stderr.slice(-400)}`))
    })
  })
}

async function ensureCoverFolder(albumDir) {
  const dir = path.join(albumDir, COVER_FOLDER)
  await fsp.mkdir(dir, { recursive: true, mode: 0o775 })
  await fsp.writeFile(path.join(dir, '.ignore'), '', { flag: 'wx' }).catch((err) => {
    if (err?.code !== 'EEXIST') throw err
  })
  return dir
}

// Telecharge la video HLS dans albumDir/cover/animated.mp4. True si ecrite.
export async function saveAnimatedArtwork(videoUrl, albumDir) {
  const dest = animatedArtworkPath(albumDir)
  if (await exists(dest)) return false
  const res = await fetch(videoUrl, { signal: AbortSignal.timeout(30_000) })
  if (!res.ok) throw new Error(`playlist ${res.status}`)
  const variantUrl = pickVariant(await res.text(), videoUrl)
  const dir = await ensureCoverFolder(albumDir)
  const tmp = path.join(dir, `.animated-${process.pid}-${Date.now()}.mp4`)
  try {
    await runFfmpeg([
      '-nostdin', '-loglevel', 'error', '-y',
      '-i', variantUrl,
      '-map', '0:v:0', '-an', '-c', 'copy',
      '-movflags', '+faststart',
      '-f', 'mp4', tmp,
    ])
    await fsp.rename(tmp, dest)
    await fsp.chmod(dest, 0o664).catch(() => {})
    return true
  } catch (err) {
    await fsp.rm(tmp, { force: true }).catch(() => {})
    // Ne laisse pas un dossier cover vide
    const left = await fsp.readdir(dir).catch(() => [])
    if (left.every((f) => f === '.ignore')) await fsp.rm(dir, { recursive: true, force: true }).catch(() => {})
    throw err
  }
}

// Une seule video a la fois, pour ne pas ralentir les telechargements.
let chain = Promise.resolve()
function serial(fn) {
  const run = chain.then(fn, fn)
  chain = run.catch(() => {})
  return run
}

/**
 * Apres un telechargement : recupere la pochette animee de l'album Apple
 * albumId dans finalDir. Ne bloque jamais le job (erreurs juste loggees).
 */
export function queueAnimatedArtworkForJob(job, finalDir) {
  if (!animatedArtworkEnabled()) return
  if (!job?.albumId || !finalDir) return
  if (job.kind !== 'album' && job.kind !== 'song') return
  serial(async () => {
    try {
      if (await exists(animatedArtworkPath(finalDir))) return
      const settings = await readSettings().catch(() => ({}))
      const raw = await withAppleRetry(() =>
        getAlbum({
          storefront: job.storefront || settings.storefront || 'us',
          id: job.albumId,
          language: settings.language || 'en-US',
        }),
      )
      const url = squareVideoUrlFromAlbum(raw?.data?.[0])
      if (!url) return
      if (await saveAnimatedArtwork(url, finalDir)) {
        console.log(`[animated] pochette animee ajoutee : ${path.relative(MUSIC_ROOT, finalDir)}`)
      }
    } catch (err) {
      console.warn(`[animated] echec pour ${path.basename(finalDir)}: ${err.message}`)
    }
  })
}

// ---------------------------------------------------------------------------
// Bibliotheque existante (Reglages > Maintenance)
// ---------------------------------------------------------------------------

const state = {
  running: false,
  phase: 'idle',
  waitingUntil: null,
  scanned: 0,
  total: 0,
  added: 0,
  skipped: 0,
  noArtwork: 0,
  noMatch: 0,
  failed: 0,
  current: null,
  startedAt: null,
  finishedAt: null,
  stopRequested: false,
  error: null,
}

let lastEmitAt = 0

function status() {
  const { running, phase, waitingUntil, scanned, total, added, skipped, noArtwork, noMatch, failed, current, startedAt, finishedAt, stopRequested, error } = state
  return { running, phase, waitingUntil, scanned, total, added, skipped, noArtwork, noMatch, failed, current, startedAt, finishedAt, stopRequested, error }
}

function emit(force = false) {
  const now = Date.now()
  if (!force && now - lastEmitAt < PROGRESS_MIN_INTERVAL_MS) return
  lastEmitAt = now
  emitEvent('animated.backfill.progress', { ...status(), done: !state.running })
}

export function getAnimatedBackfillStatus() {
  return status()
}

export function stopAnimatedBackfill() {
  if (!state.running) return { ok: false, running: false }
  state.stopRequested = true
  emit(true)
  return { ok: true, running: true }
}

const callApple = (fn) =>
  withAppleRetry(fn, {
    shouldStop: () => state.stopRequested,
    onWait: (until) => {
      state.waitingUntil = until
      state.phase = until ? 'waiting' : 'matching'
      emit(true)
    },
  })

const AUDIO_RE = /\.(flac|m4a|mp3)$/i
const SKIP_TOP = new Set([PLAYLISTS_FOLDER, DISCOVERY_FOLDER].map((s) => s.toLowerCase()))

// Tous les dossiers qui contiennent directement des fichiers audio.
async function collectAlbumDirs(dir, out, depth = 0) {
  const entries = await fsp.readdir(dir, { withFileTypes: true }).catch(() => [])
  if (entries.some((e) => e.name === '.ignore')) return
  const audio = entries.filter((e) => e.isFile() && AUDIO_RE.test(e.name)).map((e) => e.name).sort()
  if (audio.length) out.push({ dir, audio })
  for (const e of entries) {
    if (!e.isDirectory() || e.name.startsWith('.') || e.name === COVER_FOLDER) continue
    if (depth === 0 && SKIP_TOP.has(e.name.toLowerCase())) continue
    await collectAlbumDirs(path.join(dir, e.name), out, depth + 1)
  }
}

async function readMisses() {
  try {
    return JSON.parse(await fsp.readFile(MISSES_FILE, 'utf8')) || {}
  } catch {
    return {}
  }
}

async function writeMisses(misses) {
  await fsp.writeFile(MISSES_FILE, JSON.stringify(misses), 'utf8').catch(() => {})
}

function norm(s) {
  return normalizeForMatchKey(String(s || '')).toLowerCase()
}

function nameMatchesLoose(a, b) {
  const x = norm(a)
  const y = norm(b)
  if (!x || !y) return false
  return x === y || y.startsWith(`${x} `) || x.startsWith(`${y} `)
}

function artistMatchesLoose(a, b) {
  const tokens = norm(a).split(' ').filter(Boolean)
  const pool = new Set(norm(b).split(' ').filter(Boolean))
  if (tokens.length === 0 || pool.size === 0) return false
  return tokens.every((t) => pool.has(t))
}

function mostCommon(values) {
  const counts = new Map()
  for (const v of values) if (v) counts.set(v, (counts.get(v) || 0) + 1)
  let best = null
  let n = 0
  for (const [v, c] of counts) if (c > n) { best = v; n = c }
  return best
}

// Retrouve l'album Apple d'un dossier : par UPC (tag BARCODE/UPC), sinon par
// recherche artiste + album (tags). Null si introuvable.
async function findAppleAlbumId({ dir, audio }, settings) {
  const storefront = settings.storefront || 'us'
  const language = settings.language || 'en-US'
  const sample = audio.slice(0, 3).map((f) => path.join(dir, f))

  let upc = ''
  for (const f of sample) {
    upc = (await readAudioIdentityTags(f).catch(() => ({})))?.upc || ''
    if (upc) break
  }
  if (upc) {
    const json = await callApple(() => getAlbumsByUpc({ storefront, upcs: [upc], language }))
    const id = json?.data?.[0]?.id
    if (id) return id
  }

  const metas = []
  for (const f of sample) metas.push(await readAudioMetaTags(f))
  const albumName = mostCommon(metas.map((m) => m.album))
  const artist = mostCommon(metas.map((m) => m.albumArtist || m.artist))
  if (!albumName || !artist) return null
  const json = await callApple(() =>
    searchCatalog({ storefront, term: `${artist} ${albumName}`.trim(), types: 'albums', limit: 5, language }),
  )
  const hit = (json?.results?.albums?.data || []).find(
    (raw) =>
      nameMatchesLoose(albumName, raw.attributes?.name) &&
      artistMatchesLoose(artist, raw.attributes?.artistName),
  )
  return hit?.id || null
}

export async function startAnimatedBackfill() {
  if (state.running) return status()
  if (!animatedArtworkEnabled()) {
    const err = new Error('Pochettes animées désactivées (AMDL_ANIMATED_ARTWORK=0)')
    err.statusCode = 400
    throw err
  }
  Object.assign(state, {
    running: true,
    phase: 'scanning',
    waitingUntil: null,
    scanned: 0,
    total: 0,
    added: 0,
    skipped: 0,
    noArtwork: 0,
    noMatch: 0,
    failed: 0,
    current: null,
    startedAt: Date.now(),
    finishedAt: null,
    stopRequested: false,
    error: null,
  })
  emit(true)
  runBackfill()
    .catch((err) => {
      state.error = err.message
      console.error('[animated] backfill failed:', err)
    })
    .finally(() => {
      state.running = false
      state.phase = 'done'
      state.current = null
      state.waitingUntil = null
      state.finishedAt = Date.now()
      emit(true)
    })
  return status()
}

async function runBackfill() {
  const settings = await readSettings().catch(() => ({}))
  const albums = []
  await collectAlbumDirs(MUSIC_ROOT, albums)
  state.total = albums.length
  state.phase = 'matching'
  emit(true)

  const misses = await readMisses()
  const now = Date.now()
  let dirty = false

  for (const album of albums) {
    if (state.stopRequested) break
    state.current = path.relative(MUSIC_ROOT, album.dir)
    emit()
    try {
      if (await exists(animatedArtworkPath(album.dir))) {
        state.skipped += 1
        continue
      }
      const miss = misses[album.dir]
      if (miss && now - miss.at < MISS_TTL_MS) {
        if (miss.reason === 'noMatch') state.noMatch += 1
        else state.noArtwork += 1
        continue
      }
      const id = await findAppleAlbumId(album, settings)
      if (!id) {
        state.noMatch += 1
        misses[album.dir] = { at: now, reason: 'noMatch' }
        dirty = true
        continue
      }
      const raw = await callApple(() =>
        getAlbum({ storefront: settings.storefront || 'us', id, language: settings.language || 'en-US' }),
      )
      const url = squareVideoUrlFromAlbum(raw?.data?.[0])
      if (!url) {
        state.noArtwork += 1
        misses[album.dir] = { at: now, reason: 'noArtwork' }
        dirty = true
        continue
      }
      await serial(() => saveAnimatedArtwork(url, album.dir))
      state.added += 1
      if (misses[album.dir]) {
        delete misses[album.dir]
        dirty = true
      }
    } catch (err) {
      if (state.stopRequested) break
      state.failed += 1
      console.warn(`[animated] ${state.current}: ${err.message}`)
    } finally {
      state.scanned += 1
      emit()
      if (dirty && state.scanned % 20 === 0) {
        await writeMisses(misses)
        dirty = false
      }
    }
  }
  if (dirty) await writeMisses(misses)
  console.log(
    `[animated] termine : ${state.added} ajoutee(s), ${state.skipped} deja la, ${state.noArtwork} sans pochette animee, ${state.noMatch} introuvable(s), ${state.failed} echec(s)`,
  )
}
