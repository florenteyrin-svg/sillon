package db

import "testing"

func TestCaseDifferentPathsAreTwoTracks(t *testing.T) {
	d, err := Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	a := &Track{Path: "Mes FLAC/Album/01.flac", Title: "a", Artist: "X", Album: "Album", Suffix: "flac", Codec: "flac"}
	b := &Track{Path: "Mes FLAC/album/01.flac", Title: "b", Artist: "X", Album: "album", Suffix: "flac", Codec: "flac"}
	a.FillIDs()
	b.FillIDs()
	if a.ID == b.ID {
		t.Fatal("same id for two different files")
	}
	if err := d.UpsertTracks([]*Track{a, b}); err != nil {
		t.Fatal(err)
	}
	var n int
	d.QueryRow(`SELECT COUNT(*) FROM tracks`).Scan(&n)
	if n != 2 {
		t.Fatalf("%d tracks, want 2", n)
	}
}
