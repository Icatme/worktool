package worktool

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

var target = Target{"device-a", "epoch-a"}

type fakeTransport struct {
	sent      chan []byte
	in        chan []byte
	closed    chan struct{}
	once      sync.Once
	sendError error
}

func fake() *fakeTransport {
	return &fakeTransport{sent: make(chan []byte, 64), in: make(chan []byte, 256), closed: make(chan struct{})}
}
func (f *fakeTransport) Send(ctx context.Context, b []byte) error {
	select {
	case f.sent <- append([]byte(nil), b...):
		return f.sendError
	case <-ctx.Done():
		return ctx.Err()
	case <-f.closed:
		return io.EOF
	}
}
func (f *fakeTransport) Receive(ctx context.Context) ([]byte, error) {
	select {
	case b := <-f.in:
		return b, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-f.closed:
		return nil, io.EOF
	}
}
func (f *fakeTransport) Close() error { f.once.Do(func() { close(f.closed) }); return nil }
func query(id string) Command         { return NewQuery(id, target, "state.get", time.Now().Add(time.Minute)) }
func reply(id, state string, seq int) []byte {
	b, _ := json.Marshal(Result{1, "result", id, target.DeviceID, target.SessionID, seq, state, "ok", json.RawMessage(`{}`)})
	return b
}
func newTestClient(t *testing.T, f *fakeTransport) *Client {
	t.Helper()
	c, e := NewClient(f, target)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { c.Close() })
	return c
}
func mustReceive[T any](t *testing.T, ch <-chan T) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(3 * time.Second):
		t.Fatal("timed out")
		var zero T
		return zero
	}
}
func TestNoImplicitTransportOrTarget(t *testing.T) {
	if _, e := NewClient(nil, target); e == nil {
		t.Fatal("nil transport accepted")
	}
	if _, e := NewClient(fake(), Target{}); e == nil {
		t.Fatal("missing target accepted")
	}
}
func TestCorrelationOutOfOrderTerminalAndEventSeparation(t *testing.T) {
	f := fake()
	c := newTestClient(t, f)
	done := make(chan completion, 1)
	go func() { r, e := c.Call(context.Background(), query("a")); done <- completion{r, e} }()
	mustReceive(t, f.sent)
	f.in <- reply("other", "succeeded", 3)
	f.in <- []byte(`{"version":1,"kind":"event","eventId":"e1","deviceId":"device-a","sessionId":"epoch-a","name":"message.observed","occurredAt":1900000000000,"data":{"conversationId":"chat","text":"hello","sender":"remote","source":"visible_ui"}}`)
	mustReceive(t, c.Events())
	select {
	case <-done:
		t.Fatal("event completed command")
	default:
	}
	f.in <- reply("a", "running", 2)
	f.in <- reply("a", "accepted", 1)
	f.in <- reply("a", "succeeded", 3)
	f.in <- reply("a", "running", 2)
	got := mustReceive(t, done)
	if got.err != nil || got.result.State != "succeeded" {
		t.Fatalf("%+v", got)
	}
	if r := mustReceive(t, c.Updates()); r.State != "running" {
		t.Fatal(r)
	}
	if r := mustReceive(t, c.Updates()); r.State != "succeeded" {
		t.Fatal(r)
	}
	if _, e := c.Call(context.Background(), query("a")); e == nil {
		t.Fatal("ID reused")
	}
}
func TestCancellationDeadlineDisconnectAndSendErrorNeverReplay(t *testing.T) {
	for _, mode := range []string{"cancel", "deadline", "disconnect", "send"} {
		t.Run(mode, func(t *testing.T) {
			f := fake()
			if mode == "send" {
				f.sendError = io.ErrUnexpectedEOF
			}
			c := newTestClient(t, f)
			ctx, cancel := context.WithCancel(context.Background())
			defer cancel()
			if mode == "deadline" {
				ctx, cancel = context.WithTimeout(context.Background(), 30*time.Millisecond)
				defer cancel()
			}
			done := make(chan error, 1)
			go func() { _, e := c.Call(ctx, query("a")); done <- e }()
			mustReceive(t, f.sent)
			if mode == "cancel" {
				cancel()
			}
			if mode == "disconnect" {
				f.Close()
			}
			err := mustReceive(t, done)
			var outcome *Error
			if !errors.As(err, &outcome) || !outcome.OutcomeUnknown {
				t.Fatalf("lost uncertainty: %v", err)
			}
			if mode == "cancel" && !errors.Is(err, context.Canceled) {
				t.Fatal(err)
			}
			select {
			case <-f.sent:
				t.Fatal("automatic replay")
			default:
			}
		})
	}
}
func TestPreCancelledNotSubmittedAndConcurrentIndependentCalls(t *testing.T) {
	f := fake()
	c := newTestClient(t, f)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := c.Call(ctx, query("cancelled"))
	var e *Error
	if !errors.As(err, &e) || e.OutcomeUnknown {
		t.Fatal(err)
	}
	select {
	case <-f.sent:
		t.Fatal("sent cancelled command")
	default:
	}
	done := make(chan error, 16)
	for i := 0; i < 16; i++ {
		go func(i int) { _, err := c.Call(context.Background(), query(fmt.Sprint("q", i))); done <- err }(i)
	}
	for i := 0; i < 16; i++ {
		raw := mustReceive(t, f.sent)
		cmd, err := DecodeCommand(raw)
		if err != nil {
			t.Fatal(err)
		}
		f.in <- reply(cmd.ID, "succeeded", 3)
	}
	for i := 0; i < 16; i++ {
		if err := mustReceive(t, done); err != nil {
			t.Fatal(err)
		}
	}
}
func TestProtocolErrorsAreSeparateAndOverflowObservable(t *testing.T) {
	f := fake()
	c := newTestClient(t, f)
	done := make(chan completion, 1)
	go func() { r, e := c.Call(context.Background(), query("a")); done <- completion{r, e} }()
	mustReceive(t, f.sent)
	f.in <- []byte(`{"version":1,"kind":"error","code":"id_conflict","commandId":"a"}`)
	mustReceive(t, c.Errors())
	select {
	case <-done:
		t.Fatal("protocol error became execution result")
	default:
	}
	for i := 0; i < 80; i++ {
		b, _ := json.Marshal(Event{1, "event", fmt.Sprint("e", i), "device-a", "epoch-a", "message.observed", 1900000000000, MessageData{"chat", "hi", "remote", "visible_ui"}})
		f.in <- b
	}
	f.in <- reply("a", "unknown", 3)
	out := mustReceive(t, done)
	var err *Error
	if !errors.As(out.err, &err) || !err.OutcomeUnknown {
		t.Fatal(out)
	}
	if c.Dropped() == 0 {
		t.Fatal("overflow not counted")
	}
}
func TestWireFixturesMalformedAndUnknownFields(t *testing.T) {
	dir := filepath.Join("..", "..", "protocol", "v1", "fixtures")
	for _, name := range []string{"command-text.json", "command-state.json"} {
		b, e := os.ReadFile(filepath.Join(dir, name))
		if e != nil {
			t.Fatal(e)
		}
		if _, e = DecodeCommand(b); e != nil {
			t.Fatal(e)
		}
	}
	for _, name := range []string{"result-unknown.json", "event-message.json", "error-conflict.json"} {
		b, e := os.ReadFile(filepath.Join(dir, name))
		if e != nil {
			t.Fatal(e)
		}
		if _, e = DecodeFrame(b); e != nil {
			t.Fatal(e)
		}
	}
	b, _ := EncodeCommand(query("a"))
	s := string(b)
	for _, raw := range []string{
		strings.Replace(s, `"version":1`, `"version":2`, 1),
		strings.Replace(s, `"version":1`, `"version":"1"`, 1),
		strings.Replace(s, `"version":1`, `"version":1,"version":1`, 1),
		strings.Replace(s, `"params":{}`, `"params":null`, 1),
		s + `{}`, strings.Repeat(" ", MaxFrame+1),
		`{"version":1,"kind":"command","x":` + strings.Repeat("[", 18) + `0` + strings.Repeat("]", 18) + `}`,
	} {
		if _, e := DecodeCommand([]byte(raw)); e == nil {
			t.Fatal("accepted malformed frame")
		}
	}
	for _, state := range []string{"accepted", "running", "nonsense"} {
		if _, e := DecodeFrame(reply("a", state, 3)); e == nil {
			t.Fatalf("bad state %s", state)
		}
	}
}
func contractDir() string {
	if v := os.Getenv("WORKTOOL_CONTRACT_DIR"); v != "" {
		return v
	}
	return filepath.Join("..", "..", "build", "contract")
}
func TestExportGoContract(t *testing.T) {
	dir := contractDir()
	if e := os.MkdirAll(dir, 0755); e != nil {
		t.Fatal(e)
	}
	c, err := NewText("go-text", target, "chat-local-1", "你好，SDK 👋", time.UnixMilli(2000000000000))
	if err != nil {
		t.Fatal(err)
	}
	b, e := EncodeCommand(c)
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(dir, "go-command.json"), b, 0644); e != nil {
		t.Fatal(e)
	}
	b, e = EncodeBatch([]Command{c, NewQuery("go-state", target, "state.get", time.UnixMilli(2000000000000))})
	if e != nil {
		t.Fatal(e)
	}
	if e = os.WriteFile(filepath.Join(dir, "go-batch.json"), b, 0644); e != nil {
		t.Fatal(e)
	}
}
func TestReadActualKotlinContract(t *testing.T) {
	b, err := os.ReadFile(filepath.Join(contractDir(), "kotlin-command.json"))
	if errors.Is(err, os.ErrNotExist) && os.Getenv("WORKTOOL_REQUIRE_KOTLIN") != "1" {
		t.Skip("run Kotlin contract test, then WORKTOOL_REQUIRE_KOTLIN=1 go test")
	}
	if err != nil {
		t.Fatal(err)
	}
	c, err := DecodeCommand(b)
	if err != nil || c.ID != "kotlin-text" {
		t.Fatalf("%+v %v", c, err)
	}
	for _, name := range []string{"kotlin-result.json", "kotlin-event.json"} {
		raw, e := os.ReadFile(filepath.Join(contractDir(), name))
		if e != nil {
			t.Fatal(e)
		}
		if _, e = DecodeFrame(raw); e != nil {
			t.Fatal(e)
		}
	}
}
