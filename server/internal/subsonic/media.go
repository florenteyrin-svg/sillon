package subsonic

import (
	"bytes"
	"fmt"
	"image"
	_ "image/gif"
	"image/jpeg"
	_ "image/png"
	"log"
	"net/http"
	"os"
	"os/exec"
	"path"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/dhowden/tag"
	"golang.org/x/image/draw"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
)

func (s *Server) trackFile(id string) (*db.Track, string, *apiError) {
	t, err := s.DB.TrackByID(id)
	if err != nil {
		return nil, "", errGeneric(err)
	}
	if t == nil {
		return nil, "", errNotFound("Song")
	}
	return t, filepath.Join(s.MusicDir, filepath.FromSlash(t.Path)), nil
}

// stream sends the original file (with seeking support). A conversion happens only when the client asks
// for another format or a lower bit rate than the file's.
func (s *Server) stream(w http.ResponseWriter, r *http.Request, u *db.User) *apiError {
	t, full, e := s.trackFile(r.Form.Get("id"))
	if e != nil {
		return e
	}
	format := strings.ToLower(r.Form.Get("format"))
	maxBR := intParam(r, "maxBitRate", 0)
	offset := intParam(r, "timeOffset", 0)
	if format == "raw" || format == t.Suffix {
		format = ""
	}
	if format == "" && (maxBR == 0 || maxBR >= t.Bitrate) && offset == 0 {
		w.Header().Set("Content-Type", contentTypes[t.Suffix])
		http.ServeFile(w, r, full)
		return nil
	}
	if format == "" {
		format = "opus"
	}
	if maxBR == 0 || maxBR > 320 {
		maxBR = 320
	}
	transcode(w, r, full, format, maxBR, offset)
	return nil
}

// ServeTrack sends a library file as it is.
func (s *Server) ServeTrack(w http.ResponseWriter, r *http.Request, t *db.Track) {
	w.Header().Set("Content-Type", contentTypes[t.Suffix])
	http.ServeFile(w, r, filepath.Join(s.MusicDir, filepath.FromSlash(t.Path)))
}

func transcode(w http.ResponseWriter, r *http.Request, full, format string, kbps, offset int) {
	args := []string{"-v", "error"}
	if offset > 0 {
		args = append(args, "-ss", strconv.Itoa(offset))
	}
	args = append(args, "-i", full, "-map", "0:a:0", "-vn")
	switch format {
	case "mp3":
		w.Header().Set("Content-Type", "audio/mpeg")
		args = append(args, "-c:a", "libmp3lame", "-b:a", fmt.Sprintf("%dk", kbps), "-f", "mp3", "pipe:1")
	case "aac", "m4a":
		w.Header().Set("Content-Type", "audio/aac")
		args = append(args, "-c:a", "aac", "-b:a", fmt.Sprintf("%dk", kbps), "-f", "adts", "pipe:1")
	case "flac":
		w.Header().Set("Content-Type", "audio/flac")
		args = append(args, "-c:a", "flac", "-f", "flac", "pipe:1")
	default:
		w.Header().Set("Content-Type", "audio/ogg")
		if kbps > 256 {
			kbps = 256
		}
		args = append(args, "-c:a", "libopus", "-b:a", fmt.Sprintf("%dk", kbps), "-f", "ogg", "pipe:1")
	}
	cmd := exec.CommandContext(r.Context(), "ffmpeg", args...)
	cmd.Stdout = w
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil && r.Context().Err() == nil {
		log.Printf("transcode %s: %v %s", full, err, stderr.String())
	}
}

