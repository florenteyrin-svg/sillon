package api

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
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
	songs, err := jellyfin.Favorites(b.URL, b.User, b.Password)
	if err != nil {
		jsonError(w, http.StatusBadGateway, err.Error())
		return
	}
	var ids, missing []string
	for i := len(songs) - 1; i >= 0; i-- { // oldest first, so the last favourite ends up on top
		s := songs[i]
		if id := a.matchSong(s); id != "" {
			ids = append(ids, id)
		} else {
			missing = append(missing, firstArtist(s)+" — "+s.Name)
		}
	}
	for _, id := range ids {
		if err := a.DB.Star(u.ID, []string{id}); err != nil {
			jsonError(w, 500, err.Error())
			return
		}
	}
	log.Printf("jellyfin: %d favourites, %d found in the library", len(songs), len(ids))
	if len(missing) > 30 {
		missing = missing[:30]
	}
	writeJSON(w, map[string]any{"total": len(songs), "imported": len(ids), "missing": missing})
}

func firstArtist(s jellyfin.Song) string {
	if len(s.Artists) > 0 {
		return s.Artists[0]
	}
	return s.AlbumArtist
}

// matchSong finds the library song for a Jellyfin favourite.
func (a *API) matchSong(s jellyfin.Song) string {
	// Same file: Jellyfin's path ends with the path inside the music folder
	parts := strings.Split(strings.ReplaceAll(s.Path, "\\", "/"), "/")
	for i := 1; i < len(parts); i++ {
		var id string
		if a.DB.QueryRow(`SELECT id FROM tracks WHERE path = ?`, strings.Join(parts[i:], "/")).Scan(&id) == nil {
			return id
		}
	}
	// Otherwise same title, and the closest artist / album
	rows, err := a.DB.Query(`SELECT id, artist, album_artist, album FROM tracks WHERE title = ? COLLATE NOCASE`, s.Name)
	if err != nil {
		return ""
	}
	defer rows.Close()
	best, bestScore := "", 0
	artist, album := fold(firstArtist(s)), fold(s.Album)
	for rows.Next() {
		var id, ar, aa, al string
		if rows.Scan(&id, &ar, &aa, &al) != nil {
			continue
		}
		score := 1
		if fa, faa := fold(ar), fold(aa); artist != "" && (fa == artist || faa == artist || strings.Contains(fa, artist) || strings.Contains(artist, fa)) {
			score += 2
		}
		if album != "" && fold(al) == album {
			score++
		}
		if score > bestScore {
			best, bestScore = id, score
		}
	}
	if bestScore >= 3 || (bestScore >= 1 && artist == "") {
		return best
	}
	return ""
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
