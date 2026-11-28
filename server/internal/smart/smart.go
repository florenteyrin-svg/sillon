// Package smart evaluates smart playlists: rules turned into one SQL query, so even 20k+ tracks take milliseconds.
package smart

import (
	"encoding/json"
	"fmt"
	"strings"
	"time"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
)

// Rules of a smart playlist, stored as JSON. Example:
//
//	{"match":"all","rules":[{"field":"genre","op":"contains","value":"rap"},
//	 {"field":"year","op":"between","value":[2000,2010]}],"sort":"random","limit":200}
type Rules struct {
	Match string `json:"match"` // "all" (default) or "any"
	Rules []Rule `json:"rules"`
	Sort  string `json:"sort"`  // random | added | added_asc | plays | last_played | title | artist | year
	Limit int    `json:"limit"` // 0 = no limit
}

type Rule struct {
	Field string          `json:"field"` // title artist album albumartist genre year added playcount lastplayed starred quality duration path
	Op    string          `json:"op"`    // is isnot contains notcontains startswith gt lt between inlast notinlast
	Value json.RawMessage `json:"value"`
}

var textFields = map[string]string{
	"title": "t.title", "artist": "t.artist", "album": "t.album", "albumartist": "t.album_artist",
	"genre": "t.genre", "path": "t.path", "quality": "t.quality", "suffix": "t.suffix",
}

var numFields = map[string]string{
	"year": "t.year", "duration": "t.duration", "playcount": "COALESCE(pl.count + pl.imported, 0)",
	"bitdepth": "t.bit_depth", "samplerate": "t.sample_rate", "track": "t.track_no",
}

// dateFields hold unix seconds and accept "inlast"/"notinlast" N days.
var dateFields = map[string]string{"added": "t.created_at", "lastplayed": "COALESCE(pl.last_played, 0)"}

func Parse(s string) (*Rules, error) {
	r := &Rules{}
	if err := json.Unmarshal([]byte(s), r); err != nil {
		return nil, fmt.Errorf("invalid rules: %w", err)
	}
	if _, _, err := r.sql(0); err != nil {
		return nil, err
	}
	return r, nil
}

func (r *Rules) sql(userID int64) (string, []any, error) {
	var conds []string
	var args []any
	for _, rule := range r.Rules {
		c, a, err := rule.sql(userID)
		if err != nil {
			return "", nil, err
		}
		conds = append(conds, c)
		args = append(args, a...)
	}
	where := ""
	if len(conds) > 0 {
		join := " AND "
		if strings.EqualFold(r.Match, "any") {
			join = " OR "
		}
		where = "WHERE (" + strings.Join(conds, join) + ")"
	}
	order := map[string]string{
		"random": "RANDOM()", "added": "t.created_at DESC", "added_asc": "t.created_at",
		"plays": "COALESCE(pl.count + pl.imported, 0) DESC", "last_played": "COALESCE(pl.last_played, 0) DESC",
		"title": "t.title COLLATE NOCASE", "artist": "t.album_artist COLLATE NOCASE, t.album, t.disc_no, t.track_no",
		"year": "t.year DESC", "": "t.album_artist COLLATE NOCASE, t.album, t.disc_no, t.track_no",
	}[r.Sort]
	if order == "" {
		return "", nil, fmt.Errorf("unknown sort %q", r.Sort)
	}
	tail := fmt.Sprintf(`LEFT JOIN plays pl ON pl.track_id = t.id AND pl.user_id = %d
		LEFT JOIN stars st ON st.item_id = t.id AND st.user_id = %d %s ORDER BY %s`, userID, userID, where, order)
	if r.Limit > 0 {
		tail += fmt.Sprintf(" LIMIT %d", r.Limit)
	}
	return tail, args, nil
}

func (rule Rule) sql(userID int64) (string, []any, error) {
	field := strings.ToLower(rule.Field)
	op := strings.ToLower(rule.Op)
	if field == "starred" {
		var v bool
		if err := json.Unmarshal(rule.Value, &v); err != nil {
			return "", nil, fmt.Errorf("starred needs true or false")
		}
		if v == (op != "isnot") {
			return "st.item_id IS NOT NULL", nil, nil
		}
		return "st.item_id IS NULL", nil, nil
	}
	if col, ok := textFields[field]; ok {
		var v string
		if err := json.Unmarshal(rule.Value, &v); err != nil {
			return "", nil, fmt.Errorf("%s needs a text value", field)
		}
		switch op {
		case "is":
			return col + " = ? COLLATE NOCASE", []any{v}, nil
		case "isnot":
			return col + " != ? COLLATE NOCASE", []any{v}, nil
		case "contains":
			return col + " LIKE ?", []any{"%" + v + "%"}, nil
		case "notcontains":
			return col + " NOT LIKE ?", []any{"%" + v + "%"}, nil
		case "startswith":
			return col + " LIKE ?", []any{v + "%"}, nil
		}
		return "", nil, fmt.Errorf("operator %q does not apply to %s", op, field)
	}
	if col, ok := dateFields[field]; ok {
		var days float64
		if err := json.Unmarshal(rule.Value, &days); err != nil {
			return "", nil, fmt.Errorf("%s needs a number of days", field)
		}
		since := time.Now().Add(-time.Duration(days*24) * time.Hour).Unix()
		switch op {
		case "inlast":
			return col + " >= ?", []any{since}, nil
		case "notinlast":
			return col + " < ?", []any{since}, nil
		}
		return "", nil, fmt.Errorf("operator %q does not apply to %s", op, field)
	}
	if col, ok := numFields[field]; ok {
		if op == "between" {
			var v [2]float64
			if err := json.Unmarshal(rule.Value, &v); err != nil {
				return "", nil, fmt.Errorf("%s between needs [min, max]", field)
			}
			return col + " BETWEEN ? AND ?", []any{v[0], v[1]}, nil
		}
		var v float64
		if err := json.Unmarshal(rule.Value, &v); err != nil {
			return "", nil, fmt.Errorf("%s needs a number", field)
		}
		sqlOp := map[string]string{"is": "=", "isnot": "!=", "gt": ">", "lt": "<"}[op]
		if sqlOp == "" {
			return "", nil, fmt.Errorf("operator %q does not apply to %s", op, field)
		}
		return col + " " + sqlOp + " ?", []any{v}, nil
	}
	return "", nil, fmt.Errorf("unknown field %q", rule.Field)
}

// Tracks evaluates the rules for the user.
func Tracks(d *db.DB, rulesJSON string, userID int64) ([]*db.Track, error) {
	r, err := Parse(rulesJSON)
	if err != nil {
		return nil, err
	}
	tail, args, err := r.sql(userID)
	if err != nil {
		return nil, err
	}
	return d.QueryTracks(tail, args...)
}