func (s *Server) download(w http.ResponseWriter, r *http.Request, u *db.User) *apiError {
	t, full, e := s.trackFile(r.Form.Get("id"))
	if e != nil {
		return e
	}
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename=%q`, filepath.Base(t.Path)))
	http.ServeFile(w, r, full)
	return nil
}

// ---- covers ----

// Covers finds album art (folder image, else the picture inside the file) and keeps resized copies.
type Covers struct {
	DB       *db.DB
	MusicDir string
	CacheDir string
	mu       sync.Mutex

	animMu sync.Mutex
	anim   map[string]animEntry // album folder -> animated cover found, checked at
}

type animEntry struct {
	ok bool
	at time.Time
}

// AnimatedFile is where à la carte saves an album's animated cover (silent looping H.264 video).
const AnimatedFile = "cover/animated.mp4"

// Animated tells whether the album folder (relative to the music folder) has an animated cover.
// Answers are kept 2 minutes, so long lists do not hit the disk for every song.
func (c *Covers) Animated(dirRel string) bool {
	c.animMu.Lock()
	defer c.animMu.Unlock()
	if c.anim == nil {
		c.anim = map[string]animEntry{}
	}
	if e, ok := c.anim[dirRel]; ok && time.Since(e.at) < 2*time.Minute {
		return e.ok
	}
	_, err := os.Stat(filepath.Join(c.MusicDir, filepath.FromSlash(dirRel), filepath.FromSlash(AnimatedFile)))
	c.anim[dirRel] = animEntry{err == nil, time.Now()}
	return err == nil
}

// AnimatedPath returns the animated cover file of an album or song id, if there is one.
func (c *Covers) AnimatedPath(id string) (string, bool) {
	var dir string
	switch {
	case strings.HasPrefix(id, "al-"):
		c.DB.QueryRow(`SELECT dir FROM albums WHERE id = ?`, id).Scan(&dir)
	case strings.HasPrefix(id, "tr-"):
		var p string
		if c.DB.QueryRow(`SELECT path FROM tracks WHERE id = ?`, id).Scan(&p) == nil {
			dir = path.Dir(p)
		}
	}
	if dir == "" {
		return "", false
	}
	full := filepath.Join(c.MusicDir, filepath.FromSlash(dir), filepath.FromSlash(AnimatedFile))
	if _, err := os.Stat(full); err != nil {
		return "", false
	}
	return full, true
}

var folderImages = []string{"cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "folder.jpeg", "folder.png",
	"front.jpg", "front.jpeg", "front.png", "album.jpg", "album.png", "artwork.jpg"}

// albumFor finds the album whose cover stands for any id (song, album, artist, playlist).
func (c *Covers) albumFor(id string) string {
	switch {
	case strings.HasPrefix(id, "al-"):
		return id
	case strings.HasPrefix(id, "tr-"):
		var a string
		c.DB.QueryRow(`SELECT album_id FROM tracks WHERE id = ?`, id).Scan(&a)
		return a
	case strings.HasPrefix(id, "ar-"):
		var a string
		c.DB.QueryRow(`SELECT id FROM albums WHERE artist_id = ? ORDER BY year DESC LIMIT 1`, id).Scan(&a)
		return a
	case id == MostPlayedID: // the song played most
		var a string
		c.DB.QueryRow(`SELECT t.album_id FROM plays pl JOIN tracks t ON t.id = pl.track_id
			ORDER BY pl.count + pl.imported DESC LIMIT 1`).Scan(&a)
		return a
	case id == FavoritesID: // the song marked last
		var a string
		c.DB.QueryRow(`SELECT t.album_id FROM stars st JOIN tracks t ON t.id = st.item_id ORDER BY st.created_at DESC LIMIT 1`).Scan(&a)
		return a
	case strings.HasPrefix(id, "pl-"):
		var a string
		c.DB.QueryRow(`SELECT t.album_id FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id
			WHERE pt.playlist_id = ? ORDER BY pt.pos LIMIT 1`, id).Scan(&a)
		return a
	}
	return ""
}

// original returns the cover image bytes of an album.
func (c *Covers) original(albumID string) ([]byte, error) {
	var dir, trackID string
	if err := c.DB.QueryRow(`SELECT dir, cover_track_id FROM albums WHERE id = ?`, albumID).Scan(&dir, &trackID); err != nil {
		return nil, err
	}
	folder := filepath.Join(c.MusicDir, filepath.FromSlash(dir))
	if entries, err := os.ReadDir(folder); err == nil {
		names := map[string]string{}
		for _, e := range entries {
			names[strings.ToLower(e.Name())] = e.Name()
		}
		for _, want := range folderImages {
			if real, ok := names[want]; ok {
				return os.ReadFile(filepath.Join(folder, real))
			}
		}
	}
	var p string
	if err := c.DB.QueryRow(`SELECT path FROM tracks WHERE id = ?`, trackID).Scan(&p); err != nil {
		return nil, err
	}
	f, err := os.Open(filepath.Join(c.MusicDir, filepath.FromSlash(p)))
	if err != nil {
		return nil, err
	}
	defer f.Close()
	m, err := tag.ReadFrom(f)
	if err != nil {
		return nil, err
	}
	if pic := m.Picture(); pic != nil && len(pic.Data) > 0 {
		return pic.Data, nil
	}
	return nil, os.ErrNotExist
}

// Get returns a JPEG (or the original image when size is 0) for the id.
func (c *Covers) Get(id string, size int) ([]byte, string, error) {
	albumID := c.albumFor(id)
	if albumID == "" {
		return nil, "", os.ErrNotExist
	}
	if size > 0 {
		size = clamp(size, 32, 1500)
		cached := filepath.Join(c.CacheDir, fmt.Sprintf("%s-%d.jpg", albumID, size))
		if b, err := os.ReadFile(cached); err == nil {
			return b, "image/jpeg", nil
		}
	}
	orig, err := c.original(albumID)
	if err != nil {
		return nil, "", err
	}
	if size == 0 {
		return orig, http.DetectContentType(orig), nil
	}
	img, _, err := image.Decode(bytes.NewReader(orig))
	if err != nil {
		return orig, http.DetectContentType(orig), nil
	}
	b := img.Bounds()
	if b.Dx() > size || b.Dy() > size {
		w, h := size, size
		if b.Dx() > b.Dy() {
			h = size * b.Dy() / b.Dx()
		} else {
			w = size * b.Dx() / b.Dy()
		}
		dst := image.NewRGBA(image.Rect(0, 0, w, h))
		draw.CatmullRom.Scale(dst, dst.Bounds(), img, b, draw.Over, nil)
		img = dst
	}
	var out bytes.Buffer
	if err := jpeg.Encode(&out, img, &jpeg.Options{Quality: 88}); err != nil {
		return nil, "", err
	}
	c.mu.Lock()
	os.MkdirAll(c.CacheDir, 0o755)
	os.WriteFile(filepath.Join(c.CacheDir, fmt.Sprintf("%s-%d.jpg", albumID, size)), out.Bytes(), 0o644)
	c.mu.Unlock()
	return out.Bytes(), "image/jpeg", nil
}

// ClearAlbum drops the resized copies of an album (its cover may have changed).
func (c *Covers) ClearAlbum(albumID string) {
	matches, _ := filepath.Glob(filepath.Join(c.CacheDir, albumID+"-*.jpg"))
	for _, m := range matches {
		os.Remove(m)
	}
}

func (s *Server) getCoverArt(w http.ResponseWriter, r *http.Request, u *db.User) *apiError {
	b, ct, err := s.Covers.Get(r.Form.Get("id"), intParam(r, "size", 0))
	if err != nil {
		return errNotFound("Cover art")
	}
	w.Header().Set("Content-Type", ct)
	w.Header().Set("Cache-Control", "public, max-age=86400")
	w.Write(b)
	return nil
}
