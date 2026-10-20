# Integration boundary and actual capability scope

The v1 implementation is in `capability-core/`, `sdk/go/`, and the Android app's `capabilities/` package. There is no production transport, gateway, listener, cloud backend, tunnel, JZ adapter or OCAI adapter in this change. The app does not become remotely reachable merely by building it.

## Android seam

`WeworkService` creates `AndroidCapabilities` only in independent mode. `LocalCapabilities.current` exposes that service instance to a future **in-process** adapter. Its `target` carries the persisted random device identifier and a new executor epoch. These values are identifiers, not authentication secrets.

On the Android main thread, a trusted adapter may call:

```kotlin
// verifiedPeer comes from real authentication performed OUTSIDE JSON decoding.
val api = LocalCapabilities.current ?: error("accessibility service unavailable")
val channel = api.attach(verifiedPeer, FrameSink { json -> outbound.send(json) })
channel.receive(inboundJson)
// Close on disconnect; no automatic reconnect/replay is supplied.
channel.close()
```

This sketch deliberately has no built-in verifier or endpoint. `VerifiedPeer` must include the exact device/epoch and an allow-list of operation names; event delivery additionally requires `message.observed` in that allow-list. Never construct it from an unauthenticated JSON identity. All adapter methods run on main; adapter sinks must return promptly, place outbound data in their own bounded queues, and surface failures. Network framing and back-pressure belong to the future adapter. Each channel replacement resets the observation baseline but preserves the engine's current-epoch deduplication ledger.

Before the accessibility service exists, the seam is absent; a future pairing/control adapter must report that state locally and cannot fabricate an epoch or permission. `state.get` after attachment reports current local policy. Queries do not grant any capability.

## Local controls and UI adapter

- Independent mode is the process default. Text and event controls start off; service startup pauses execution. Controls cannot be changed by protocol messages.
- The existing floating record icon acts as **bind current chat** in independent mode. It remains the old node-print action in legacy mode. Pause/resume invalidates a binding. No new Android permission is automatically enabled.
- The local binding is a random, single-use text handle, valid for at most 60 seconds of monotonic time. The user must already be in the chat, with an empty draft. It is not a durable enterprise-WeChat conversation ID.
- The adapter uses the existing project's text input and message-list layout knowledge, but calls only bounded node reads, `ACTION_SET_TEXT`, and one exact `ACTION_CLICK`. It does not invoke the broad legacy room-search/send/retry loop.
- A supported chat activity must have exactly one visible editable node and one visible `ListView`. The retained nodes/window and header snapshot must still match. Header text is only an extra change detector, never a unique identity or lookup key. The send control must be uniquely visible, clickable, outside the message list and vertically aligned with the editor. Unknown layouts fail closed. Local node identities and navigation invalidation are conservative UI checks; they are not a provider-issued recipient identity and need device race/ambiguity acceptance before enabling text in actual use.
- Existing drafts are never overwritten. After input, the adapter rechecks local policy, selection validity and the exact draft. If any check fails, it does not click. A possible input change yields `unknown`; a click also yields `unknown` because the APK cannot prove upstream delivery. There is no restore/retry that could create another side effect.
- Navigation, scrolling, manual text changes, window changes, pause and configuration changes invalidate the handle. One text attempt consumes it. Binding a new chat establishes a fresh message baseline.
- The binding gesture itself closes the floating menu. It captures the intended chat immediately and completes once the replacement logo is attached; it never recaptures a later chat. Completion is single-use, limited to 500 ms, and rejected after chat interaction, pause/configuration change or captured-node/header mismatch. Actual old/new floating window IDs are recorded for one second; only events from those exact IDs can be ignored, and only if the selected chat surface still matches. Other/unknown window changes invalidate an active binding. Local authorization switches disable saved view state, so process restoration cannot silently restore a grant or resume execution.
- Observation is limited to simple visible text rows with an unambiguous avatar/content structure. Initial contents are not events. Non-text/ambiguous rows or ambiguous snapshot overlap reset the baseline. Repeated identical content can be dropped conservatively. There is no auto-scroll, background chat traversal, media access, history upload or complete-message guarantee.

## Legacy compatibility isolation

The old WS/config/upload/download entry points check `LegacyMode`. An inert WS manager handles legacy logging calls in independent mode without sending. The old command looper and demo auto-test do not start in independent mode.

Legacy mode requires an explicit local switch on each fresh process. Once either accessibility executor has started, mode choice is locked until process restart to prevent live executor mixing. To change mode, first disable the accessibility service, close/restart the app process, choose the mode, then re-enable the service yourself. A saved host/robot number alone does not enable networking. Turning off the accessibility service closes its socket and invalidates the new executor epoch. The legacy protocol itself is not redesigned and retains its earlier acceptance limitations; the v1 SDK never connects to it.

## Validation and remaining work

`scripts/test-capabilities.ps1` runs actual bidirectional Go/Kotlin encoding tests, fake principal/executor tests, Go race checks and vet. `:app:testDebugUnitTest` includes an offline-constructor/mode-lock regression in addition to earlier protocol/HTTP tests. Debug and unsigned Release/R8 builds and Android lint are required separately.

No real device, emulator, permissions, account or commercial server was used to validate this implementation. The actual UI adapter is **not certified working** on any WeCom version by these tests. Run the [device checklist](PLAN.md#尚需设备验收) before use. Production authentication/pairing/transport, reliable provider identity, durable result recovery, a test-account text loop, and JZ/OCAI integration remain future work.
