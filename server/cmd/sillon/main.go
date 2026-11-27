// Sillon: self-hosted music server. Indexes the music folder, follows new files instantly,
// serves the Subsonic API (Music Assistant, Symfonium...) and its own API for the Sillon apps.
package main

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/florenteyrin-svg/sillon/server/internal/alacarte"
	"github.com/florenteyrin-svg/sillon/server/internal/api"
	"github.com/florenteyrin-svg/sillon/server/internal/config"
	"github.com/florenteyrin-svg/sillon/server/internal/db"
	"github.com/florenteyrin-svg/sillon/server/internal/events"
	"github.com/florenteyrin-svg/sillon/server/internal/scanner"
	"github.com/florenteyrin-svg/sillon/server/internal/subsonic"
)

var version = "dev"

func main() {
	log.SetFlags(log.LstdFlags)
	cfg := config.Load()
	subsonic.Version = version
	log.Printf("Sillon %s — music: %s, data: %s", version, cfg.MusicDir, cfg.DataDir)

	if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
		log.Fatal(err)
	}
	d, err := db.Open(cfg.DataDir)
	if err != nil {
		log.Fatal(err)
	}
	if pass, err := d.EnsureAdmin(cfg.AdminUser, cfg.AdminPass); err != nil {
		log.Fatal(err)
	} else if pass != "" {
		log.Printf("first start: account %q created with password %s (change it with SILLON_ADMIN_PASSWORD before the first start, or keep this one)", cfg.AdminUser, pass)
	}

	hub := events.NewHub()
	sc := scanner.New(cfg.MusicDir, d, hub)
	covers := &subsonic.Covers{DB: d, MusicDir: cfg.MusicDir, CacheDir: filepath.Join(cfg.DataDir, "covers")}
	sub := &subsonic.Server{DB: d, Scanner: sc, MusicDir: cfg.MusicDir, DataDir: cfg.DataDir, Covers: covers}
	ac := alacarte.New(cfg.AlacarteURL, cfg.AlacarteUser, cfg.AlacartePass)
	a := &api.API{DB: d, Hub: hub, Scanner: sc, Alacarte: ac, Subsonic: sub}

	// A new or changed album may have a new cover
	go func() {
		ch := hub.Subscribe()
		for b := range ch {
			var ev struct {
				Type string `json:"type"`
				Data struct {
					Albums []string `json:"albums"`
				} `json:"data"`
			}
			if json.Unmarshal(b, &ev) == nil && ev.Type == "library.added" {
				for _, id := range ev.Data.Albums {
					covers.ClearAlbum(id)
				}
			}
		}
	}()

	stop := make(chan struct{})
	// Watch first, so files arriving during the first scan are not missed
	go func() {
		if err := sc.Watch(stop); err != nil {
			log.Printf("watch: %v (new files will be found by the periodic rescan)", err)
		}
	}()
	go func() {
		if err := sc.Scan(); err != nil {
			log.Printf("scan: %v", err)
		}
		if cfg.RescanEvery <= 0 {
			return
		}
		t := time.NewTicker(cfg.RescanEvery)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				if err := sc.Scan(); err != nil {
					log.Printf("scan: %v", err)
				}
			}
		}
	}()

	mux := http.NewServeMux()
	sub.Register(mux)
	a.Register(mux)
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Write([]byte("Sillon " + version + "\n"))
	})
	srv := &http.Server{Addr: cfg.Listen, Handler: logRequests(mux), ReadHeaderTimeout: 10 * time.Second}
	go func() {
		log.Printf("listening on %s", cfg.Listen)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	<-sig
	log.Print("stopping")
	close(stop)
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	srv.Shutdown(ctx)
	d.Close()
}

// logRequests logs failed and slow requests only, to keep the log readable.
func logRequests(h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		rw := &statusWriter{ResponseWriter: w, status: 200}
		h.ServeHTTP(rw, r)
		if d := time.Since(start); rw.status >= 400 || (d > 2*time.Second && r.URL.Path != "/api/events") {
			log.Printf("%s %s -> %d (%s)", r.Method, r.URL.Path, rw.status, d.Round(time.Millisecond))
		}
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}
