// Package api is Sillon's own API for its apps: live events, Apple Music search and downloads
// through à la carte, smart playlists and incremental library sync. Everything else uses the Subsonic API.
package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/florenteyrin-svg/sillon/server/internal/alacarte"
	"github.com/florenteyrin-svg/sillon/server/internal/db"
	"github.com/florenteyrin-svg/sillon/server/internal/events"
	"github.com/florenteyrin-svg/sillon/server/internal/mix"
	"github.com/florenteyrin-svg/sillon/server/internal/scanner"
	"github.com/florenteyrin-svg/sillon/server/internal/smart"
	"github.com/florenteyrin-svg/sillon/server/internal/streamcache"
	"github.com/florenteyrin-svg/sillon/server/internal/subsonic"
)

type API struct {
	DB       *db.DB
	Hub      *events.Hub
	Scanner  *scanner.Scanner
	Alacarte *alacarte.Client // nil when not configured
	Subsonic *subsonic.Server
	Cache    *streamcache.Cache
	Mix      *mix.Client // nil when the DJ worker is not configured

	polling sync.Mutex
}

func (a *API) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /api/health", a.health)
	mux.HandleFunc("GET /api/me", a.auth(a.me))
	mux.HandleFunc("GET /api/events", a.auth(a.events))
	mux.HandleFunc("GET /api/library/status", a.auth(a.status))
	mux.HandleFunc("GET /api/library/changes", a.auth(a.changes))
	mux.HandleFunc("GET /api/animated-cover/{id}", a.auth(a.animatedCover))
	mux.HandleFunc("GET /api/catalog/search", a.auth(a.catalogSearch))
	mux.HandleFunc("GET /api/catalog/album/{id}", a.auth(a.catalogAlbum))
	mux.HandleFunc("GET /api/catalog/artist/{id}", a.auth(a.catalogArtist))
	mux.HandleFunc("POST /api/catalog/download", a.auth(a.catalogDownload))
	mux.HandleFunc("GET /api/catalog/jobs", a.auth(a.catalogJobs))
	mux.HandleFunc("POST /api/catalog/play", a.auth(a.catalogPlay))
	mux.HandleFunc("GET /api/catalog/stream/{id}", a.auth(a.catalogStream))
	mux.HandleFunc("GET /api/recent", a.auth(a.recent))
	mux.HandleFunc("GET /api/mix/transition", a.auth(a.mixTransition))
	mux.HandleFunc("GET /api/mix/file/{key}", a.auth(a.mixFile))
	mux.HandleFunc("GET /api/smart-playlists", a.auth(a.smartList))
	mux.HandleFunc("POST /api/smart-playlists", a.auth(a.smartCreate))
	mux.HandleFunc("PUT /api/smart-playlists/{id}", a.auth(a.smartUpdate))
	mux.HandleFunc("POST /api/smart-playlists/preview", a.auth(a.smartPreview))
}

type authed func(w http.ResponseWriter, r *http.Request, u *db.User)

// auth accepts the same logins as the Subsonic API (API key as Bearer, Basic, or u/p/t/s parameters).
func (a *API) auth(h authed) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// JSON bodies stay unread here: only the query string carries the login
		if strings.HasPrefix(r.Header.Get("Content-Type"), "application/x-www-form-urlencoded") {
			r.ParseForm()
		} else {
			r.Form = r.URL.Query()
		}
		u, err := a.Subsonic.Authenticate(r)
		if err != nil {
			jsonError(w, http.StatusUnauthorized, err.Error())
			return
		}
		h(w, r, u)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func jsonError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": msg})
}

func (a *API) health(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{"ok": true, "version": subsonic.Version, "alacarte": a.Alacarte != nil, "dj": a.Mix != nil})
}

func (a *API) me(w http.ResponseWriter, r *http.Request, u *db.User) {
	writeJSON(w, map[string]any{"name": u.Name, "admin": u.Admin, "apiKey": u.APIKey})
}

func (a *API) status(w http.ResponseWriter, r *http.Request, u *db.User) {
	var tracks, albums, artists int
	var hires, cd int
	a.DB.QueryRow(`SELECT COUNT(*), COALESCE(SUM(quality='hires'),0), COALESCE(SUM(quality='cd'),0) FROM tracks`).Scan(&tracks, &hires, &cd)
	a.DB.QueryRow(`SELECT COUNT(*) FROM albums`).Scan(&albums)
	a.DB.QueryRow(`SELECT COUNT(*) FROM artists`).Scan(&artists)
	writeJSON(w, map[string]any{
		"scanning": a.Scanner.Scanning(), "lastScan": a.Scanner.LastScan().Unix(),
		"tracks": tracks, "albums": albums, "artists": artists, "hires": hires, "cd": cd, "lossy": tracks - hires - cd,
	})
}

