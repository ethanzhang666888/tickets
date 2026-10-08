# Android touch evidence — 2026-10-09

## Updated status after controlled device testing

The sections below headed **Initial read-only investigation** preserve the earlier 01:30 snapshot, not the final device state.

- Official prebuilt artifacts were subsequently provisioned: `@devicefarmer/minitouch-prebuilt@1.3.2` ARM64 binary SHA-256 `0dc348ee8e2b1b06ad44390b8a6975649f7d1ec63e9221b989b4e9ffc8996efb`; `@devicefarmer/stfservice-prebuilt@2.5.8` APK SHA-256 `11d995025c357302d1ea1e82eea0e91c7fe4bb3ae7a01a1c2fb6342cf1ef2893`. Package lifecycle scripts were disabled. The STF APK was used as a shell-process classpath, not installed as a service application.
- Actual EOF-only minitouch startup against `/dev/input/event4` failed with `Permission denied`. Direct evdev injection is unavailable under the tested shell identity.
- With the temporary shell-started `jp.co.cyberagent.stf.MinitouchAgent`, minitouch completed an EOF-only STF/InputManager handshake: protocol `v 1`, capabilities `^ 10 1080 2340 0`. This proves startup compatibility, not successful injection.
- A separate three-method synthetic instrumentation test passed 108 button clicks (6 warmups + 30 measured per method). Retained sample arrays and scope are in `android-touch-synthetic-2026-10-09.json`. Confirmed handler-effect medians: native `ACTION_CLICK` via instrumentation 1.2299475 ms; UiAutomation input injection 30.317708 ms; on-device `input tap` 291.9419265 ms. These are not desktop UIAutomator2 end-to-end, real Damai, or rendered-frame timings.
- After the user's accidentally cancelled installation, the isolated verification/test packages installed successfully. The four-method test then failed before any sample because `LocalSocket.soTimeout` was set before socket creation. This ordering was corrected and the test rebuilt.
- The next four-method run failed before any sample with `java.io.IOException: Connection refused` at socket connection (JUnit timestamp `2026-10-08T18:02:51` UTC). Both owned ADB touch-service sessions had exited with code 255 at `18:02:32` UTC. The cause of their exit is not established; this is not evidence that minitouch touch injection itself is incompatible. Subsequent process inspection confirmed neither owned touch process remained. No minitouch latency sample exists yet.
- Normal companion package was preserved. No real order was submitted or paid. Four-method performance and phone-local production acceptance remain incomplete.

## Initial read-only investigation

### Decision supported by the initial evidence

**Keep minitouch as a preferred candidate, not as a selected or proven backend.** On the connected Huawei SEA-AL10, Android 10/API 29, direct touch-device write access checks fail under the ADB shell identity. No minitouch executable at the two conventional deployment paths, minitouch process/socket, STF package, or `minitouchagent` socket was observed. The current feasibility companion has only a locally initiated synthetic accessibility click, not a remote synthetic dispatch endpoint.

Consequently all three latency comparisons are **unmeasured**. Minitouch is additionally **blocked in the currently observed environment**. This is not a measured runtime failure, and it is not proof that a properly provisioned STF-backed minitouch cannot work on this phone. No speed ranking, confidence claim, or ticket/purchase success claim is warranted.

The delivered command-line tool is deliberately **read-only**. Its synthetic benchmark core and safety/statistics tests are usable for a future scoped harness, but this delivery does **not** include live injection adapters or a working three-backend phone benchmark. That boundary prevents silently turning the feasibility APK into a generic arbitrary-app coordinate injector.

## Provenance and licensing

Retrieved from the official DeviceFarmer repository on 2026-10-09 (UTC+8):

