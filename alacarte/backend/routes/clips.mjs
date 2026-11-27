import express from 'express'

import {
  artistClips,
  cancelClipJob,
  clearFinishedClipJobs,
  enqueueClip,
  listClipJobs,
  searchClips,
} from '../lib/clips.mjs'
import { isMissingCatalogResource, sendAppleError } from '../lib/appleErrors.mjs'

// Onglet Clips (perso)
export const clipsRouter = express.Router()

function fail(res, err) {
  if (err?.statusCode) return res.status(err.statusCode).json({ error: err.message })
  return sendAppleError(res, err, isMissingCatalogResource(err) ? 404 : 502)
}

clipsRouter.get('/search', async (req, res) => {
  const term = String(req.query.q || '').trim()
  if (!term) return res.json({ clips: [], artists: [] })
  try {
    res.json(await searchClips(term))
  } catch (err) {
    fail(res, err)
  }
})

clipsRouter.get('/artist/:id', async (req, res) => {
  try {
    res.json(await artistClips(req.params.id))
  } catch (err) {
    fail(res, err)
  }
})

clipsRouter.get('/jobs', (_req, res) => {
  res.json({ jobs: listClipJobs() })
})

clipsRouter.post('/download', async (req, res) => {
  try {
    res.json(await enqueueClip(req.body?.id))
  } catch (err) {
    fail(res, err)
  }
})

clipsRouter.post('/jobs/:id/cancel', (req, res) => {
  res.json({ ok: cancelClipJob(req.params.id) })
})

clipsRouter.post('/jobs/clear', (_req, res) => {
  clearFinishedClipJobs()
  res.json({ ok: true })
})
