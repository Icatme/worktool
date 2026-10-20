# PR #4–#6 final merge review

Pre-merge snapshot, 2026-10-20. The user authorized review, fixes, ready conversion and merging these three PRs. This does not authorize releases, signing, deployment, paid model calls, production transport, real accounts or device operations.

## Pinned review targets

| PR | Head reviewed | Diff base |
| --- | --- | --- |
| [#4](https://github.com/Icatme/worktool/pull/4) | `be745f69402e696d3cedff330224f680aafb8407` | master `a614299530d61adf4edb6345b10c5252aca19157` |
| [#5](https://github.com/Icatme/worktool/pull/5) | `67d4281e64f100ba54beac7901445e810ce1b963` | #4 head |
| [#6](https://github.com/Icatme/worktool/pull/6) | `206ecb8f12dd8d224ed84bf875ac32f9c0469084` | #5 head |

The initial remote snapshot matched these commits. All three PRs were open drafts, with no pending checks or review requests. No branch protection or active rulesets were reported, and the repository had zero Actions workflows and runs. These checks do not substitute for local tests or authorize bypassing protection.

## Review outcome

The previous independent review reports were checked against the current files. This round also performed a new path-by-path diff review and local tool checks. No new paid-model review process or external model API was invoked. No additional reproducible blocking defect was accepted; no functional source change was needed after the pinned #6 head. This closeout changes documentation only.

Reviewed boundaries:

- Independent startup creates an inert WS output sink, does not start the legacy command loop, and gates old HTTP/WS entry points. A saved address cannot enable legacy mode. The process-local choice locks when the executor starts.
- Grants are local, default off and cannot be restored by Android saved-view state. Service startup pauses independent execution. Pause, configuration changes, navigation and executor replacement invalidate the selected handle.
- Text targets a locally captured current surface, not the first display-name match. Binding completion retains the original nodes/header and is single-use, generation checked and limited to 500 ms. Active handles expire after 60 seconds and are consumed before possible input.
- Existing drafts and ambiguous layouts refuse execution. Local validity is checked again after input. Both click return values and an unconfirmed input attempt yield `unknown`; neither is interpreted as delivery success or a reason to resend.
- The command ledger retains per-command identity and terminal uncertainty. Permission/expiry/pause are rechecked for queued work. Go correlates device/epoch/command/sequence, separates events and protocol errors, bounds queues, and never automatically replays on cancellation or disconnect.
- Kotlin and Go agree on explicit JSON fields, Unicode/whitespace rules, integer syntax, depth and size limits. The core reuses the existing Gson version. Go has no third-party dependencies; Gradle dependency repositories remain Google Maven/Maven Central with the official plugin portal.

Previously accepted fixes remain present: bounded replayable terminal evidence, original UTF-8/property-name validation, invalidation before click, queued policy-failure finalization, final batch-depth validation, shared whitespace rules, authorization-view restoration disabled, exact overlay-window correlation, visible-only observation, queue teardown and keyboard restoration. The last Android review fixes preserve activity identity across nameless window events and defer binding completion until the logo attaches.

## Fresh verification

All checks below passed on the pinned #6 source. The only subsequent edits are this review record and the historical-status clarification in `VALIDATION.md`. Full logs remain in the task workspace.

| Check | Fresh result / evidence |
| --- | --- |
| Bidirectional Go → Kotlin → Go contracts | `merge-contracts-1.txt`: 19 Kotlin JUnit methods; mandatory generated fixtures in both directions; Go tests (12 top-level functions plus subcases), race and vet passed |
| Android full rebuild | `merge-android-full-1.txt`: Debug, unsigned Release/R8, app units, verification and all three module lint tasks with `--rerun-tasks`; successful in 2m 45s, all 272 tasks executed |
| App JVM tests | 12 methods, zero failures/errors/skips; verification repeats 7 existing methods and is not additional distinct coverage |
| Lint | Zero errors; 287 warnings (app 246, baselibrary 10, floatwindow 31) |
| Source contracts / formatting | 30 checks passed (`merge-source-contracts.txt`); `gofmt -l` and `git diff --check` clean |
| Dependency scope | `go list -m all` contains only the SDK module; no new dependency repositories or toolchain downloads |
| APK structure | Both APKs contain no native `.so`; Release `zipalign -c -P 16 -v 4` passed (`merge-zipalign.txt`) |
| Structured report | `merge-validation-results.json` retains actual JUnit/lint counts, APK sizes and hashes |

The unsigned Release validation output is 3,740,191 bytes, SHA-256 `cdefcbef748539c6fa4f38e6c9f204ba10a1ba4eebb26f778fcb90d4309746ca`. It was not signed, installed or distributed. No byte-for-byte APK reproducibility or 16 KB-device behavior is claimed.

## Merge procedure and limits

Merge #4, then retarget #5 to master and merge it, then retarget #6 to master and merge it. Use ordinary merge commits with the checked head SHA; preserve branch ancestry and do not force, bypass protection or delete recovery branches. Every merge message includes `[skip ci]`. Verify each merge result before continuing.

The final master tree must equal the locally verified #6 tree, including this documentation-only closeout. Record actual merge SHAs and the final tree comparison in the task progress log and final handoff, and verify the repository still has no Actions runs. A mismatch requires inspection rather than claiming validation for different code.

No emulator, Android ART/device, WeCom layout or recipient-race acceptance was performed. The simple node/header checks cannot establish a provider-issued conversation identity. Existing lint warnings and pending device acceptance remain; production authentication, pairing, durable recovery and a real transport are still absent. See [the device checklist](PLAN.md#尚需设备验收) and [the integration limits](INTEGRATION.md).

## Follow-up priorities (planning only)

1. Build an Android fixture/instrumentation harness for navigation, identical headers, rotation, keyboard, permission loss and process replacement. Then, only with separately approved test devices/accounts, validate one text conversation on API 34/35/36 and a minSdk 24 representative. Keep unsupported layouts closed.
2. Specify and review one authenticated, explicitly configured transport: local device approval, device/epoch binding, operation scopes, TLS, bounded framing and disconnect handling. Reuse the current injected seam; do not start with a full backend or tunnel.
3. Design durable result lookup and reconciliation before automated recovery. Preserve `unknown`; distinguish retries of a query from new UI actions. A stable recipient identity and evidence for delivery need an actual supported source, not display-name matching.
4. Improve visible-message loss diagnostics and redacted operational logs, then reduce lint/deprecation debt in small batches. Keep optional legacy capabilities isolated until each has its own acceptance evidence.