- Repository: https://github.com/DeviceFarmer/minitouch
- Current `master` observed via GitHub API: commit `0f881ad36cdab6745704c5dc39e2be5453035c4f`, committed 2026-08-16, package version **1.3.2**. API provenance: https://api.github.com/repos/DeviceFarmer/minitouch/commits/master
- Pinned source: https://github.com/DeviceFarmer/minitouch/blob/0f881ad36cdab6745704c5dc39e2be5453035c4f/jni/minitouch/minitouch.c
- Pinned README: https://github.com/DeviceFarmer/minitouch/blob/0f881ad36cdab6745704c5dc39e2be5453035c4f/README.md
- Pinned license: https://github.com/DeviceFarmer/minitouch/blob/0f881ad36cdab6745704c5dc39e2be5453035c4f/LICENSE — **Apache License 2.0**, Copyright CyberAgent, Inc. Preserve applicable copyright/license notices and review redistribution obligations if shipping binaries.
- Pinned changelog: https://github.com/DeviceFarmer/minitouch/blob/0f881ad36cdab6745704c5dc39e2be5453035c4f/CHANGELOG.md — 1.3.2 updates the CI NDK; 1.3.1 includes numeric input-device discovery ordering and 16 KB load segment alignment. Neither establishes unrestricted Android 10 input permissions.
- Dependency: `jni/vendor/libevdev/source`, commit `d447a75e9e23f2286c44859f07b955981e82d483`, from the upstream libevdev submodule. Pinned `COPYING` retrieved through its GitHub mirror: https://github.com/whot/libevdev/blob/d447a75e9e23f2286c44859f07b955981e82d483/COPYING . It contains permissive copyright/permission terms plus a Linux-header GPLv2 notice and explicitly says there is no GPL code this package links to. Do not label the whole native dependency bundle solely Apache-2.0 without retaining dependency notices.
- STF setup and privilege explanation: https://github.com/DeviceFarmer/STFService.apk#running — the ordinary Android service and the ADB-launched CLI process are distinct; the latter needs permissions unavailable to ordinary third-party apps.

No minitouch source build, package installation, binary download, binary execution, or minitouch socket connection was performed. There is therefore no binary checksum/build provenance or runtime handshake to report. A future artifact must be built from pinned sources or independently matched to official release/package provenance, hashed, and identified by ABI/build before use. Do not run package lifecycle scripts or unknown binaries as a shortcut to provenance.

### What the source actually does

The inspected `consider_device` opens an input device with `O_RDWR`; touch events are written to that descriptor. If no suitable evdev device is found, `main` attempts `connect_android_service`, connecting to the abstract Unix socket **`minitouchagent`**, then forwards commands through `proxy_handler`. The README explicitly says Android 10 and newer do not work under default security policy and require the STFService workaround.

This distinction matters to the comparison: **minitouch-direct** and **minitouch-via-STF/InputManager** are different execution paths. An ordinary APK wrapper inherits its app UID and security domain; embedding this C binary cannot grant shell privileges or permission to write input devices. With the STF workaround, results must say “STF/InputManager-backed”, not imply direct kernel injection or fully standalone ordinary-APK operation.

The minitouch protocol sends a version, capability limits, and PID on connection, but sends **no per-command completion acknowledgement**. Successful socket writes are dispatch evidence only. The protocol coordinate maxima need not equal display dimensions; mappings must be derived from the actual backend's handshake and current rotation. No mapping or gesture was attempted here.

## Actual connected-device observations

Snapshot: **2026-10-09 01:30:46 UTC+8** (`2026-10-08T17:30:46.338645+00:00`). Serial was explicitly scoped to `6HJDU19731013874` for every command; the JSON omits the serial as unnecessary metadata.

| Property | Observed result |
|---|---|
| Manufacturer/model | HUAWEI / SEA-AL10 |
| Android | 10, API 29 |
| ABI list | arm64-v8a, armeabi-v7a, armeabi |
| Build | `user`, `ro.debuggable=0`; an earlier check also returned `ro.secure=1` |
| ADB shell identity | UID 2000, includes input group 1004, `u:r:shell:s0` |
| SELinux | Enforcing |
| Display | Physical and input surface 1080 × 2340; rotation 0 |
| Density | Physical 480, override 408 |
| Relevant input device | `/dev/input/event4`, `input_mt_wrapper`, `INPUT_PROP_DIRECT` |
| Touch axis metadata | X 0–1079, Y 0–2339; metadata read via `getevent -lp`, which exits without streaming input |
| Event nodes | event0–event6: read checks pass, write access checks fail |
| event4 mode/label | `crw-rw---- root input`, `u:object_r:input_device:s0` |
| Current foreground | Companion **MainActivity**, not SyntheticActivity; keyguard flag false |
| Installed companion | `0.1.0-feasibility`, version code 1, minSdk 29, targetSdk 35 |
| ADB input | `/system/bin/input` exists; no input command executed |
| UIAutomator | Existing `app_process / com.wetest.uia2.Main -p 9008` observed; untouched |
| minitouch | No process/socket observed; no file at `/data/local/tmp/minitouch` or `minitouch-nopie` |
| STF | No `jp.co.cyberagent.stf` package or `minitouchagent` socket observed |

