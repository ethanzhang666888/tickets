# Local build evidence — 2026-10-08

## Measured picker layout: read-only capture gate

- A subsequent paired real-picker audit returned a complete static structure: 40 nodes / 53 ms, 23 visible resource-ID nodes. It uses `preform_scrollview` with direct `tv_perform_name` / `tv_price_name` headings and anonymous cards, plus a separate `bottom_layout`. Every recorded selected/checked flag was false; this cannot verify the requested session/tier or authorize a click.
- `PickerCaptureEvidence` recognizes only this measured complete parent/child shape, unique markers and single root. Missing/duplicate markers, changed hierarchy, extra root overlays, known dialogs, incomplete scans and strong payment/confirmation text prevent recognition. A generic payment reference on this exact layout no longer causes the ordinary read-only capture to be classified as a cashier. Unknown layouts retain the original broad guard.
- OCR retains strong payment/confirmation stops even when the layout was recognized. Generic-word handling requires both the original capture's layout and a fresh post-OCR layout check. `pickerLayoutRecognized` describes structure only, never event identity, a selected ticket, or execution permission. No Damai clicking was added.
- `:app:assembleDebug :app:lintDebug :app:connectedVerificationAndroidTest` passed, including **7 isolated device tests**: the four earlier checks plus generic-word handling, malformed layouts, and strong native/OCR payment markers. Python **143 tests passed**; the new decoder flag regression first failed before its allowlist update. Full JVM tests remain pending the separate parser implementation.
- The ordinary application is being updated for a manually consented real-picker screenshot/OCR trial. Neither a successful build nor these synthetic safety cases establishes real-picker OCR, exact target selection, buyer identity, or end-to-end purchase execution.

## Earlier continuation: native-only audit and isolated instrumentation

- Added a fifth authenticated `native_audit` action, enabled only by a manual one-use 120-second phone grant. It stops projection, performs no OCR/click, rejects known cashier markers and exports fixed structural counts only. Ordinary capture/payment guards remain unchanged.
- The first audit checked grant absence, one-use consumption and a 40-node result. A repeated audit on an operator-confirmed session/tier picker returned 39 nodes / 44 ms but zero known session/tier containers. This does not pass the real picker feasibility gate. See `../docs/android-native-audit-2026-10-08.json`.
- Added count-only resource-ID, WebView, session/tier-label and same-app window diagnostics. The device returned 39 nodes / 57 ms, 22 resource-ID nodes, 2 session-label nodes, 1 tier-label node, no standard WebView node, and exactly one app window matching the active/focused inspected window. This narrows the mismatch to unrecognized layout structure without proving its exact cause. A subsequent bounded `nativeStructure` diagnostic adds conventional static resource names, parent indexes, fixed widget categories and boolean states, never source text/descriptions. Its maximum is 80 nodes; partial structures are discarded. `:app:assembleDebug :app:lintDebug` passed. A screen-off receiver independent of projection now revokes native audit consent even when the capture service has stopped; device screen-off retest of this specific route is still outstanding. No fresh full JVM pass is claimed here while the independent parser implementation/test work remains incomplete.
- Python regression: **142 tests passed** after both diagnostic decoder updates. The new tests first failed because the fields were discarded, then passed with strict validation and allowlisting. The desktop `.app` was rebuilt successfully after the bounded structural decoder update. The existing external Cargo plugin-template diagnostic remains unrelated to this change.
- The original `connectedDebugAndroidTest` run completed **3 tests** on SEA-AL10 / API 29: synthetic submission persistence/handoff/manual acknowledgement, corruption rejection, and one-use audit permission. Its records lived only in unique test directories, not a live submission store.
- After that run, the normal prototype package was absent and was reinstalled. Connected instrumentation now uses the separate **`local.tickets.companion.verification`** target; the test asserts this identity. Its merged manifest has no companion activities, accessibility/capture services or internet permission. `:app:assembleVerification :app:assembleVerificationAndroidTest` passed. The isolated target subsequently passed **4 device tests**, adding Android JSON metadata/bounds verification to the three earlier behaviors. Before/after checks confirmed the normal package remained installed at exactly the same path. This verifies package preservation, not an exhaustive audit of every byte of its private storage.
- The bounded-structure build installed successfully, but its real-page retest stopped at invalid/absent local eight-digit pairing input, before any pairing or inspection request. New resource-name/parent metadata has therefore not yet been collected from the real picker. JavaScript **78 tests** and Rust **62 tests** passed in this continuation. These do not replace the outstanding full Kotlin regression or the phone capability gate.
- Neither the storage library nor pure authorization rules are a connected phone purchase executor. Real buyer identities, full confirmation fields, verified inventory refresh, USB-disconnected execution, desktop/phone exclusion, real Alipay handoff and the two-hour stability gate remain outstanding. No new order or reservation was submitted.

## Earlier build: bounded native traversal budget

