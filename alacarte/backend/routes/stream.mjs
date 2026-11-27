// Sillon: "play now" for catalog songs that are not in the library.
//
// The song is downloaded into the stream cache (AMDL_STREAM_CACHE, outside the music folder), never into
// the library. amdp is told to write the file as it decrypts it (max-memory-limit 0), so Sillon can send
// the audio to the phone while the file grows instead of waiting for the end.
//
// Layout of the cache, one folder per Apple song id:
//   <cache>/<id>/status.json   {"status":"running"|"done"|"failed", ...}
//   <cache>/<id>/**.m4a(.part) the audio, written by amdp
//
// Sillon reads these files on the shared volume; it also removes old folders when the cache is full.
import express from 'express'
import fsp from 'node:fs/promises'
import path from 'node:path'

import { spawnAmdp, writeAmdpConfig } from '../lib/amdpRunner.mjs'
import { readAppleCreds, readSettings } from '../lib/settingsStore.mjs'

export const STREAM_CACHE = process.env.AMDL_STREAM_CACHE || '/cache'
const MAX_PARALLEL = 2

export const streamRouter = express.Router()

const running = new Map() // id -> { startedAt }

function safeId(id) {
  return /^[0-9]{1,20}$/.test(String(id || '')) ? String(id) : null
}

async function writeStatus(dir, status) {
  const tmp = path.join(dir, 'status.json.tmp')
  await fsp.writeFile(tmp, JSON.stringify({ ...status, at: Date.now() }))
  await fsp.rename(tmp, path.join(dir, 'status.json'))
}

async function readStatus(dir) {
  try {
    return JSON.parse(await fsp.readFile(path.join(dir, 'status.json'), 'utf8'))
  } catch {
    return null
  }
}

async function start(id, albumId, storefront) {
  const dir = path.join(STREAM_CACHE, id)
  await fsp.rm(dir, { recursive: true, force: true })
  await fsp.mkdir(dir, { recursive: true })
  const settings = await readSettings()
  const creds = await readAppleCreds()
  const sf = storefront || settings.storefront || 'us'
  const url = albumId
    ? `https://music.apple.com/${encodeURIComponent(sf)}/album/_/${encodeURIComponent(albumId)}?i=${encodeURIComponent(id)}`
    : `https://music.apple.com/${encodeURIComponent(sf)}/song/_/${encodeURIComponent(id)}`
  await writeAmdpConfig({
    settings,
    mediaUserToken: creds.mediaUserToken,
    stagingRoot: dir,
    // Write while decrypting (no in-memory buffer), so playback can start on the first fragments.
    // Lyrics and covers stay off: this copy is only for listening.
    overrides: {
      'max-memory-limit': 0,
      'embed-lrc': false,
      'save-lrc-file': false,
    },
  })
  await writeStatus(dir, { status: 'running', id, albumId: albumId || null })
  const { waitExit } = spawnAmdp({ args: ['--song', url], cwd: dir, onLine: () => {} })
  waitExit
    .then(async (r) => {
      const file = await findAudio(dir)
      if (r.code === 0 && file) {
        await writeStatus(dir, { status: 'done', id, file: path.relative(dir, file) })
      } else {
        const tail = `${r.stderr || ''}\n${r.stdout || ''}`.trim().split('\n').slice(-3).join(' ')
        await writeStatus(dir, { status: 'failed', id, error: tail.slice(0, 300) || `exit ${r.code}` })
      }
    })
    .catch((err) => writeStatus(dir, { status: 'failed', id, error: err.message }).catch(() => {}))
    .finally(() => running.delete(id))
}

async function findAudio(dir) {
  const stack = [dir]
  while (stack.length) {
    const d = stack.pop()
    let entries = []
    try {
      entries = await fsp.readdir(d, { withFileTypes: true })
    } catch {
      continue
    }
    for (const e of entries) {
      const p = path.join(d, e.name)
      if (e.isDirectory()) stack.push(p)
      else if (/\.(m4a|mp4|flac)$/i.test(e.name)) return p
    }
  }
  return null
}

// POST /api/stream/song/:id  body: { albumId?, storefront? }
// Starts the download into the cache unless it is already there or running.
streamRouter.post('/song/:id', async (req, res) => {
  const id = safeId(req.params.id)
  if (!id) return res.status(400).json({ error: 'invalid song id' })
  const dir = path.join(STREAM_CACHE, id)
  const status = await readStatus(dir)
  if (running.has(id) || status?.status === 'done') {
    return res.json({ id, status: running.has(id) ? 'running' : 'done' })
  }
  if (running.size >= MAX_PARALLEL) {
    return res.status(429).json({ error: 'too many songs being prepared, try again in a moment' })
  }
  // Marked as running before the first await, so two quick requests never start it twice
  running.set(id, { startedAt: Date.now() })
  try {
    await start(id, req.body?.albumId ? String(req.body.albumId) : null, req.body?.storefront)
    res.status(202).json({ id, status: 'running' })
  } catch (err) {
    running.delete(id)
    res.status(500).json({ error: err.message })
  }
})

streamRouter.get('/song/:id', async (req, res) => {
  const id = safeId(req.params.id)
  if (!id) return res.status(400).json({ error: 'invalid song id' })
  const status = await readStatus(path.join(STREAM_CACHE, id))
  if (!status) return res.status(404).json({ error: 'not in the stream cache' })
  res.json({ ...status, status: running.has(id) ? 'running' : status.status })
})
