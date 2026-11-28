package db

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"strings"
	"time"
)

type User struct {
	ID       int64
	Name     string
	Password string
	APIKey   string
	Admin    bool
}

func randomHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// EnsureAdmin creates the first account when there is none. It returns the generated password, if any.
func (d *DB) EnsureAdmin(name, password string) (generated string, err error) {
	var n int
	if err := d.QueryRow(`SELECT COUNT(*) FROM users`).Scan(&n); err != nil {
		return "", err
	}
	if n > 0 {
		return "", nil
	}
	if password == "" {
		password = randomHex(8)
		generated = password
	}
	_, err = d.Exec(`INSERT INTO users (name, password, api_key, admin) VALUES (?, ?, ?, 1)`, name, password, randomHex(20))
	return generated, err
}

func (d *DB) scanUser(row *sql.Row) (*User, error) {
	u := &User{}
	err := row.Scan(&u.ID, &u.Name, &u.Password, &u.APIKey, &u.Admin)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return u, err
}

func (d *DB) UserByName(name string) (*User, error) {
	return d.scanUser(d.QueryRow(`SELECT id, name, password, api_key, admin FROM users WHERE name = ? COLLATE NOCASE`, name))
}

func (d *DB) UserByAPIKey(key string) (*User, error) {
	if key == "" {
		return nil, nil
	}
	return d.scanUser(d.QueryRow(`SELECT id, name, password, api_key, admin FROM users WHERE api_key = ?`, key))
}

func (d *DB) Users() ([]*User, error) {
	rows, err := d.Query(`SELECT id, name, password, api_key, admin FROM users ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*User
	for rows.Next() {
		u := &User{}
		if err := rows.Scan(&u.ID, &u.Name, &u.Password, &u.APIKey, &u.Admin); err != nil {
			return nil, err
		}
		out = append(out, u)
	}
	return out, rows.Err()
}

// ---- Favourites ----

func (d *DB) Star(userID int64, ids []string) error {
	now := time.Now().Unix()
	for _, id := range ids {
		if _, err := d.Exec(`INSERT OR IGNORE INTO stars (user_id, item_id, created_at) VALUES (?, ?, ?)`, userID, id, now); err != nil {
			return err
		}
	}
	return nil
}

// StarAt marks a favourite with the date it was added; a favourite already there takes the older date.
func (d *DB) StarAt(userID int64, id string, at int64) error {
	_, err := d.Exec(`INSERT INTO stars (user_id, item_id, created_at) VALUES (?, ?, ?)
		ON CONFLICT (user_id, item_id) DO UPDATE SET created_at = MIN(created_at, excluded.created_at)`, userID, id, at)
	return err
}

func (d *DB) Unstar(userID int64, ids []string) error {
	for _, id := range ids {
		if _, err := d.Exec(`DELETE FROM stars WHERE user_id = ? AND item_id = ?`, userID, id); err != nil {
			return err
		}
	}
	return nil
}

// Stars returns item id -> starred time for the user.
func (d *DB) Stars(userID int64) (map[string]int64, error) {
	rows, err := d.Query(`SELECT item_id, created_at FROM stars WHERE user_id = ?`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int64{}
	for rows.Next() {
		var id string
		var at int64
		if err := rows.Scan(&id, &at); err != nil {
			return nil, err
		}
		out[id] = at
	}
	return out, rows.Err()
}

// ---- Plays ----

func (d *DB) RecordPlay(userID int64, trackID string, at time.Time) error {
	_, err := d.Exec(`INSERT INTO plays (user_id, track_id, count, last_played) VALUES (?, ?, 1, ?)
		ON CONFLICT(user_id, track_id) DO UPDATE SET count = count + 1, last_played = excluded.last_played`,
		userID, trackID, at.Unix())
	return err
}

// Plays returns track id -> play count for the user.
func (d *DB) Plays(userID int64) (map[string]int, error) {
	rows, err := d.Query(`SELECT track_id, count FROM plays WHERE user_id = ?`, userID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]int{}
	for rows.Next() {
		var id string
		var c int
		if err := rows.Scan(&id, &c); err != nil {
			return nil, err
		}
		out[id] = c
	}
	return out, rows.Err()
}

// ---- Play queue (resume on any device) ----

type PlayQueue struct {
	TrackIDs   []string
	Current    string
	PositionMs int64
	ChangedAt  int64
	ChangedBy  string
}

func (d *DB) SavePlayQueue(userID int64, q PlayQueue) error {
	_, err := d.Exec(`INSERT INTO play_queues (user_id, track_ids, current, position_ms, changed_at, changed_by)
		VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT(user_id) DO UPDATE SET track_ids = excluded.track_ids,
		current = excluded.current, position_ms = excluded.position_ms, changed_at = excluded.changed_at,
		changed_by = excluded.changed_by`,
		userID, strings.Join(q.TrackIDs, ","), q.Current, q.PositionMs, time.Now().UnixMilli(), q.ChangedBy)
	return err
}

func (d *DB) PlayQueue(userID int64) (*PlayQueue, error) {
	var ids string
	q := &PlayQueue{}
	err := d.QueryRow(`SELECT track_ids, current, position_ms, changed_at, changed_by FROM play_queues WHERE user_id = ?`,
		userID).Scan(&ids, &q.Current, &q.PositionMs, &q.ChangedAt, &q.ChangedBy)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	if ids != "" {
		q.TrackIDs = strings.Split(ids, ",")
	}
	return q, nil
}

// ---- Catalog plays (songs listened to from Apple Music without downloading) ----

type CatalogPlay struct {
	AppleID, Title, Artist, Album, AppleAlbumID, Artwork string
	Duration                                             float64
	PlayedAt                                             int64
}

func (d *DB) RecordCatalogPlay(userID int64, p CatalogPlay) error {
	_, err := d.Exec(`INSERT INTO catalog_plays (user_id, apple_id, title, artist, album, apple_album_id, artwork, duration, played_at)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT(user_id, apple_id) DO UPDATE SET
		title = CASE WHEN excluded.title != '' THEN excluded.title ELSE title END,
		artist = CASE WHEN excluded.artist != '' THEN excluded.artist ELSE artist END,
		album = CASE WHEN excluded.album != '' THEN excluded.album ELSE album END,
		apple_album_id = CASE WHEN excluded.apple_album_id != '' THEN excluded.apple_album_id ELSE apple_album_id END,
		artwork = CASE WHEN excluded.artwork != '' THEN excluded.artwork ELSE artwork END,
		duration = CASE WHEN excluded.duration > 0 THEN excluded.duration ELSE duration END,
		played_at = excluded.played_at`,
		userID, p.AppleID, p.Title, p.Artist, p.Album, p.AppleAlbumID, p.Artwork, p.Duration, time.Now().Unix())
	return err
}

func (d *DB) CatalogPlays(userID int64, limit int) ([]CatalogPlay, error) {
	rows, err := d.Query(`SELECT apple_id, title, artist, album, apple_album_id, artwork, duration, played_at
		FROM catalog_plays WHERE user_id = ? ORDER BY played_at DESC LIMIT ?`, userID, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []CatalogPlay
	for rows.Next() {
		var p CatalogPlay
		if err := rows.Scan(&p.AppleID, &p.Title, &p.Artist, &p.Album, &p.AppleAlbumID, &p.Artwork, &p.Duration, &p.PlayedAt); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}
