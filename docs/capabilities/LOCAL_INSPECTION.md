# Local inspection and simulated UI validation

Cloud recovery base: `master` at `b9e08bfa608a18d4b1fc6ad93f1eb58f2eeecff0` (2026-10-20).

## Local read-only scope

The existing control screen has **查看本地状态与记录**. It shows an explicitly refreshed snapshot of the current independent executor: closed/policy-available/paused flags, accessibility and local grants, whether a selection remains bound, pending count, and result-delivery failure count. It shows at most the latest 50 recorded commands. Opening the screen already invalidates the chat binding under the existing navigation rule; inspection never binds a chat, enables a grant or sends a command.

`AndroidCapabilities.inspectLocal()` and `localResult(recordId)` are main-thread, in-process reads. They do not require a channel, invoke a sink, schedule execution or add ledger entries. The latter returns the latest redacted record or `null` if absent. These are not new protocol operations or exported endpoints.

Each record contains only a local ordinal, known operation, sequence, state and allow-listed reason code. Arbitrary operation names and executor codes are replaced with fixed labels. Command IDs, peer/device/session/conversation IDs, input text, result data and exception text are omitted. The existing bounded execution ledger remains the source of truth; no file, database, telemetry, separate audit ledger or persistence is added. Traffic rejected before ledger admission (including unauthorized or wrong-epoch requests) is not recorded. The list is not a complete security audit log.

The ordinal is scoped to the current executor instance. Service reconstruction clears records and creates a new epoch; old commands remain invalid. A missing record after restart does **not** prove that the earlier action did not execute. `unknown` stays `unknown`: inspection and duplicate commands do not resend, and the UI has no retry control. Result-delivery failures do not imply message-delivery failure.

## Executable contract coverage

`SimulatedUiTest` injects a synthetic `TextSurface` into the real `SelectedConversation` and `CommandEngine` path. It verifies:

- Conversation switch, local text-grant or accessibility revocation, and lock state after admission prevent input and clicks.
- The same changes during input produce `unknown` and prevent clicking.
- Duplicate commands before and after execution perform at most one input/click, including click true, false and exception outcomes.
- An unconfirmed input does not click; restart rejects the previous epoch.

`LocalInspectionTest` verifies read-only admission/pending/terminal queries, detached snapshots, bounded records, `unknown` retention, absence of payload and identity fields, redaction of arbitrary executor codes/operation names, unavailable policy, closed executor, delivery failure and exclusion of unauthorized/wrong-epoch traffic.

These tests simulate the visible UI state. They do not execute `AccessibilityNodeInfo`, `AndroidTextSurface`, real lock/unlock, Android events or WeCom. Passing them is not device or layout acceptance.

## Cloud validation

The cloud environment has JDK 21 but no Android SDK/Gradle installation. The Gradle wrapper download could not resolve its host through the Java network path. Public Maven downloads through the existing container network succeeded: the exact configured Kotlin 2.3.10, Gson 2.13.2 and JUnit 4.13.2 artifacts, and the compiler's declared runtime dependencies, were verified against Maven's published SHA-1 values; local SHA-256 values were also recorded. No repository dependency versions changed.

The actual Kotlin compiler runs with JVM target 1.8 and a 512 MB heap, followed by JUnit. Go exports its actual command/batch fixtures before JUnit; Kotlin emits the reciprocal fixtures before Go tests run with `WORKTOOL_REQUIRE_KOTLIN=1`. All compile/test commands use the shared cloud build lock and Go parallelism is limited to two workers / one package.

Cloud execution passed: 30 JUnit methods (28 capability-core and two existing text-outcome tests), bidirectional Go/Kotlin fixtures, Go test/race/vet, and 30 Android source contract checks. Evidence is retained in `/tmp/worktool-jvm/check.sh`, `/tmp/worktool-jvm/final-validation.log`, and `/tmp/worktool-jvm/SHA256SUMS` in this cloud session. Android app compilation, lint, APK generation and device validation are not part of these JVM/source checks and remain unverified in this environment. No signing, credential creation or secret readout, real accounts, real channel, tunnel, CI, deployment or release was used.
