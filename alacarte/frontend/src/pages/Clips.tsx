import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useTranslation } from 'react-i18next'

import { api, type Clip, type ClipArtist, type ClipJob } from '../api/client'
import { Badge } from '../components/Badge'
import { Button } from '../components/Button'
import { Input } from '../components/Input'
import { PageTitle } from '../components/PageTitle'
import { ProgressBar } from '../components/ProgressBar'
import { Dots } from '../components/Dots'
import { cx } from '../lib/cx'

// Onglet Clips (perso) : chercher un clip ou un artiste, voir tous les clips
// d'un artiste, et les telecharger dans Mes Clips/<Artiste>/<Titre>.mp4.

function duration(ms: number | null) {
  if (!ms) return ''
  const s = Math.round(ms / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

const active = (j?: ClipJob | null) => !!j && (j.status === 'queued' || j.status === 'running')

export function ClipsPage() {
  const { t } = useTranslation()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const artistId = params.get('artist')
  const [input, setInput] = useState(q)
  const [clips, setClips] = useState<Clip[]>([])
  const [artists, setArtists] = useState<ClipArtist[]>([])
  const [artistName, setArtistName] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [jobs, setJobs] = useState<ClipJob[]>([])
  const [requested, setRequested] = useState<Set<string>>(new Set())
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => setInput(q), [q])

  const refreshJobs = useCallback(() => {
    api.clipsJobs().then((r) => setJobs(r.jobs)).catch(() => {})
  }, [])

  useEffect(() => {
    refreshJobs()
  }, [refreshJobs])

  const anyActive = jobs.some(active)
  useEffect(() => {
    const timer = setInterval(refreshJobs, anyActive ? 2000 : 15000)
    return () => clearInterval(timer)
  }, [anyActive, refreshJobs])

  // Charge la liste : les clips d'un artiste, ou une recherche.
  useEffect(() => {
    let cancelled = false
    setError(null)
    if (artistId) {
      setLoading(true)
      api
        .clipsArtist(artistId)
        .then((r) => {
          if (cancelled) return
          setClips(r.clips)
          setArtists([])
          setArtistName(r.artistName)
        })
        .catch((err) => !cancelled && setError(err?.message || 'Erreur'))
        .finally(() => !cancelled && setLoading(false))
    } else if (q.trim()) {
      setLoading(true)
      api
        .clipsSearch(q.trim())
        .then((r) => {
          if (cancelled) return
          setClips(r.clips)
          setArtists(r.artists)
          setArtistName(null)
        })
        .catch((err) => !cancelled && setError(err?.message || 'Erreur'))
        .finally(() => !cancelled && setLoading(false))
    } else {
      setClips([])
      setArtists([])
      setArtistName(null)
    }
    return () => {
      cancelled = true
    }
  }, [q, artistId])

  const typeSearch = (value: string) => {
    setInput(value)
    if (debounce.current) clearTimeout(debounce.current)
    debounce.current = setTimeout(() => {
      const next = new URLSearchParams()
      if (value.trim()) next.set('q', value)
      setParams(next, { replace: true })
    }, 400)
  }

  const openArtist = (id: string) => {
    const next = new URLSearchParams()
    next.set('artist', id)
    if (q) next.set('q', q)
    setParams(next)
  }

  const backToSearch = () => {
    const next = new URLSearchParams()
    if (q) next.set('q', q)
    setParams(next)
  }

  const jobByClip = useMemo(() => {
    const m = new Map<string, ClipJob>()
    for (const j of [...jobs].sort((a, b) => a.createdAt - b.createdAt)) m.set(j.clipId, j)
    return m
  }, [jobs])

  const download = async (clip: Clip) => {
    setRequested((s) => new Set(s).add(clip.id))
    try {
      await api.clipsDownload(clip.id)
    } catch (err: any) {
      setError(`${clip.name} : ${err?.message || 'Erreur'}`)
    }
    refreshJobs()
  }

  const downloadable = clips.filter((c) => {
    const j = jobByClip.get(c.id)
    return !c.inLibrary && !active(j) && j?.status !== 'done'
  })

  const downloadAll = async () => {
    for (const c of downloadable) await download(c)
  }

  const finished = jobs.filter((j) => !active(j))

  return (
    <div className="space-y-6 pb-24">
      <PageTitle
        sub={<span className="text-sm text-quiet">{t('clips.subtitle')}</span>}
      >
        {t('nav.clips')}
      </PageTitle>

      <Input
        value={input}
        onChange={(e) => typeSearch(e.target.value)}
        placeholder={t('clips.searchPlaceholder')}
        autoFocus
      />

      {jobs.length > 0 && (
        <section className="space-y-3 rounded-card bg-surface p-4">
          <div className="flex items-center justify-between gap-3">
            <div className="text-sm font-medium">{t('clips.downloads')}</div>
            {finished.length > 0 && (
              <Button
                variant="ghost"
                onClick={() => api.clipsClear().then(refreshJobs).catch(() => {})}
              >
                {t('clips.clearFinished')}
              </Button>
            )}
          </div>
          <ul className="space-y-3">
            {jobs.map((j) => (
              <li key={j.id} className="flex items-center gap-3">
                {j.artwork ? (
                  <img src={j.artwork} alt="" className="aspect-video w-20 shrink-0 rounded object-cover" />
                ) : (
                  <div className="aspect-video w-20 shrink-0 rounded bg-[var(--field)]" />
                )}
                <div className="min-w-0 flex-1 space-y-1">
                  <div className="truncate text-sm">
                    {j.name} <span className="text-quiet">· {j.artistName}</span>
                  </div>
                  {active(j) ? (
                    <ProgressBar value={j.progress} label={`${j.message}${j.status === 'running' ? ` · ${j.progress} %` : ''}`} />
                  ) : (
                    <Badge variant={j.status === 'done' ? 'ok' : 'bad'}>
                      {j.status === 'done' ? t('clips.inLibrary') : j.error || j.message}
                    </Badge>
                  )}
                </div>
                {active(j) && (
                  <Button
                    variant="ghost"
                    className="!text-rose-500"
                    onClick={() => api.clipsCancel(j.id).then(refreshJobs).catch(() => {})}
                  >
                    {t('clips.cancel')}
                  </Button>
                )}
              </li>
            ))}
          </ul>
        </section>
      )}

      {error && <Badge variant="bad">{error}</Badge>}

      {artistId && (
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-3">
            <Button variant="ghost" onClick={backToSearch}>
              ← {t('clips.back')}
            </Button>
            <div className="text-lg font-semibold">{artistName || ''}</div>
            {!loading && <span className="text-sm text-quiet">{t('clips.count', { count: clips.length })}</span>}
          </div>
          {downloadable.length > 0 && (
            <Button variant="primary" onClick={downloadAll}>
              {t('clips.downloadAll', { count: downloadable.length })}
            </Button>
          )}
        </div>
      )}

      {!artistId && artists.length > 0 && (
        <div className="flex flex-wrap gap-2">
          {artists.map((a) => (
            <button
              key={a.id}
              type="button"
              onClick={() => openArtist(a.id)}
              className="flex items-center gap-2 rounded-full bg-surface py-1.5 pl-1.5 pr-4 text-sm hover:bg-[var(--hover)]"
            >
              {a.artwork ? (
                <img src={a.artwork} alt="" className="h-8 w-8 rounded-full object-cover" />
              ) : (
                <span className="h-8 w-8 rounded-full bg-[var(--field)]" />
              )}
              {a.name}
            </button>
          ))}
        </div>
      )}

      {loading ? (
        <div className="grid place-items-center py-16">
          <Dots />
        </div>
      ) : clips.length > 0 ? (
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
          {clips.map((c) => {
            const j = jobByClip.get(c.id) || c.job
            const done = c.inLibrary || j?.status === 'done'
            const busy = active(j) || (requested.has(c.id) && !j)
            return (
              <div key={c.id} className="flex min-w-0 flex-col gap-2">
                <div className="plate relative aspect-video w-full overflow-hidden rounded-card">
                  {c.artwork && <img src={c.artwork} alt="" loading="lazy" className="h-full w-full object-cover" />}
                  <div className="absolute bottom-2 right-2 flex gap-1">
                    {c.has4K && <span className="rounded bg-black/70 px-1.5 py-0.5 text-[11px] font-semibold text-white">4K</span>}
                    {c.hasHDR && <span className="rounded bg-black/70 px-1.5 py-0.5 text-[11px] font-semibold text-white">HDR</span>}
                    {c.durationMs && (
                      <span className="rounded bg-black/70 px-1.5 py-0.5 text-[11px] text-white tabular-nums">{duration(c.durationMs)}</span>
                    )}
                  </div>
                </div>
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="truncate text-sm font-medium" title={c.name}>
                      {c.name}
                      {c.contentRating === 'explicit' && <span className="ml-1.5 text-xs text-quiet">🅴</span>}
                    </div>
                    <div className="truncate text-xs text-quiet">
                      {c.artistId && !artistId ? (
                        <button type="button" className="hover:underline" onClick={() => openArtist(c.artistId!)}>
                          {c.artistName}
                        </button>
                      ) : (
                        c.artistName
                      )}
                      {c.year ? ` · ${c.year}` : ''}
                    </div>
                  </div>
                  <Button
                    className={cx('shrink-0', done && '!opacity-60')}
                    disabled={done || busy}
                    onClick={() => download(c)}
                  >
                    {done
                      ? t('clips.inLibrary')
                      : busy
                        ? j?.status === 'running'
                          ? `${j.progress} %`
                          : t('clips.queued')
                        : t('clips.download')}
                  </Button>
                </div>
              </div>
            )
          })}
        </div>
      ) : (
        (q || artistId) && !error && (
          <div className="py-12 text-center text-sm text-quiet">{t('clips.nothing')}</div>
        )
      )}
    </div>
  )
}
