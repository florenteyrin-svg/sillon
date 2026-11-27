package smart

import (
	"testing"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
)

func TestRules(t *testing.T) {
	d, err := db.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := d.EnsureAdmin("a", "b"); err != nil {
		t.Fatal(err)
	}
	mk := func(path, genre string, year int, lossless bool, bits int) *db.Track {
		tr := &db.Track{Path: path, Title: path, Artist: "X", Album: "Y", Genre: genre, Year: year,
			Lossless: lossless, BitDepth: bits, SampleRate: 44100, Suffix: "flac", Codec: "flac"}
		tr.FillIDs()
		return tr
	}
	if err := d.UpsertTracks([]*db.Track{
		mk("a", "Rap", 2005, true, 24), mk("b", "Rap FR", 2015, true, 16), mk("c", "Pop", 2005, false, 0),
	}); err != nil {
		t.Fatal(err)
	}
	cases := map[string]int{
		`{"rules":[{"field":"genre","op":"contains","value":"rap"}]}`:                                          2,
		`{"rules":[{"field":"year","op":"between","value":[2000,2010]}]}`:                                      2,
		`{"rules":[{"field":"quality","op":"is","value":"hires"}]}`:                                            1,
		`{"match":"any","rules":[{"field":"genre","op":"is","value":"pop"},{"field":"year","op":"gt","value":2010}]}`: 2,
		`{"rules":[{"field":"added","op":"inlast","value":1}],"limit":2,"sort":"random"}`:                       2,
		`{"rules":[{"field":"starred","op":"is","value":true}]}`:                                               0,
	}
	for rules, want := range cases {
		got, err := Tracks(d, rules, 1)
		if err != nil {
			t.Fatalf("%s: %v", rules, err)
		}
		if len(got) != want {
			t.Errorf("%s: %d tracks, want %d", rules, len(got), want)
		}
	}
	if _, err := Parse(`{"rules":[{"field":"nope","op":"is","value":1}]}`); err == nil {
		t.Error("unknown field accepted")
	}
}
