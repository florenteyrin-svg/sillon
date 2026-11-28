package subsonic

import (
	"bufio"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
	"github.com/florenteyrin-svg/sillon/server/internal/smart"
)

// ---- favourites ----

func starIDs(r *http.Request) []string {
	var ids []string
	for _, k := range []string{"id", "albumId", "artistId"} {
		ids = append(ids, r.Form[k]...)
	}
	return ids
}

func (s *Server) star(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	if err := s.DB.Star(u.ID, starIDs(r)); err != nil {
		return nil, errGeneric(err)
	}
	return nil, nil
}

func (s *Server) unstar(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	if err := s.DB.Unstar(u.ID, starIDs(r)); err != nil {
		return nil, errGeneric(err)
	}
	return nil, nil
}

func (s *Server) starred(u *db.User) (M, *apiError) {
	ud := s.userData(u)
	artists, err := s.DB.QueryArtists(`JOIN stars st ON st.item_id = r.id AND st.user_id = ? ORDER BY st.created_at DESC`, u.ID)
	if err != nil {
		return nil, errGeneric(err)
	}
	albums, err := s.DB.QueryAlbums(`JOIN stars st ON st.item_id = a.id AND st.user_id = ? ORDER BY st.created_at DESC`, u.ID)
	if err != nil {
		return nil, errGeneric(err)
	}
	tracks, err := s.DB.QueryTracks(`JOIN stars st ON st.item_id = t.id AND st.user_id = ? ORDER BY st.created_at DESC`, u.ID)
	if err != nil {
		return nil, errGeneric(err)
	}
	ar := make([]M, 0, len(artists))
	for _, a := range artists {
		ar = append(ar, s.artist(a, ud))
	}
	return M{"artist": ar, "album": s.albums(albums, ud), "song": s.songs(tracks, ud)}, nil
}

func (s *Server) getStarred2(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	m, e := s.starred(u)
	return M{"starred2": m}, e
}

func (s *Server) getStarred(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	m, e := s.starred(u)
	return M{"starred": m}, e
}

// FavoritesID is the "Favoris" playlist: the songs marked with the heart, last one first. It is listed
// with the other playlists (app, Android Auto, Music Assistant) and cannot be edited as a playlist.
const FavoritesID = "pl-favoris"

type favoritesList struct {
	p      *db.Playlist
	tracks []*db.Track
}

func (s *Server) favorites(u *db.User) (*favoritesList, error) {
	tracks, err := s.DB.QueryTracks(`JOIN stars st ON st.item_id = t.id AND st.user_id = ? ORDER BY st.created_at DESC, t.title`, u.ID)
	if err != nil {
		return nil, err
	}
	p := &db.Playlist{ID: FavoritesID, UserID: u.ID, Owner: u.Name, Name: "Favoris", Rules: "favorites", SongCount: len(tracks)}
	for _, t := range tracks {
		p.Duration += t.Duration
	}
	if len(tracks) > 0 {
		var last int64
		s.DB.QueryRow(`SELECT MAX(created_at) FROM stars WHERE user_id = ?`, u.ID).Scan(&last)
		p.CreatedAt, p.UpdatedAt = last, last
	}
	return &favoritesList{p, tracks}, nil
}

// MostPlayedID is "Les plus écoutés": the 100 songs played most (in Sillon, plus Jellyfin's history).
const MostPlayedID = "pl-plus-ecoutes"

func (s *Server) mostPlayed(u *db.User) (*favoritesList, error) {
	tracks, err := s.DB.QueryTracks(`JOIN plays pl ON pl.track_id = t.id AND pl.user_id = ?
		WHERE pl.count + pl.imported > 0 ORDER BY pl.count + pl.imported DESC, pl.last_played DESC LIMIT 100`, u.ID)
	if err != nil {
		return nil, err
	}
	p := &db.Playlist{ID: MostPlayedID, UserID: u.ID, Owner: u.Name, Name: "Les plus écoutés", Rules: "most-played", SongCount: len(tracks)}
	for _, t := range tracks {
		p.Duration += t.Duration
	}
	return &favoritesList{p, tracks}, nil
}

