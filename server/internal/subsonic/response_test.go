package subsonic

import (
	"bytes"
	"encoding/xml"
	"strings"
	"testing"
)

func TestXML(t *testing.T) {
	var b bytes.Buffer
	enc := xml.NewEncoder(&b)
	writeXML(enc, "r", M{"a": 1, "child": M{"x": "y"}, "list": []M{{"i": 1}, {"i": 2}}, "nums": []int{1}, "s": M{"value": "texte"}})
	enc.Flush()
	got := b.String()
	for _, want := range []string{`<r a="1">`, `<child x="y"></child>`, `<list i="1"></list><list i="2"></list>`, `<nums>1</nums>`, `<s>texte</s>`} {
		if !strings.Contains(got, want) {
			t.Errorf("missing %s in %s", want, got)
		}
	}
}