The write check uses `test -w`, not an input-device write or `O_RDWR` probe. Readable metadata and input-group membership do not establish injection rights. The difference between mode bits and effective write access is consistent with the upstream warning, but this investigation did not inspect policy rules or capture an actual denied write; it does not attribute a specific SELinux rule as a proven cause. Absence at two known paths is not an exhaustive filesystem search.

No taps, gestures, activity launches, installs, root requests, process starts/stops, foreground changes, permission changes, UIAutomator startup, screenshot capture, page-tree extraction, purchase operations, or payment operations were performed.

## Existing implementations inspected

- `tools/android/driver.py` pins `uiautomator2==3.7.0`; its existing driver ultimately uses `device.click` at the center of a validated target rectangle. It also performs page/authorization checks. It was not imported or started, since startup/cleanup may alter phone processes.
- Companion `SyntheticActivity` owns resource ID `local.tickets.companion:id/synthetic_target`. Its button handler increments `Session.testClickCount`. The separate local “accessibility click” button calls `clickSynthetic`, which checks the own package and unique visible/enabled native button before `ACTION_CLICK`.
- Existing remote feasibility actions are `capabilities`, `inspect`, `native_audit`, `status`, `stop`; capability metadata advertises `testClickLocalOnly=true`, and the envelope says `executionSupported=false`.
- Synthetic status text refreshes every **500 ms**. Timing that text through UI extraction would add polling/render refresh latency; it must not be represented as native click-handler latency.

These are source inspections and installed-package/version metadata, not a paired live capabilities exchange. No token or private page text was retrieved. The parent implementation may subsequently change companion sources; recheck the installed build and its protocol before any later measurement.

## Delivered reproducible tooling

Files added only within the assigned scope:

- `tools/android/touch_benchmark.py`: fixed-command read-only probe, sanitized capability classification, exact synthetic snapshot guard, deterministic randomized interleaving, bounded benchmark core, and nearest-rank p95 summaries.
- `tools/android/test_touch_benchmark.py`: offline tests with an in-memory synthetic fixture and fake monotonic clock; no phone I/O.
- This report and `docs/android-touch-evidence-2026-10-09.json`.

Reproduce the safe device probe with `python3 tools/android/touch_benchmark.py --serial 6HJDU19731013874`. It accepts only this assigned serial and prints sanitized JSON. A successful probe command can exit 0 while reporting `status=blocked`: that means metadata collection completed, **not** that touch injection works. Query failures remain explicitly listed and unknown values are not converted into claims of absence.

Run offline tests with `PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tools/android -p test_touch_benchmark.py -v`. Final verification: **25 offline tests passed**; the evidence JSON parsed successfully. These checks establish probe parsing, safety guards, abort behavior and statistics, not real device touch performance.

### Benchmark method and boundaries

`run_benchmark(observe, dispatch, available_methods, ...)` is a trusted in-process integration seam, not a live transport and not a security sandbox. The caller must implement a genuinely scoped dispatcher and bounded observations. There is deliberately no CLI option for coordinates, arbitrary packages, shell payloads, minitouch socket writes, or live clicks.

Before every dispatch and on every effect observation, the core requires:

1. Exact owner package and exact focused/resumed synthetic activity, unlocked display, display ID 0, synthetic flag, one exact target resource ID, valid target bounds and nonnegative integer click counter.
2. Stable session identifier, rotation, width, height, target bounds and identity for the whole run. Missing/unknown values fail closed.
3. An unchanged counter before dispatch, then **exactly +1** for confirmation. Extra/decreasing counters, changed focus, changed geometry, dispatch exceptions, or timeouts stop the entire run without retry or subsequent injections. No late effect is attributed to the next backend.

Default design: **3 warmups + 30 measured trials per available method**, randomized method order within each round using seed `20261009`; each round contains each available method once. Unsupported methods are excluded rather than assigned zero latency. Warmups are reported separately and excluded from median/p95. The run is bounded to at most 30 warmups and 300 measured trials per method and 10 seconds configured effect timeout; callbacks themselves must enforce transport deadlines because arbitrary Python callbacks cannot be preempted by this core.