// ---- plays ----

func (s *Server) scrobble(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	// submission=false is a "now playing" notice: nothing to count
	if r.Form.Get("submission") == "false" {
		return nil, nil
	}
	times := r.Form["time"]
	for i, id := range r.Form["id"] {
		at := time.Now()
		if i < len(times) {
			if ms, err := strconv.ParseInt(times[i], 10, 64); err == nil {
				at = time.UnixMilli(ms)
			}
		}
		if err := s.DB.RecordPlay(u.ID, id, at); err != nil {
			return nil, errGeneric(err)
		}
	}
	return nil, nil
}

func (s *Server) getNowPlaying(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	return M{"nowPlaying": M{"entry": []M{}}}, nil
}

// ---- playlists ----

func (s *Server) playlist(p *db.Playlist) M {
	m := M{
		"id": p.ID, "name": p.Name, "comment": p.Comment, "owner": p.Owner, "public": p.Public,
		"songCount": p.SongCount, "duration": int(p.Duration + 0.5), "created": iso(p.CreatedAt),
		"changed": iso(p.UpdatedAt), "coverArt": p.ID, "readonly": p.Smart(),
		// Sillon's own field: the app shows smart playlists apart
		"smart": p.Smart() && p.ID != FavoritesID && p.ID != MostPlayedID,
	}
	return m
}

func (s *Server) visible(p *db.Playlist, u *db.User) bool {
	return p != nil && (p.UserID == u.ID || p.Public)
}

// PlaylistTracks returns the tracks of any playlist; a smart one is evaluated now.
func (s *Server) PlaylistTracks(p *db.Playlist, u *db.User) ([]*db.Track, error) {
	if p.Smart() {
		return smart.Tracks(s.DB, p.Rules, u.ID)
	}
	return s.DB.PlaylistTracks(p.ID)
}

func (s *Server) getPlaylists(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	ps, err := s.DB.Playlists(u.ID)
	if err != nil {
		return nil, errGeneric(err)
	}
	out := make([]M, 0, len(ps)+1)
	if fav, err := s.favorites(u); err == nil {
		out = append(out, s.playlist(fav.p))
	}
	if top, err := s.mostPlayed(u); err == nil && len(top.tracks) > 0 {
		out = append(out, s.playlist(top.p))
	}
	for _, p := range ps {
		if p.Smart() {
			if ts, err := smart.Tracks(s.DB, p.Rules, u.ID); err == nil {
				p.SongCount, p.Duration = len(ts), 0
				for _, t := range ts {
					p.Duration += t.Duration
				}
			}
		}
		out = append(out, s.playlist(p))
	}
	return M{"playlists": M{"playlist": out}}, nil
}

func (s *Server) getPlaylist(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	if id := r.Form.Get("id"); id == FavoritesID || id == MostPlayedID {
		get := s.favorites
		if id == MostPlayedID {
			get = s.mostPlayed
		}
		fav, err := get(u)
		if err != nil {
			return nil, errGeneric(err)
		}
		m := s.playlist(fav.p)
		m["entry"] = s.songs(fav.tracks, s.userData(u))
		return M{"playlist": m}, nil
	}
	p, err := s.DB.PlaylistByID(r.Form.Get("id"))
	if err != nil {
		return nil, errGeneric(err)
	}
	if !s.visible(p, u) {
		return nil, errNotFound("Playlist")
	}
	tracks, err := s.PlaylistTracks(p, u)
	if err != nil {
		return nil, errGeneric(err)
	}
	p.SongCount, p.Duration = len(tracks), 0
	for _, t := range tracks {
		p.Duration += t.Duration
	}
	m := s.playlist(p)
	m["entry"] = s.songs(tracks, s.userData(u))
	return M{"playlist": m}, nil
}

