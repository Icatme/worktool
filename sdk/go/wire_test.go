package worktool

import (
	"encoding/json"
	"strings"
	"testing"
	"time"
)

func TestExactFieldNamesAndUnicode(t *testing.T) {
	c, err := NewText("x", target, "chat", "hello", time.UnixMilli(2000000000000))
	if err != nil {
		t.Fatal(err)
	}
	b, _ := EncodeCommand(c)
	raw := string(b)
	extended := strings.Replace(raw, `"text":"hello"`, `"text":"hello","Text":"wrong"`, 1)
	extended = strings.Replace(extended, `"operation":"text.send"`, `"operation":"text.send","Operation":"delete"`, 1)
	got, err := DecodeCommand([]byte(extended))
	if err != nil || got.Operation != "text.send" || string(got.Params) != string(c.Params) {
		t.Fatalf("extension changed semantic fields: %+v %v", got, err)
	}
	for _, value := range []string{`\ud800`, `\udc00`, `\ud800x`, `\ud800\u1234`} {
		bad := strings.Replace(raw, `"text":"hello"`, `"text":"`+value+`"`, 1)
		if _, err := DecodeCommand([]byte(bad)); err == nil {
			t.Fatalf("accepted %s", value)
		}
	}
	good := strings.Replace(raw, `"text":"hello"`, `"text":"\ud83d\udc4b"`, 1)
	if _, err := DecodeCommand([]byte(good)); err != nil {
		t.Fatal(err)
	}
	for _, raw := range []string{
		`{"version":1,"kind":"error","code":"malformed","commandId":null}`,
		`{"version":1,"kind":"error","code":"malformed","commandId":""}`,
	} {
		if _, err := DecodeFrame([]byte(raw)); err == nil {
			t.Fatal("accepted invalid optional ID")
		}
	}
}

func TestInvalidUTF8IsRejectedBeforeSerialization(t *testing.T) {
	if _, err := NewText("x", target, "chat", string([]byte{'a', 0xff, 'b'}), time.Now().Add(time.Minute)); err == nil {
		t.Fatal("constructor silently repaired invalid UTF-8")
	}
}

func TestBatchDepthIsCheckedAfterWrapping(t *testing.T) {
	c := NewQuery("deep", target, "future.operation", time.Now().Add(time.Minute))
	c.Params = json.RawMessage(`{"x":` + strings.Repeat("[", 14) + `0` + strings.Repeat("]", 14) + `}`)
	if _, err := EncodeCommand(c); err != nil {
		t.Fatal(err)
	}
	if _, err := EncodeBatch([]Command{c, query("query")}); err == nil {
		t.Fatal("batch nesting exceeded limit")
	}
}
