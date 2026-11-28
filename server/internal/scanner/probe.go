package scanner

import (
	"bytes"
	"context"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/dhowden/tag"

	"github.com/florenteyrin-svg/sillon/server/internal/db"
)

// AudioExts are the file types Sillon indexes.
var AudioExts = map[string]bool{
	".flac": true, ".mp3": true, ".m4a": true, ".alac": true, ".aac": true, ".ogg": true, ".oga": true,
	".opus": true, ".wav": true, ".aif": true, ".aiff": true, ".wv": true, ".ape": true, ".dsf": true,
	".dff": true, ".mpc": true, ".wma": true,
}

var losslessCodecs = map[string]bool{
	"flac": true, "alac": true, "ape": true, "wavpack": true, "tta": true, "mlp": true, "truehd": true,
	"dsd_lsbf": true, "dsd_msbf": true, "dsd_lsbf_planar": true, "dsd_msbf_planar": true,
}

// probe reads the tags and audio properties of one file. FLAC (most of the library) is read natively,
// which is much faster than starting ffprobe; other formats go through ffprobe.
func probe(root, rel string, info os.FileInfo) (*db.Track, error) {
	full := filepath.Join(root, filepath.FromSlash(rel))
	t := &db.Track{
		Path:   rel,
		Mtime:  info.ModTime().Unix(),
		Size:   info.Size(),
		Suffix: strings.TrimPrefix(strings.ToLower(filepath.Ext(rel)), "."),
	}
	var err error
	if t.Suffix == "flac" {
		err = probeFLAC(full, t)
	}
	if t.Suffix != "flac" || err != nil {
		if err2 := probeFFprobe(full, t); err2 != nil {
			if err != nil {
				return nil, fmt.Errorf("%v; ffprobe: %w", err, err2)
			}
			return nil, err2
		}
	}
	finish(t, full)
	if t.Atmos && SkipAtmos {
		return nil, ErrSkipped
	}
	return t, nil
}

// SkipAtmos leaves Dolby Atmos files out of the library (they duplicate the FLAC versions of the same albums).
var SkipAtmos = true

// ErrSkipped marks a file deliberately left out of the library.
var ErrSkipped = errors.New("left out of the library")

func probeFLAC(full string, t *db.Track) error {
	f, err := os.Open(full)
	if err != nil {
		return err
	}
	defer f.Close()
	// STREAMINFO is always the first metadata block, right after "fLaC"
	var head [4 + 4 + 34]byte
	if _, err := io.ReadFull(f, head[:]); err != nil {
		return err
	}
	if string(head[:4]) != "fLaC" || head[4]&0x7f != 0 {
		return errors.New("not a FLAC stream")
	}
	si := head[8:]
	// bytes 10..17: 20 bits sample rate, 3 bits channels-1, 5 bits bits-per-sample-1, 36 bits total samples
	v := binary.BigEndian.Uint64(si[10:18])
	t.SampleRate = int(v >> 44)
	t.Channels = int((v>>41)&0x7) + 1
	t.BitDepth = int((v>>36)&0x1f) + 1
	total := v & 0xfffffffff
	if t.SampleRate > 0 {
		t.Duration = float64(total) / float64(t.SampleRate)
	}
	t.Codec = "flac"
	t.Lossless = true
	if t.Duration > 0 {
		t.Bitrate = int(float64(t.Size*8) / t.Duration / 1000)
	}
	if _, err := f.Seek(0, io.SeekStart); err != nil {
		return err
	}
	m, err := tag.ReadFrom(f)
	if err != nil {
		return nil // audio properties are known; tags fall back to the file name
	}
	t.Title, t.Artist, t.Album, t.AlbumArtist, t.Genre = m.Title(), m.Artist(), m.Album(), m.AlbumArtist(), m.Genre()
	t.Year = m.Year()
	t.TrackNo, _ = m.Track()
	t.DiscNo, _ = m.Disc()
	raw := m.Raw()
	if t.Year == 0 {
		t.Year = yearOf(rawString(raw, "date", "originaldate", "year"))
	}
	if t.AlbumArtist == "" {
		t.AlbumArtist = rawString(raw, "albumartist", "album artist", "album_artist")
	}
	return nil
}

func rawString(raw map[string]interface{}, keys ...string) string {
	for _, k := range keys {
		for rk, rv := range raw {
			if strings.EqualFold(rk, k) {
				if s, ok := rv.(string); ok && s != "" {
					return s
				}
			}
		}
	}
	return ""
}

type ffprobeOut struct {
	Streams []struct {
		CodecType        string `json:"codec_type"`
		CodecName        string `json:"codec_name"`
		SampleRate       string `json:"sample_rate"`
		Channels         int    `json:"channels"`
		BitsPerSample    int    `json:"bits_per_sample"`
		BitsPerRawSample string `json:"bits_per_raw_sample"`
		SampleFmt        string `json:"sample_fmt"`
		Profile          string `json:"profile"`
		BitRate          string `json:"bit_rate"`
		Tags             map[string]string `json:"tags"`
	} `json:"streams"`
	Format struct {
		Duration string            `json:"duration"`
		BitRate  string            `json:"bit_rate"`
		Tags     map[string]string `json:"tags"`
	} `json:"format"`
}