- The diagnostic-only device retest retained a truncated scan with **5 nodes / 114 ms**; a later complete native-only scan had 86 nodes / 39 ms. This identifies the 100 ms deadline, not the 1,200-node limit, as the observed stop trigger.
- `NodeScanBudget` now permits up to 500 ms between traversal checks, retaining the 1,200-node cap and all incomplete-scan, foreground and payment stop rules. An individual Android node call cannot be interrupted, so this is a traversal budget, not a hard real-time latency guarantee. No partial tree is accepted.
- Three budget tests failed against the extracted original 100 ms policy, then passed after the change; they cover the observed 114 ms case, deadline/node boundaries and invalid clock/counters.
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed with **43 JVM tests**. Latest APK is `app/build/outputs/apk/debug/app-debug.apk`.
- The adjusted APK was installed and completed a real Damai detail-page retest: 86 native nodes, 32 OCR blocks and one candidate. A subsequent group completed 30 warm capture/OCR requests without an incomplete scan: native median 7 ms; screenshot median 87.5 ms; OCR median 346.5 ms / p95 414 ms; USB round-trip median 650.09 ms / p95 710.74 ms. Capture Stop was acknowledged afterward. See `../docs/android-phone-detail-baseline-2026-10-08.json`. Page identity/accuracy and equivalent desktop/browser comparisons remain unverified.
- The same adjusted build also passed two operator-driven stop checks: after a successful synthetic OCR baseline, enabling the dangerous-page checkbox produced `payment_foreground`; after fresh manual consent and another successful baseline, locking the phone produced `screen_locked`. Normal summaries were cleared and the final Stop request was acknowledged. Retained completed OCR diagnostics describe the preceding safe baselines, not OCR of the dangerous/locked screen. Actual Alipay foreground and low-level resource release were not separately measured.

## Prior diagnostic update: retain the incomplete native scan

- Added `lastIncompleteScan` to the safe status envelope and desktop decoder. It contains only the existing fixed-category/count/timing summary; no node text, identifiers, screenshots or pairing data. A new capture grant resets it; later successful native-only inspections do not erase the failure evidence.
- The 100 ms / 1,200-node bounds and fail-closed scan behavior are unchanged. This update diagnoses the real-page `node_scan_incomplete` result; it does not claim to fix it.
- Android `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug` passed against this update (40 existing JVM tests). The new desktop decoder regression first failed with a missing summary, then the full Python suite passed **137 tests** in the provisioned offline environment. The desktop `.app` was rebuilt successfully.
- Device records below were collected on the preceding reader-generation build. This diagnostic update was subsequently installed and its real-page retest established the deadline evidence recorded above.

## Prior device-tested build: isolated capture readers and bounded idle transitions

Artifact: `app/build/outputs/apk/debug/app-debug.apk`, package `local.tickets.companion`, version `1` / `0.1.0-feasibility`.

- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: successful after the current source changes.
- JVM tests: **40**, including three foreground-transition tests and three reader-generation tests. Both new groups were first observed failing against their unimplemented behavior, then passed.
- An idle missing accessibility root gets at most a one-second recovery window; known out-of-scope apps, lock/permission loss, failed scans and missing roots during an active capture still stop immediately. No frame is read during that idle wait.
- A target-device diagnostic run received 62 frames and rejected all 62 under the former timestamp test; OCR never started. The maximum observed numerical clock difference was 1,069,068 ms. This is not a measured frame age.
- Each capture request now closes the old reader and attaches a newly created reader/surface with a distinct generation. Only the current reader's current-generation callback can enter OCR, once. Stop/completion invalidate the generation. Timestamp differences remain diagnostics only; they no longer decide freshness.
- The 12-second timeout and foreground/payment/privacy checks remain. The current artifact was installed and retested: one frame arrived, zero frames were rejected, and the clock difference was 1,069,197 ms. The blank-frame check then stopped capture with `secure_or_blank_frame` before OCR (`ocrStarted=false`). This verifies passage beyond the former timestamp filter, not successful OCR; whether protection or a transient initial frame caused the blank image remains unproven.
- A subsequent guided synthetic-page run completed two capture/OCR requests (OCR 662/349 ms). A separate group completed 30 warm requests: OCR median 331.5 ms / nearest-rank p95 384 ms; screenshot median 69 ms; native median 4 ms; USB round-trip median 586.935 ms / p95 608.2 ms. The group's first request was not process-cold. See `../docs/android-synthetic-baseline-2026-10-08.json`; this is not a matched real-page or desktop/browser comparison.
- A separate nonempty-result check observed 26 OCR blocks and one Chinese candidate on the synthetic page. The operator then enabled the synthetic secure-window checkbox: one frame arrived, capture stopped with `secure_or_blank_frame`, and `ocrStarted=false`. Neither text nor images were exported. This validates that controlled protected-window case, not the cause of the earlier blank frame or every safety condition.
- The digest and size below describe an **earlier diagnostics artifact**, not the current APK.

## Prior diagnostics-only build

Artifact: `app/build/outputs/apk/debug/app-debug.apk`

