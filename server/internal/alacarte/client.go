// Package alacarte talks to à la carte (Apple Music downloader) on the internal Docker network,
// so the apps search and download through Sillon only: à la carte itself is never exposed.
package alacarte

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

type Client struct {
	base, user, pass string
	http             *http.Client
	mu               sync.Mutex
	cookie           string
}

func New(base, user, pass string) *Client {
	if base == "" {
		return nil
	}
	return &Client{base: strings.TrimRight(base, "/"), user: user, pass: pass, http: &http.Client{Timeout: 20 * time.Second}}
}

type Error struct {
	Status  int
	Message string
}

func (e *Error) Error() string { return fmt.Sprintf("à la carte: %d %s", e.Status, e.Message) }

func (c *Client) login() error {
	body, _ := json.Marshal(map[string]string{"username": c.user, "password": c.pass})
	req, _ := http.NewRequest("POST", c.base+"/api/auth/login", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Origin", c.base) // à la carte refuses state-changing requests from another origin
	resp, err := c.http.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return readError(resp)
	}
	var parts []string
	for _, ck := range resp.Cookies() {
		parts = append(parts, ck.Name+"="+ck.Value)
	}
	c.cookie = strings.Join(parts, "; ")
	return nil
}

func readError(resp *http.Response) error {
	b, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	var e struct {
		Error string `json:"error"`
	}
	if json.Unmarshal(b, &e) == nil && e.Error != "" {
		return &Error{resp.StatusCode, e.Error}
	}
	return &Error{resp.StatusCode, http.StatusText(resp.StatusCode)}
}

// Do sends a request and decodes the JSON answer into out (when not nil). It signs in again once on 401.
func (c *Client) Do(method, path string, in, out any) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	for attempt := 0; attempt < 2; attempt++ {
		if c.cookie == "" && c.user != "" {
			if err := c.login(); err != nil {
				return err
			}
		}
		var body io.Reader
		if in != nil {
			b, _ := json.Marshal(in)
			body = bytes.NewReader(b)
		}
		req, _ := http.NewRequest(method, c.base+path, body)
		if in != nil {
			req.Header.Set("Content-Type", "application/json")
		}
		req.Header.Set("Origin", c.base)
		if c.cookie != "" {
			req.Header.Set("Cookie", c.cookie)
		}
		resp, err := c.http.Do(req)
		if err != nil {
			return err
		}
		if resp.StatusCode == 401 && attempt == 0 {
			resp.Body.Close()
			c.cookie = ""
			continue
		}
		defer resp.Body.Close()
		if resp.StatusCode >= 300 {
			return readError(resp)
		}
		if out == nil {
			return nil
		}
		return json.NewDecoder(resp.Body).Decode(out)
	}
	return &Error{401, "sign-in refused"}
}

// Search queries the Apple Music catalog. The answer is passed through as is (albums, artists, songs, playlists).
func (c *Client) Search(q string, limit int) (map[string]any, error) {
	var out map[string]any
	path := fmt.Sprintf("/api/search?q=%s&limit=%d&types=albums,artists,songs,playlists", url.QueryEscape(q), limit)
	return out, c.Do("GET", path, nil, &out)
}

func (c *Client) Album(id string) (map[string]any, error) {
	var out map[string]any
	return out, c.Do("GET", "/api/album/"+url.PathEscape(id), nil, &out)
}

func (c *Client) Artist(id string) (map[string]any, error) {
	var out map[string]any
	return out, c.Do("GET", "/api/artist/"+url.PathEscape(id), nil, &out)
}

// Download queues an album, a song or a playlist.
func (c *Client) Download(kind, id, albumID string) (map[string]any, error) {
	var out map[string]any
	switch kind {
	case "album":
		return out, c.Do("POST", "/api/download", map[string]any{"albumId": id}, &out)
	case "song":
		body := map[string]any{"songId": id}
		if albumID != "" {
			body["albumId"] = albumID
		}
		return out, c.Do("POST", "/api/download/song", body, &out)
	case "playlist":
		return out, c.Do("POST", "/api/download/playlist", map[string]any{"playlistId": id}, &out)
	}
	return nil, fmt.Errorf("unknown download type %q", kind)
}

// Jobs returns the current queue and the recent history.
func (c *Client) Jobs() ([]map[string]any, error) {
	var q, h struct {
		Jobs []map[string]any `json:"jobs"`
	}
	if err := c.Do("GET", "/api/queue", nil, &q); err != nil {
		return nil, err
	}
	if err := c.Do("GET", "/api/queue/history?limit=50", nil, &h); err != nil {
		return q.Jobs, nil
	}
	seen := map[any]bool{}
	var out []map[string]any
	for _, j := range append(q.Jobs, h.Jobs...) { // queue entries first: they are the freshest
		if !seen[j["id"]] {
			seen[j["id"]] = true
			out = append(out, j)
		}
	}
	return out, nil
}

// StartStream asks à la carte to fetch a song into the stream cache (see alacarte/backend/routes/stream.mjs).
func (c *Client) StartStream(id, albumID string) (string, error) {
	var out struct {
		Status string `json:"status"`
	}
	body := map[string]any{}
	if albumID != "" {
		body["albumId"] = albumID
	}
	err := c.Do("POST", "/api/stream/song/"+url.PathEscape(id), body, &out)
	return out.Status, err
}
