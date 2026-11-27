package db

import (
	"database/sql"
	"path"
	"time"
)

// Track is one audio file of the library.
type Track struct {
	ID            string
	Path          string // relative to the music folder, with forward slashes
	Mtime         int64
	Size          int64
	Title         string
	Artist        string
	ArtistID      string
	Album         string
	AlbumID       string
	AlbumArtist   string
	AlbumArtistID string
	TrackNo       int
	DiscNo        int
	Year          int
	Genre         string
	Duration      float64
	Suffix        string
	Codec         string
	Lossless      bool
	BitDepth      int
	SampleRate    int
	Bitrate       int
	Channels      int
	Quality       string
	Atmos         bool // set by the scanner; not stored apart (quality = atmos)
	HasLyrics     bool
	CreatedAt     int64
}

// Quality labels shown as a logo in the apps.
const (
	QualityHiRes = "hires" // lossless, more than 16 bit or more than 48 kHz
	QualityCD    = "cd"    // lossless, 16 bit / 44.1 or 48 kHz
	QualityLossy = "lossy" // MP3, AAC, Opus...
	QualityAtmos = "atmos" // Dolby Atmos (E-AC-3 JOC, TrueHD)
)

func QualityOf(lossless bool, bitDepth, sampleRate int) string {
	switch {
	case !lossless:
		return QualityLossy
	case bitDepth > 16 || sampleRate > 48000:
		return QualityHiRes
	default:
		return QualityCD
	}
}

// FillIDs computes the track, artist and album ids from its tags and path.
func (t *Track) FillIDs() {
	t.ID = ID("tr-", t.Path)
	if t.AlbumArtist == "" {
		t.AlbumArtist = t.Artist
	}
	t.ArtistID = ID("ar-", t.Artist)
	t.AlbumArtistID = ID("ar-", t.AlbumArtist)
	// Same album name by the same album artist = one album, wherever its files are.
	// The Dolby Atmos version of an album stays a separate album.
	t.Quality = QualityOf(t.Lossless, t.BitDepth, t.SampleRate)
	if t.Atmos {
		t.Quality = QualityAtmos
		t.AlbumID = ID("al-", t.AlbumArtist, t.Album, "atmos")
	} else {
		t.AlbumID = ID("al-", t.AlbumArtist, t.Album)
	}
}

// Known returns path -> (mtime, size) of every indexed file, to skip unchanged files when scanning.
func (d *DB) Known() (map[string][2]int64, error) {
	rows, err := d.Query(`SELECT path, mtime, size FROM tracks`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string][2]int64{}
	for rows.Next() {
		var p string
		var m, s int64
		if err := rows.Scan(&p, &m, &s); err != nil {
			return nil, err
		}
		out[p] = [2]int64{m, s}
	}
	return out, rows.Err()
}

