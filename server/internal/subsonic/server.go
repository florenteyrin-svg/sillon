package subsonic

import (
	"context"
	"crypto/md5"
	"crypto/subtle"
	"encoding/hex"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
	"github.com/florenteyrin-svg/sillon/server/internal/scanner"
)

var Version = "dev"

type Server struct {
	DB       *db.DB
	Scanner  *scanner.Scanner
	MusicDir string
	DataDir  string
	Covers   *Covers
}

type handler func(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError)

type ctxKey struct{}

// UserFrom returns the signed-in user of a request that went through Authenticate.
func UserFrom(r *http.Request) *db.User {
	u, _ := r.Context().Value(ctxKey{}).(*db.User)
	return u
}

func (s *Server) Register(mux *http.ServeMux) {
	routes := map[string]handler{
		"ping":                      s.ping,
		"getLicense":                s.getLicense,
		"getOpenSubsonicExtensions": s.getExtensions,
		"getMusicFolders":           s.getMusicFolders,
		"getIndexes":                s.getIndexes,
		"getArtists":                s.getArtists,
		"getArtist":                 s.getArtist,
		"getAlbum":                  s.getAlbum,
		"getSong":                   s.getSong,
		"getMusicDirectory":         s.getMusicDirectory,
		"getAlbumList":              s.getAlbumList,
		"getAlbumList2":             s.getAlbumList2,
		"getRandomSongs":            s.getRandomSongs,
		"getSongsByGenre":           s.getSongsByGenre,
		"getGenres":                 s.getGenres,
		"getTopSongs":               s.getTopSongs,
		"getSimilarSongs":           s.getSimilarSongs,
		"getSimilarSongs2":          s.getSimilarSongs2,
		"getArtistInfo":             s.getArtistInfo,
		"getArtistInfo2":            s.getArtistInfo2,
		"getAlbumInfo":              s.getAlbumInfo,
		"getAlbumInfo2":             s.getAlbumInfo,
		"search2":                   s.search2,
		"search3":                   s.search3,
		"getStarred":                s.getStarred,
		"getStarred2":               s.getStarred2,
		"star":                      s.star,
		"unstar":                    s.unstar,
		"setRating":                 s.ok,
		"scrobble":                  s.scrobble,
		"getNowPlaying":             s.getNowPlaying,
		"getPlaylists":              s.getPlaylists,
		"getPlaylist":               s.getPlaylist,
		"createPlaylist":            s.createPlaylist,
		"updatePlaylist":            s.updatePlaylist,
		"deletePlaylist":            s.deletePlaylist,
		"getPlayQueue":              s.getPlayQueue,
		"savePlayQueue":             s.savePlayQueue,
		"getLyrics":                 s.getLyrics,
		"getLyricsBySongId":         s.getLyricsBySongId,
		"getUser":                   s.getUser,
		"startScan":                 s.startScan,
		"getScanStatus":             s.getScanStatus,
		"getPodcasts":               s.empty("podcasts", "channel"),
		"getNewestPodcasts":         s.empty("newestPodcasts", "episode"),
		"getInternetRadioStations":  s.empty("internetRadioStations", "internetRadioStation"),
		"getBookmarks":              s.empty("bookmarks", "bookmark"),
		"getShares":                 s.empty("shares", "share"),
		"getVideos":                 s.empty("videos", "video"),
	}
	binary := map[string]func(http.ResponseWriter, *http.Request, *db.User) *apiError{
		"stream":      s.stream,
		"download":    s.download,
		"getCoverArt": s.getCoverArt,
		"getAvatar":   func(w http.ResponseWriter, r *http.Request, u *db.User) *apiError { return errNotFound("Avatar") },
	}
	mux.HandleFunc("/rest/{method}", func(w http.ResponseWriter, r *http.Request) {
		r.ParseForm()
		name := strings.TrimSuffix(r.PathValue("method"), ".view")
		u, e := s.Authenticate(r)
		if e != nil {
			write(w, r, nil, e)
			return
		}
		r = r.WithContext(context.WithValue(r.Context(), ctxKey{}, u))
		if h, ok := routes[name]; ok {
			body, e := h(w, r, u)
			write(w, r, body, e)
			return
		}
		if h, ok := binary[name]; ok {
			if e := h(w, r, u); e != nil {
				write(w, r, nil, e)
			}
			return
		}
		write(w, r, nil, &apiError{0, "Unknown method " + name})
	})
}

