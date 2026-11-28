package api

import "testing"

func TestCoreAndArtist(t *testing.T) {
	for in, want := range map[string]string{
		"4 Etoiles (feat. Rohff) (radio edit)":                                  "4etoiles",
		"(When You Gonna) Give It Up To Me (feat. Keyshia Cole) (Main Version)": "giveituptome",
		"Channel 43 (Radio Edit)":                                               "channel43",
		"Get Up feat. Chamillionaire":                                           "getup",
		"Horses":                                                                "horses",
		"Fade - Original Mix":                                                   "fade",
	} {
		if got := core(in); got != want {
			t.Errorf("core(%q) = %q, want %q", in, got, want)
		}
	}
	if artistKey("Potbelleez, The") != artistKey("The Potbelleez") {
		t.Error("The Potbelleez")
	}
	if p := splitArtists("Mike Candys & Evelyn feat. Patrick Miller"); len(p) != 3 {
		t.Errorf("split: %q", p)
	}
}