// UpsertTracks writes tracks in one transaction. A track keeps its first created_at.
func (d *DB) UpsertTracks(tracks []*Track) error {
	if len(tracks) == 0 {
		return nil
	}
	tx, err := d.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	stmt, err := tx.Prepare(`INSERT INTO tracks (id, path, mtime, size, title, artist, artist_id, album, album_id,
		album_artist, album_artist_id, track_no, disc_no, year, genre, duration, suffix, codec, lossless, bit_depth,
		sample_rate, bitrate, channels, quality, has_lyrics, created_at)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
		ON CONFLICT(path) DO UPDATE SET mtime=excluded.mtime, size=excluded.size, title=excluded.title,
		artist=excluded.artist, artist_id=excluded.artist_id, album=excluded.album, album_id=excluded.album_id,
		album_artist=excluded.album_artist, album_artist_id=excluded.album_artist_id, track_no=excluded.track_no,
		disc_no=excluded.disc_no, year=excluded.year, genre=excluded.genre, duration=excluded.duration,
		suffix=excluded.suffix, codec=excluded.codec, lossless=excluded.lossless, bit_depth=excluded.bit_depth,
		sample_rate=excluded.sample_rate, bitrate=excluded.bitrate, channels=excluded.channels,
		quality=excluded.quality, has_lyrics=excluded.has_lyrics`)
	if err != nil {
		return err
	}
	defer stmt.Close()
	for _, t := range tracks {
		if t.CreatedAt == 0 {
			t.CreatedAt = time.Now().Unix()
		}
		if _, err := stmt.Exec(t.ID, t.Path, t.Mtime, t.Size, t.Title, t.Artist, t.ArtistID, t.Album, t.AlbumID,
			t.AlbumArtist, t.AlbumArtistID, t.TrackNo, t.DiscNo, t.Year, t.Genre, t.Duration, t.Suffix, t.Codec,
			t.Lossless, t.BitDepth, t.SampleRate, t.Bitrate, t.Channels, t.Quality, t.HasLyrics, t.CreatedAt); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// DeletePaths removes tracks by relative path, or every track under a folder when prefix is true.
func (d *DB) DeletePaths(paths []string, prefix bool) (int64, error) {
	tx, err := d.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	var n int64
	for _, p := range paths {
		var res sql.Result
		if prefix {
			res, err = tx.Exec(`DELETE FROM tracks WHERE path = ? OR substr(path, 1, ?) = ?`, p, len(p)+1, p+"/")
		} else {
			res, err = tx.Exec(`DELETE FROM tracks WHERE path = ?`, p)
		}
		if err != nil {
			return 0, err
		}
		c, _ := res.RowsAffected()
		n += c
	}
	return n, tx.Commit()
}

// RefreshAggregates rebuilds the albums and artists tables from the tracks (a few ms for 20k tracks).
func (d *DB) RefreshAggregates() error {
	tx, err := d.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()
	// Keep when an album first appeared, so "recently added" stays stable across rebuilds
	if _, err := tx.Exec(`CREATE TEMP TABLE IF NOT EXISTS album_created AS SELECT id, created_at FROM albums WHERE 0`); err != nil {
		return err
	}
	stmts := []string{
		`DELETE FROM temp.album_created`,
		`INSERT INTO temp.album_created SELECT id, created_at FROM albums`,
		`DELETE FROM albums`,
		`INSERT INTO albums (id, name, artist, artist_id, year, genre, song_count, duration, cover_track_id, dir, quality, created_at)
		 SELECT t.album_id, MIN(t.album), MIN(t.album_artist), MIN(t.album_artist_id), MAX(t.year),
		        COALESCE((SELECT genre FROM tracks g WHERE g.album_id = t.album_id AND g.genre != '' LIMIT 1), ''),
		        COUNT(*), SUM(t.duration),
		        (SELECT id FROM tracks c WHERE c.album_id = t.album_id ORDER BY c.disc_no, c.track_no LIMIT 1),
		        '',
		        CASE WHEN SUM(t.quality = 'atmos') > 0 THEN 'atmos' WHEN SUM(t.quality = 'hires') > 0 THEN 'hires' WHEN SUM(t.quality = 'cd') > 0 THEN 'cd' ELSE 'lossy' END,
		        COALESCE((SELECT created_at FROM temp.album_created a WHERE a.id = t.album_id), MIN(t.created_at))
		 FROM tracks t GROUP BY t.album_id`,
		`DELETE FROM artists`,
		`INSERT INTO artists (id, name, album_count, cover_album_id)
		 SELECT artist_id, MIN(artist), COUNT(*), MAX(id) FROM albums GROUP BY artist_id`,
	}
	for _, s := range stmts {
		if _, err := tx.Exec(s); err != nil {
			return err
		}
	}
	// Album folder = folder of its first track (for cover.jpg next to the files)
	rows, err := tx.Query(`SELECT a.id, t.path FROM albums a JOIN tracks t ON t.id = a.cover_track_id`)
	if err != nil {
		return err
	}
	dirs := map[string]string{}
	for rows.Next() {
		var id, p string
		if err := rows.Scan(&id, &p); err != nil {
			rows.Close()
			return err
		}
		dirs[id] = path.Dir(p)
	}
	rows.Close()
	for id, dir := range dirs {
		if _, err := tx.Exec(`UPDATE albums SET dir = ? WHERE id = ?`, dir, id); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// TrackByID returns one track, or nil.
func (d *DB) TrackByID(id string) (*Track, error) {
	ts, err := d.QueryTracks(`WHERE t.id = ?`, id)
	if err != nil || len(ts) == 0 {
		return nil, err
	}
	return ts[0], nil
}

// QueryTracks selects tracks with the given SQL tail (WHERE / ORDER BY / LIMIT, table alias t).
func (d *DB) QueryTracks(tail string, args ...any) ([]*Track, error) {
	rows, err := d.Query(`SELECT t.id, t.path, t.mtime, t.size, t.title, t.artist, t.artist_id, t.album, t.album_id,
		t.album_artist, t.album_artist_id, t.track_no, t.disc_no, t.year, t.genre, t.duration, t.suffix, t.codec,
		t.lossless, t.bit_depth, t.sample_rate, t.bitrate, t.channels, t.quality, t.has_lyrics, t.created_at
		FROM tracks t `+tail, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Track
	for rows.Next() {
		t := &Track{}
		if err := rows.Scan(&t.ID, &t.Path, &t.Mtime, &t.Size, &t.Title, &t.Artist, &t.ArtistID, &t.Album, &t.AlbumID,
			&t.AlbumArtist, &t.AlbumArtistID, &t.TrackNo, &t.DiscNo, &t.Year, &t.Genre, &t.Duration, &t.Suffix,
			&t.Codec, &t.Lossless, &t.BitDepth, &t.SampleRate, &t.Bitrate, &t.Channels, &t.Quality, &t.HasLyrics,
			&t.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

// Album is one album of the library.
type Album struct {
	ID, Name, Artist, ArtistID, Genre, CoverTrackID, Dir, Quality string
	Year, SongCount                                               int
	Duration                                                      float64
	CreatedAt                                                     int64
}

func (d *DB) QueryAlbums(tail string, args ...any) ([]*Album, error) {
	rows, err := d.Query(`SELECT a.id, a.name, a.artist, a.artist_id, a.genre, a.cover_track_id, a.dir, a.quality,
		a.year, a.song_count, a.duration, a.created_at FROM albums a `+tail, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Album
	for rows.Next() {
		a := &Album{}
		if err := rows.Scan(&a.ID, &a.Name, &a.Artist, &a.ArtistID, &a.Genre, &a.CoverTrackID, &a.Dir, &a.Quality,
			&a.Year, &a.SongCount, &a.Duration, &a.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (d *DB) AlbumByID(id string) (*Album, error) {
	as, err := d.QueryAlbums(`WHERE a.id = ?`, id)
	if err != nil || len(as) == 0 {
		return nil, err
	}
	return as[0], nil
}

// Artist is an album artist of the library.
type Artist struct {
	ID, Name, CoverAlbumID string
	AlbumCount             int
}

func (d *DB) QueryArtists(tail string, args ...any) ([]*Artist, error) {
	rows, err := d.Query(`SELECT r.id, r.name, r.cover_album_id, r.album_count FROM artists r `+tail, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []*Artist
	for rows.Next() {
		a := &Artist{}
		if err := rows.Scan(&a.ID, &a.Name, &a.CoverAlbumID, &a.AlbumCount); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

func (d *DB) ArtistByID(id string) (*Artist, error) {
	as, err := d.QueryArtists(`WHERE r.id = ?`, id)
	if err != nil || len(as) == 0 {
		return nil, err
	}
	return as[0], nil
}
