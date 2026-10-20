# Local validation record

Date: 2026-10-20. Base: merged master `a614299530d61adf4edb6345b10c5252aca19157`. No GitHub Actions, real service, device, account, message, credential creation, signing or deployment is part of this round.

## Recoverable work

- [PR #4](https://github.com/Icatme/worktool/pull/4): protocol and plan, `be745f69402e696d3cedff330224f680aafb8407`, base master.
- [PR #5](https://github.com/Icatme/worktool/pull/5): Kotlin core / Go SDK, `67d4281e64f100ba54beac7901445e810ce1b963`, base `docs/capability-protocol-v1`.
- [PR #6](https://github.com/Icatme/worktool/pull/6): Android integration, base `feat/capability-core-sdk`, with tested source commit `81ee23f68b75c5133a0da86d842edb3339365dc7`. Subsequent closeout edits only update validation documentation.

This section records the initial draft delivery. The user subsequently authorized review, fixes and merging these three PRs. See [the final merge review](MERGE_REVIEW.md) for the new verification round; GitHub records the actual merge status and merge commits.

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
| Android final integration | `capability-android-full-5.txt`: Debug, unsigned Release/R8, app unit tests, verification and three-module lint passed in 2m 2s; 272 tasks (59 executed, 213 up-to-date) |
| Android app JVM tests | 12 JUnit methods, zero failures/errors/skips: HTTP 6, protocol wrapper 1, offline/mode lock 1, overlay correlation 1, binding completion 1, text outcomes 2 |
| Reused verification tests | 7 methods (the same HTTP 6 and protocol wrapper 1), passing; not additional distinct test coverage |
| Android lint | Zero errors, 287 warnings: app 246, baselibrary 10, floatwindow 31; existing warning debt remains |
| Architecture/source checks | `capability-source-contracts.txt`: 30 checks, including no saved-view restoration of the four authorization controls |
| Core independent review | `review-capability-core-1.txt` through `-4.txt`; seven verified findings fixed; final focused review clean |
| Android independent review | `review-capability-android-1.txt` through `-3.txt`; accepted findings fixed; final focused static review clean |
| APK structure | `capability-apk-checks.json`: Debug and unsigned Release contain no native `.so`; `capability-apk-zipalign.txt`: Release `zipalign -c -P 16 -v 4` passed |
| Report counts | `capability-final-report-counts.json`: actual JUnit XML and lint XML counts for the tested source commit |

The Go/Kotlin contract tests run on JVM, not Android ART. Kotlin tests use fake principals and executors; Android selection tests use fake `TextSurface`s. Android app JVM tests additionally exercise the inert WS constructor, process mode lock, exact overlay-window correlation and the fact that click `true`/`false` both yield `unknown`. Existing HTTP tests use only local MockWebServer. Existing protocol/export regression checks are reused; duplicated execution in the `verification` module is not counted as new distinct coverage.

Covered failure boundaries include multiple commands in one batch, duplicate and conflicting IDs, queued pause/permission/expiry, principal/epoch mismatch, unavailable/ambiguous chat, local invalidation during input, a possible draft change after input failure, exceptions, output/frame/depth limits, Unicode agreement, out-of-order results, event/result separation, cancellation/timeout uncertainty, disconnection without replay and subscriber overflow.

## Review fixes and limits

Core fixes: bounded validated terminal results; reject original invalid UTF-8; validate Unicode property names; check selection invalidation generation before click; finalize queued policy failures; validate final batch depth; explicit shared whitespace rules.

Android fixes: local authorization switches cannot restore saved checked state; binding-control window transitions are identified by actual short-lived window IDs; only visible text can enter snapshots; legacy teardown removes queued messages and releases queued identities together; independent mode leaves/restores the normal keyboard. An initial review's expression-body compilation claim did not reproduce in the configured successful build; the method was nevertheless made an explicit block for clarity. Run `capability-android-full-3.txt` failed on non-public `View.accessibilityWindowId`; it was replaced with the public node API, without hidden API access or reflection. Runs `-4.txt` and `-5.txt` passed; the failed log is retained. The final review fixes preserve the known activity when window events have no package/class, and wait for the replacement floating logo to attach before completing the original, single-use 500 ms binding request.

The final unsigned Release APK is 3,740,191 bytes, SHA-256 `11cf994e4916765f6eeabb157b794d71d3844139fe062810b9dec32a0da8cc42`. It is a local validation output, not a signed distribution or release. No native libraries require an ELF alignment audit in this APK; the ZIP alignment check does not establish behavior on a 16 KB-page device.

The app no longer labels historical WeCom adaptation data as a guarantee that the new capability adapter has been tested.

## Still not verified

No emulator or real device was used. API 34/35/36 and minSdk 24 behavior, actual WeCom node layouts, recipient/session race handling, floating-window event ordering, lock/unlock, background behavior and the full test-account text loop remain unverified. A successful build/lint result does not establish those behaviors.

There is no production transport or authentication implementation, pairing flow, durable result recovery, stable provider conversation ID, reliable complete message feed or JZ/OCAI connection. These are explicit future integration work. The [device checklist](PLAN.md#尚需设备验收) and [integration boundary](INTEGRATION.md) describe the required next acceptance steps. No test fixture or fake transport should be shipped as a production authentication path.
