# WorkTool capability protocol v1

UTF-8 JSON messages, maximum 65,536 bytes each, object root. Integers must be exact JSON integers, timestamps Unix milliseconds, strings Unicode. Unknown object fields are ignored for additive evolution; known fields must have the documented types. Unsupported versions and kinds are rejected. Duplicate JSON object keys and nesting deeper than 16 are rejected. No URL, credentials or implicit transport are part of this protocol.

## Command

```json
{"version":1,"kind":"command","id":"cmd-1","target":{"deviceId":"device-a","sessionId":"epoch-a"},"operation":"text.send","params":{"conversationId":"local-handle","text":"hello"},"expiresAt":2000000000000}
```

`id`, device/session identifiers and conversation handles are nonempty ASCII `[A-Za-z0-9._:-]`, at most 128 characters. Every command has its own ID. `expiresAt` is required, positive and at most 2^53−1. The receiver checks it again immediately before execution. `params` is an object. Operations are strings up to 64 characters.

`target.sessionId` is the execution epoch advertised by a trusted local pairing/adapter, **not a chat name**. The SDK requires an explicit target; discovery and pairing are outside v1. The APK renews its epoch whenever the executor is rebuilt, including switching modes.

| Operation | Parameters | Result data |
| --- | --- | --- |
| `state.get` | `{}` | `paused`, `accessibility`, `textAllowed`, `eventsAllowed`, optional `conversationId` |
| `capabilities.get` | `{}` | `operations`, `events`, `limits`, `textOutcome` |
| `text.send` | `conversationId`, nonblank `text` ≤ 4,000 UTF-16 code units | optional evidence; no delivered/read guarantee |

Queries report actual local state. Text permission and event observation default off. A command cannot enable permissions, resume the device, bind a chat or change allowed operations. All commands require a verified peer authorized for that device/session and operation. Display names cannot select a recipient.

A batch is `{"version":1,"kind":"batch","commands":[...commands...]}` with 1–32 commands. The entire syntax is checked before any command is admitted. Commands execute separately in array order; there is no atomic transaction or batch-wide success. Each result refers to a command ID. A repeated ID in a batch follows ordinary deduplication/conflict rules.

## Results and protocol errors

```json
{"version":1,"kind":"result","commandId":"cmd-1","deviceId":"device-a","sessionId":"epoch-a","sequence":3,"state":"unknown","code":"ui_click_unconfirmed","data":{}}
```

| State | Meaning |
| --- | --- |
| `accepted` (sequence 1) | validated and recorded in the current epoch; not executed yet |
| `running` (sequence 2) | execution started |
| `succeeded` (sequence 3) | operation-specific success was verified; queries use this |
| `failed` (sequence 3) | executor established a failure |
| `partial_failure` (sequence 3) | partial completion with explicit evidence; reserved, no multi-recipient v1 operation |
| `unknown` (sequence 3) | action may have happened; final effect cannot be established |
| `rejected` (sequence 1 or 3) | policy/expiry/capability/target validation refused execution before a side effect |

`code` is a bounded machine-readable string and `data` an object, never a raw exception or secret. A text UI click without delivery evidence returns `unknown`, not `succeeded`. Error codes include `unsupported_operation`, `target_mismatch`, `unauthorized`, `paused`, `permission_denied`, `operation_disabled`, `expired`, `target_unavailable`, `target_ambiguous`, `busy`, `ledger_full`, and `execution_unknown`.

Protocol errors use `{"version":1,"kind":"error","code":"malformed","commandId":"cmd-1"}` (command ID optional). They are **not** execution results and never overwrite a pending command's state. Codes include `unsupported_version`, `malformed`, `id_conflict`, `frame_too_large`. Consumers surface them separately and continue waiting or cancel locally.

The receiver keeps a bounded ledger (1,024 entries by default) for the complete current epoch. The key is the command ID; owner identity and semantic payload are retained. Repeating the same owner's command returns its latest result without executing again. Expiry does not erase a recorded outcome. Different payload or owner for the same ID cannot replace the original. Unknown extension fields do not change command identity. A full ledger refuses new commands; renewal requires a new epoch. There is no persistence or exactly-once guarantee across restarts.

SDKs accept increasing result sequences only for a matching device/epoch/command, validate state/sequence combinations and keep a terminal result final. Transport disconnect fails outstanding waits with **unknown outcome** and never replays commands. Context deadline/cancellation after a send attempt likewise means unknown outcome, not rollback. Before sending, local errors such as missing target/transport or an already-cancelled context are definitely not submitted. Applications must reconcile uncertainty themselves; issuing a new ID is a new action.

## Events

```json
{"version":1,"kind":"event","eventId":"evt-1","deviceId":"device-a","sessionId":"epoch-a","name":"message.observed","occurredAt":1900000000000,"data":{"conversationId":"local-handle","text":"hello","sender":"remote","source":"visible_ui"}}
```

Events have their own IDs and never satisfy command waits. Event IDs identify local observations, not upstream enterprise-WeChat message IDs. `sender` is `remote` or `self`; `source` is `visible_ui`. Initial visible contents establish a baseline. Ambiguous overlap, navigation or scrolling resets the baseline rather than inventing new-message events. A bounded subscriber queue reports overflow; it cannot silently promise complete delivery. No replay, persistence or server webhook is provided.

## Transport and authentication boundary

Go's caller supplies a `Transport` with `Send(ctx, frame)`, `Receive(ctx)` and `Close()`. Implementations must honor cancellation and unblock on close. A transport instance is single-client owned. Its `Send` error may be after partial delivery. There is no default host, automatic reconnection or automatic retry.

Android's in-process adapter receives JSON plus an independently verified principal and emits JSON via an injected sink. Principal authorization is never read from JSON. This is an integration seam, not a listener reachable from other apps. Production authentication, network framing/TLS, channel pairing and secret storage must be implemented and reviewed in a later transport adapter. Tests use fake peers and memory channels only.

See [the implementation plan](../../docs/capabilities/PLAN.md) for the local UI safety boundary and device acceptance requirements.
