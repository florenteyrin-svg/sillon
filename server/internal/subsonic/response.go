// Package subsonic serves the (Open)Subsonic API, so Music Assistant, Symfonium and other players can use Sillon.
package subsonic

import (
	"encoding/json"
	"encoding/xml"
	"fmt"
	"net/http"
	"sort"
	"strconv"
)

const apiVersion = "1.16.1"

// M is a response object. In XML, plain values become attributes, M values child elements,
// []M repeated child elements, and the key "value" the element text.
type M = map[string]any

type apiError struct {
	Code    int
	Message string
}

func (e *apiError) Error() string { return e.Message }

func errMissing(name string) *apiError {
	return &apiError{10, "Required parameter is missing: " + name}
}
func errNotFound(what string) *apiError { return &apiError{70, what + " not found"} }
func errAuth() *apiError                { return &apiError{40, "Wrong username or password"} }
func errForbidden() *apiError           { return &apiError{50, "User is not authorized for the given operation"} }
func errGeneric(err error) *apiError    { return &apiError{0, err.Error()} }

func write(w http.ResponseWriter, r *http.Request, body M, e *apiError) {
	root := M{
		"status":        "ok",
		"version":       apiVersion,
		"type":          "sillon",
		"serverVersion": Version,
		"openSubsonic":  true,
	}
	for k, v := range body {
		root[k] = v
	}
	if e != nil {
		root["status"] = "failed"
		root["error"] = M{"code": e.Code, "message": e.Message}
	}
	format := r.Form.Get("f")
	switch format {
	case "json", "jsonp":
		b, err := json.Marshal(M{"subsonic-response": root})
		if err != nil {
			http.Error(w, err.Error(), 500)
			return
		}
		if cb := r.Form.Get("callback"); format == "jsonp" && cb != "" {
			w.Header().Set("Content-Type", "application/javascript")
			fmt.Fprintf(w, "%s(%s);", cb, b)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write(b)
	default:
		w.Header().Set("Content-Type", "text/xml; charset=utf-8")
		w.Write([]byte(xml.Header))
		root["xmlns"] = "http://subsonic.org/restapi"
		enc := xml.NewEncoder(w)
		writeXML(enc, "subsonic-response", root)
		enc.Flush()
	}
}

func writeXML(enc *xml.Encoder, name string, obj M) {
	start := xml.StartElement{Name: xml.Name{Local: name}}
	keys := make([]string, 0, len(obj))
	for k := range obj {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var children []string
	text := ""
	for _, k := range keys {
		switch v := obj[k].(type) {
		case M, []M, []int, []string:
			children = append(children, k)
		case nil:
		default:
			if k == "value" {
				text = fmt.Sprint(v)
				continue
			}
			start.Attr = append(start.Attr, xml.Attr{Name: xml.Name{Local: k}, Value: scalar(v)})
		}
	}
	enc.EncodeToken(start)
	if text != "" {
		enc.EncodeToken(xml.CharData(text))
	}
	for _, k := range children {
		switch v := obj[k].(type) {
		case M:
			writeXML(enc, k, v)
		case []M:
			for _, item := range v {
				writeXML(enc, k, item)
			}
		case []int:
			for _, item := range v {
				writeXML(enc, k, M{"value": item})
			}
		case []string:
			for _, item := range v {
				writeXML(enc, k, M{"value": item})
			}
		}
	}
	enc.EncodeToken(start.End())
}

func scalar(v any) string {
	switch x := v.(type) {
	case string:
		return x
	case bool:
		return strconv.FormatBool(x)
	case float64:
		return strconv.FormatFloat(x, 'f', -1, 64)
	default:
		return fmt.Sprint(x)
	}
}