func (s *Server) createPlaylist(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	songs := r.Form["songId"]
	if id := r.Form.Get("playlistId"); id != "" { // Subsonic: createPlaylist with an id replaces its songs
		p, err := s.DB.PlaylistByID(id)
		if err != nil || p == nil || p.UserID != u.ID || p.Smart() {
			return nil, errNotFound("Playlist")
		}
		if err := s.DB.SetPlaylistTracks(id, songs); err != nil {
			return nil, errGeneric(err)
		}
		r.Form.Set("id", id)
		return s.getPlaylist(w, r, u)
	}
	name := r.Form.Get("name")
	if name == "" {
		return nil, errMissing("name")
	}
	p, err := s.DB.CreatePlaylist(u.ID, name, "")
	if err != nil {
		return nil, errGeneric(err)
	}
	if err := s.DB.SetPlaylistTracks(p.ID, songs); err != nil {
		return nil, errGeneric(err)
	}
	r.Form.Set("id", p.ID)
	return s.getPlaylist(w, r, u)
}

func (s *Server) updatePlaylist(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	id := r.Form.Get("playlistId")
	p, err := s.DB.PlaylistByID(id)
	if err != nil {
		return nil, errGeneric(err)
	}
	if p == nil || p.UserID != u.ID {
		return nil, errForbidden()
	}
	var name, comment *string
	var public *bool
	if r.Form.Has("name") {
		v := r.Form.Get("name")
		name = &v
	}
	if r.Form.Has("comment") {
		v := r.Form.Get("comment")
		comment = &v
	}
	if r.Form.Has("public") {
		v := r.Form.Get("public") == "true"
		public = &v
	}
	if err := s.DB.UpdatePlaylist(id, name, comment, public, nil); err != nil {
		return nil, errGeneric(err)
	}
	add, remove := r.Form["songIdToAdd"], r.Form["songIndexToRemove"]
	if (len(add) > 0 || len(remove) > 0) && !p.Smart() {
		ids, err := s.DB.PlaylistTrackIDs(id)
		if err != nil {
			return nil, errGeneric(err)
		}
		drop := map[int]bool{}
		for _, ix := range remove {
			if i, err := strconv.Atoi(ix); err == nil {
				drop[i] = true
			}
		}
		kept := make([]string, 0, len(ids)+len(add))
		for i, tid := range ids {
			if !drop[i] {
				kept = append(kept, tid)
			}
		}
		kept = append(kept, add...)
		if err := s.DB.SetPlaylistTracks(id, kept); err != nil {
			return nil, errGeneric(err)
		}
	}
	return nil, nil
}

func (s *Server) deletePlaylist(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	p, err := s.DB.PlaylistByID(r.Form.Get("id"))
	if err != nil {
		return nil, errGeneric(err)
	}
	if p == nil || p.UserID != u.ID {
		return nil, errForbidden()
	}
	if err := s.DB.DeletePlaylist(p.ID); err != nil {
		return nil, errGeneric(err)
	}
	return nil, nil
}

// ---- play queue: what plays and where, to resume on any device ----

func (s *Server) getPlayQueue(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	q, err := s.DB.PlayQueue(u.ID)
	if err != nil {
		return nil, errGeneric(err)
	}
	if q == nil {
		return nil, nil
	}
	byID := map[string]*db.Track{}
	if len(q.TrackIDs) > 0 {
		ph := strings.TrimSuffix(strings.Repeat("?,", len(q.TrackIDs)), ",")
		args := make([]any, len(q.TrackIDs))
		for i, id := range q.TrackIDs {
			args[i] = id
		}
		ts, err := s.DB.QueryTracks(`WHERE t.id IN (`+ph+`)`, args...)
		if err != nil {
			return nil, errGeneric(err)
		}
		for _, t := range ts {
			byID[t.ID] = t
		}
	}
	ud := s.userData(u)
	entries := make([]M, 0, len(q.TrackIDs))
	for _, id := range q.TrackIDs {
		if t, ok := byID[id]; ok {
			entries = append(entries, s.song(t, ud))
		}
	}
	return M{"playQueue": M{
		"current": q.Current, "position": q.PositionMs, "username": u.Name,
		"changed": time.UnixMilli(q.ChangedAt).UTC().Format(time.RFC3339Nano), "changedBy": q.ChangedBy,
		"entry": entries,
	}}, nil
}

