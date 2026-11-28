// Package db holds Sillon's SQLite database: the library index, users, playlists and play state.
package db

import (
	"crypto/sha1"
	"database/sql"
	"encoding/hex"
	"fmt"
	"path/filepath"
	"strings"

	_ "modernc.org/sqlite"
)

type DB struct{ *sql.DB }

func Open(dataDir string) (*DB, error) {
	path := filepath.Join(dataDir, "sillon.db")
	dsn := "file:" + path + "?_pragma=journal_mode(WAL)&_pragma=busy_timeout(10000)&_pragma=foreign_keys(ON)&_pragma=synchronous(NORMAL)"
	sqlDB, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, err
	}
	// SQLite writes one at a time; a small pool keeps readers fast without lock fights.
	sqlDB.SetMaxOpenConns(8)
	d := &DB{sqlDB}
	if err := d.migrate(); err != nil {
		return nil, fmt.Errorf("migrate: %w", err)
	}
	return d, nil
}

var migrations = []string{
	`CREATE TABLE tracks (
		id TEXT PRIMARY KEY,
		path TEXT NOT NULL UNIQUE,     -- relative to the music folder
		mtime INTEGER NOT NULL,
		size INTEGER NOT NULL,
		title TEXT NOT NULL,
		artist TEXT NOT NULL,
		artist_id TEXT NOT NULL,
		album TEXT NOT NULL,
		album_id TEXT NOT NULL,
		album_artist TEXT NOT NULL,
		album_artist_id TEXT NOT NULL,
		track_no INTEGER NOT NULL DEFAULT 0,
		disc_no INTEGER NOT NULL DEFAULT 0,
		year INTEGER NOT NULL DEFAULT 0,
		genre TEXT NOT NULL DEFAULT '',
		duration REAL NOT NULL DEFAULT 0,  -- seconds
		suffix TEXT NOT NULL,
		codec TEXT NOT NULL,
		lossless INTEGER NOT NULL,
		bit_depth INTEGER NOT NULL DEFAULT 0,
		sample_rate INTEGER NOT NULL DEFAULT 0,
		bitrate INTEGER NOT NULL DEFAULT 0, -- kbps
		channels INTEGER NOT NULL DEFAULT 2,
		quality TEXT NOT NULL,              -- hires | cd | lossy
		has_lyrics INTEGER NOT NULL DEFAULT 0,
		created_at INTEGER NOT NULL
	);
	CREATE INDEX tracks_album ON tracks(album_id, disc_no, track_no);
	CREATE INDEX tracks_artist ON tracks(artist_id);
	CREATE INDEX tracks_album_artist ON tracks(album_artist_id);
	CREATE INDEX tracks_created ON tracks(created_at);
	CREATE INDEX tracks_title ON tracks(title COLLATE NOCASE);

	CREATE TABLE albums (
		id TEXT PRIMARY KEY,
		name TEXT NOT NULL,
		artist TEXT NOT NULL,
		artist_id TEXT NOT NULL,
		year INTEGER NOT NULL DEFAULT 0,
		genre TEXT NOT NULL DEFAULT '',
		song_count INTEGER NOT NULL,
		duration REAL NOT NULL,
		cover_track_id TEXT NOT NULL,       -- a track of the album, to find its cover
		dir TEXT NOT NULL,                  -- folder of that track, for cover.jpg / folder.jpg
		quality TEXT NOT NULL,              -- best quality of its tracks
		created_at INTEGER NOT NULL
	);
	CREATE INDEX albums_artist ON albums(artist_id);
	CREATE INDEX albums_name ON albums(name COLLATE NOCASE);
	CREATE INDEX albums_created ON albums(created_at);

	CREATE TABLE artists (
		id TEXT PRIMARY KEY,
		name TEXT NOT NULL,
		album_count INTEGER NOT NULL,
		cover_album_id TEXT NOT NULL
	);

	CREATE TABLE users (
		id INTEGER PRIMARY KEY,
		name TEXT NOT NULL UNIQUE,
		password TEXT NOT NULL,   -- kept readable: the Subsonic token login needs it
		api_key TEXT NOT NULL UNIQUE,
		admin INTEGER NOT NULL DEFAULT 0
	);

	CREATE TABLE playlists (
		id TEXT PRIMARY KEY,
		user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		name TEXT NOT NULL,
		comment TEXT NOT NULL DEFAULT '',
		public INTEGER NOT NULL DEFAULT 0,
		rules TEXT,               -- JSON rules of a smart playlist, NULL for a normal one
		created_at INTEGER NOT NULL,
		updated_at INTEGER NOT NULL
	);
	CREATE TABLE playlist_tracks (
		playlist_id TEXT NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,
		pos INTEGER NOT NULL,
		track_id TEXT NOT NULL,
		PRIMARY KEY (playlist_id, pos)
	);

	CREATE TABLE stars (
		user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		item_id TEXT NOT NULL,
		created_at INTEGER NOT NULL,
		PRIMARY KEY (user_id, item_id)
	);

	CREATE TABLE plays (
		user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		track_id TEXT NOT NULL,
		count INTEGER NOT NULL DEFAULT 0,
		last_played INTEGER NOT NULL DEFAULT 0,
		PRIMARY KEY (user_id, track_id)
	);

	CREATE TABLE play_queues (
		user_id INTEGER PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
		track_ids TEXT NOT NULL,  -- comma separated
		current TEXT NOT NULL DEFAULT '',
		position_ms INTEGER NOT NULL DEFAULT 0,
		changed_at INTEGER NOT NULL,
		changed_by TEXT NOT NULL DEFAULT ''
	);`,
	// Songs played from the Apple Music catalog (not in the library), for "Recently played"
	`CREATE TABLE catalog_plays (
		user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
		apple_id TEXT NOT NULL,
		title TEXT NOT NULL DEFAULT '',
		artist TEXT NOT NULL DEFAULT '',
		album TEXT NOT NULL DEFAULT '',
		apple_album_id TEXT NOT NULL DEFAULT '',
		artwork TEXT NOT NULL DEFAULT '',
		duration REAL NOT NULL DEFAULT 0,
		played_at INTEGER NOT NULL,
		PRIMARY KEY (user_id, apple_id)
	);
	CREATE INDEX catalog_plays_recent ON catalog_plays(user_id, played_at);`,
	// Track ids now keep the path's letter case: index everything again with the new ids
	`DELETE FROM tracks; DELETE FROM albums; DELETE FROM artists;`,
	// Dolby Atmos files are no longer part of the library (they doubled the FLAC albums)
	`DELETE FROM tracks WHERE quality = 'atmos'; DELETE FROM albums WHERE quality = 'atmos';`,
}

