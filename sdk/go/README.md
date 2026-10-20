# WorkTool capability SDK (Go)

Module: `github.com/Icatme/worktool/sdk/go`. Standard library only; Go 1.23 or newer. This is a draft API in the repository, without a release tag. It implements [protocol v1](../../protocol/v1/README.md), not the legacy SaaS numeric API.

```go
// transport must already be authenticated and explicitly provided by your application.
client, err := worktool.NewClient(transport, worktool.Target{
    DeviceID: pairedDeviceID, SessionID: currentExecutionEpoch,
})
if err != nil { return err }
defer client.Close()

ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
defer cancel()
result, err := client.Call(ctx, worktool.NewQuery(
    "unique-command-id", target, "capabilities.get", time.Now().Add(time.Minute),
))
// Inspect result and *worktool.Error.OutcomeUnknown before making any further decision.
```

There is no default transport, endpoint, listener, authentication bypass, automatic retry or reconnect. `NewClient` takes ownership of a transport and starts only its supplied `Receive` loop. Do not share that transport with another client. `Transport.Send/Receive` must honor context cancellation, and `Close` must unblock both. Implement TLS, peer authentication, framing and pairing separately before production use.

`NewText` requires a current, locally issued conversation handle. It never searches by display name. `Call` sends once, correlates only matching device/epoch/command results and waits for a terminal state. A successful return requires `succeeded`. Rejection/failure/unknown return both the received result and a structured error. UI clicks may return `unknown` even when they actually sent the message.

An already-cancelled context or local validation error means not submitted. After a send attempt, timeout, cancellation or send error can have an unknown outcome. Cancelling the wait does not undo a UI action. Do not generate a new command ID to blindly retry. This SDK refuses reusing IDs in the same client, including after a timeout, and bounds its identity ledger to 1,024 commands. A replacement client does not expand the APK epoch ledger.

`Events()`, `Updates()` and `Errors()` are bounded streams. Events are independent from command results. `Dropped()` is a cumulative overflow count; the error stream reports overflow when it has capacity. Consume streams using `select` with `Done()`; channels are not closed to avoid racing senders. `Call`'s terminal result does not depend on consuming `Updates()`.

`EncodeBatch` prepares a frame for adapter/contract use. `Call` submits one command; concurrent `Call`s have independent IDs and waits. This version does not expose a batch client call or transactional semantics.

Run local tests:

```powershell
# From the repository root, with the already installed JDK/Android SDK configured:
.\scripts\test-capabilities.ps1 -Gradle .\gradlew.bat
```

The script first runs the Go encoder, then Kotlin tests read those files and write Kotlin output; the final Go tests require those Kotlin outputs. All channels, identities, executors and contents are synthetic. No phone, accessibility permission, enterprise-WeChat account, server or network transport is needed. See the plan for still-pending real-device acceptance.