func (s *Server) savePlayQueue(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	pos, _ := strconv.ParseInt(r.Form.Get("position"), 10, 64)
	err := s.DB.SavePlayQueue(u.ID, db.PlayQueue{
		TrackIDs: r.Form["id"], Current: r.Form.Get("current"), PositionMs: pos, ChangedBy: r.Form.Get("c"),
	})
	if err != nil {
		return nil, errGeneric(err)
	}
	return nil, nil
}

// ---- lyrics, from the .lrc file next to the track ----

var lrcLine = regexp.MustCompile(`^\[(\d+):(\d+)(?:[.:](\d+))?\](.*)$`)

type lyricLine struct {
	startMs int64
	text    string
}

func (s *Server) readLyrics(t *db.Track) (lines []lyricLine, synced bool) {
	full := filepath.Join(s.MusicDir, filepath.FromSlash(t.Path))
	f, err := os.Open(strings.TrimSuffix(full, filepath.Ext(full)) + ".lrc")
	if err != nil {
		return nil, false
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if m := lrcLine.FindStringSubmatch(line); m != nil {
			min, _ := strconv.ParseInt(m[1], 10, 64)
			sec, _ := strconv.ParseInt(m[2], 10, 64)
			frac := m[3]
			var ms int64
			if frac != "" {
				ms, _ = strconv.ParseInt((frac + "00")[:3], 10, 64)
			}
			lines = append(lines, lyricLine{min*60000 + sec*1000 + ms, strings.TrimSpace(m[4])})
			synced = true
		} else if line != "" && !strings.HasPrefix(line, "[") {
			lines = append(lines, lyricLine{0, line})
		}
	}
	return lines, synced
}

func (s *Server) getLyricsBySongId(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	t, err := s.DB.TrackByID(r.Form.Get("id"))
	if err != nil {
		return nil, errGeneric(err)
	}
	if t == nil {
		return nil, errNotFound("Song")
	}
	lines, synced := s.readLyrics(t)
	if len(lines) == 0 {
		return M{"lyricsList": M{"structuredLyrics": []M{}}}, nil
	}
	ls := make([]M, 0, len(lines))
	for _, l := range lines {
		m := M{"value": l.text}
		if synced {
			m["start"] = l.startMs
		}
		ls = append(ls, m)
	}
	return M{"lyricsList": M{"structuredLyrics": []M{{
		"displayArtist": t.Artist, "displayTitle": t.Title, "lang": "xxx", "synced": synced, "line": ls,
	}}}}, nil
}

func (s *Server) getLyrics(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	tracks, err := s.DB.QueryTracks(`WHERE t.has_lyrics = 1 AND t.title = ? COLLATE NOCASE AND (? = '' OR t.artist = ? COLLATE NOCASE) LIMIT 1`,
		r.Form.Get("title"), r.Form.Get("artist"), r.Form.Get("artist"))
	if err != nil {
		return nil, errGeneric(err)
	}
	if len(tracks) == 0 {
		return M{"lyrics": M{}}, nil
	}
	lines, _ := s.readLyrics(tracks[0])
	text := make([]string, 0, len(lines))
	for _, l := range lines {
		text = append(text, l.text)
	}
	return M{"lyrics": M{"artist": tracks[0].Artist, "title": tracks[0].Title, "value": strings.Join(text, "\n")}}, nil
}