func probeFFprobe(full string, t *db.Track) error {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, "ffprobe", "-v", "quiet", "-print_format", "json", "-show_format", "-show_streams", full)
	var out bytes.Buffer
	cmd.Stdout = &out
	if err := cmd.Run(); err != nil {
		return err
	}
	var p ffprobeOut
	if err := json.Unmarshal(out.Bytes(), &p); err != nil {
		return err
	}
	tags := map[string]string{}
	for k, v := range p.Format.Tags {
		tags[strings.ToLower(k)] = v
	}
	found := false
	for _, s := range p.Streams {
		if s.CodecType != "audio" {
			continue
		}
		found = true
		t.Codec = s.CodecName
		t.Atmos = strings.Contains(strings.ToLower(s.Profile), "atmos") ||
			((s.CodecName == "eac3" || s.CodecName == "truehd") && s.Channels > 2)
		t.SampleRate, _ = strconv.Atoi(s.SampleRate)
		t.Channels = s.Channels
		t.BitDepth = s.BitsPerSample
		if raw, _ := strconv.Atoi(s.BitsPerRawSample); raw > 0 {
			t.BitDepth = raw
		}
		if br, _ := strconv.Atoi(s.BitRate); br > 0 {
			t.Bitrate = br / 1000
		}
		for k, v := range s.Tags { // Ogg/Opus keep their tags on the stream
			if _, ok := tags[strings.ToLower(k)]; !ok {
				tags[strings.ToLower(k)] = v
			}
		}
		break
	}
	if !found {
		return errors.New("no audio stream")
	}
	t.Lossless = losslessCodecs[t.Codec] || strings.HasPrefix(t.Codec, "pcm_")
	if strings.HasPrefix(t.Codec, "dsd") {
		t.BitDepth = 1
	}
	if !t.Lossless {
		t.BitDepth = 0
	}
	t.Duration, _ = strconv.ParseFloat(p.Format.Duration, 64)
	if t.Bitrate == 0 {
		if br, _ := strconv.Atoi(p.Format.BitRate); br > 0 {
			t.Bitrate = br / 1000
		}
	}
	t.Title = tags["title"]
	t.Artist = tags["artist"]
	t.Album = tags["album"]
	t.AlbumArtist = firstNonEmpty(tags["album_artist"], tags["albumartist"], tags["album artist"])
	t.Genre = tags["genre"]
	t.Year = yearOf(firstNonEmpty(tags["date"], tags["year"], tags["originaldate"]))
	t.TrackNo = leadingInt(tags["track"])
	t.DiscNo = leadingInt(firstNonEmpty(tags["disc"], tags["discnumber"]))
	return nil
}

// finish fills what the tags did not give (title from the file name, folder names) and the ids.
func finish(t *db.Track, full string) {
	if t.Title == "" {
		name := strings.TrimSuffix(filepath.Base(t.Path), filepath.Ext(t.Path))
		// "01. Title" / "01 - Title"
		if i := strings.IndexFunc(name, func(r rune) bool { return r < '0' || r > '9' }); i > 0 {
			if n, err := strconv.Atoi(name[:i]); err == nil && t.TrackNo == 0 {
				t.TrackNo = n
			}
			name = strings.TrimLeft(name[i:], " .-_")
		}
		t.Title = name
	}
	dir := filepath.Dir(t.Path)
	if t.Album == "" {
		t.Album = filepath.Base(dir)
		if t.Album == "." {
			t.Album = "Sans album"
		}
	}
	if t.Artist == "" {
		parent := filepath.Base(filepath.Dir(dir))
		if parent == "." || parent == "/" {
			parent = ""
		}
		t.Artist = firstNonEmpty(t.AlbumArtist, parent, "Artiste inconnu")
	}
	t.Title, t.Artist, t.Album, t.AlbumArtist = strings.TrimSpace(t.Title), strings.TrimSpace(t.Artist),
		strings.TrimSpace(t.Album), strings.TrimSpace(t.AlbumArtist)
	if t.Channels == 0 {
		t.Channels = 2
	}
	// Lyrics written next to the file (à la carte writes .lrc files)
	if _, err := os.Stat(strings.TrimSuffix(full, filepath.Ext(full)) + ".lrc"); err == nil {
		t.HasLyrics = true
	}
	t.FillIDs()
}

func firstNonEmpty(v ...string) string {
	for _, s := range v {
		if strings.TrimSpace(s) != "" {
			return s
		}
	}
	return ""
}

func leadingInt(s string) int {
	s = strings.TrimSpace(s)
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	n, _ := strconv.Atoi(s[:i])
	return n
}

func yearOf(s string) int {
	if len(s) >= 4 {
		if y, err := strconv.Atoi(s[:4]); err == nil {
			return y
		}
	}
	return 0
}
