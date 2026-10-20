// Package worktool implements the capability v1 contract. It has no network endpoint or default transport.
package worktool

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"strconv"
	"strings"
	"time"
	"unicode/utf16"
	"unicode/utf8"
)

const MaxFrame = 65536

type Target struct {
	DeviceID  string `json:"deviceId"`
	SessionID string `json:"sessionId"`
}
type Command struct {
	Version   int             `json:"version"`
	Kind      string          `json:"kind"`
	ID        string          `json:"id"`
	Target    Target          `json:"target"`
	Operation string          `json:"operation"`
	Params    json.RawMessage `json:"params"`
	ExpiresAt int64           `json:"expiresAt"`
}
type TextParams struct {
	ConversationID string `json:"conversationId"`
	Text           string `json:"text"`
}
type Result struct {
	Version   int             `json:"version"`
	Kind      string          `json:"kind"`
	CommandID string          `json:"commandId"`
	DeviceID  string          `json:"deviceId"`
	SessionID string          `json:"sessionId"`
	Sequence  int             `json:"sequence"`
	State     string          `json:"state"`
	Code      string          `json:"code"`
	Data      json.RawMessage `json:"data"`
}

func (r Result) Terminal() bool { return r.State != "accepted" && r.State != "running" }

type MessageData struct {
	ConversationID string `json:"conversationId"`
	Text           string `json:"text"`
	Sender         string `json:"sender"`
	Source         string `json:"source"`
}
type Event struct {
	Version    int         `json:"version"`
	Kind       string      `json:"kind"`
	EventID    string      `json:"eventId"`
	DeviceID   string      `json:"deviceId"`
	SessionID  string      `json:"sessionId"`
	Name       string      `json:"name"`
	OccurredAt int64       `json:"occurredAt"`
	Data       MessageData `json:"data"`
}
type ProtocolError struct {
	Version   int    `json:"version"`
	Kind      string `json:"kind"`
	Code      string `json:"code"`
	CommandID string `json:"commandId,omitempty"`
}

func (e ProtocolError) Error() string { return "worktool protocol: " + e.Code }

type Frame struct {
	Result *Result
	Event  *Event
	Error  *ProtocolError
}

var idPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)
var codePattern = regexp.MustCompile(`^[a-z0-9_]{1,64}$`)

func (t Target) valid() bool {
	return idPattern.MatchString(t.DeviceID) && idPattern.MatchString(t.SessionID)
}
func textValid(s string) bool {
	return utf8.ValidString(s) && strings.TrimFunc(s, protocolSpace) != "" && len(utf16.Encode([]rune(s))) <= 4000
}
func protocolSpace(c rune) bool {
	return (c >= 0x9 && c <= 0xd) || (c >= 0x1c && c <= 0x20) ||
		c == 0x85 || c == 0xa0 || c == 0x1680 || (c >= 0x2000 && c <= 0x200a) ||
		(c >= 0x2028 && c <= 0x2029) || c == 0x202f || c == 0x205f || c == 0x3000
}
func timestampValid(t int64) bool { return t > 0 && t <= 9007199254740991 }
func malformed() error            { return ProtocolError{Version: 1, Kind: "error", Code: "malformed"} }
func object(raw json.RawMessage) bool {
	return len(bytes.TrimSpace(raw)) > 0 && bytes.TrimSpace(raw)[0] == '{'
}

// Struct decoding in encoding/json is case-insensitive. Select exact v1 keys first so
// future fields such as "State" cannot override "state", unlike Kotlin's exact-key codec.
func decodeFields(raw []byte, dst any, names ...string) error {
	var all map[string]json.RawMessage
	if json.Unmarshal(raw, &all) != nil || all == nil {
		return malformed()
	}
	selected := make(map[string]json.RawMessage, len(names))
	for _, name := range names {
		if value, ok := all[name]; ok {
			selected[name] = value
		}
	}
	b, err := json.Marshal(selected)
	if err != nil {
		return malformed()
	}
	return json.Unmarshal(b, dst)
}

