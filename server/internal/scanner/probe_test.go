package scanner

import (
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestProbeQuality(t *testing.T) {
	if _, err := exec.LookPath("ffmpeg"); err != nil {
		t.Skip("ffmpeg missing")
	}
	dir := t.TempDir()
	files := map[string][]string{
		"hires.flac": {"-ar", "96000", "-sample_fmt", "s32", "-c:a", "flac"},
		"cd.flac":    {"-ar", "44100", "-sample_fmt", "s16", "-c:a", "flac"},
		"lossy.mp3":  {"-c:a", "libmp3lame", "-b:a", "320k"},
	}
	for name, args := range files {
		cmd := append([]string{"-v", "error", "-y", "-f", "lavfi", "-i", "sine=duration=2", "-metadata", "title=Titre"}, args...)
		if out, err := exec.Command("ffmpeg", append(cmd, filepath.Join(dir, name))...).CombinedOutput(); err != nil {
			t.Fatalf("ffmpeg %s: %v %s", name, err, out)
		}
	}
	want := map[string]string{"hires.flac": "hires", "cd.flac": "cd", "lossy.mp3": "lossy"}
	for name, q := range want {
		info, _ := os.Stat(filepath.Join(dir, name))
		tr, err := probe(dir, name, info)
		if err != nil {
			t.Fatal(err)
		}
		if tr.Quality != q || tr.Title != "Titre" || tr.Duration < 1.9 || tr.Duration > 2.1 {
			t.Errorf("%s: quality %s title %q duration %.2f", name, tr.Quality, tr.Title, tr.Duration)
		}
	}
}