// events streams live changes (Server-Sent Events): a downloaded track shows up in the app at once.
func (a *API) events(w http.ResponseWriter, r *http.Request, u *db.User) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		jsonError(w, 500, "streaming unsupported")
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.Header().Set("X-Accel-Buffering", "no") // nginx: do not buffer the stream
	ch := a.Hub.Subscribe()
	defer a.Hub.Unsubscribe(ch)
	fmt.Fprint(w, ": connected\n\n")
	flusher.Flush()
	ping := time.NewTicker(25 * time.Second)
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case b := <-ch:
			fmt.Fprintf(w, "data: %s\n\n", b)
			flusher.Flush()
		case <-ping.C:
			fmt.Fprint(w, ": ping\n\n")
			flusher.Flush()
		}
	}
}

// changes lists what was added since a time, so the app updates its local copy without reloading everything.
func (a *API) changes(w http.ResponseWriter, r *http.Request, u *db.User) {
	since, _ := strconv.ParseInt(r.Form.Get("since"), 10, 64)
	tracks, err := a.DB.QueryTracks(`WHERE t.created_at > ? ORDER BY t.created_at LIMIT 5000`, since)
	if err != nil {
		jsonError(w, 500, err.Error())
		return
	}
	ids := make([]string, 0, len(tracks))
	for _, t := range tracks {
		ids = append(ids, t.ID)
	}
	var total int
	a.DB.QueryRow(`SELECT COUNT(*) FROM tracks`).Scan(&total)
	writeJSON(w, map[string]any{"now": time.Now().Unix(), "added": ids, "total": total})
}

// animatedCover sends an album's animated cover (looping silent video), for an album or song id.
func (a *API) animatedCover(w http.ResponseWriter, r *http.Request, u *db.User) {
	full, ok := a.Subsonic.Covers.AnimatedPath(r.PathValue("id"))
	if !ok {
		jsonError(w, http.StatusNotFound, "no animated cover")
		return
	}
	w.Header().Set("Content-Type", "video/mp4")
	w.Header().Set("Cache-Control", "public, max-age=86400")
	http.ServeFile(w, r, full)
}

// ---- Apple Music catalog through à la carte ----

func (a *API) requireAlacarte(w http.ResponseWriter) bool {
	if a.Alacarte == nil {
		jsonError(w, http.StatusServiceUnavailable, "à la carte is not configured (ALACARTE_URL)")
		return false
	}
	return true
}

func alacarteError(w http.ResponseWriter, err error) {
	var ae *alacarte.Error
	if errors.As(err, &ae) {
		jsonError(w, ae.Status, ae.Message)
		return
	}
	jsonError(w, http.StatusBadGateway, err.Error())
}

func norm(s string) string {
	s = strings.ToLower(strings.TrimSpace(s))
	// "Title (feat. X)" / "Title - Single" should still match
	for _, cut := range []string{" (feat", " [feat", " - single", " - ep"} {
		if i := strings.Index(s, cut); i > 0 {
			s = s[:i]
		}
	}
	return s
}

// markOwned adds "inLibrary" (and the Sillon id) to catalog albums and songs that are already in the library.
func (a *API) markOwned(res map[string]any) {
	albums := map[string]string{} // artist|album -> id
	if rows, err := a.DB.Query(`SELECT id, artist, name FROM albums`); err == nil {
		for rows.Next() {
			var id, artist, name string
			rows.Scan(&id, &artist, &name)
			albums[norm(artist)+"|"+norm(name)] = id
		}
		rows.Close()
	}
	songs := map[string]string{} // artist|title -> id
	if rows, err := a.DB.Query(`SELECT id, artist, album_artist, title FROM tracks`); err == nil {
		for rows.Next() {
			var id, artist, albumArtist, title string
			rows.Scan(&id, &artist, &albumArtist, &title)
			songs[norm(artist)+"|"+norm(title)] = id
			songs[norm(albumArtist)+"|"+norm(title)] = id
		}
		rows.Close()
	}
	str := func(m map[string]any, keys ...string) string {
		for _, k := range keys {
			if v, ok := m[k].(string); ok && v != "" {
				return v
			}
		}
		return ""
	}
	if list, ok := res["albums"].([]any); ok {
		for _, x := range list {
			if m, ok := x.(map[string]any); ok {
				id, owned := albums[norm(str(m, "artistName", "artist"))+"|"+norm(str(m, "name", "title"))]
				m["inLibrary"] = owned
				if owned {
					m["libraryId"] = id
				}
			}
		}
	}
	if list, ok := res["songs"].([]any); ok {
		for _, x := range list {
			if m, ok := x.(map[string]any); ok {
				id, owned := songs[norm(str(m, "artistName", "artist"))+"|"+norm(str(m, "name", "title"))]
				m["inLibrary"] = owned
				if owned {
					m["libraryId"] = id
				}
			}
		}
	}
}

