import express from 'express'

import { sendAppleError } from '../lib/appleErrors.mjs'
import { getAlbum, normalizeAlbum } from '../lib/appleApi.mjs'
import { readSettings } from '../lib/settingsStore.mjs'
import { squareVideoUrlFromAlbum } from '../lib/animatedArtwork.mjs'

export const albumRouter = express.Router()

albumRouter.get('/:id', async (req, res) => {
  try {
    const id = String(req.params.id || '').trim()
    if (!id) return res.status(400).json({ error: 'id required' })
    const settings = await readSettings()
    const storefront = String(
      req.query.storefront || settings.storefront || 'us',
    )
    const language = settings.language || 'en-US'
    const raw = await getAlbum({ storefront, id, language })
    const album = normalizeAlbum(raw?.data?.[0])
    if (!album) return res.status(404).json({ error: 'album not found' })
    // Sillon : la pochette animée d'Apple (playlist HLS), lue directement par l'appli
    album.animatedVideo = squareVideoUrlFromAlbum(raw?.data?.[0])
    res.json({ album, storefront })
  } catch (err) {
    sendAppleError(res, err)
  }
})
