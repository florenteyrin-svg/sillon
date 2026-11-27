// Package mix asks the Sillon DJ worker for transitions between two library songs and serves them.
package mix

import (
	"bytes"
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// Version is part of every transition's name: changing how transitions are made renders them again.
const Version = "4"

type Client struct {
	URL      string // e.g. http://sillon-dj:8085 (empty: DJ mix off)
	MusicDir string // where the worker sees the music (same mount path)
	Dir      string // transitions folder, shared with the worker
	http     *http.Client
	mu       sync.Mutex
	asked    map[string]time.Time
	segMu    sync.Map // segment key -> *sync.Mutex (one render per segment)
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

// Trim removes transitions not played for 14 days, and DJ segments (whole songs) after a day.
func (c *Client) Trim() {
	entries, _ := os.ReadDir(c.Dir)
	for _, e := range entries {
		info, err := e.Info()
		if err != nil {
			continue
		}
		maxAge := 14 * 24 * time.Hour
		if strings.HasPrefix(e.Name(), "seg-") {
			maxAge = 24 * time.Hour
		}
		if time.Since(info.ModTime()) > maxAge {
			os.Remove(filepath.Join(c.Dir, e.Name()))
		}
	}
}

// SegmentFormat is shared by every DJ segment, so the player goes from one to the next without a gap.
const segmentRate = "44100"

// Segment renders one song of a DJ mix as a single file: the transition that brings it in (from prev,
// when given), then the song itself up to where the transition to next starts (when given).
// Songs touch end to start with no jump inside a file: no cut between them.
// songDur is the song's length in seconds; prev/next transitions must be "done".
func (c *Client) Segment(songRel string, songDur float64, prevKey string, prevT *Transition, nextT *Transition) (string, error) {
	start, end := 0.0, songDur
	if prevT != nil {
		start = prevT.BIn
	}
	if nextT != nil {
		end = nextT.AOut
	}
	if end-start < 1 {
		return "", fmt.Errorf("segment too short")
	}
	h := sha1.Sum([]byte(fmt.Sprintf("%s\x00%s\x00%s\x00%.4f\x00%.4f", Version, songRel, prevKey, start, end)))
	key := "seg-" + hex.EncodeToString(h[:10])
	out := filepath.Join(c.Dir, key+".flac")
	if _, err := os.Stat(out); err == nil {
		now := time.Now()
		os.Chtimes(out, now, now)
		return out, nil
	}
	m, _ := c.segMu.LoadOrStore(key, &sync.Mutex{})
	mu := m.(*sync.Mutex)
	mu.Lock()
	defer mu.Unlock()
	if _, err := os.Stat(out); err == nil {
		return out, nil
	}
	song := filepath.Join(c.MusicDir, filepath.FromSlash(songRel))
	var args []string
	args = append(args, "-v", "error", "-y")
	filter := ""
	if prevT != nil {
		args = append(args, "-i", filepath.Join(c.Dir, prevKey+".flac"))
	}
	args = append(args, "-ss", fmt.Sprintf("%.6f", start), "-t", fmt.Sprintf("%.6f", end-start), "-i", song)
	norm := "aresample=" + segmentRate + ",aformat=sample_fmts=s32:channel_layouts=stereo"
	if prevT != nil {
		filter = "[0:a]" + norm + "[t];[1:a]" + norm + "[s];[t][s]concat=n=2:v=0:a=1[out]"
	} else {
		filter = "[0:a]" + norm + "[out]"
	}
	tmp := out + ".tmp.flac"
	args = append(args, "-filter_complex", filter, "-map", "[out]", "-c:a", "flac", "-ar", segmentRate, tmp)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	defer cancel()
	cmd := exec.CommandContext(ctx, "ffmpeg", args...)
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		os.Remove(tmp)
		return "", fmt.Errorf("ffmpeg: %v %s", err, strings.TrimSpace(stderr.String()))
	}
	if err := os.Rename(tmp, out); err != nil {
		return "", err
	}
	return out, nil
}

// Done returns the finished transition from A to B, or nil (without asking for it).
func (c *Client) Done(aRel, bRel string) (*Transition, string) {
	key := Key(aRel, bRel)
	b, err := os.ReadFile(filepath.Join(c.Dir, key+".json"))
	if err != nil {
		return nil, key
	}
	var t Transition
	if json.Unmarshal(b, &t) != nil || t.Status != "done" {
		return nil, key
	}
	return &t, key
}
