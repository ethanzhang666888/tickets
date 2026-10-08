# Android feasibility companion — milestone 1

This is a **real Kotlin Android prototype**, not a ticket-buying engine. Every protocol response declares `protocolVersion=1`, `stage=feasibility`, and `executionSupported=false`.

- Application: `local.tickets.companion`, minimum API 29, target/compile API 35.
- Intended device: Huawei SEA-AL10 / HarmonyOS 4 / Android API 29, Damai 9.0.35.
- Damai package allowlist: **`cn.damai`**. The initial assignment said `com.damai`; the manifest/accessibility scope was corrected in the shared workspace to `cn.damai`, and the implementation now agrees. Verify the installed package before device validation; do not broaden the allowlist silently.
- Native node reads: Damai and the companion's synthetic test activity only. MainActivity is a secure consent/settings staging screen and is not traversed.
- The sole native `ACTION_CLICK` requires our package, visible synthetic activity, exactly one known view ID, and an enabled visible native Button. There is no Damai click, gesture, submit, order, payment, or remote test-click action.
- API 29 MediaProjection uses an explicit Android consent activity and a foreground service. No permission or consent is enabled/accepted programmatically.
- Bundled Chinese OCR: `com.google.mlkit:text-recognition-chinese:16.0.1`, not the Play-services download variant.

## Local build

The machine already had Homebrew JDK 21 installed even though `/usr/bin/java` could not locate it. No additional JDK was installed. The Android command-line tools and Android 35 SDK platform/build tools were installed locally; Gradle also resolved its default Android 34 build tools before the project was explicitly pinned to 35.0.0.

Use these environment values:

