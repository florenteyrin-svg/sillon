package db

import "testing"

func TestQualityOf(t *testing.T) {
	cases := []struct {
		lossless   bool
		bits, rate int
		want       string
	}{
		{true, 16, 44100, QualityCD},
		{true, 16, 48000, QualityCD},
		{true, 24, 44100, QualityHiRes},
		{true, 16, 96000, QualityHiRes},
		{true, 24, 192000, QualityHiRes},
		{false, 0, 44100, QualityLossy},
	}
	for _, c := range cases {
		if got := QualityOf(c.lossless, c.bits, c.rate); got != c.want {
			t.Errorf("QualityOf(%v, %d, %d) = %s, want %s", c.lossless, c.bits, c.rate, got, c.want)
		}
	}
}
