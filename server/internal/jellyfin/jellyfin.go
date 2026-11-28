// Package jellyfin reads the favourite songs of a Jellyfin account, to bring them into Sillon.
package jellyfin

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Song struct {
	Name        string   `json:"Name"`
	Album       string   `json:"Album"`
	AlbumArtist string   `json:"AlbumArtist"`
	Artists     []string `json:"Artists"`
	Path        string   `json:"Path"`
	DateCreated string   `json:"DateCreated"` // when the song was added to Jellyfin
	UserData    struct {
		PlayCount      int    `json:"PlayCount"`
		LastPlayedDate string `json:"LastPlayedDate"`
	} `json:"UserData"`
}

// LastPlayed is when the song was last played in Jellyfin (zero when unknown).
func (s Song) LastPlayed() time.Time {
	t, _ := time.Parse(time.RFC3339Nano, s.UserData.LastPlayedDate)
	return t
}

// Added is when the song was added to Jellyfin (zero when unknown).
func (s Song) Added() time.Time {
	t, _ := time.Parse(time.RFC3339Nano, s.DateCreated)
	return t
}

const client = `MediaBrowser Client="Sillon", Device="Sillon", DeviceId="sillon-import", Version="1.0"`

// Fetch signs in with the account (the password is only sent to Jellyfin, never kept) and returns the
// songs marked as favourite and the songs played at least once (with their play count).
func Fetch(server, user, password string) (favorites, played []Song, err error) {
	server = strings.TrimRight(strings.TrimSpace(server), "/")
	if !strings.HasPrefix(server, "http://") && !strings.HasPrefix(server, "https://") {
		server = "http://" + server
	}
	hc := &http.Client{Timeout: 60 * time.Second}

	body, _ := json.Marshal(map[string]string{"Username": user, "Pw": password})
	req, _ := http.NewRequest("POST", server+"/Users/AuthenticateByName", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", client)
	res, err := hc.Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("Jellyfin injoignable : %v", err)
	}
	defer res.Body.Close()
	if res.StatusCode == 401 {
		return nil, nil, fmt.Errorf("Jellyfin refuse l'utilisateur ou le mot de passe")
	}
	if res.StatusCode != 200 {
		return nil, nil, fmt.Errorf("Jellyfin répond %s", res.Status)
	}
	var auth struct {
		AccessToken string `json:"AccessToken"`
		User        struct {
			ID string `json:"Id"`
		} `json:"User"`
	}
	if err := json.NewDecoder(res.Body).Decode(&auth); err != nil || auth.AccessToken == "" {
		return nil, nil, fmt.Errorf("réponse de connexion Jellyfin illisible")
	}

	get := func(filter, sortBy string) ([]Song, error) {
		q := url.Values{
			"Filters": {filter}, "IncludeItemTypes": {"Audio"}, "Recursive": {"true"},
			"Fields": {"Path,DateCreated"}, "SortBy": {sortBy}, "SortOrder": {"Descending"}, "EnableImages": {"false"},
		}
		req, _ := http.NewRequest("GET", server+"/Users/"+auth.User.ID+"/Items?"+q.Encode(), nil)
		req.Header.Set("Authorization", client+`, Token="`+auth.AccessToken+`"`)
		res, err := (&http.Client{Timeout: 5 * time.Minute}).Do(req)
		if err != nil {
			return nil, fmt.Errorf("Jellyfin injoignable : %v", err)
		}
		defer res.Body.Close()
		if res.StatusCode != 200 {
			return nil, fmt.Errorf("Jellyfin répond %s", res.Status)
		}
		var out struct {
			Items []Song `json:"Items"`
		}
		if err := json.NewDecoder(res.Body).Decode(&out); err != nil {
			return nil, fmt.Errorf("réponse Jellyfin illisible")
		}
		return out.Items, nil
	}
	if favorites, err = get("IsFavorite", "DateCreated,SortName"); err != nil {
		return nil, nil, err
	}
	if played, err = get("IsPlayed", "PlayCount,SortName"); err != nil {
		return nil, nil, err
	}
	return favorites, played, nil
}