Two separate host-clock metrics:

- `dispatchMs`: time until the dispatcher returns; includes its transport/call overhead and makes no success claim.
- `confirmedEffectMs`: time from dispatch start to the first fresh observation of the target-handler counter increment, including transport and polling. This is an observable application-state effect, **not** proof a new frame was presented, and not pure on-device event latency.

Both metrics include sample counts, median and nearest-rank p95. Successes, timeouts, errors, warmup attempts/successes/timeouts, partial-run status, and per-trial outcomes remain explicit. Observation arriving after the deadline is censored even if the counter has incremented. Any future Android event-handler timestamps must use the same device monotonic clock for both endpoints; never subtract host and phone clock values.

The offline fixture proves control flow/statistics only. Its artificial durations are **not phone measurements** and are intentionally absent from the evidence JSON. No confidence intervals or backend winner are computed from dispatch timings.

### Important remaining safety limitation

Checking Android foreground and then sending a global coordinate injection has a time-of-check/time-of-use race: focus may change between those operations. A post-check detects a change but cannot undo a misdirected event. Accordingly no unsafe ADB/minitouch/UIAutomator coordinate adapter is supplied. Passing a snapshot to an arbitrary callback alone does not remove that race. A future dispatcher must independently enforce ownership at dispatch, or a separately authorized supervised test must explicitly accept a residual global-injection risk; this task did not authorize that risk.

## Exact next steps for the coordinating implementation

### Safe actions already reproducible without input

1. Run the fixed read-only probe command above. Do not import the production driver to “check” UIAutomator, and do not restart or stop its currently running process.
2. Run the offline tests. These require no Android connection or permissions.

### Preconditions before parent-coordinated device actions

1. Add and independently test a **synthetic-only** scoped endpoint or instrumentation harness, with physical on-device arming, short-lived exclusive session, strict own-activity/window/display/geometry checks, immediate revocation on pause/lock/rotation, the single hard-coded target, and a fresh counter observation channel. It must not expose production/purchase actions or arbitrary coordinates. Existing feasibility pairing and `status` alone do not provide this contract.
2. If a new companion build is required, coordinate installation with the user rather than performing it implicitly. This task did not install or modify the running APK.
3. Ask the user to manually open the companion, then **“4. 打开合成测试页（无订单）”** and keep that synthetic page visible. Do not turn on the simulated-dangerous-page checkbox. Confirm exact `local.tickets.companion/.SyntheticActivity`, display 0, rotation 0, 1080×2340 and the current target bounds using the newly scoped harness. Abort if any value is unknown or differs. Merely having MainActivity in front is insufficient.
4. Keep hands off the screen during a run and exclude competing automation; do not silently stop existing processes. If competing automation cannot be excluded safely, leave the test blocked.
5. Measure only actually available and safely scoped backends; record first-use/initialization cost separately from warm dispatch. For comparable calls, report whether dispatch includes session/ADB connection setup, whether a persistent connection is used, tap down/up duration, exact software versions, and whether the path is direct evdev or STF/InputManager.
6. To make minitouch available, the parent first needs separate authorization for provisioning: pinned artifact with reviewed provenance and hash, and on this API29 environment likely STF service plus its ADB-launched privileged process. Installing an ordinary wrapper APK alone is insufficient. Do not change SELinux, root the device, or assume the current shell can write event4. None of these provisioning actions was done or authorized by this investigation.
7. Only after authorized provisioning, a separately approved no-input capability startup/handshake can test actual runtime readiness. Do not connect to or take over an existing minitouch single-client session. A running socket/handshake still is not successful injection evidence.
8. Once the ownership boundary is solved and actions are explicitly coordinated, run the interleaved synthetic benchmark; abort on the first uncertainty. A confirmed counter increment must accompany every success. Include all blocked, timed-out and incomplete attempts rather than comparing only successful fast samples.

Selection rule: prioritize verified compatibility, ownership/safety and confirmed effect reliability. If both candidates satisfy those requirements, compare confirmed-effect median/p95 and operational dependencies; honor the user's minitouch preference when the evidence supports it. Today's evidence supports **retaining an available existing path provisionally while investigating a privileged minitouch/STF path**, not claiming minitouch is faster or permanently impossible. Phone-local accessibility dispatch remains a distinct candidate to evaluate, not a performance result inferred from this investigation.
