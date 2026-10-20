# Local validation record

Date: 2026-10-20. Base: merged master `a614299530d61adf4edb6345b10c5252aca19157`. No GitHub Actions, real service, device, account, message, credential creation, signing or deployment is part of this round.

## Recoverable work

- [PR #4](https://github.com/Icatme/worktool/pull/4): protocol and plan, `be745f69402e696d3cedff330224f680aafb8407`, base master.
- [PR #5](https://github.com/Icatme/worktool/pull/5): Kotlin core / Go SDK, `67d4281e64f100ba54beac7901445e810ce1b963`, base `docs/capability-protocol-v1`.
- Android integration is the dependent `feat/android-capability-adapter` batch. Final draft/head verification is recorded in the PR and task progress log.

All PRs remain drafts; this round has no merge authorization. Existing master is not changed by these drafts.

## Toolchain and reproducible commands

Existing JDK 17, Android SDK 36 / Build Tools 35.0.0, AGP 8.13.2, Gradle 8.13, Kotlin 2.3.10; Go 1.26.4 and GCC. No new system install or SDK license acceptance.

```powershell
# Set JAVA_HOME, ANDROID_HOME and (optionally) GRADLE_USER_HOME to existing local tools.
.\scripts\test-capabilities.ps1 -Gradle .\gradlew.bat
.\gradlew.bat --no-daemon --console=plain -PunsignedValidation=true `
    :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :verification:test `
    :app:lint :baselibrary:lint :floatwindow:lint
python scripts/check_android_contracts.py
```

`unsignedValidation` avoids creating or using a debug signing credential. Release runs R8 but is unsigned.

## Scope and evidence

The task workspace retains complete logs (outside the repository, not claims of cloud attachments):

| Check | Evidence |
| --- | --- |
| Go → Kotlin/JVM → Go JSON | `capability-contracts-6.txt`: mandatory generated files in both directions, passing |
| Kotlin core | 19 JUnit methods: 14 contract/engine, 5 selection tests, passing |
| Go | 12 top-level test functions including contract export/read and subcases; `go test`, `go test -race`, `go vet` passing |
| Android baseline integration build | `capability-android-full-2.txt`: Debug, unsigned Release/R8, unit tests, three-module lint passed |
| Android latest integration | Final build/lint/review evidence is updated below before delivery |
| Architecture/source checks | `capability-source-contracts.txt`: 30 checks, including no saved-view restoration of the four authorization controls |
| Core independent review | `review-capability-core-1.txt` through `-4.txt`; seven verified findings fixed; final focused review clean |

The Go/Kotlin contract tests run on JVM, not Android ART. Kotlin tests use fake principals and executors; Android selection tests use fake `TextSurface`s. Android app JVM tests additionally exercise the inert WS constructor, process mode lock, exact overlay-window correlation and the fact that click `true`/`false` both yield `unknown`. Existing HTTP tests use only local MockWebServer. Existing protocol/export regression checks are reused; duplicated execution in the `verification` module is not counted as new distinct coverage.

Covered failure boundaries include multiple commands in one batch, duplicate and conflicting IDs, queued pause/permission/expiry, principal/epoch mismatch, unavailable/ambiguous chat, local invalidation during input, a possible draft change after input failure, exceptions, output/frame/depth limits, Unicode agreement, out-of-order results, event/result separation, cancellation/timeout uncertainty, disconnection without replay and subscriber overflow.

## Review fixes and limits

Core fixes: bounded validated terminal results; reject original invalid UTF-8; validate Unicode property names; check selection invalidation generation before click; finalize queued policy failures; validate final batch depth; explicit shared whitespace rules.

Android fixes: local authorization switches cannot restore saved checked state; binding-control window transitions are identified by actual short-lived window IDs; only visible text can enter snapshots; legacy teardown removes queued messages and releases queued identities together; independent mode leaves/restores the normal keyboard. An initial review's expression-body compilation claim did not reproduce in the configured successful build; the method was nevertheless made an explicit block for clarity. A later build caught non-public `View.accessibilityWindowId`; it was replaced with the public node API, without hidden API access or reflection. Failed build logs are retained.

The app no longer labels historical WeCom adaptation data as a guarantee that the new capability adapter has been tested.

## Still not verified

No emulator or real device was used. API 34/35/36 and minSdk 24 behavior, actual WeCom node layouts, recipient/session race handling, floating-window event ordering, lock/unlock, background behavior and the full test-account text loop remain unverified. A successful build/lint result does not establish those behaviors.

There is no production transport or authentication implementation, pairing flow, durable result recovery, stable provider conversation ID, reliable complete message feed or JZ/OCAI connection. These are explicit future integration work. The [device checklist](PLAN.md#尚需设备验收) and [integration boundary](INTEGRATION.md) describe the required next acceptance steps. No test fixture or fake transport should be shipped as a production authentication path.