// Authenticate accepts every Subsonic login form: API key, token + salt, or password (plain or "enc:" hex).
func (s *Server) Authenticate(r *http.Request) (*db.User, *apiError) {
	if r.Form == nil {
		r.ParseForm()
	}
	if key := r.Form.Get("apiKey"); key != "" {
		u, err := s.DB.UserByAPIKey(key)
		if err != nil {
			return nil, errGeneric(err)
		}
		if u == nil {
			return nil, &apiError{44, "Invalid API key"}
		}
		return u, nil
	}
	// The apps also send "Authorization: Bearer <api key>"
	if auth := r.Header.Get("Authorization"); strings.HasPrefix(auth, "Bearer ") {
		u, err := s.DB.UserByAPIKey(strings.TrimPrefix(auth, "Bearer "))
		if err != nil {
			return nil, errGeneric(err)
		}
		if u != nil {
			return u, nil
		}
		return nil, errAuth()
	}
	name := r.Form.Get("u")
	if name == "" {
		if bu, bp, ok := r.BasicAuth(); ok {
			u, err := s.DB.UserByName(bu)
			if err == nil && u != nil && subtle.ConstantTimeCompare([]byte(u.Password), []byte(bp)) == 1 {
				return u, nil
			}
			return nil, errAuth()
		}
		return nil, errMissing("u")
	}
	u, err := s.DB.UserByName(name)
	if err != nil {
		return nil, errGeneric(err)
	}
	if u == nil {
		return nil, errAuth()
	}
	if t, salt := r.Form.Get("t"), r.Form.Get("s"); t != "" && salt != "" {
		sum := md5.Sum([]byte(u.Password + salt))
		if subtle.ConstantTimeCompare([]byte(strings.ToLower(t)), []byte(hex.EncodeToString(sum[:]))) == 1 {
			return u, nil
		}
		return nil, errAuth()
	}
	p := r.Form.Get("p")
	if p == "" {
		return nil, errMissing("p")
	}
	if strings.HasPrefix(p, "enc:") {
		b, err := hex.DecodeString(p[4:])
		if err != nil {
			return nil, errAuth()
		}
		p = string(b)
	}
	if subtle.ConstantTimeCompare([]byte(u.Password), []byte(p)) == 1 {
		return u, nil
	}
	return nil, errAuth()
}

// ---- small helpers ----

func intParam(r *http.Request, name string, def int) int {
	if v, err := strconv.Atoi(r.Form.Get(name)); err == nil {
		return v
	}
	return def
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}

func iso(unix int64) string { return time.Unix(unix, 0).UTC().Format(time.RFC3339) }

func (s *Server) ok(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) { return nil, nil }

func (s *Server) empty(key, item string) handler {
	return func(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
		return M{key: M{item: []M{}}}, nil
	}
}

func (s *Server) ping(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) { return nil, nil }

func (s *Server) getLicense(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	return M{"license": M{"valid": true}}, nil
}

func (s *Server) getExtensions(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	return M{"openSubsonicExtensions": []M{
		{"name": "apiKeyAuthentication", "versions": []int{1}},
		{"name": "songLyrics", "versions": []int{1}},
		{"name": "formPost", "versions": []int{1}},
	}}, nil
}

func (s *Server) getUser(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	return M{"user": M{
		"username": u.Name, "adminRole": u.Admin, "scrobblingEnabled": true, "settingsRole": u.Admin,
		"downloadRole": true, "uploadRole": false, "playlistRole": true, "coverArtRole": true,
		"commentRole": false, "podcastRole": false, "streamRole": true, "jukeboxRole": false,
		"shareRole": false, "videoConversionRole": false, "folder": []int{1},
	}}, nil
}

func (s *Server) startScan(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	if !s.Scanner.Scanning() {
		go s.Scanner.Scan()
	}
	return s.getScanStatus(w, r, u)
}

func (s *Server) getScanStatus(w http.ResponseWriter, r *http.Request, u *db.User) (M, *apiError) {
	var n int64
	s.DB.QueryRow(`SELECT COUNT(*) FROM tracks`).Scan(&n)
	return M{"scanStatus": M{"scanning": s.Scanner.Scanning(), "count": n}}, nil
}
