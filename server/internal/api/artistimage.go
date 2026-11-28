package api

import (
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
)

// Artist photos come from Apple Music (through à la carte), found by the artist's name, and are kept in
// the data folder. An artist with no photo on Apple Music is asked again after a week.
const artistNoPhotoRetry = 7 * 24 * time.Hour

var errNoPhoto = errors.New("no photo for this artist on Apple Music")

var artistImageLocks sync.Map // artist id -> *sync.Mutex: one download per artist at a time

func (a *API) artistImage(w http.ResponseWriter, r *http.Request, u *db.User) {
	id := r.PathValue("id")
	if !strings.HasPrefix(id, "ar-") || strings.ContainsAny(id, `/\.`) {
		jsonError(w, 404, "unknown artist")
		return
	}
	dir := filepath.Join(a.Subsonic.DataDir, "artists")
	img, none := filepath.Join(dir, id+".jpg"), filepath.Join(dir, id+".nophoto")
	m, _ := artistImageLocks.LoadOrStore(id, &sync.Mutex{})
	m.(*sync.Mutex).Lock()
	defer m.(*sync.Mutex).Unlock()

	if _, err := os.Stat(img); err != nil {
		if st, err := os.Stat(none); err == nil && time.Since(st.ModTime()) < artistNoPhotoRetry {
			jsonError(w, 404, "no photo for this artist")
			return
		}
		if err := a.fetchArtistImage(id, dir, img); err != nil {
			log.Printf("artist photo: %s: %v", id, err)
			if errors.Is(err, errNoPhoto) { // not asked again for a month; a network problem is retried next time
				os.MkdirAll(dir, 0o755)
				os.WriteFile(none, nil, 0o644)
			}
			jsonError(w, 404, err.Error())
			return
		}
		os.Remove(none)
	}
	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "public, max-age=604800")
	http.ServeFile(w, r, img)
}

func (a *API) fetchArtistImage(id, dir, dest string) error {
	if a.Alacarte == nil {
		return fmt.Errorf("à la carte is not configured")
	}
	ar, err := a.DB.ArtistByID(id)
	if err != nil || ar == nil {
		return fmt.Errorf("unknown artist")
	}
	res, err := a.Alacarte.Search(ar.Name, 5)
	if err != nil {
		return err
	}
	list, _ := res["artists"].([]any)
	want, url := fold(ar.Name), ""
	for _, x := range list {
		m, _ := x.(map[string]any)
		name, _ := m["name"].(string)
		if fold(name) != want {
			continue
		}
		if _, ok := m["artworkTemplate"]; !ok {
			// À la carte too old to give artist photos (rebuild it): not remembered as "no photo"
			return fmt.Errorf("%s: à la carte gives no artist photos, rebuild it (docker compose up -d --build)", ar.Name)
		}
		if t, _ := m["artworkTemplate"].(string); t != "" { // Apple: ".../{w}x{h}bb.{f}"
			url = strings.NewReplacer("{w}", "1400", "{h}", "1400", "{f}", "jpg", "{c}", "").Replace(t)
		} else if u, _ := m["artworkUrlLarge"].(string); u != "" {
			url = u
		}
		break
	}
	if url == "" {
		return fmt.Errorf("%s: %w", ar.Name, errNoPhoto)
	}
	hc := &http.Client{Timeout: 30 * time.Second}
	resp, err := hc.Get(url)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return fmt.Errorf("photo: %s", resp.Status)
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	tmp := dest + ".tmp"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	if _, err := io.Copy(f, resp.Body); err != nil {
		f.Close()
		os.Remove(tmp)
		return err
	}
	f.Close()
	log.Printf("artist photo: %s", ar.Name)
	return os.Rename(tmp, dest)
}
