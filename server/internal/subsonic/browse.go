package subsonic

import (
	"net/http"
	"path"
	"sort"
	"strings"
	"unicode"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
)

// userData holds the favourites and play counts of the signed-in user, read once per request.
type userData struct {
	stars map[string]int64
	plays map[string]int
}

func (s *Server) userData(u *db.User) userData {
	stars, _ := s.DB.Stars(u.ID)
	plays, _ := s.DB.Plays(u.ID)
	return userData{stars, plays}
}

var contentTypes = map[string]string{
	"flac": "audio/flac", "mp3": "audio/mpeg", "m4a": "audio/mp4", "alac": "audio/mp4", "aac": "audio/aac",
	"ogg": "audio/ogg", "oga": "audio/ogg", "opus": "audio/ogg", "wav": "audio/wav", "aif": "audio/aiff",
	"aiff": "audio/aiff", "wv": "audio/x-wavpack", "ape": "audio/x-ape", "dsf": "audio/x-dsf", "dff": "audio/x-dff",
	"mpc": "audio/x-musepack", "wma": "audio/x-ms-wma",
}

func (s *Server) song(t *db.Track, ud userData) M {
	m := M{
		"id": t.ID, "parent": t.AlbumID, "isDir": false, "title": t.Title, "album": t.Album, "artist": t.Artist,
		"albumId": t.AlbumID, "artistId": t.ArtistID, "coverArt": t.AlbumID, "size": t.Size,
		"contentType": contentTypes[t.Suffix], "suffix": t.Suffix, "duration": int(t.Duration + 0.5),
		"bitRate": t.Bitrate, "samplingRate": t.SampleRate, "channelCount": t.Channels, "path": t.Path,
		"type": "music", "mediaType": "song", "created": iso(t.CreatedAt), "displayArtist": t.Artist,
		"displayAlbumArtist": t.AlbumArtist, "isVideo": false,
		// Sillon's own field: the logo to show (hires | cd | atmos | lossy)
		"quality": t.Quality,
	}
	if s.Covers != nil && s.Covers.Animated(path.Dir(t.Path)) {
		m["animatedCover"] = true // video at /api/animated-cover/{id}
	}
	if t.BitDepth > 0 {
		m["bitDepth"] = t.BitDepth
	}
	if t.TrackNo > 0 {
		m["track"] = t.TrackNo
	}
	if t.DiscNo > 0 {
		m["discNumber"] = t.DiscNo
	}
	if t.Year > 0 {
		m["year"] = t.Year
	}
	if t.Genre != "" {
		m["genre"] = t.Genre
		m["genres"] = []M{{"name": t.Genre}}
	}
	if at, ok := ud.stars[t.ID]; ok {
		m["starred"] = iso(at)
	}
	if c := ud.plays[t.ID]; c > 0 {
		m["playCount"] = c
	}
	return m
}

func (s *Server) songs(ts []*db.Track, ud userData) []M {
	out := make([]M, 0, len(ts))
	for _, t := range ts {
		out = append(out, s.song(t, ud))
	}
	return out
}

func (s *Server) album(a *db.Album, ud userData) M {
	m := M{
		"id": a.ID, "name": a.Name, "title": a.Name, "album": a.Name, "artist": a.Artist, "artistId": a.ArtistID,
		"coverArt": a.ID, "songCount": a.SongCount, "duration": int(a.Duration + 0.5), "created": iso(a.CreatedAt),
		"isDir": true, "parent": a.ArtistID, "displayArtist": a.Artist, "quality": a.Quality,
	}
	if s.Covers != nil && a.Dir != "" && s.Covers.Animated(a.Dir) {
		m["animatedCover"] = true
	}
	if a.Year > 0 {
		m["year"] = a.Year
	}
	if a.Genre != "" {
		m["genre"] = a.Genre
		m["genres"] = []M{{"name": a.Genre}}
	}
	if at, ok := ud.stars[a.ID]; ok {
		m["starred"] = iso(at)
	}
	return m
}

func (s *Server) albums(as []*db.Album, ud userData) []M {
	out := make([]M, 0, len(as))
	for _, a := range as {
		out = append(out, s.album(a, ud))
	}
	return out
}