// Validate the complete frame before typed decoding: reject duplicates, excessive depth and trailing data.
func strict(raw []byte) (map[string]json.RawMessage, error) {
	if len(raw) > MaxFrame {
		return nil, ProtocolError{Version: 1, Kind: "error", Code: "frame_too_large"}
	}
	if !utf8.Valid(raw) || !validUnicodeEscapes(raw) {
		return nil, malformed()
	}
	d := json.NewDecoder(bytes.NewReader(raw))
	d.UseNumber()
	var walk func(int) error
	walk = func(depth int) error {
		if depth > 16 {
			return malformed()
		}
		t, err := d.Token()
		if err != nil {
			return malformed()
		}
		switch t {
		case json.Delim('{'):
			seen := map[string]bool{}
			for d.More() {
				key, err := d.Token()
				if err != nil {
					return malformed()
				}
				k, ok := key.(string)
				if !ok || seen[k] {
					return malformed()
				}
				seen[k] = true
				if err := walk(depth + 1); err != nil {
					return err
				}
			}
			end, err := d.Token()
			if err != nil || end != json.Delim('}') {
				return malformed()
			}
		case json.Delim('['):
			for d.More() {
				if err := walk(depth + 1); err != nil {
					return err
				}
			}
			end, err := d.Token()
			if err != nil || end != json.Delim(']') {
				return malformed()
			}
		}
		return nil
	}
	if err := walk(0); err != nil {
		return nil, err
	}
	if _, err := d.Token(); err != io.EOF {
		return nil, malformed()
	}
	var o map[string]json.RawMessage
	if err := json.Unmarshal(raw, &o); err != nil || o == nil {
		return nil, malformed()
	}
	var version int
	if err := json.Unmarshal(o["version"], &version); err != nil || version == 0 {
		return nil, malformed()
	}
	if version != 1 {
		return nil, ProtocolError{Version: 1, Kind: "error", Code: "unsupported_version"}
	}
	return o, nil
}

// encoding/json replaces isolated surrogate escapes with U+FFFD; v1 rejects them instead.
func validUnicodeEscapes(raw []byte) bool {
	inString := false
	for i := 0; i < len(raw); i++ {
		if raw[i] == '"' {
			inString = !inString
			continue
		}
		if !inString || raw[i] != '\\' {
			continue
		}
		i++
		if i >= len(raw) {
			return false
		}
		if raw[i] != 'u' {
			continue
		}
		if i+4 >= len(raw) {
			return false
		}
		n, err := strconv.ParseUint(string(raw[i+1:i+5]), 16, 16)
		if err != nil {
			return false
		}
		i += 4
		if n >= 0xDC00 && n <= 0xDFFF {
			return false
		}
		if n >= 0xD800 && n <= 0xDBFF {
			if i+6 >= len(raw) || raw[i+1] != '\\' || raw[i+2] != 'u' {
				return false
			}
			low, err := strconv.ParseUint(string(raw[i+3:i+7]), 16, 16)
			if err != nil || low < 0xDC00 || low > 0xDFFF {
				return false
			}
			i += 6
		}
	}
	return true
}

