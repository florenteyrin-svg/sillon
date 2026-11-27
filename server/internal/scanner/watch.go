package scanner

import (
	"io/fs"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/fsnotify/fsnotify"
)

// quiet is how long a file must stay untouched before it is read, so a download in progress is read once, complete.
const quiet = 2 * time.Second

// Watch follows changes in the music folder and indexes them within a few seconds. It blocks.
func (s *Scanner) Watch(stop <-chan struct{}) error {
	w, err := fsnotify.NewWatcher()
	if err != nil {
		return err
	}
	defer w.Close()
	n := s.addTree(w, s.root)
	log.Printf("watch: following %d folders", n)

	pending := map[string]time.Time{}
	tick := time.NewTicker(500 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-stop:
			return nil
		case ev, ok := <-w.Events:
			if !ok {
				return nil
			}
			name := ev.Name
			if strings.HasPrefix(filepath.Base(name), ".") {
				continue
			}
			if ev.Has(fsnotify.Create) {
				if info, err := os.Stat(name); err == nil && info.IsDir() {
					s.addTree(w, name)
				}
			}
			if ev.Has(fsnotify.Chmod) && !ev.Has(fsnotify.Write) {
				continue
			}
			pending[name] = time.Now()
		case err, ok := <-w.Errors:
			if !ok {
				return nil
			}
			log.Printf("watch: %v", err)
		case <-tick.C:
			if len(pending) == 0 {
				continue
			}
			var ready []string
			for p, at := range pending {
				if time.Since(at) >= quiet {
					ready = append(ready, p)
					delete(pending, p)
				}
			}
			if len(ready) > 0 {
				s.UpdatePaths(ready)
			}
		}
	}
}

// addTree watches a folder and all its sub-folders (inotify is not recursive).
func (s *Scanner) addTree(w *fsnotify.Watcher, dir string) int {
	n := 0
	filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || !d.IsDir() {
			return nil
		}
		if p != s.root && strings.HasPrefix(d.Name(), ".") {
			return filepath.SkipDir
		}
		if err := w.Add(p); err != nil {
			log.Printf("watch: cannot follow %s: %v (raise fs.inotify.max_user_watches on the host)", p, err)
			return filepath.SkipDir
		}
		n++
		return nil
	})
	return n
}