- Package: `local.tickets.companion`
- Version: `1` / `0.1.0-feasibility`
- Size: 53,161,908 bytes (universal debug APK; ARM64, ARMv7, x86, x86_64)
- SHA-256: `f0f369cb89e68bdf1ab7ef9c1c2c126decbbad056054e52303b9fe748aaefb1d`
- `apksigner verify --verbose`: verified, APK Signature Scheme v2, one debug signer.
- `:app:testDebugUnitTest :app:assembleDebug :app:lintDebug`: BUILD SUCCESSFUL.
- Build environment for this update: JDK 21 at `/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`; SDK `/opt/homebrew/share/android-commandlinetools`.
- Lint: zero errors; four existing `SetTextI18n` warnings for prototype Chinese UI strings/interpolation. New pairing strings are resources.
- JVM tests: 34 total, zero failures/errors/skips (5 SafetyTest + 2 CaptureGuardTest + 8 AuthorizationTest + 8 EnrollmentTest + 5 PairingProtocolTest + 6 CaptureDiagnosticsTest).
- The six existing diagnostics tests first failed against empty method bodies, then passed with real counters/reset, positive maximum clock difference with overflow-safe saturation, fixed phases, frozen stop evidence and a strict five-field JSON export.
- Capture diagnostics are observation-only: the original `image.timestamp / 1_000_000 < requestedAt` rejection condition, surface handling, OCR invocation, and `> 12_000` timeout remain intact. Timeout is still `capture_or_ocr_timeout`; the preserved phase distinguishes its stage. This build does not claim to fix capture.
- Enrollment tests were first run against unimplemented method bodies (8 failures), then passed after implementation. Coverage includes exact 120-second expiry, five valid-format failures, one-time consumption, renewal/revocation, leading zeroes, invalid formats and monotonic-clock rollback.
- Strict handshake parser tests execute the same pure Kotlin grammar used on Android, avoiding Android/JVM JSONObject parsing differences. Extra/duplicate fields, wrong/string/decimal versions, trailing content, single quotes and escaped keys cannot succeed or provide free valid-candidate guesses.
- Focused source review confirmed main-thread-only enrollment state access, no full-token UI, protected/non-saved/non-accessible code view, no clipboard use, and enrollment revocation through local/protocol/notification Stop and service destruction.
- Capture foreground regression: the newly introduced test failed against the earlier permissive same-allowlist behavior, then passed after enforcing the exact expected Damai/synthetic foreground and visible synthetic activity.
- APK inspection confirmed embedded Hani Chinese recognition models and `arm64-v8a/libmlkit_google_ocr_pipeline.so`.
- Merged manifest inspection confirmed disabled backup, restricted accessibility scope, no DataTransport backend-discovery/upload scheduler components, no optional AndroidX automatic initializer provider.
- Official Gradle 8.9 wrapper JAR SHA-256 matched the published checksum.

No adb/device action was performed by this diagnostics task. The coordinating task reported successful eight-digit pairing and synthetic native inspection/click (counter 1), followed by `capture_or_ocr_timeout` after about 12 seconds. The new diagnostic APK has not been installed/tested on the phone by this task. Frame delivery, clock-domain compatibility and OCR completion remain unproven; the new counters are intended to distinguish them rather than assume a cause.

## Diagnostic response schema

`Session.status()` now includes `captureDiagnostics` with exactly these five fields (also shown by the synthetic page's existing status JSON):

- `phase`: one of `idle`, `awaiting_frame`, `frame_rejected_timestamp`, `processing_frame`, `ocr_running`, `complete`, `ocr_failed`, `capture_failed`.
- `receivedFrames`: integer 0–1,000,000; actual non-null images delivered into the pending request's `consume`, before foreground validation. Idle/pre-request drain and frames arriving while OCR is processing are not counted.
- `rejectedFrames`: integer 0–1,000,000; frames rejected by the unchanged timestamp condition only.
- `maxFrameClockBehindMs`: integer 0–2,147,483,647; maximum positive `requestedAtMs - frameTimestampMs` seen at the comparison point, saturated safely even on subtraction overflow. This is a numerical clock difference, not proof of frame age or compatible clock domains. Neither absolute timestamp is exported.
- `ocrStarted`: boolean; true once the code enters the recognizer invocation stage, retained after completion/failure/stop.

Diagnostics reset when a capture request passes the existing admission guards, remain process-memory-only, and freeze at `stopSafely` while the normal summary is cleared. A busy/rejected request does not erase previous capture evidence. Each capture service owns its diagnostic instance, so old-service callbacks cannot overwrite a new service's evidence. `complete` records a successful OCR callback, not proof that later foreground/payment checks permitted export; consult top-level `captureState`/`error` too.

To interpret the unchanged timeout: `awaiting_frame` with zero received frames means no non-null frame reached this request's consumer; `frame_rejected_timestamp` with nonzero rejected frames identifies the timestamp filter; `ocr_running` with `ocrStarted=true` identifies an entered recognizer stage without a completion/failure callback before stop. Acquisition/bitmap/secure-frame failures and OCR failures use the fixed failure phases. No raw text, images, identities, credentials or exception strings are added.

The concurrently supplied `policy/Authorization.kt` and its eight tests were preserved; this task did not author or connect them to platform controls. The APK's platform entry points remain feasibility-only, with no purchase execution route.
