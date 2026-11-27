// Package scanner indexes the music folder and keeps the index in sync with the files.
package scanner

import (
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
	"github.com/florenteyrin-svg/sillon/server/internal/events"
)

type Scanner struct {
	root string
	db   *db.DB
	hub  *events.Hub

	mu       sync.Mutex // one full scan at a time; single-file updates also take it
	scanning atomic.Bool
	count    atomic.Int64
	lastScan atomic.Int64
}

func New(root string, d *db.DB, hub *events.Hub) *Scanner {
	return &Scanner{root: root, db: d, hub: hub}
}

func (s *Scanner) Scanning() bool { return s.scanning.Load() }
func (s *Scanner) Count() int64   { return s.count.Load() }
func (s *Scanner) LastScan() time.Time {
	return time.Unix(s.lastScan.Load(), 0)
}

// rel turns an absolute path into the library-relative form stored in the database.
func (s *Scanner) rel(full string) (string, bool) {
	r, err := filepath.Rel(s.root, full)
	if err != nil || r == "." || strings.HasPrefix(r, "..") {
		return "", false
	}
	return filepath.ToSlash(r), true
}

func isAudio(name string) bool {
	if strings.HasPrefix(filepath.Base(name), ".") { // macOS "._" files and hidden temp files
		return false
	}
	return AudioExts[strings.ToLower(filepath.Ext(name))]
}

// Scan walks the whole library: new and changed files are read, missing ones removed.
// Unchanged files (same modification time and size) are skipped, so a rescan of 20k tracks takes seconds.
func (s *Scanner) Scan() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.scanning.Store(true)
	defer s.scanning.Store(false)
	start := time.Now()
	s.hub.Publish(events.Event{Type: "library.scan", Data: map[string]any{"scanning": true}})

	known, err := s.db.Known()
	if err != nil {
		return err
	}
	seen := make(map[string]bool, len(known))
	type job struct {
		rel  string
		info os.FileInfo
	}
	jobs := make(chan job, 256)
	results := make(chan *db.Track, 256)
	var wg sync.WaitGroup
	for i := 0; i < runtime.NumCPU(); i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range jobs {
				t, err := probe(s.root, j.rel, j.info)
				if err != nil {
					log.Printf("scan: skip %s: %v", j.rel, err)
					continue
				}
				results <- t
			}
		}()
	}
	var written, changed int
	done := make(chan error, 1)
	go func() {
		var batch []*db.Track
		var werr error
		flush := func() {
			if werr == nil {
				werr = s.db.UpsertTracks(batch)
			}
			written += len(batch)
			batch = batch[:0]
		}
		for t := range results {
			batch = append(batch, t)
			if len(batch) >= 500 {
				flush()
				log.Printf("scan: %d files read", written)
			}
		}
		flush()
		done <- werr
	}()

	walkErr := filepath.WalkDir(s.root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			log.Printf("scan: %s: %v", p, err)
			return nil
		}
		if d.IsDir() {
			if p != s.root && strings.HasPrefix(d.Name(), ".") {
				return filepath.SkipDir
			}
			return nil
		}
		if !isAudio(p) {
			return nil
		}
		rel, ok := s.rel(p)
		if !ok {
			return nil
		}
		seen[rel] = true
		info, err := d.Info()
		if err != nil {
			return nil
		}
		if k, ok := known[rel]; ok && k[0] == info.ModTime().Unix() && k[1] == info.Size() {
			return nil
		}
		changed++
		jobs <- job{rel, info}
		return nil
	})
	close(jobs)
	wg.Wait()
	close(results)
	if err := <-done; err != nil {
		return err
	}
	if walkErr != nil {
		return walkErr
	}

	// A missing music folder (unmounted disk) must not wipe the library
	var gone []string
	if len(seen) > 0 || len(known) == 0 {
		for p := range known {
			if !seen[p] {
				gone = append(gone, p)
			}
		}
	}
	if len(gone) > 0 {
		if _, err := s.db.DeletePaths(gone, false); err != nil {
			return err
		}
	}
	if changed > 0 || len(gone) > 0 {
		if err := s.db.RefreshAggregates(); err != nil {
			return err
		}
	}
	s.count.Store(int64(len(seen)))
	s.lastScan.Store(time.Now().Unix())
	log.Printf("scan: done in %s, %d tracks, %d new or changed, %d removed",
		time.Since(start).Round(time.Millisecond), len(seen), written, len(gone))
	s.hub.Publish(events.Event{Type: "library.scan", Data: map[string]any{
		"scanning": false, "tracks": len(seen), "changed": written, "removed": len(gone)}})
	return nil
}

// UpdatePaths indexes the given files or folders right away (used by the watcher), and reports what changed.
func (s *Scanner) UpdatePaths(fulls []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var added []*db.Track
	var removed []string
	for _, full := range fulls {
		rel, ok := s.rel(full)
		if !ok {
			continue
		}
		info, err := os.Stat(full)
		if err != nil { // deleted or renamed away: drop the file, or everything under the folder
			if n, err := s.db.DeletePaths([]string{rel}, true); err == nil && n > 0 {
				removed = append(removed, rel)
			}
			continue
		}
		if info.IsDir() {
			filepath.WalkDir(full, func(p string, d fs.DirEntry, err error) error {
				if err == nil && !d.IsDir() && isAudio(p) {
					if r, ok := s.rel(p); ok {
						if i, err := d.Info(); err == nil {
							if t, err := probe(s.root, r, i); err == nil {
								added = append(added, t)
							}
						}
					}
				}
				return nil
			})
			continue
		}
		if !isAudio(full) {
			continue
		}
		t, err := probe(s.root, rel, info)
		if err != nil {
			log.Printf("watch: skip %s: %v", rel, err)
			continue
		}
		added = append(added, t)
	}
	// A new folder and the files inside it can both be in the list: keep each track once
	uniq := added[:0]
	seenIDs := map[string]bool{}
	for _, t := range added {
		if !seenIDs[t.ID] {
			seenIDs[t.ID] = true
			uniq = append(uniq, t)
		}
	}
	added = uniq
	if len(added) == 0 && len(removed) == 0 {
		return
	}
	if err := s.db.UpsertTracks(added); err != nil {
		log.Printf("watch: %v", err)
		return
	}
	if err := s.db.RefreshAggregates(); err != nil {
		log.Printf("watch: %v", err)
	}
	if len(added) > 0 {
		ids := make([]string, 0, len(added))
		albums := map[string]bool{}
		for _, t := range added {
			ids = append(ids, t.ID)
			albums[t.AlbumID] = true
			log.Printf("library: + %s — %s (%s)", t.Artist, t.Title, t.Quality)
		}
		albumIDs := make([]string, 0, len(albums))
		for a := range albums {
			albumIDs = append(albumIDs, a)
		}
		s.hub.Publish(events.Event{Type: "library.added", Data: map[string]any{"tracks": ids, "albums": albumIDs}})
	}
	if len(removed) > 0 {
		s.hub.Publish(events.Event{Type: "library.removed", Data: map[string]any{"paths": removed}})
	}
}
