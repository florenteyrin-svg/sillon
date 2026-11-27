// Package mix asks the Sillon DJ worker for transitions between two library songs and serves them.
package mix

import (
	"bytes"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// Version is part of every transition's name: changing how transitions are made renders them again.
const Version = "1"

type Client struct {
	URL      string // e.g. http://sillon-dj:8085 (empty: DJ mix off)
	MusicDir string // where the worker sees the music (same mount path)
	Dir      string // transitions folder, shared with the worker
	http     *http.Client
	mu       sync.Mutex
	asked    map[string]time.Time
}

func New(url, musicDir, dir string) *Client {
	if url == "" {
		return nil
	}
	os.MkdirAll(dir, 0o755)
	return &Client{URL: url, MusicDir: musicDir, Dir: dir, http: &http.Client{Timeout: 10 * time.Second}, asked: map[string]time.Time{}}
}

type Transition struct {
	Status string  `json:"status"` // preparing | done | failed
	AOut   float64 `json:"aOut"`   // seconds in song A where the transition starts
	BIn    float64 `json:"bIn"`    // seconds in song B where it carries on after the transition
	Length float64 `json:"length"`
	Style  string  `json:"style"` // vocal-swap | crossfade
	Error  string  `json:"error,omitempty"`
	URL    string  `json:"url,omitempty"`
}

func Key(aPath, bPath string) string {
	h := sha1.Sum([]byte(Version + "\x00" + aPath + "\x00" + bPath))
	return "mx-" + hex.EncodeToString(h[:10])
}

// Get returns the transition from A to B (paths relative to the music folder), asking for it if needed.
func (c *Client) Get(aRel, bRel string) (*Transition, error) {
	key := Key(aRel, bRel)
	if b, err := os.ReadFile(filepath.Join(c.Dir, key+".json")); err == nil {
		var t Transition
		if json.Unmarshal(b, &t) == nil {
			if t.Status == "done" {
				t.URL = "/api/mix/file/" + key
				now := time.Now()
				os.Chtimes(filepath.Join(c.Dir, key+".flac"), now, now)
			}
			return &t, nil
		}
	}
	c.mu.Lock()
	at, asked := c.asked[key]
	if asked && time.Since(at) < 3*time.Minute {
		c.mu.Unlock()
		return &Transition{Status: "preparing"}, nil
	}
	c.asked[key] = time.Now()
	c.mu.Unlock()
	body, _ := json.Marshal(map[string]string{
		"a": filepath.Join(c.MusicDir, filepath.FromSlash(aRel)), "b": filepath.Join(c.MusicDir, filepath.FromSlash(bRel)),
		"out": c.Dir, "key": key,
	})
	resp, err := c.http.Post(c.URL+"/render", "application/json", bytes.NewReader(body))
	if err != nil {
		c.mu.Lock()
		delete(c.asked, key)
		c.mu.Unlock()
		return nil, fmt.Errorf("DJ worker: %w", err)
	}
	resp.Body.Close()
	if resp.StatusCode >= 300 {
		return nil, fmt.Errorf("DJ worker answered %d", resp.StatusCode)
	}
	return &Transition{Status: "preparing"}, nil
}

// File returns the path of a finished transition, for a valid key only.
func (c *Client) File(key string) (string, bool) {
	if len(key) != 23 || key[:3] != "mx-" {
		return "", false
	}
	if _, err := hex.DecodeString(key[3:]); err != nil {
		return "", false
	}
	p := filepath.Join(c.Dir, key+".flac")
	_, err := os.Stat(p)
	return p, err == nil
}

// Trim removes transitions not played for 14 days.
func (c *Client) Trim() {
	entries, _ := os.ReadDir(c.Dir)
	for _, e := range entries {
		if info, err := e.Info(); err == nil && time.Since(info.ModTime()) > 14*24*time.Hour {
			os.Remove(filepath.Join(c.Dir, e.Name()))
		}
	}
}
