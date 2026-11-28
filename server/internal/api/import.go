package api

import (
	"encoding/json"
	"log"
	"net/http"
	"regexp"
	"sort"
	"strings"
	"time"
	"unicode"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
	"github.com/florenteyrin-svg/sillon/server/internal/jellyfin"
)

// importJellyfin marks as favourites in Sillon the songs that are favourites in Jellyfin. Each song is
// found by its file (same music folder) or else by title, artist and album. Already marked songs stay.
func (a *API) importJellyfin(w http.ResponseWriter, r *http.Request, u *db.User) {
	var b struct {
		URL, User, Password string
	}
	if err := json.NewDecoder(r.Body).Decode(&b); err != nil || b.URL == "" || b.User == "" {
		jsonError(w, 400, "adresse et utilisateur Jellyfin requis")
		return
	}
	songs, played, err := jellyfin.Fetch(b.URL, b.User, b.Password)
	if err != nil {
		jsonError(w, http.StatusBadGateway, err.Error())
		return
	}
	idx, err := a.songIndex()
	if err != nil {
		jsonError(w, 500, err.Error())
		return
	}
	// Jellyfin does not say when a song was made a favourite: the date it was added to Jellyfin stands for
	// it, so the Favoris playlist is in the order of addition, newest first, like the songs hearted in Sillon.
	var ids []string
	var at []int64
	missing := []string{}
	now := time.Now().Unix()
	for i, s := range songs {
		if id := a.matchSong(s, idx); id != "" {
			ids = append(ids, id)
			t := s.Added().Unix()
			if s.Added().IsZero() || t > now {
				t = now - int64(i) // unknown date: keep Jellyfin's order
			}
			at = append(at, t)
		} else {
			missing = append(missing, firstArtist(s)+" — "+s.Name)
		}
	}
	for i, id := range ids {
		if err := a.DB.StarAt(u.ID, id, at[i]); err != nil {
			jsonError(w, 500, err.Error())
			return
		}
	}
	log.Printf("jellyfin: %d favourites, %d found in the library", len(songs), len(ids))

	// Plays: "Les plus écoutés" starts with Jellyfin's history
	playsFound := 0
	for _, s := range played {
		if s.UserData.PlayCount <= 0 {
			continue
		}
		id := a.matchSong(s, idx)
		if id == "" {
			continue
		}
		var last int64
		if t := s.LastPlayed(); !t.IsZero() {
			last = t.Unix()
		}
		if err := a.DB.ImportPlays(u.ID, id, s.UserData.PlayCount, last); err != nil {
			jsonError(w, 500, err.Error())
			return
		}
		playsFound++
	}
	log.Printf("jellyfin: %d songs played, %d found in the library", len(played), playsFound)
	for _, m := range missing {
		log.Printf("jellyfin: not found: %s", m)
	}
	sort.Strings(missing)
	writeJSON(w, map[string]any{"total": len(songs), "imported": len(ids), "missing": missing,
		"played": len(played), "playsImported": playsFound})
}

func firstArtist(s jellyfin.Song) string {
	if len(s.Artists) > 0 {
		return s.Artists[0]
	}
	return s.AlbumArtist
}

type libSong struct{ id, artists, album string }

// songIndex lists the library songs by title, written loosely (see core).
func (a *API) songIndex() (map[string][]libSong, error) {
	rows, err := a.DB.Query(`SELECT id, title, artist, album_artist, album FROM tracks`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	idx := map[string][]libSong{}
	for rows.Next() {
		var id, title, ar, aa, al string
		if rows.Scan(&id, &title, &ar, &aa, &al) != nil {
			continue
		}
		ls := libSong{id, artistKey(ar) + "|" + artistKey(aa) + "|" + fold(title), fold(al)}
		for _, k := range uniq(fold(title), core(title)) {
			idx[k] = append(idx[k], ls)
		}
	}
	return idx, nil
}

// matchSong finds the library song for a Jellyfin favourite: same file first, then same title (with or
// without "(feat. …)", "(Radio Edit)"…) by the same artist.
func (a *API) matchSong(s jellyfin.Song, idx map[string][]libSong) string {
	parts := strings.Split(strings.ReplaceAll(s.Path, "\\", "/"), "/")
	for i := 1; i < len(parts); i++ {
		var id string
		if a.DB.QueryRow(`SELECT id FROM tracks WHERE path = ?`, strings.Join(parts[i:], "/")).Scan(&id) == nil {
			return id
		}
	}
	var artists []string
	for _, ar := range append(append([]string{}, s.Artists...), s.AlbumArtist) {
		// "A & B", "A feat. B", "A, B": each name counts
		for _, p := range splitArtists(ar) {
			if k := artistKey(p); len(k) >= 2 {
				artists = append(artists, k)
			}
		}
	}
	album := fold(s.Album)
	for _, key := range uniq(fold(s.Name), core(s.Name)) {
		best, bestScore := "", 0
		for _, c := range idx[key] {
			score := 0
			for _, ar := range artists {
				if strings.Contains(c.artists, ar) {
					score = 2
					break
				}
			}
			if score == 0 && len(artists) > 0 {
				continue
			}
			if album != "" && c.album == album {
				score++
			}
			if score > bestScore || best == "" {
				best, bestScore = c.id, score
			}
		}
		if best != "" {
			return best
		}
	}
	return ""
}

var (
	brackets   = regexp.MustCompile(`[\(\[][^\)\]]*[\)\]]`)
	featTail   = regexp.MustCompile(`(?i)\s(feat\.?|ft\.?|featuring)\s.*$`)
	artistSeps = regexp.MustCompile(`(?i)\s*(,|&|/|;|\sx\s|\sand\s|\set\s|\sfeat\.?\s|\sft\.?\s|\sfeaturing\s|\swith\s)\s*`)
)

// core is a title without what is often written differently: (feat. …), [Radio Edit], " - Remastered"…
func core(title string) string {
	t := brackets.ReplaceAllString(title, " ")
	t = featTail.ReplaceAllString(t, "")
	if i := strings.Index(t, " - "); i > 0 {
		t = t[:i]
	}
	if c := fold(t); c != "" {
		return c
	}
	return fold(title)
}

func splitArtists(s string) []string {
	return artistSeps.Split(s, -1)
}

// artistKey writes an artist name the same way whatever the order: "Potbelleez, The" = "The Potbelleez".
func artistKey(s string) string {
	f := fold(s)
	f = strings.TrimPrefix(f, "the")
	f = strings.TrimSuffix(f, "the")
	return f
}

func uniq(v ...string) []string {
	var out []string
	for _, s := range v {
		if s != "" && (len(out) == 0 || out[len(out)-1] != s) {
			out = append(out, s)
		}
	}
	return out
}

// fold keeps only letters and digits, in lower case, so small spelling differences do not matter.
func fold(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			b.WriteRune(r)
		}
	}
	return b.String()
}
