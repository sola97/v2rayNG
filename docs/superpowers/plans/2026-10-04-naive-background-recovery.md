# Naive Background Recovery Implementation Plan

> **For agentic workers:** Implement the bounded tasks below with independent file ownership; the primary agent reviews integration, runs final checks, commits, and pushes. The optional superpowers execution skills are not installed in this session.

**Goal:** Recover native Naive connections after Android background network interruption without manually restarting the VPN, and bound CONNECT waits for TCP and UoT.

**Architecture:** Keep one Cronet Engine per Xray outbound. Add a connection establishment boundary that waits for CONNECT within a fixed timeout, closes cancelled streams, and refreshes stale pooled connections at most once per recovery interval before a single pre-payload retry. Android reports long screen suspension, exit from device idle, and meaningful network restoration through the existing AAR notifyNetworkChanged API.

**Tech Stack:** Go, cronet-go at d22f2ea3630e, Kotlin/JUnit, Android network/power callbacks, Jenkins Docker Android build.

**Spec:** `docs/native-naiveproxy-implementation.md`, plus the user's reported HTTPS background outage and authorization to fix it on 2026-10-04.

## Global Constraints

- Preserve plugin-free native Naive, default enabled UoT v2, phone v1/v2/off configuration, and HEV native packaging.
- Do not restart the whole VPN as recovery and do not periodically replay application payload.
- Bound and close failed/cancelled CONNECT attempts; caller cancellation and authentication/certificate errors must not cause a pool-wide retry.
- Long screen suspension threshold is 30 seconds; ordinary quick screen toggles must not reset healthy connections.
- Use 5-second CONNECT attempt timeout and a maximum of one pre-payload retry. Coalesce timeout-driven pool refreshes to at most once per 5 seconds per outbound. The user selected this shorter budget during implementation.
- Keep network callbacks safe across repeated events and service restart; reset transient tracking state when stopping.
- Commit and push completed slices with `fix(naive)` or `docs(naive)` scope; Core precedes AndroidLib pin, which precedes final App build.
- Android recovery remains unverified on a physical device until an authorized ADB device is present.

## Task 1: Android Recovery Notifications

Files: `CoreServiceManager.kt`, `CoreVpnService.kt`, `DefaultNetworkMonitor.kt`, a small pure Kotlin recovery-state helper and its JUnit tests.

- [x] Track screen suspension using monotonic elapsed time. On screen-off record time; on screen-on consume it and notify only when suspension lasted at least 30 seconds. Handle duplicate screen/unlock events without resetting twice.
- [x] Register `PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED`; notify only on idle-to-active transition. Read the current idle state when the Core starts, and clear recovery state when it stops.
- [x] Handle blocked-to-unblocked and changed link properties on the actual tracked network, avoiding initial-state resets and unrelated network events. Continue available/lost handling and VPN underlying-network selection.
- [x] Test long/short screen-off, duplicate events, idle transition, initial-state handling, and lifecycle cleanup. Jenkins #25 compiled the app and passed all 42 Android tests, including 9 recovery-state tests.

Representative state contract:

```kotlin
fun onScreenOff(elapsedRealtime: Long)
fun onScreenOn(elapsedRealtime: Long): Boolean
fun onDeviceIdleChanged(idle: Boolean): Boolean
fun reset(deviceIdle: Boolean)
```

## Task 2: Bounded Core CONNECT and Cancellation

Files: `proxy/naive/outbound.go`, `proxy/naive/connection.go`, `proxy/naive/connection_test.go`, existing outbound tests.

- [x] Introduce a shared dialer used by TCP and the UoT client, with `DialContext(ctx, network, destination)` creating `DialEarly`, waiting for `HandshakeContext` under a 5-second child context, and returning the established connection only on success.
- [x] Register `context.AfterFunc` on the original request context to close the stream. Stop this callback when the returned connection is explicitly closed; do not retain the handshake deadline as the established connection lifetime.
- [x] On a CONNECT timeout caused by the attempt budget, close the attempt, coalesce pooled connection clearing under the outbound mutex, and retry once before any caller payload is forwarded. Preserve caller cancellation and errors such as authentication/CA without retrying or resetting the pool.
- [x] Route UoT setup through this dialer, including request writes, so it has the same establishment/cancellation rules.
- [x] Fake NaiveConn tests: stalled handshake times out, stale first attempt recovers on second, cancelled caller closes the connection and prevents retry, successful connection survives the establishment budget, explicit close unregisters cancellation, repeated/concurrent failures do not repeatedly clear the pool, rejection errors propagate without retry.
- [x] Run full `go test -tags with_purego ./proxy/naive`, config selectors, and singbridge regression packages. Review and commit/push Core. Repeat Naive/config tests against the final committed Core.

## Task 3: Dependency Pin and Live Interoperability

Files: AndroidLib `go.mod`, `go.sum`; v2rayNG `ci/e2e` only where recovery evidence requires it; CI runner defaults when the Core SHA changes.

- [x] Resolve the pushed Core SHA through Go module tooling, review only expected changes, and commit/push AndroidLib.
- [x] Build current Xray and run the checked-in sing-box TCP/UoT v2 echo harness. Extend evidence with server interruption/restart and a real TCP-accepting/TLS-silent fixture using the same Xray process; require subsequent TCP/UDP recovery. Two CONNECT attempts completed in 10.048 seconds.
- [x] No eager-CONNECT interoperability regression observed in the tested HTTPS/TCP/default-UoT-v2 path; use the validated Core pin.

## Task 4: Full Android Build and Delivery

- [x] Commit/push reviewed App changes; pass explicit immutable refs rather than changing reusable runner defaults.
- [x] Trigger `v2rayng-naive-android-ci` with the three pushed immutable SHAs. Inspect the actual failed stage before any infrastructure retry.
- [x] Require SUCCESS, commit manifest consistency, JUnit results including recovery tests, AAR, five F-Droid APKs, and per-ABI Go/HEV native-library gate. Jenkins #25 passed in 356146 ms.
- [x] Download all five APKs and AAR; independently verify SHA-256, ZIP integrity, exact ABI sets, nonempty required native libraries and signatures. Record build URL and local paths. The new certificate differs from #21: no direct in-place upgrade; preserve the installed app and back up configurations before any migration.
- [x] Update implementation records with exact evidence and the physical-device/signing limitations; commit/push documentation as the final documentation slice.

## Review

The plan addresses Android lifecycle notification gaps and unbounded CONNECT waits separately. Retries occur before caller payload forwarding, with a fixed attempt limit. Existing AAR network notification is sufficient, so no new gomobile API is needed. The reported phone outage cannot be declared reproduced by desktop tests.