func (a *API) catalogSearch(w http.ResponseWriter, r *http.Request, u *db.User) {
	if !a.requireAlacarte(w) {
		return
	}
	q := strings.TrimSpace(r.Form.Get("q"))
	if q == "" {
		writeJSON(w, map[string]any{"albums": []any{}, "artists": []any{}, "songs": []any{}, "playlists": []any{}})
		return
	}
	limit, _ := strconv.Atoi(r.Form.Get("limit"))
	if limit <= 0 || limit > 50 {
		limit = 25
	}
	res, err := a.Alacarte.Search(q, limit)
	if err != nil {
		alacarteError(w, err)
		return
	}
	a.markOwned(res)
	writeJSON(w, res)
}

func (a *API) catalogAlbum(w http.ResponseWriter, r *http.Request, u *db.User) {
	if !a.requireAlacarte(w) {
		return
	}
	res, err := a.Alacarte.Album(r.PathValue("id"))
	if err != nil {
		alacarteError(w, err)
		return
	}
	if album, ok := res["album"].(map[string]any); ok {
		wrapped := map[string]any{"albums": []any{album}}
		if tracks, ok := album["tracks"].([]any); ok {
			wrapped["songs"] = tracks
		}
		a.markOwned(wrapped)
	}
	writeJSON(w, res)
}

func (a *API) catalogArtist(w http.ResponseWriter, r *http.Request, u *db.User) {
	if !a.requireAlacarte(w) {
		return
	}
	res, err := a.Alacarte.Artist(r.PathValue("id"))
	if err != nil {
		alacarteError(w, err)
		return
	}
	a.markOwned(res)
	writeJSON(w, res)
}

