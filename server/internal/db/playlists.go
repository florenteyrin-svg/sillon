package db

import (
	"database/sql"
	"errors"
	"time"
)

type Playlist struct {
	ID        string
	UserID    int64
	Owner     string
	Name      string
	Comment   string
	Public    bool
	Rules     string // JSON of a smart playlist, empty for a normal one
	CreatedAt int64
	UpdatedAt int64
	SongCount int
	Duration  float64
}

func (p *Playlist) Smart() bool { return p.Rules != "" }

// Playlists lists the playlists the user can see (their own and public ones).
func (d *DB) Playlists(userID int64) ([]*Playlist, error) {
	return d.queryPlaylists(`WHERE p.user_id = ? OR p.public = 1 ORDER BY p.name COLLATE NOCASE`, userID)
}

func (d *DB) PlaylistByID(id string) (*Playlist, error) {
	ps, err := d.queryPlaylists(`WHERE p.id = ?`, id)
	if err != nil || len(ps) == 0 {
		return nil, err
	}
	return ps[0], nil
}

func (d *DB) queryPlaylists(tail string, args ...any) ([]*Playlist, error) {
	rows, err := d.Query(`SELECT p.id, p.user_id, u.name, p.name, p.comment, p.public, COALESCE(p.rules, ''),
		p.created_at, p.updated_at,
		(SELECT COUNT(*) FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id WHERE pt.playlist_id = p.id),
		(SELECT COALESCE(SUM(t.duration), 0) FROM playlist_tracks pt JOIN tracks t ON t.id = pt.track_id WHERE pt.playlist_id = p.id)
		FROM playlists p JOIN users u ON u.id = p.user_id `+tail, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Playlist
	for rows.Next() {
		p := &Playlist{}
		if err := rows.Scan(&p.ID, &p.UserID, &p.Owner, &p.Name, &p.Comment, &p.Public, &p.Rules, &p.CreatedAt,
			&p.UpdatedAt, &p.SongCount, &p.Duration); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// PlaylistTracks returns the tracks of a normal playlist in order (missing files are skipped).
func (d *DB) PlaylistTracks(id string) ([]*Track, error) {
	return d.QueryTracks(`JOIN playlist_tracks pt ON pt.track_id = t.id WHERE pt.playlist_id = ? ORDER BY pt.pos`, id)
}

func (d *DB) CreatePlaylist(userID int64, name, rules string) (*Playlist, error) {
	now := time.Now().Unix()
	id := ID("pl-", name, randomHex(8))
	var r any
	if rules != "" {
		r = rules
	}
	if _, err := d.Exec(`INSERT INTO playlists (id, user_id, name, rules, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)`,
		id, userID, name, r, now, now); err != nil {
		return nil, err
	}
	return d.PlaylistByID(id)
}

// SetPlaylistTracks replaces the whole track list.
func (d *DB) SetPlaylistTracks(id string, trackIDs []string) error {
	tx, err := d.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM playlist_tracks WHERE playlist_id = ?`, id); err != nil {
		return err
	}
	for i, tid := range trackIDs {
		if _, err := tx.Exec(`INSERT INTO playlist_tracks (playlist_id, pos, track_id) VALUES (?, ?, ?)`, id, i, tid); err != nil {
			return err
		}
	}
	if _, err := tx.Exec(`UPDATE playlists SET updated_at = ? WHERE id = ?`, time.Now().Unix(), id); err != nil {
		return err
	}
	return tx.Commit()
}

// PlaylistTrackIDs returns the raw stored ids in order, including ids of files gone from the library.
func (d *DB) PlaylistTrackIDs(id string) ([]string, error) {
	rows, err := d.Query(`SELECT track_id FROM playlist_tracks WHERE playlist_id = ? ORDER BY pos`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			return nil, err
		}
		out = append(out, s)
	}
	return out, rows.Err()
}

func (d *DB) UpdatePlaylist(id string, name, comment *string, public *bool, rules *string) error {
	if name != nil {
		if _, err := d.Exec(`UPDATE playlists SET name = ? WHERE id = ?`, *name, id); err != nil {
			return err
		}
	}
	if comment != nil {
		if _, err := d.Exec(`UPDATE playlists SET comment = ? WHERE id = ?`, *comment, id); err != nil {
			return err
		}
	}
	if public != nil {
		if _, err := d.Exec(`UPDATE playlists SET public = ? WHERE id = ?`, *public, id); err != nil {
			return err
		}
	}
	if rules != nil {
		if _, err := d.Exec(`UPDATE playlists SET rules = ? WHERE id = ?`, *rules, id); err != nil {
			return err
		}
	}
	_, err := d.Exec(`UPDATE playlists SET updated_at = ? WHERE id = ?`, time.Now().Unix(), id)
	return err
}

func (d *DB) DeletePlaylist(id string) error {
	res, err := d.Exec(`DELETE FROM playlists WHERE id = ?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

var ErrNotFound = errors.New("not found")