func NewQuery(id string, target Target, operation string, expiry time.Time) Command {
	return Command{1, "command", id, target, operation, json.RawMessage(`{}`), expiry.UnixMilli()}
}
func NewText(id string, target Target, conversationID, text string, expiry time.Time) (Command, error) {
	if !textValid(text) || !idPattern.MatchString(conversationID) {
		return Command{}, malformed()
	}
	params, _ := json.Marshal(TextParams{conversationID, text})
	return Command{1, "command", id, target, "text.send", params, expiry.UnixMilli()}, nil
}
func DecodeCommand(raw []byte) (Command, error) {
	o, err := strict(raw)
	if err != nil {
		return Command{}, err
	}
	var c Command
	if decodeFields(raw, &c, "version", "kind", "id", "operation", "params", "expiresAt") != nil ||
		decodeFields(o["target"], &c.Target, "deviceId", "sessionId") != nil {
		return c, malformed()
	}
	if c.Kind != "command" || !idPattern.MatchString(c.ID) || !c.Target.valid() ||
		c.Operation == "" || len(utf16.Encode([]rune(c.Operation))) > 64 ||
		!timestampValid(c.ExpiresAt) || !object(c.Params) {
		return c, malformed()
	}
	switch c.Operation {
	case "text.send":
		var p TextParams
		if err := decodeFields(c.Params, &p, "conversationId", "text"); err != nil || !idPattern.MatchString(p.ConversationID) || !textValid(p.Text) {
			return c, malformed()
		}
		c.Params, _ = json.Marshal(p)
	case "state.get", "capabilities.get":
		c.Params = json.RawMessage(`{}`)
	}
	return c, nil
}
func EncodeCommand(c Command) ([]byte, error) {
	if !utf8.ValidString(c.Operation) || !utf8.ValidString(c.ID) || !c.Target.valid() {
		return nil, malformed()
	}
	raw, err := json.Marshal(c)
	if err != nil {
		return nil, malformed()
	}
	normalized, err := DecodeCommand(raw)
	if err != nil {
		return nil, err
	}
	return json.Marshal(normalized)
}
func EncodeBatch(commands []Command) ([]byte, error) {
	if len(commands) == 0 || len(commands) > 32 {
		return nil, malformed()
	}
	frames := make([]json.RawMessage, len(commands))
	for i, c := range commands {
		b, err := EncodeCommand(c)
		if err != nil {
			return nil, err
		}
		frames[i] = b
	}
	raw, err := json.Marshal(struct {
		Version  int               `json:"version"`
		Kind     string            `json:"kind"`
		Commands []json.RawMessage `json:"commands"`
	}{1, "batch", frames})
	if err == nil {
		_, err = strict(raw)
	}
	return raw, err
}
func DecodeFrame(raw []byte) (Frame, error) {
	o, err := strict(raw)
	if err != nil {
		return Frame{}, err
	}
	var kind string
	if json.Unmarshal(o["kind"], &kind) != nil {
		return Frame{}, malformed()
	}
	switch kind {
	case "result":
		var r Result
		if decodeFields(raw, &r, "version", "kind", "commandId", "deviceId", "sessionId", "sequence", "state", "code", "data") != nil || !idPattern.MatchString(r.CommandID) ||
			!(Target{r.DeviceID, r.SessionID}).valid() || !codePattern.MatchString(r.Code) || !object(r.Data) {
			return Frame{}, malformed()
		}
		valid := false
		switch r.State {
		case "accepted":
			valid = r.Sequence == 1
		case "running":
			valid = r.Sequence == 2
		case "rejected":
			valid = r.Sequence == 1 || r.Sequence == 3
		case "succeeded", "failed", "partial_failure", "unknown":
			valid = r.Sequence == 3
		}
		if !valid {
			return Frame{}, malformed()
		}
		return Frame{Result: &r}, nil
	case "event":
		var e Event
		if decodeFields(raw, &e, "version", "kind", "eventId", "deviceId", "sessionId", "name", "occurredAt") != nil ||
			decodeFields(o["data"], &e.Data, "conversationId", "text", "sender", "source") != nil ||
			!idPattern.MatchString(e.EventID) || !(Target{e.DeviceID, e.SessionID}).valid() ||
			e.Name != "message.observed" || !timestampValid(e.OccurredAt) || !idPattern.MatchString(e.Data.ConversationID) ||
			!textValid(e.Data.Text) || (e.Data.Sender != "remote" && e.Data.Sender != "self") || e.Data.Source != "visible_ui" {
			return Frame{}, malformed()
		}
		return Frame{Event: &e}, nil
	case "error":
		var e ProtocolError
		_, hasID := o["commandId"]
		if decodeFields(raw, &e, "version", "kind", "code", "commandId") != nil || !codePattern.MatchString(e.Code) ||
			(hasID && !idPattern.MatchString(e.CommandID)) {
			return Frame{}, malformed()
		}
		return Frame{Error: &e}, nil
	default:
		return Frame{}, fmt.Errorf("%w: unexpected kind", malformed())
	}
}
