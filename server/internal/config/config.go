// Package config reads Sillon's settings from environment variables.
package config

import (
	"os"
	"strconv"
	"time"
)

type Config struct {
	MusicDir   string // library root, read (and watched) recursively
	DataDir    string // database, cover cache, mix cache
	Listen     string // HTTP address, e.g. ":4747"
	AdminUser  string // first account, created on first start
	AdminPass  string // its password; generated and logged when empty
	RescanEvery time.Duration // safety rescan in case a file change was missed

	CacheDir   string // songs played from the catalog without being downloaded (shared with à la carte)
	CacheMaxGB float64

	AlacarteURL  string // e.g. http://alacarte-web:7373 (empty: catalog search off)
	AlacarteUser string
	AlacartePass string
}

func Load() Config {
	return Config{
		MusicDir:     env("SILLON_MUSIC_DIR", "/music"),
		DataDir:      env("SILLON_DATA_DIR", "/data"),
		Listen:       env("SILLON_LISTEN", ":4747"),
		AdminUser:    env("SILLON_ADMIN_USER", "admin"),
		AdminPass:    os.Getenv("SILLON_ADMIN_PASSWORD"),
		RescanEvery:  time.Duration(envInt("SILLON_RESCAN_HOURS", 6)) * time.Hour,
		CacheDir:     env("SILLON_CACHE_DIR", "/cache"),
		CacheMaxGB:   float64(envInt("SILLON_CACHE_MAX_GB", 10)),
		AlacarteURL:  os.Getenv("ALACARTE_URL"),
		AlacarteUser: os.Getenv("ALACARTE_USER"),
		AlacartePass: os.Getenv("ALACARTE_PASSWORD"),
	}
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envInt(key string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(key)); err == nil {
		return v
	}
	return def
}