func (s *Server) artist(a *db.Artist, ud userData) M {
	m := M{"id": a.ID, "name": a.Name, "albumCount": a.AlbumCount, "coverArt": a.ID}
	if at, ok := ud.stars[a.ID]; ok {
		m["starred"] = iso(at)
	}
	return m
}

func (s *Server) getMusicFolders(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	return M{"musicFolders": M{"musicFolder": []M{{"id": 1, "name": "Musique"}}}}, nil
}

var articles = []string{"The "}

func sortName(name string) string {
	for _, a := range articles {
		if len(name) > len(a) && strings.EqualFold(name[:len(a)], a) {
			return name[len(a):]
		}
	}
	return name
}

func (s *Server) artistIndex(u *db.User) ([]M, *apiError) {
	artists, err := s.DB.QueryArtists(`ORDER BY r.name COLLATE NOCASE`)
	if err != nil {
		return nil, errGeneric(err)
	}
	ud := s.userData(u)
	groups := map[string][]M{}
	for _, a := range artists {
		key := "#"
		if sn := []rune(strings.ToUpper(sortName(a.Name))); len(sn) > 0 && unicode.IsLetter(sn[0]) {
			key = string(sn[0])
		}
		groups[key] = append(groups[key], s.artist(a, ud))
	}
	keys := make([]string, 0, len(groups))
	for k := range groups {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	index := make([]M, 0, len(keys))
	for _, k := range keys {
		g := groups[k]
		sort.SliceStable(g, func(i, j int) bool {
			return strings.ToLower(sortName(g[i]["name"].(string))) < strings.ToLower(sortName(g[j]["name"].(string)))
		})
		index = append(index, M{"name": k, "artist": g})
	}
	return index, nil
}

func (s *Server) getArtists(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	index, e := s.artistIndex(u)
	if e != nil {
		return nil, e
	}
	return M{"artists": M{"ignoredArticles": "The", "index": index}}, nil
}

func (s *Server) getIndexes(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	index, e := s.artistIndex(u)
	if e != nil {
		return nil, e
	}
	return M{"indexes": M{"ignoredArticles": "The", "lastModified": s.Scanner.LastScan().UnixMilli(), "index": index}}, nil
}

func (s *Server) getArtist(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	id := r.Form.Get("id")
	a, err := s.DB.ArtistByID(id)
	if err != nil {
		return nil, errGeneric(err)
	}
	if a == nil {
		return nil, errNotFound("Artist")
	}
	albums, err := s.DB.QueryAlbums(`WHERE a.artist_id = ? ORDER BY a.year DESC, a.name COLLATE NOCASE`, id)
	if err != nil {
		return nil, errGeneric(err)
	}
	ud := s.userData(u)
	m := s.artist(a, ud)
	m["album"] = s.albums(albums, ud)
	return M{"artist": m}, nil
}

func (s *Server) getAlbum(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	id := r.Form.Get("id")
	a, err := s.DB.AlbumByID(id)
	if err != nil {
		return nil, errGeneric(err)
	}
	if a == nil {
		return nil, errNotFound("Album")
	}
	tracks, err := s.DB.QueryTracks(`WHERE t.album_id = ? ORDER BY t.disc_no, t.track_no, t.path`, id)
	if err != nil {
		return nil, errGeneric(err)
	}
	ud := s.userData(u)
	m := s.album(a, ud)
	m["song"] = s.songs(tracks, ud)
	return M{"album": m}, nil
}

func (s *Server) getSong(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	t, err := s.DB.TrackByID(r.Form.Get("id"))
	if err != nil {
		return nil, errGeneric(err)
	}
	if t == nil {
		return nil, errNotFound("Song")
	}
	return M{"song": s.song(t, s.userData(u))}, nil
}

// getMusicDirectory serves folder-style clients: root -> artists -> albums -> songs.
func (s *Server) getMusicDirectory(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	id := r.Form.Get("id")
	ud := s.userData(u)
	switch {
	case strings.HasPrefix(id, "ar-"):
		a, err := s.DB.ArtistByID(id)
		if err != nil || a == nil {
			return nil, errNotFound("Directory")
		}
		albums, _ := s.DB.QueryAlbums(`WHERE a.artist_id = ? ORDER BY a.year DESC`, id)
		return M{"directory": M{"id": id, "name": a.Name, "parent": "1", "child": s.albums(albums, ud)}}, nil
	case strings.HasPrefix(id, "al-"):
		a, err := s.DB.AlbumByID(id)
		if err != nil || a == nil {
			return nil, errNotFound("Directory")
		}
		tracks, _ := s.DB.QueryTracks(`WHERE t.album_id = ? ORDER BY t.disc_no, t.track_no, t.path`, id)
		return M{"directory": M{"id": id, "name": a.Name, "parent": a.ArtistID, "child": s.songs(tracks, ud)}}, nil
	default:
		artists, _ := s.DB.QueryArtists(`ORDER BY r.name COLLATE NOCASE`)
		children := make([]M, 0, len(artists))
		for _, a := range artists {
			children = append(children, M{"id": a.ID, "title": a.Name, "isDir": true, "parent": "1", "coverArt": a.ID})
		}
		return M{"directory": M{"id": "1", "name": "Musique", "child": children}}, nil
	}
}

func (s *Server) albumListQuery(r *http.Request, u *db.User) ([]*db.Album, *apiError) {
	size := clamp(intParam(r, "size", 10), 1, 500)
	offset := clamp(intParam(r, "offset", 0), 0, 1<<30)
	var tail string
	var args []any
	switch r.Form.Get("type") {
	case "random":
		tail = `ORDER BY RANDOM()`
	case "newest":
		tail = `ORDER BY a.created_at DESC, a.name`
	case "alphabeticalByName", "":
		tail = `ORDER BY a.name COLLATE NOCASE`
	case "alphabeticalByArtist":
		tail = `ORDER BY a.artist COLLATE NOCASE, a.year, a.name COLLATE NOCASE`
	case "frequent", "highest":
		tail = `JOIN (SELECT t.album_id, SUM(p.count + p.imported) n FROM plays p JOIN tracks t ON t.id = p.track_id
			WHERE p.user_id = ? GROUP BY t.album_id) f ON f.album_id = a.id ORDER BY f.n DESC`
		args = append(args, u.ID)
	case "recent":
		tail = `JOIN (SELECT t.album_id, MAX(p.last_played) l FROM plays p JOIN tracks t ON t.id = p.track_id
			WHERE p.user_id = ? GROUP BY t.album_id) f ON f.album_id = a.id ORDER BY f.l DESC`
		args = append(args, u.ID)
	case "starred":
		tail = `JOIN stars st ON st.item_id = a.id AND st.user_id = ? ORDER BY st.created_at DESC`
		args = append(args, u.ID)
	case "byYear":
		from, to := intParam(r, "fromYear", 0), intParam(r, "toYear", 9999)
		if from <= to {
			tail = `WHERE a.year BETWEEN ? AND ? ORDER BY a.year, a.name`
			args = append(args, from, to)
		} else {
			tail = `WHERE a.year BETWEEN ? AND ? ORDER BY a.year DESC, a.name`
			args = append(args, to, from)
		}
	case "byGenre":
		tail = `WHERE a.id IN (SELECT album_id FROM tracks WHERE genre = ? COLLATE NOCASE) ORDER BY a.name COLLATE NOCASE`
		args = append(args, r.Form.Get("genre"))
	default:
		return nil, &apiError{0, "Unknown list type " + r.Form.Get("type")}
	}
	tail += ` LIMIT ? OFFSET ?`
	args = append(args, size, offset)
	albums, err := s.DB.QueryAlbums(tail, args...)
	if err != nil {
		return nil, errGeneric(err)
	}
	return albums, nil
}

func (s *Server) getAlbumList2(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	albums, e := s.albumListQuery(r, u)
	if e != nil {
		return nil, e
	}
	return M{"albumList2": M{"album": s.albums(albums, s.userData(u))}}, nil
}

func (s *Server) getAlbumList(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	albums, e := s.albumListQuery(r, u)
	if e != nil {
		return nil, e
	}
	return M{"albumList": M{"album": s.albums(albums, s.userData(u))}}, nil
}

func (s *Server) getRandomSongs(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	size := clamp(intParam(r, "size", 10), 1, 500)
	conds := []string{"1"}
	var args []any
	if g := r.Form.Get("genre"); g != "" {
		conds = append(conds, "t.genre = ? COLLATE NOCASE")
		args = append(args, g)
	}
	if y := intParam(r, "fromYear", 0); y > 0 {
		conds = append(conds, "t.year >= ?")
		args = append(args, y)
	}
	if y := intParam(r, "toYear", 0); y > 0 {
		conds = append(conds, "t.year <= ?")
		args = append(args, y)
	}
	args = append(args, size)
	tracks, err := s.DB.QueryTracks(`WHERE `+strings.Join(conds, " AND ")+` ORDER BY RANDOM() LIMIT ?`, args...)
	if err != nil {
		return nil, errGeneric(err)
	}
	return M{"randomSongs": M{"song": s.songs(tracks, s.userData(u))}}, nil
}

func (s *Server) getSongsByGenre(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	g := r.Form.Get("genre")
	if g == "" {
		return nil, errMissing("genre")
	}
	tracks, err := s.DB.QueryTracks(`WHERE t.genre = ? COLLATE NOCASE ORDER BY t.album_artist, t.album, t.disc_no, t.track_no LIMIT ? OFFSET ?`,
		g, clamp(intParam(r, "count", 10), 1, 500), intParam(r, "offset", 0))
	if err != nil {
		return nil, errGeneric(err)
	}
	return M{"songsByGenre": M{"song": s.songs(tracks, s.userData(u))}}, nil
}

func (s *Server) getGenres(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	rows, err := s.DB.Query(`SELECT genre, COUNT(*), COUNT(DISTINCT album_id) FROM tracks WHERE genre != ''
		GROUP BY genre COLLATE NOCASE ORDER BY genre COLLATE NOCASE`)
	if err != nil {
		return nil, errGeneric(err)
	}
	defer rows.Close()
	genres := []M{}
	for rows.Next() {
		var name string
		var songs, albums int
		rows.Scan(&name, &songs, &albums)
		genres = append(genres, M{"value": name, "songCount": songs, "albumCount": albums})
	}
	return M{"genres": M{"genre": genres}}, nil
}

func (s *Server) getTopSongs(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	name := r.Form.Get("artist")
	tracks, err := s.DB.QueryTracks(`LEFT JOIN plays p ON p.track_id = t.id AND p.user_id = ?
		WHERE t.artist = ? COLLATE NOCASE OR t.album_artist = ? COLLATE NOCASE
		ORDER BY COALESCE(p.count + p.imported, 0) DESC, t.year DESC LIMIT ?`, u.ID, name, name, clamp(intParam(r, "count", 50), 1, 500))
	if err != nil {
		return nil, errGeneric(err)
	}
	return M{"topSongs": M{"song": s.songs(tracks, s.userData(u))}}, nil
}

// similarTracks: songs of the same genre (and other artists' songs first), until real analysis is added.
func (s *Server) similarTracks(id string, count int) ([]*db.Track, error) {
	var genre, artistID string
	if strings.HasPrefix(id, "ar-") {
		artistID = id
		s.DB.QueryRow(`SELECT genre FROM tracks WHERE album_artist_id = ? AND genre != '' LIMIT 1`, id).Scan(&genre)
	} else if strings.HasPrefix(id, "al-") {
		s.DB.QueryRow(`SELECT genre, artist_id FROM albums WHERE id = ?`, id).Scan(&genre, &artistID)
	} else {
		s.DB.QueryRow(`SELECT genre, album_artist_id FROM tracks WHERE id = ?`, id).Scan(&genre, &artistID)
	}
	if genre == "" {
		return s.DB.QueryTracks(`WHERE t.album_artist_id != ? ORDER BY RANDOM() LIMIT ?`, artistID, count)
	}
	return s.DB.QueryTracks(`WHERE t.genre = ? COLLATE NOCASE AND t.album_artist_id != ? ORDER BY RANDOM() LIMIT ?`,
		genre, artistID, count)
}

func (s *Server) getSimilarSongs2(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	tracks, err := s.similarTracks(r.Form.Get("id"), clamp(intParam(r, "count", 50), 1, 500))
	if err != nil {
		return nil, errGeneric(err)
	}
	return M{"similarSongs2": M{"song": s.songs(tracks, s.userData(u))}}, nil
}

func (s *Server) getSimilarSongs(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	tracks, err := s.similarTracks(r.Form.Get("id"), clamp(intParam(r, "count", 50), 1, 500))
	if err != nil {
		return nil, errGeneric(err)
	}
	return M{"similarSongs": M{"song": s.songs(tracks, s.userData(u))}}, nil
}

func (s *Server) getArtistInfo2(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	return M{"artistInfo2": M{"similarArtist": []M{}}}, nil
}

func (s *Server) getArtistInfo(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	return M{"artistInfo": M{"similarArtist": []M{}}}, nil
}

func (s *Server) getAlbumInfo(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	return M{"albumInfo": M{}}, nil
}

// ---- search ----

func searchCond(cols []string, query string) (string, []any) {
	query = strings.Trim(strings.TrimSpace(query), `"`)
	if query == "" || query == "*" {
		return "1", nil
	}
	var conds []string
	var args []any
	for _, word := range strings.Fields(query) {
		word = strings.TrimSuffix(word, "*")
		var ors []string
		for _, c := range cols {
			ors = append(ors, c+" LIKE ?")
			args = append(args, "%"+word+"%")
		}
		conds = append(conds, "("+strings.Join(ors, " OR ")+")")
	}
	return strings.Join(conds, " AND "), args
}

func (s *Server) searchAll(r *http.Request, u *db.User) ([]*db.Artist, []*db.Album, []*db.Track, *apiError) {
	q := r.Form.Get("query")
	ac, ao := clamp(intParam(r, "artistCount", 20), 0, 500), intParam(r, "artistOffset", 0)
	bc, bo := clamp(intParam(r, "albumCount", 20), 0, 500), intParam(r, "albumOffset", 0)
	sc, so := clamp(intParam(r, "songCount", 20), 0, 1000), intParam(r, "songOffset", 0)
	var artists []*db.Artist
	var albums []*db.Album
	var tracks []*db.Track
	var err error
	if ac > 0 {
		c, a := searchCond([]string{"r.name"}, q)
		artists, err = s.DB.QueryArtists(`WHERE `+c+` ORDER BY r.name COLLATE NOCASE LIMIT ? OFFSET ?`, append(a, ac, ao)...)
		if err != nil {
			return nil, nil, nil, errGeneric(err)
		}
	}
	if bc > 0 {
		c, a := searchCond([]string{"a.name", "a.artist"}, q)
		albums, err = s.DB.QueryAlbums(`WHERE `+c+` ORDER BY a.name COLLATE NOCASE LIMIT ? OFFSET ?`, append(a, bc, bo)...)
		if err != nil {
			return nil, nil, nil, errGeneric(err)
		}
	}
	if sc > 0 {
		c, a := searchCond([]string{"t.title", "t.artist", "t.album"}, q)
		order := `t.title COLLATE NOCASE`
		if c == "1" { // full listing (used by apps to sync the whole library): stable order
			order = `t.album_artist COLLATE NOCASE, t.album, t.disc_no, t.track_no`
		}
		tracks, err = s.DB.QueryTracks(`WHERE `+c+` ORDER BY `+order+` LIMIT ? OFFSET ?`, append(a, sc, so)...)
		if err != nil {
			return nil, nil, nil, errGeneric(err)
		}
	}
	return artists, albums, tracks, nil
}

func (s *Server) search3(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	artists, albums, tracks, e := s.searchAll(r, u)
	if e != nil {
		return nil, e
	}
	ud := s.userData(u)
	ar := make([]M, 0, len(artists))
	for _, a := range artists {
		ar = append(ar, s.artist(a, ud))
	}
	return M{"searchResult3": M{"artist": ar, "album": s.albums(albums, ud), "song": s.songs(tracks, ud)}}, nil
}

func (s *Server) search2(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	body, e := s.search3(w, r, u)
	if e != nil {
		return nil, e
	}
	return M{"searchResult2": body["searchResult3"]}, nil
}