- `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
- SDK manager: `/opt/homebrew/bin/sdkmanager`
- Existing adb: `/opt/homebrew/bin/adb`

From this directory run `sh ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` with the environment above. A new machine needs JDK 17 or 21 and SDK packages `platforms;android-35` and `build-tools;35.0.0` installed first. No Android Studio or emulator is required for the local build.

Pinned components: Gradle 8.9 (distribution SHA-256 checked by the wrapper), Android Gradle Plugin 8.7.3, Kotlin 2.0.21, Android build tools 35.0.0, ML Kit Chinese OCR 16.0.1, JUnit 4.13.2. The checked-in Gradle wrapper JAR is the official Gradle v8.9.0 artifact (SHA-256 `498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17`). `gradlew` is invoked via `sh`, so it does not depend on an executable file bit. Debug signing uses the local Android debug key and is not a reproducible production signature.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Safe install/launch — coordination required

No phone operations are performed by the build scripts. After agreeing with the person operating the phone, the following commands install and launch **only this companion**:

1. `adb -s 6HJDU19731013874 install -r /Users/ethan/Desktop/抢票神器/android-companion/app/build/outputs/apk/debug/app-debug.apk`
2. `adb -s 6HJDU19731013874 shell am start -n local.tickets.companion/.MainActivity`

The user must handle any Huawei install confirmation. In the app, use the first button to open accessibility settings and **manually enable** the companion. Read the disclosure. Use the second button to request screen capture and **manually accept or reject** Android's capture prompt. Never use `settings put secure`, accessibility gestures, or adb taps to approve these permissions.

Start with the synthetic page, not Damai. A native test click should increment the counter once. Inspect should return node counts and, when capture is authorized, screenshot dimensions, capture/OCR timing and OCR block/candidate counts. OCR is asynchronous: `inspect` can return `awaiting_frame`; poll `status` until complete or an error. Opening Damai from the companion only starts the installed app and schedules one read after 1.6 seconds; it does not navigate to or select a product. Alternatively, manually open the intended non-payment page and use the authenticated `inspect` request.

## Loopback-only JSONL endpoint

The endpoint starts only while the user-enabled accessibility service is connected. It binds **device `127.0.0.1:18765`**, backlog 2, one request/response per connection, maximum input 8192 bytes, bounded read and main-thread waits. Invalid clients cannot disable the listener. The authenticated actions remain exactly `capabilities`, `inspect`, `native_audit`, `status`, and `stop`. The additional unauthenticated `pair` handshake only enrolls a desktop; it cannot inspect, click or execute a purchase.

### Pair using eight digits

1. Use the shared repository client: `python3 tools/android/companion_probe.py --serial <device-serial> --action inspect --samples 30 --dialog` from the repository root. It creates a dedicated dynamic USB forward and removes only its own forward afterward; no manual port forwarding is needed. `--dialog` is macOS-only; omit it for non-echoing terminal input elsewhere.
2. In the companion, manually enable its accessibility service, then tap **生成 8 位配对码（有效 2 分钟）**. No code is created automatically on launch.
3. Enter the displayed eight digits, preserving leading zeroes. Generate only once for this attempt. The phone no longer displays the 48-character bearer token, and the obsolete token-only probe has been removed.
4. After pairing succeeds, the guided client separately asks the user to grant screen capture and open the synthetic page. Only after that confirmation does it inspect. The process token stays in memory and is never printed, logged, persisted, copied to the clipboard, or exposed in command-line arguments.

An enrollment request has exactly three keys, for example `{"protocolVersion":1,"action":"pair","pairingCode":"12345678"}` followed by a newline. Key order and standard JSON whitespace may vary. The code is a string of exactly eight ASCII decimal digits. Duplicate/extra keys, escaped keys/code digits, single quotes, numeric codes, string/decimal versions and trailing content cannot be accepted. Enrollment parsing is implemented separately from `JSONObject` so Android's more permissive parser cannot change the enrollment rules tested on the JVM.

Success returns exactly `{"protocolVersion":1,"stage":"feasibility","executionSupported":false,"ok":true,"token":"<48 lowercase hexadecimal characters>"}` (JSON key order is not significant). Recognized failed enrollment requests return the same envelope with `ok:false` and `error:"pairing_failed"`, without a token or any distinction between an expired, exhausted, revoked, consumed or incorrect code. Invalid transport framing and requests that are not recognizable enrollment requests retain the existing generic protocol error.

The code is generated with `SecureRandom`, lasts **120 seconds** using Android's monotonic elapsed clock, permits at most **five failed valid-format candidate attempts**, and is consumed once on success. A valid-format candidate in a malformed envelope consumes an attempt and can never succeed, even if the candidate is correct. Invalid-format candidates are never compared with the secret. The exact 120-second boundary is expired; clock rollback also revokes the code. A fresh generation immediately revokes the previous outstanding code. **撤销当前配对码**, local/protocol Stop, notification Stop, accessibility-service destruction, and process death clear outstanding enrollment. Expired or consumed codes disappear from the phone display on its next refresh.

The 192-bit random bearer token stays process-memory-only. Renewing/revoking an enrollment code or stopping capture does **not** invalidate already-issued bearer tokens; process death invalidates all credentials. `FLAG_SECURE` still protects the activity, and the code view is excluded from accessibility, autofill and saved view state. There is no clipboard integration.

Authenticated requests are unchanged: exactly `protocolVersion` (integer 1), `token` (the token returned by successful pairing), and `action`. Existing responses do not include the token. `capabilities.actions` lists the five authenticated actions and adds `enrollment:{"action":"pair","codeDigits":8,"validityMs":120000,"maxFailedAttempts":5}` as handshake metadata. All enrollment state changes, verification and token exchange run on the Android main thread, serialized with generation/revocation/stop.

The bridge uses raw TCP over loopback/USB forwarding, not TLS. It must never be exposed through LAN forwarding or a network proxy. This is local development pairing, not a hardened production authentication system.

## One-use native layout audit

`python3 tools/android/companion_probe.py --serial <device-serial> --action native_audit --dialog` pairs first, then asks the operator to press **一次性核对大麦页面（仅节点，不截图不点击）** on the phone and manually open the intended page. The grant lasts 120 seconds and is consumed by one request, including a failed inspection. Stop, screen-off and accessibility interruption revoke it. This route stops projection and never captures, OCRs or clicks. It can examine native confirmation structure that the ordinary broad capture guard rejects, but refuses known cashier markers, foreign roots, incomplete scans and window changes.

Results contain fixed counts: known containers, checkbox/masked-identifier/document-type markers, resource-ID/WebView presence, session/tier labels and scoped window metadata. A separate `nativeStructure` diagnostic contains at most 80 nodes with parent indexes, allowlisted widget kinds, boolean states and conventional `cn.damai:id/<lowercase_resource_name>` identifiers. It accepts no text or description inputs. Overflow/invalid parents discard the whole structural list; the desktop independently checks shape, bounds, types and resource-name syntax. Resource identifiers describe the installed UI layout, not buyer identity. Source text and window titles are not exported. A masked identifier can be a phone number; neither a checkbox count nor a successful scan proves the required buyer identity triple. `identityTriplesVerified` and `executionReady` remain false. A scan with no known markers is a diagnostic result, not a passed picker or confirmation gate.

## Isolated device storage tests

Run `:app:connectedVerificationAndroidTest` with `ANDROID_SERIAL=<serial>` and the build environment above. The test target is **`local.tickets.companion.verification`**, separate from the normal installed application. Its manifest removes the launcher activities, accessibility service, capture service and internet permission; it cannot run the bridge or buy tickets. The tests assert this package and use unique synthetic directories within its private no-backup storage.

Connected Android tests may uninstall their target during cleanup. Never repoint instrumentation to the normal package or use app-data clearing to resolve a submission fence. The initial debug-target run passed three tests, but the normal prototype was absent afterward and had to be reinstalled. No live phone submission store existed at that stage. The separate verification target subsequently passed four device tests; before/after checks confirmed that the normal package remained installed at the same path. This tests package preservation, not every byte of its private storage. Reinstallation is not an acceptable recovery mechanism once real submission evidence exists.

The storage component is not yet wired to a purchase executor. Directory fsync and reopen/corruption checks do not establish exactly-once behavior of an end-to-end order, desktop/phone mutual exclusion, or protection from app deletion/backup rollback.

## Privacy and stop conditions

- Raw accessibility text, event strings, screenshots, OCR text and XML are not written to files or exported. Frame bitmaps are in-memory and recycled after OCR. Summaries contain only fixed page categories, counts, timing, dimensions, fixed status/error codes and test counters. The explicitly granted native audit additionally exports the bounded static layout metadata described above.
- `allowBackup=false`, `fullBackupContent=false`, and Android 12+ extraction rules exclude cloud/device transfers. No analytics code is added. ML Kit's DataTransport upload backend discovery, upload scheduler service/receiver, and optional AndroidX auto-initializers are removed from the merged manifest. `INTERNET` remains necessary for the device-loopback socket. Packet-level no-telemetry validation on the target device remains a gate; manifest inspection is not a traffic measurement.
- Capture stops on lock/screen-off, accessibility interruption/disconnect, projection revocation, known out-of-scope foreground, detected payment/confirmation text, failed scans, display-size change, and capture/OCR timeout. A missing root or incomplete native scan only while idle shares a bounded one-second recovery window, without consuming frames; during a capture either stops immediately. Persistent instability also stops. The 500 ms traversal budget and 1,200-node bound remain unchanged; no partial tree is accepted. The foreground guard polls and responds to scoped accessibility events, and rechecks before consuming/exporting a frame. A frame cannot cross between Damai and the synthetic activity.
- Every explicit inspection attaches a newly created image reader/surface. Callback ownership and a per-request generation reject obsolete buffers. The target Huawei delivered frames whose timestamps could not be directly compared to `elapsedRealtime`; that comparison is now diagnostic only, not a freshness decision.
- Payment detection retains a broad keyword stop on unknown layouts. On the measured complete picker shape (`preform_scrollview`, unique session/tier headings and the recorded parent relationships), a generic payment reference is permitted for read-only capture. Strong cashier, payment-action and confirmation/submit markers still stop. OCR uses that exception only after a fresh native layout recheck. This is not proof of complete payment-page recognition in arbitrary WebViews, nor permission to click. No purchase action exists regardless of classification.
- Black/protected captures report `secure_or_blank_frame`; capture startup/read failures have explicit safe codes. A black-frame heuristic cannot distinguish all `FLAG_SECURE` cases from genuinely dark content. Use the synthetic protected-window checkbox to validate Huawei behavior.
- Rotation/display resizing stops capture and requires renewed user consent. Process death does not reuse a stored projection token or restart capture automatically.
- A foreground-service notification includes a Stop action. The app and authenticated `stop` action also release projection resources. Stop additionally revokes outstanding enrollment codes; disabling accessibility closes the local endpoint and revokes enrollment. Already-issued process bearer tokens are unchanged until process death.

## Required real-device feasibility gate (not yet passed)

On 2026-10-08 the reader-generation build completed 30 warm synthetic OCR requests (median 331.5 ms, p95 384 ms). A separate check retained 26 recognized blocks and one Chinese candidate, then verified that enabling the synthetic protected-window checkbox stopped before OCR. These results do not establish accuracy or real Damai readability. The first real-page trial stopped on `node_scan_incomplete`; the latest diagnostic update adds `lastIncompleteScan`, a safe summary retained from the failing scan and reset on new capture consent. The diagnostic retest retained a 5-node / 114 ms incomplete scan, identifying the 100 ms traversal deadline. The latest build uses a tested 500 ms traversal budget with the unchanged 1,200-node cap and incomplete-scan stop rule; an individual Android node call is not interruptible. The adjusted build completed a real Damai detail-page nonempty OCR check (86 nodes, 32 blocks, one candidate), then 30 warm capture/OCR requests (OCR median 346.5 ms / p95 414 ms; USB round-trip median 650.09 ms / p95 710.74 ms), followed by an acknowledged Stop. Selection/buyer/confirmation pages and the full gate remain unverified. On this adjusted build, operator-driven synthetic dangerous-page and screen-lock tests reported `payment_foreground` and `screen_locked` respectively after separate successful OCR baselines; final Stop was acknowledged. These are not real Alipay or low-level resource-release measurements. Full evidence and limitations are in `../docs/android-device-design.md`.

Coordinate phone access first. Validate and record **only structured summaries** for:

1. Manual accessibility enable/disable and connected/listener state.
2. Unique synthetic native click count (exactly +1) and native node timing.
3. User consent denied, granted, and revoked; verify persistent foreground notification.
4. Chinese synthetic OCR without Google Play services and ideally with external networking disabled; record cold/warm timings without source text/images.
5. Synthetic secure-window behavior and dangerous-page checkbox stop.
6. Lock, app switch, external payment-app foreground, accessibility loss, rotation and explicit stop; verify resources are released.
7. Damai 9.0.35 native node availability, safe page classification, OCR fallback timing and protected-window behavior, with no navigation into order submission/payment.
8. User-generated eight-digit enrollment, expiry at 120 seconds, five failed candidates, one-use consumption, regeneration/revocation and Stop. Then authenticated capabilities/status/inspect/stop; malformed/oversized/wrong-token messages must fail without crashing it.
9. On-device traffic observation if a strict no-telemetry assertion is required.

Passing JVM tests, APK compilation and Android lint does **not** establish any of the device/OCR timing results above. Do not implement or enable a buying engine on the strength of a build alone.

## Repository boundary

Changes for this prototype live under `android-companion/`. Its nested `.gitignore` excludes Gradle caches, build artifacts, local SDK settings and signing material. No root `.gitignore` edit is necessary; if the parent prefers central rules, it can additionally ignore `android-companion/.gradle/`, `android-companion/**/build/`, and `android-companion/local.properties`. No commits or publishing are performed by this milestone.
