package streamcache

import (
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestTrimRemovesOldestFirst(t *testing.T) {
	dir := t.TempDir()
	c := &Cache{Dir: dir, MaxBytes: 2500}
	for i, id := range []string{"111", "222", "333"} {
		d := filepath.Join(dir, id)
		os.MkdirAll(d, 0o755)
		os.WriteFile(filepath.Join(d, "a.m4a"), make([]byte, 1000), 0o644)
		os.WriteFile(filepath.Join(d, "status.json"), []byte(`{"status":"done","file":"a.m4a"}`), 0o644)
		at := time.Now().Add(time.Duration(i-10) * time.Minute)
		os.Chtimes(d, at, at)
	}
	c.Trim()
	if _, err := os.Stat(filepath.Join(dir, "111")); !os.IsNotExist(err) {
		t.Error("oldest song kept")
	}
	if !c.Ready("222") || !c.Ready("333") {
		t.Error("recent songs removed")
	}
}
