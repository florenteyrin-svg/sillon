// Package streamcache plays Apple Music catalog songs right away: à la carte writes the song into the
// cache folder while it decrypts it, and Sillon sends the audio to the phone as the file grows.
// The cache lives outside the music folder and keeps its size under a limit (oldest songs go first).
package streamcache

import (
	"encoding/json"
	"errors"
	"io"
	"io/fs"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"
)

type Cache struct {
	Dir      string
	MaxBytes int64
}

type Status struct {
	Status string `json:"status"` // running | done | failed
	File   string `json:"file"`   // audio file, relative to the song folder (when done)
	Error  string `json:"error"`
	At     int64  `json:"at"`
}

var idRe = regexp.MustCompile(`^[0-9]{1,20}$`)

func ValidID(id string) bool { return idRe.MatchString(id) }

func (c *Cache) dir(id string) string { return filepath.Join(c.Dir, id) }

func (c *Cache) Status(id string) *Status {
	b, err := os.ReadFile(filepath.Join(c.dir(id), "status.json"))
	if err != nil {
		return nil
	}
	var s Status
	if json.Unmarshal(b, &s) != nil {
		return nil
	}
	return &s
}

// Ready tells whether the song is fully in the cache.
func (c *Cache) Ready(id string) bool {
	s := c.Status(id)
	return s != nil && s.Status == "done"
}

// findAudio returns the song's audio file: the finished file when there is one, else the growing ".part".
func (c *Cache) findAudio(id string) (path string, partial bool) {
	if s := c.Status(id); s != nil && s.Status == "done" && s.File != "" {
		p := filepath.Join(c.dir(id), filepath.FromSlash(s.File))
		if _, err := os.Stat(p); err == nil {
			return p, false
		}
	}
	var final, part string
	filepath.WalkDir(c.dir(id), func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		name := strings.ToLower(d.Name())
		switch {
		case strings.HasSuffix(name, ".m4a.part") || strings.HasSuffix(name, ".mp4.part"):
			part = p
		case strings.HasSuffix(name, ".m4a") || strings.HasSuffix(name, ".mp4") || strings.HasSuffix(name, ".flac"):
			final = p
		}
		return nil
	})
	if final != "" {
		return final, c.Status(id) == nil || c.Status(id).Status == "running"
	}
	return part, part != ""
}

// Serve sends the song: the finished file (with seeking), or the growing file as it is written.
func (c *Cache) Serve(w http.ResponseWriter, r *http.Request, id string) {
	now := time.Now()
	os.Chtimes(c.dir(id), now, now) // most recently played = kept longest

	// Wait for the first bytes (Apple's licence and the first fragments take a few seconds)
	deadline := time.Now().Add(30 * time.Second)
	var path string
	var partial bool
	for {
		if s := c.Status(id); s != nil && s.Status == "failed" {
			http.Error(w, "Apple Music: "+s.Error, http.StatusBadGateway)
			return
		}
		path, partial = c.findAudio(id)
		if path != "" {
			if info, err := os.Stat(path); err == nil && info.Size() > 64*1024 {
				break
			}
		}
		if time.Now().After(deadline) || r.Context().Err() != nil {
			http.Error(w, "the song is not ready", http.StatusGatewayTimeout)
			return
		}
		time.Sleep(150 * time.Millisecond)
	}
	w.Header().Set("Content-Type", "audio/mp4")
	if !partial {
		http.ServeFile(w, r, path)
		return
	}
	c.tail(w, r, id, path)
}

// tail streams a file that is still being written, until the download is over and everything was sent.
func (c *Cache) tail(w http.ResponseWriter, r *http.Request, id, path string) {
	f, err := os.Open(path)
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	defer f.Close() // the open file stays readable even when amdp renames it at the end
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(http.StatusOK)
	flusher, _ := w.(http.Flusher)
	buf := make([]byte, 256*1024)
	lastGrowth := time.Now()
	for {
		n, err := f.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if flusher != nil {
				flusher.Flush()
			}
			lastGrowth = time.Now()
			continue
		}
		if err != nil && !errors.Is(err, io.EOF) {
			return
		}
		s := c.Status(id)
		if s != nil && s.Status != "running" {
			// Finished: send what was written after our last read, then stop
			io.Copy(w, f)
			return
		}
		if time.Since(lastGrowth) > 60*time.Second || r.Context().Err() != nil {
			return
		}
		time.Sleep(120 * time.Millisecond)
	}
}

// Remove drops a song from the cache (e.g. once it is downloaded into the library).
func (c *Cache) Remove(id string) {
	if s := c.Status(id); s != nil && s.Status == "running" {
		return
	}
	os.RemoveAll(c.dir(id))
}

// Trim deletes the least recently played songs until the cache is under its limit.
func (c *Cache) Trim() {
	entries, err := os.ReadDir(c.Dir)
	if err != nil {
		return
	}
	type item struct {
		id   string
		size int64
		at   time.Time
	}
	var items []item
	var total int64
	for _, e := range entries {
		if !e.IsDir() || !ValidID(e.Name()) {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		var size int64
		filepath.WalkDir(c.dir(e.Name()), func(p string, d fs.DirEntry, err error) error {
			if err == nil && !d.IsDir() {
				if i, err := d.Info(); err == nil {
					size += i.Size()
				}
			}
			return nil
		})
		total += size
		items = append(items, item{e.Name(), size, info.ModTime()})
	}
	if total <= c.MaxBytes {
		return
	}
	sort.Slice(items, func(i, j int) bool { return items[i].at.Before(items[j].at) })
	for _, it := range items {
		if total <= c.MaxBytes {
			break
		}
		if s := c.Status(it.id); s != nil && s.Status == "running" {
			continue
		}
		if os.RemoveAll(c.dir(it.id)) == nil {
			total -= it.size
			log.Printf("cache: removed %s (%d MB)", it.id, it.size>>20)
		}
	}
}

// Run trims the cache every 10 minutes. It blocks.
func (c *Cache) Run(stop <-chan struct{}) {
	t := time.NewTicker(10 * time.Minute)
	defer t.Stop()
	c.Trim()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			c.Trim()
		}
	}
}