func (d *DB) migrate() error {
	if _, err := d.Exec(`CREATE TABLE IF NOT EXISTS schema_version (v INTEGER NOT NULL)`); err != nil {
		return err
	}
	var v int
	if err := d.QueryRow(`SELECT COALESCE(MAX(v), 0) FROM schema_version`).Scan(&v); err != nil {
		return err
	}
	for i := v; i < len(migrations); i++ {
		tx, err := d.Begin()
		if err != nil {
			return err
		}
		if _, err := tx.Exec(migrations[i]); err != nil {
			tx.Rollback()
			return fmt.Errorf("migration %d: %w", i+1, err)
		}
		if _, err := tx.Exec(`INSERT INTO schema_version (v) VALUES (?)`, i+1); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
	}
	return nil
}

// ExactID is like ID but keeps the letter case (file paths are case sensitive).
func ExactID(prefix string, parts ...string) string {
	h := sha1.Sum([]byte(strings.Join(parts, "\x00")))
	return prefix + hex.EncodeToString(h[:8])
}

// ID builds a short stable id with a type prefix (tr-, al-, ar-) from the given parts.
func ID(prefix string, parts ...string) string {
	h := sha1.Sum([]byte(strings.ToLower(strings.Join(parts, "\x00"))))
	return prefix + hex.EncodeToString(h[:8])
}
