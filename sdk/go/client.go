package worktool

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"time"
)

// Transport is authenticated and owned by one Client. Close must unblock Receive and Send.
// Send/Receive must honor context cancellation. A Send error does not prove non-delivery.
type Transport interface {
	Send(context.Context, []byte) error
	Receive(context.Context) ([]byte, error)
	Close() error
}
type Error struct {
	Code           string
	CommandID      string
	OutcomeUnknown bool
	Cause          error
}

func (e *Error) Error() string { return "worktool: " + e.Code }
func (e *Error) Unwrap() error { return e.Cause }

type completion struct {
	result Result
	err    error
}
type pendingCall struct {
	done      chan completion
	last      int
	attempted bool
}

// Client never retries, reconnects, or opens a socket. Use a fresh client after disconnect.
type Client struct {
	transport Transport
	target    Target
	ctx       context.Context
	cancel    context.CancelFunc
	mu        sync.Mutex
	pending   map[string]*pendingCall
	used      map[string]bool
	closed    bool
	sendGate  chan struct{}
	events    chan Event
	updates   chan Result
	errors    chan error
	dropped   atomic.Uint64
	closeOnce sync.Once
}

func NewClient(transport Transport, target Target) (*Client, error) {
	if transport == nil {
		return nil, &Error{Code: "missing_transport"}
	}
	if !target.valid() {
		return nil, &Error{Code: "missing_target"}
	}
	ctx, cancel := context.WithCancel(context.Background())
	c := &Client{transport: transport, target: target, ctx: ctx, cancel: cancel, pending: map[string]*pendingCall{},
		used: map[string]bool{}, sendGate: make(chan struct{}, 1), events: make(chan Event, 32),
		updates: make(chan Result, 64), errors: make(chan error, 32)}
	go c.receive()
	return c, nil
}

// Events, Updates and Errors are bounded observation streams. Done signals their end.
// Dropped reports total overflow; a full errors channel also increments this count.
func (c *Client) Events() <-chan Event   { return c.events }
func (c *Client) Updates() <-chan Result { return c.updates }
func (c *Client) Errors() <-chan error   { return c.errors }
func (c *Client) Done() <-chan struct{}  { return c.ctx.Done() }
func (c *Client) Dropped() uint64        { return c.dropped.Load() }
func (c *Client) report(err error) {
	select {
	case c.errors <- err:
	default:
		c.dropped.Add(1)
	}
}
func (c *Client) overflow() { c.dropped.Add(1); c.report(&Error{Code: "subscriber_overflow"}) }

func (c *Client) Call(ctx context.Context, command Command) (Result, error) {
	if ctx == nil {
		return Result{}, &Error{Code: "missing_context"}
	}
	if err := ctx.Err(); err != nil {
		return Result{}, &Error{Code: "not_submitted", CommandID: command.ID, Cause: err}
	}
	if command.Target != c.target {
		return Result{}, &Error{Code: "target_mismatch", CommandID: command.ID}
	}
	raw, err := EncodeCommand(command)
	if err != nil {
		return Result{}, err
	}
	if command.ExpiresAt <= time.Now().UnixMilli() {
		return Result{}, &Error{Code: "expired", CommandID: command.ID}
	}
	p := &pendingCall{done: make(chan completion, 1)}
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return Result{}, &Error{Code: "disconnected", CommandID: command.ID}
	}
	if c.used[command.ID] {
		c.mu.Unlock()
		return Result{}, &Error{Code: "id_reused", CommandID: command.ID}
	}
	// Bound client memory without forgetting identities and permitting accidental repeats.
	if len(c.used) >= 1024 {
		c.mu.Unlock()
		return Result{}, &Error{Code: "ledger_full", CommandID: command.ID}
	}
	c.used[command.ID] = true
	c.pending[command.ID] = p
	c.mu.Unlock()
	defer func() { c.mu.Lock(); delete(c.pending, command.ID); c.mu.Unlock() }()
	select {
	case c.sendGate <- struct{}{}:
	case <-ctx.Done():
		return Result{}, &Error{Code: "not_submitted", CommandID: command.ID, Cause: ctx.Err()}
	case done := <-p.done:
		return done.result, done.err
	}
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		<-c.sendGate
		return Result{}, &Error{Code: "disconnected", CommandID: command.ID}
	}
	if err := ctx.Err(); err != nil {
		c.mu.Unlock()
		<-c.sendGate
		return Result{}, &Error{Code: "not_submitted", CommandID: command.ID, Cause: err}
	}
	// Waiting for another Send can outlive the command even when its context remains live.
	// Refuse it before marking an attempt, so local expiry never implies unknown delivery.
	if command.ExpiresAt <= time.Now().UnixMilli() {
		c.mu.Unlock()
		<-c.sendGate
		return Result{}, &Error{Code: "expired", CommandID: command.ID}
	}
	p.attempted = true
	c.mu.Unlock()
	err = c.transport.Send(ctx, raw)
	<-c.sendGate
	// A terminal result can arrive synchronously during Send and is stronger evidence than a send error.
	select {
	case done := <-p.done:
		return done.result, done.err
	default:
	}
	if err != nil {
		return Result{}, &Error{Code: "send_unknown", CommandID: command.ID, OutcomeUnknown: true, Cause: err}
	}
	select {
	case done := <-p.done:
		return done.result, done.err
	case <-ctx.Done():
		select {
		case done := <-p.done:
			return done.result, done.err
		default:
		}
		return Result{}, &Error{Code: "wait_cancelled", CommandID: command.ID, OutcomeUnknown: true, Cause: ctx.Err()}
	}
}
func (c *Client) receive() {
	for {
		raw, err := c.transport.Receive(c.ctx)
		if err != nil {
			c.shutdown(err)
			return
		}
		frame, err := DecodeFrame(raw)
		if err != nil {
			c.report(err)
			continue
		}
		if frame.Error != nil {
			c.report(*frame.Error)
			continue
		}
		if frame.Event != nil {
			e := *frame.Event
			if (Target{e.DeviceID, e.SessionID}) != c.target {
				c.report(&Error{Code: "target_mismatch"})
				continue
			}
			select {
			case c.events <- e:
			default:
				c.overflow()
			}
			continue
		}
		r := *frame.Result
		if (Target{r.DeviceID, r.SessionID}) != c.target {
			c.report(&Error{Code: "target_mismatch", CommandID: r.CommandID})
			continue
		}
		c.mu.Lock()
		p := c.pending[r.CommandID]
		if p == nil || !p.attempted || r.Sequence <= p.last {
			c.mu.Unlock()
			continue
		}
		p.last = r.Sequence
		if r.Terminal() {
			var resultErr error
			if r.State != "succeeded" {
				resultErr = &Error{Code: r.Code, CommandID: r.CommandID, OutcomeUnknown: r.State == "unknown"}
			}
			p.done <- completion{r, resultErr}
			delete(c.pending, r.CommandID)
		}
		c.mu.Unlock()
		select {
		case c.updates <- r:
		default:
			c.overflow()
		}
	}
}
func (c *Client) shutdown(cause error) {
	c.closeOnce.Do(func() {
		c.mu.Lock()
		c.closed = true
		for id, p := range c.pending {
			p.done <- completion{err: &Error{Code: "disconnected", CommandID: id, OutcomeUnknown: p.attempted, Cause: cause}}
		}
		c.pending = map[string]*pendingCall{}
		c.mu.Unlock()
		c.cancel()
		_ = c.transport.Close()
	})
}
func (c *Client) Close() error { c.shutdown(errors.New("client closed")); return nil }