func (a *API) catalogDownload(w http.ResponseWriter, r *http.Request, u *db.User) {
	if !a.requireAlacarte(w) {
		return
	}
	var body struct {
		Type    string `json:"type"` // album | song | playlist
		ID      string `json:"id"`
		AlbumID string `json:"albumId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil || body.ID == "" {
		jsonError(w, 400, "type and id are required")
		return
	}
	res, err := a.Alacarte.Download(body.Type, body.ID, body.AlbumID)
	if err != nil {
		alacarteError(w, err)
		return
	}
	// The library copy replaces the listening copy
	if body.Type == "song" && a.Cache != nil && streamcache.ValidID(body.ID) {
		a.Cache.Remove(body.ID)
	}
	go a.pollJobs()
	w.WriteHeader(http.StatusAccepted)
	writeJSON(w, res)
}

func (a *API) catalogJobs(w http.ResponseWriter, r *http.Request, u *db.User) {
	if !a.requireAlacarte(w) {
		return
	}
	jobs, err := a.Alacarte.Jobs()
	if err != nil {
		alacarteError(w, err)
		return
	}
	if jobs == nil {
		jobs = []map[string]any{}
	}
	writeJSON(w, map[string]any{"jobs": jobs})
}

// catalogPlay starts listening to an Apple Music song that is not in the library. The answer comes at
// once with the address to play; the audio starts flowing a few seconds later, while it downloads.
func (a *API) catalogPlay(w http.ResponseWriter, r *http.Request, u *db.User) {
	if !a.requireAlacarte(w) {
		return
	}
	var b struct {
		ID       string  `json:"id"`
		AlbumID  string  `json:"albumId"`
		Title    string  `json:"title"`
		Artist   string  `json:"artist"`
		Album    string  `json:"album"`
		Artwork  string  `json:"artwork"`
		Duration float64 `json:"duration"`
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil || !streamcache.ValidID(b.ID) {
		jsonError(w, 400, "a catalog song id is required")
		return
	}
	a.DB.RecordCatalogPlay(u.ID, db.CatalogPlay{AppleID: b.ID, Title: b.Title, Artist: b.Artist, Album: b.Album,
		AppleAlbumID: b.AlbumID, Artwork: b.Artwork, Duration: b.Duration})
	status := "done"
	if !a.Cache.Ready(b.ID) {
		var err error
		if status, err = a.Alacarte.StartStream(b.ID, b.AlbumID); err != nil {
			alacarteError(w, err)
			return
		}
	}
	writeJSON(w, map[string]any{"id": b.ID, "status": status, "url": "/api/catalog/stream/" + b.ID})
}

func (a *API) catalogStream(w http.ResponseWriter, r *http.Request, u *db.User) {
	id := r.PathValue("id")
	if !streamcache.ValidID(id) || a.Cache == nil {
		jsonError(w, 404, "unknown song")
		return
	}
	// Opened without a "play" call first (e.g. resumed after a restart): start fetching it now
	if a.Cache.Status(id) == nil && a.Alacarte != nil {
		if _, err := a.Alacarte.StartStream(id, r.Form.Get("albumId")); err != nil {
			alacarteError(w, err)
			return
		}
	}
	a.Cache.Serve(w, r, id)
	go a.Cache.Trim()
}

// recent lists what was played lately, library songs and catalog songs together (newest first).
func (a *API) recent(w http.ResponseWriter, r *http.Request, u *db.User) {
	limit, _ := strconv.Atoi(r.Form.Get("limit"))
	if limit <= 0 || limit > 200 {
		limit = 30
	}
	type item struct {
		Kind     string  `json:"kind"` // library | catalog
		ID       string  `json:"id"`   // Sillon track id or Apple song id
		Title    string  `json:"title"`
		Artist   string  `json:"artist"`
		Album    string  `json:"album"`
		AlbumID  string  `json:"albumId,omitempty"`
		Artwork  string  `json:"artwork,omitempty"` // catalog: Apple artwork template
		Quality  string  `json:"quality,omitempty"`
		Duration float64 `json:"duration"`
		PlayedAt int64   `json:"playedAt"`
		Cached   bool    `json:"cached,omitempty"`
	}
	var items []item
	tracks, err := a.DB.QueryTracks(`JOIN plays p ON p.track_id = t.id AND p.user_id = ? ORDER BY p.last_played DESC LIMIT ?`, u.ID, limit)
	if err != nil {
		jsonError(w, 500, err.Error())
		return
	}
	played := map[string]int64{}
	if rows, err := a.DB.Query(`SELECT track_id, last_played FROM plays WHERE user_id = ? ORDER BY last_played DESC LIMIT ?`, u.ID, limit); err == nil {
		for rows.Next() {
			var id string
			var at int64
			rows.Scan(&id, &at)
			played[id] = at
		}
		rows.Close()
	}
	for _, t := range tracks {
		items = append(items, item{Kind: "library", ID: t.ID, Title: t.Title, Artist: t.Artist, Album: t.Album,
			AlbumID: t.AlbumID, Quality: t.Quality, Duration: t.Duration, PlayedAt: played[t.ID]})
	}
	cps, err := a.DB.CatalogPlays(u.ID, limit)
	if err != nil {
		jsonError(w, 500, err.Error())
		return
	}
	for _, p := range cps {
		items = append(items, item{Kind: "catalog", ID: p.AppleID, Title: p.Title, Artist: p.Artist, Album: p.Album,
			AlbumID: p.AppleAlbumID, Artwork: p.Artwork, Duration: p.Duration, PlayedAt: p.PlayedAt,
			Cached: a.Cache != nil && a.Cache.Ready(p.AppleID)})
	}
	sort.Slice(items, func(i, j int) bool { return items[i].PlayedAt > items[j].PlayedAt })
	if len(items) > limit {
		items = items[:limit]
	}
	if items == nil {
		items = []item{}
	}
	writeJSON(w, items)
}

// mixTransition gives the DJ transition between two library songs (from = song playing, to = next one).
// The first call starts preparing it; the app asks again until it is "done".
func (a *API) mixTransition(w http.ResponseWriter, r *http.Request, u *db.User) {
	if a.Mix == nil {
		jsonError(w, http.StatusServiceUnavailable, "DJ mix is not set up (SILLON_DJ_URL)")
		return
	}
	from, err1 := a.DB.TrackByID(r.Form.Get("from"))
	to, err2 := a.DB.TrackByID(r.Form.Get("to"))
	if err1 != nil || err2 != nil || from == nil || to == nil {
		jsonError(w, 404, "unknown song")
		return
	}
	t, err := a.Mix.Get(from.Path, to.Path)
	if err != nil {
		jsonError(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, t)
}

func (a *API) mixFile(w http.ResponseWriter, r *http.Request, u *db.User) {
	if a.Mix == nil {
		jsonError(w, 404, "no DJ mix")
		return
	}
	p, ok := a.Mix.File(r.PathValue("key"))
	if !ok {
		jsonError(w, 404, "no such transition")
		return
	}
	w.Header().Set("Content-Type", "audio/flac")
	w.Header().Set("Cache-Control", "public, max-age=604800")
	http.ServeFile(w, r, p)
}

// pollJobs follows à la carte's queue after a download request and relays progress as live events,
// until nothing is running any more.
func (a *API) pollJobs() {
	if !a.polling.TryLock() {
		return
	}
	defer a.polling.Unlock()
	last := map[any]string{}
	idle := 0
	for idle < 3 {
		time.Sleep(2 * time.Second)
		jobs, err := a.Alacarte.Jobs()
		if err != nil {
			log.Printf("alacarte: %v", err)
			idle++
			continue
		}
		active := false
		for _, j := range jobs {
			st, _ := j["status"].(string)
			if st != "done" && st != "failed" && st != "cancelled" {
				active = true
			}
			b, _ := json.Marshal(j)
			if last[j["id"]] != string(b) {
				last[j["id"]] = string(b)
				a.Hub.Publish(events.Event{Type: "download.job", Data: j})
			}
		}
		if active {
			idle = 0
		} else {
			idle++
		}
	}
}

// ---- smart playlists ----

type smartBody struct {
	Name   string          `json:"name"`
	Rules  json.RawMessage `json:"rules"`
	Public *bool           `json:"public"`
}

func (a *API) smartList(w http.ResponseWriter, r *http.Request, u *db.User) {
	ps, err := a.DB.Playlists(u.ID)
	if err != nil {
		jsonError(w, 500, err.Error())
		return
	}
	out := []map[string]any{}
	for _, p := range ps {
		if p.Smart() {
			out = append(out, map[string]any{"id": p.ID, "name": p.Name, "rules": json.RawMessage(p.Rules), "public": p.Public})
		}
	}
	writeJSON(w, out)
}

func (a *API) smartCreate(w http.ResponseWriter, r *http.Request, u *db.User) {
	var b smartBody
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil || b.Name == "" || len(b.Rules) == 0 {
		jsonError(w, 400, "name and rules are required")
		return
	}
	if _, err := smart.Parse(string(b.Rules)); err != nil {
		jsonError(w, 400, err.Error())
		return
	}
	p, err := a.DB.CreatePlaylist(u.ID, b.Name, string(b.Rules))
	if err != nil {
		jsonError(w, 500, err.Error())
		return
	}
	if b.Public != nil {
		a.DB.UpdatePlaylist(p.ID, nil, nil, b.Public, nil)
	}
	writeJSON(w, map[string]any{"id": p.ID})
}

func (a *API) smartUpdate(w http.ResponseWriter, r *http.Request, u *db.User) {
	p, err := a.DB.PlaylistByID(r.PathValue("id"))
	if err != nil || p == nil || p.UserID != u.ID || !p.Smart() {
		jsonError(w, 404, "smart playlist not found")
		return
	}
	var b smartBody
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil {
		jsonError(w, 400, err.Error())
		return
	}
	var name, rules *string
	if b.Name != "" {
		name = &b.Name
	}
	if len(b.Rules) > 0 {
		if _, err := smart.Parse(string(b.Rules)); err != nil {
			jsonError(w, 400, err.Error())
			return
		}
		s := string(b.Rules)
		rules = &s
	}
	if err := a.DB.UpdatePlaylist(p.ID, name, nil, b.Public, rules); err != nil {
		jsonError(w, 500, err.Error())
		return
	}
	writeJSON(w, map[string]any{"id": p.ID})
}

func (a *API) smartPreview(w http.ResponseWriter, r *http.Request, u *db.User) {
	var b smartBody
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil || len(b.Rules) == 0 {
		jsonError(w, 400, "rules are required")
		return
	}
	start := time.Now()
	tracks, err := smart.Tracks(a.DB, string(b.Rules), u.ID)
	if err != nil {
		jsonError(w, 400, err.Error())
		return
	}
	var dur float64
	for _, t := range tracks {
		dur += t.Duration
	}
	writeJSON(w, map[string]any{"count": len(tracks), "duration": int(dur), "ms": time.Since(start).Milliseconds()})
}
