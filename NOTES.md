# ClipCascade Plus: Phase 0 recon notes

Upstream base: `Sathvik-Rao/ClipCascade` at `fd13b2b` (v3.4.1, Android 3.4.1, server 3.4.0). Written 2026-11-04.

**How far to trust each claim**

| Tag | Meaning |
|---|---|
| **[RUN]** | Observed by running it on a throwaway copy of the official server image |
| **[CODE]** | Read in source (upstream, AOSP `android16-release`, or the library) |
| **[DOCS]** | Stated in developer.android.com |
| **[UNVERIFIED]** | Reasoned or remembered, not checked. Needs a test |

Nothing has been tested on a phone. No device is reachable from this machine, and Samsung changes to Android cannot be read from AOSP.

---

## 0. Summary

1. **The main cause of "I have to open the app" is confirmed.** The service cannot log in. When its session dies, it retries forever and can never succeed (§5, H1).
2. **The second cause is also in the code.** If the process dies, nothing brings it back. The 15-minute "watchdog" only posts a "tap to restart" notification (§5, H2).
3. **Part of the plan will not work as written.** `READ_LOGS` cannot provide the trigger after a reboot, because Android refuses log access to apps that are not on top (§6.2). Phase 3's fallback and one Phase 6 test depend on it, so it needs a different trigger.
4. **Recommendation:** a native Kotlin service and clipboard layer, keeping the React Native UI, with P2P left on the existing JS code (§8). This needs your confirmation.

---

## 1. Tech stack

| Item | Value |
|---|---|
| App | React Native 0.80.2 + React 19, Hermes, with hand-written Kotlin modules |
| Service logic | **JavaScript**: `StartForegroundService.js` (~1,900 lines), run by Notifee's foreground service |
| Other key libraries | `@notifee/react-native` 9.1.x, `@stomp/stompjs` 7.1.x, `react-native-webrtc`, `react-native-aes-gcm-crypto`, Shizuku API 13.1.5, WorkManager 2.10 |
| SDK | min 26, target 35, compile 35; NDK 27.1; Gradle 8.14.1 |
| CI build | `.github/workflows/release.yml`: Node 20, Temurin 17, `npm ci`, `./gradlew assembleRelease`. Keystore comes from secrets, `gradle.properties` from `gradle.properties.ci` |
| Identity | `applicationId` and `namespace` are `com.clipcascade`. Native code mostly uses `BuildConfig.APPLICATION_ID`, so changing the ID is low risk; the ADB hints in `App.js` are hardcoded |

**Build host:** this Pi has no JDK, Android SDK or `gh`. Google's Linux build tools are x86-64 **[UNVERIFIED]**, so the plan is to build APKs on GitHub Actions, not here.

---

## 2. Protocol

### Login **[CODE + RUN]**
1. `GET /login`, then parse the hidden `_csrf` input. This sets an anonymous `JSESSIONID`.
2. `POST /login` as a form with `username`, `password`, `_csrf`.
   - `password` is the **lowercase hex SHA3-512 of the raw password**, not the raw password. **[RUN]** this form logged in and returned a 302 to `/`.
3. `GET /validate-session` returns the body `OK` when logged in. Use this as the real check, because a failed login can also end in HTTP 200 after redirects. A body containing "bad credentials" means wrong credentials.
4. `GET /csrf-token` is only needed for logout.

### Discovery (needs a session)
| Endpoint | Response |
|---|---|
| `GET /server-mode` | `{"mode":"P2S"}` or `{"mode":"P2P"}` |
| `GET /max-size` (P2S) | `{"maxsize":1048576}` **[RUN]** |
| `GET /stun-url` (P2P) | `{"url":"..."}` |

An unauthenticated request to any of these gets a **302 to `/login`, not a 401/403 [RUN]**. The new client has to treat that redirect as "session dead".

### P2S WebSocket **[CODE]**
- Endpoint: `ws(s)://host/clipsocket`, with the `JSESSIONID` cookie on the handshake. Allowed origins default to `*`.
- Without a valid session, the handshake also gets a 302 **[RUN]**.
- STOMP `CONNECT`: `accept-version`, `host`, `heart-beat:0,20000`.
- Heartbeats: the server sends every 20 s and expects none from clients (`ServerConstants.java:29-30`). Keep the header exactly as it is.
- Subscribe to `/user/queue/cliptext`. Send to `/app/cliptext`.
- Frame encoding differs: mobile uses binary frames (`forceBinaryWSFrames: true`), desktop sends text. The server takes both.
- Send body: `{"payload":"<string>","type":"text|image|files"}`. The server also carries an optional `metadata` and defaults a null `type` to `text`.
- **The server echoes every message to all of the user's sessions, including the sender**, so loop prevention is required.

| Type | Payload before encryption |
|---|---|
| `text` | The text |
| `image` | Base64 of the file bytes |
| `files` | JSON `{"filename":"<base64>", ...}` |

### P2P **[CODE]**
- Endpoint `/p2psignaling`: plain JSON over WebSocket, not STOMP.
- Server sends `ASSIGNED_ID {peerId}` and `PEER_LIST {peers}`. Peers relay `OFFER`, `ANSWER`, `ICE_CANDIDATE` using `fromPeerId` / `toPeerId`.
- Clips travel over WebRTC data channels in 15,360-byte fragments, with `metadata {id, isFragmented, index, totalFragments, combinedRawPayloadSizeInBytes}`.
- **Your server has `CC_P2P_ENABLED=false`**, so you only use P2S.

---

## 3. Encryption **[CODE, cross-checked against desktop]**

| Part | Value (same on mobile and desktop unless noted) |
|---|---|
| KDF | PBKDF2-HMAC-SHA256, 32-byte output |
| Password input | UTF-8 of the **raw** password |
| Salt | UTF-8 of `username + rawPassword + saltSetting` |
| Rounds | `hash_rounds`, default 664937 |
| Cipher | AES-256-GCM, 16-byte tag, no associated data |
| Wire format | `payload` is a JSON string `{"nonce":b64,"ciphertext":b64,"tag":b64}`, standard Base64 |
| Plaintext | UTF-8 of the type-specific payload above |

Sources: `cipher_manager.py:21-41`, `App.js:650-664`, `StartForegroundService.js:201-233`.

**Nonce length differs:** PyCryptodome's GCM default is 16 bytes, Android's is 12 **[UNVERIFIED]**. Kotlin must accept any length when decrypting. Phase 1 fixtures must cover both directions.

**Not tested yet:** a full send/receive and real encrypt/decrypt round trip. That is Phase 1's job.

---

## 4. Current background architecture **[CODE]**

- **Start:** the UI or `HeadlessTask.js` calls `StartForegroundService()`. It claims a run ID in `ServiceRunRegistry`, then shows a Notifee notification, and the JS runner starts.
- **Foreground type:** `remoteMessaging`, on Notifee's own service.
- **Loop:** a 1 s poll (`pollFlagsLoop`) checks flags in AsyncStorage. `wsIsRunning` is the "should run" flag. A 1 s timer answers the worker's `echo` ping with `pong`.
- **Disconnect (P2S):** stompjs retries every 10 s; `beforeConnect` re-runs discovery.
- **Disconnect (P2P):** a manual 10 s timer.
- **Storage:** everything is in AsyncStorage, unencrypted, including `hashed_password` (the AES key).
- **Raw password:** with encryption on, the app does **not** save it (`App.js:1110-1113`). The saved form is the login hash, and only with encryption off.
- **Copy while disconnected:** `sendClipBoardP2S` returns `false` and the clip is not retried from the clipboard path, so it is lost.

---

## 5. Why it disconnects and stops

### H1: A lost session can never recover in the background. **CONFIRMED [RUN + CODE]**
- The server keeps sessions in memory by default (`CC_SESSION_PERSISTENCE_ENABLED:false`, `application.properties`). Your `docker-compose.yml` does not set it.
- **[RUN]** After `docker restart`, the same cookie got a 302 to `/login` on `/validate-session`, `/server-mode` and the `/clipsocket` handshake.
- The redirect is followed to an HTML login page with status 200, so `response.ok` passes and `response.json()` throws (`ServerDiscovery.js`).
- `refreshSettings` catches that and loops every 10 s **forever** (`StartForegroundService.js:159-173`).
- Login code exists only in the UI (`App.js:703`). The service has no way out until you open the app.
- Any server restart or image update therefore strands every phone.

### H2: Process death is permanent. **CONFIRMED [CODE]**
- Notifee's service returns `START_NOT_STICKY`, and calls `stopSelf()` on a null intent (`ForegroundService.java:79-83,144`, read from Notifee's `main` branch, not from the 9.1.8 AAR).
- There is no `onTaskRemoved` handling.
- `ScheduleService.kt` only runs `showNotificationIfNotPresent`: "Tap to restart". It never restarts anything.

### H3: Any uncaught transport error ends the service. **CONFIRMED [CODE]**
- `runTransport`'s catch returns `false`. The `finally` block then stops the foreground service and sets `wsIsRunning=false` (`StartForegroundService.js:1835-1866`).
- This matches your "service shows as stopped" symptom.

### H4: Nothing starts it after a reboot by default. **CONFIRMED [CODE]**
- `relaunch_on_boot` defaults to `false` (`App.js:419`).
- The UI tells users to leave it off if they use `READ_LOGS` (`App.js:1566`). §6.2 explains why.
- There is no `MY_PACKAGE_REPLACED` receiver either.

### H5: Slow, network-blind reconnects. **CONFIRMED [CODE]; effect size unknown**
- There is no `NetworkCallback` anywhere, so a Wi-Fi to mobile switch waits for a timeout.
- Dead-connection detection takes more than 40 s (`ttl * 2` in stompjs), and `discardWebsocketOnCommFailure` is off, so it attempts a graceful close on a dead socket.
- **Counters from a real self-hosted server** over about 3.3 days: 171 WebSocket sessions, **140 transport errors**, 165 STOMP CONNECTs, only 2 clean DISCONNECTs, and 0 connected when sampled.
- That is roughly 2 reconnects an hour across all clients. I can't attribute them per device, or say whether a reverse-proxy tunnel causes them. Reconnects are routine on your setup, so this path has to be solid.

### H6: JS timers under Doze. **[UNVERIFIED]**
All retry and heartbeat logic runs on JS timers inside a React Native runtime. How these behave in Doze on One UI is untested.

### H7: Samsung kills. **[UNVERIFIED]**
Plausible and the plan already covers it. With H1 to H4 in place, a single kill or restart is enough to look like a permanent failure.

---

## 6. Clipboard capture

### 6.1 Android's rules **[CODE: `ClipboardService.java:1312-1390`, android16]**
Reading in the background is allowed for:
- apps holding `READ_CLIPBOARD_IN_BACKGROUND`, which **shell has** and normal apps cannot get (`signature|role`);
- the **default IME**, checked only by reading `Settings.Secure.DEFAULT_INPUT_METHOD`, so per AOSP it does not need to be bound or visible.

Denials log `Denying clipboard access to <pkg>...`, including when a change notification to a registered listener is refused. That is the line upstream's trick watches for.

### 6.2 `READ_LOGS` + overlay (upstream's mechanism)
- **How it works:** `ClipboardListenerModule.kt` runs `logcat -T <now> ClipboardService:E *:S`. On a denial line naming our package, it opens `ClipboardFloatingActivity`, whose overlay gains focus long enough to read the clip.
- **Problem [CODE: `LogcatManagerService.java:423-426,438`]:** the log-access prompt is only shown when the app is `PROCESS_STATE_TOP`. **Otherwise the request is declined automatically.** A foreground service is not TOP.
- **Result:** after a reboot or a background restart, `logcat` gets no system logs and there is no capture until you open the app and accept the prompt. Upstream's own help text describes exactly this workflow.
- **So this plan item fails as written:** "capture works through the fallback before Shizuku is reactivated" cannot rely on `READ_LOGS`.
- **Possible Android 16 bug:** the KDE fork uses the filter `E ClipboardService` above API 35, while upstream always uses `ClipboardService:E`. Check this on the A54 **[UNVERIFIED]**.

### 6.3 Shizuku references

| | KDE Connect fork (`Batestinha/...`) | ClipShare `clipshare_clipboard_listener` 1.3.6 |
|---|---|---|
| Licence | Repo is GPL; `ClipboardListener.kt` is GPL-2-only OR GPL-3-only OR KDE-accepted; the user-service file has no header | MIT (GPL-compatible) |
| Runs as | Shizuku user service (shell, uid 2000) | `app_process` dex under Shizuku, plus user services |
| Detects a copy | Runs `logcat` **as shell** and watches for the denial line naming our package | Direct `IClipboard.addPrimaryClipChangedListener` as shell, no logs |
| Reads the clip | Reflection on `IClipboard.getPrimaryClip`, package `com.android.shell`, with arguments built to fit each signature | Own `IClipboard` stub with `(pkg, tag, userId, deviceId)` |
| Content | **Text only** | Not fully traced for images |

**Proposed mechanism:**
- A Shizuku user service registers `addPrimaryClipChangedListener` as `com.android.shell`, which is event-driven and does not need logcat. The logcat watcher is a backup if registration fails on One UI.
- Choose `IClipboard` signatures by reflection, because they change between Android versions.
- **Images and files:** URI read access is expected to go to the caller (shell), not our app **[UNVERIFIED]**. The service should open the URI itself and stream it back through a `ParcelFileDescriptor`. Neither reference is confirmed to do this, so this is new work.
- I would write this fresh, using the references for mechanism, and credit them.

**Side effects to test on the phone:**
- **Clipboard toast:** shell lacks `SUPPRESS_CLIPBOARD_ACCESS_NOTIFICATION` **[CODE]**, so each new clip may show a "pasted from your clipboard" toast. A privacy setting may turn it off **[UNVERIFIED]**. The IME path is exempt.
- **One UI:** Samsung may modify `ClipboardService`.

### 6.4 IME switch
- **Feasible per AOSP:** `WRITE_SECURE_SETTINGS` has the `development` flag, and shell holds it **[CODE]**, so it can be granted once and should survive reboots **[UNVERIFIED]**.
- **Unknowns:**
  - whether One UI honours the setting change;
  - whether swapping dismisses or flickers the keyboard while you type;
  - whether Samsung requires more than the setting.
- **Restore safety:** persist the previous IME before switching, restore in `finally`, and restore on service start. If our IME is ever shown, it immediately switches back. Otherwise a crash could leave you without a keyboard.

### 6.5 What triggers the fallback without Shizuku?

| Option | Survives reboot without opening the app? | Reliability | Battery | Verdict |
|---|---|---|---|---|
| `READ_LOGS` logcat | **No** (§6.2) | High once approved | Low | Only while the app has been opened since boot |
| `WATCH_APPOPS` | - | - | - | **Not available**: `signature\|privileged` **[CODE]** |
| Poll `WRITE_CLIPBOARD` app-op times via `GET_APP_OPS_STATS` | Likely | Should catch all copies, if readable | Moderate; poll only with the screen on | **Try first.** `development` flag so it's grantable **[CODE]**, but needs a hidden API and is untested |
| Accessibility service | Yes | Heuristic: misses programmatic copies and non-standard UIs | Low to moderate | Second choice. Sideloaded apps hit restricted settings; enabling via `WRITE_SECURE_SETTINGS` may avoid that **[UNVERIFIED]** |
| Shizuku auto-start (`thedjchi/Shizuku` fork, Apache-2.0) | Yes, after Wi-Fi connects | Its README claims start-on-boot, a watchdog and TCP mode **[UNVERIFIED]** | Low | **Try alongside.** May shrink the gap with no app code |

**Recommendation:** a short test on the A54 before building Phase 3. Try the Shizuku fork plus app-op polling. If polling fails, use the accessibility service. Keep `READ_LOGS` as a further fallback for when the app has been opened.

---

## 7. Android version decisions

### Foreground service type: **keep `remoteMessaging`** (differs from the plan)
- The plan assumed `dataSync` was the risk. Upstream doesn't use it.
- `remoteMessaging`: "Transfer text messages from one device to another", no prerequisites, and not on the Android 15 list of types blocked from `BOOT_COMPLETED` **[DOCS]**. No runtime limit is documented.
- `specialUse` would also work, but offers no benefit I could find. Switching is a small change if testing suggests otherwise.

### Restarting from the background **[DOCS]**
- Apps generally **cannot** start a foreground service from the background. Turning off battery optimisation for the app is a documented exemption, and boot and package-replaced broadcasts are too.
- So the battery step in Phase 4 is **required** for the watchdog and network-triggered restarts, not just advice.
- Whether a system-restarted `START_STICKY` service may call `startForeground` without that exemption needs a test **[UNVERIFIED]**.

### Other
- **Android 15 overlay rule [DOCS]:** holding `SYSTEM_ALERT_WINDOW` only exempts foreground-service starts while an overlay is visible. Don't rely on it.
- **Android 16 [DOCS]:** job quotas apply to WorkManager even alongside a foreground service. A short watchdog job should be fine. Deep-sleeping apps may not run it at all **[UNVERIFIED]**, which is why an alarm backup is in the plan.
- **`LOCKED_BOOT_COMPLETED`: skip.** Credential storage is locked until first unlock, so there's nothing to connect with **[UNVERIFIED]**.
- **Target SDK:** currently 35, and the plan says "current". API 36 brings UI changes such as edge-to-edge, and affects RN screens. Suggest staying on 35 until the service works, then bumping in a separate commit.

---

## 8. Recommendation: native service, keep the UI

| Option | For | Against |
|---|---|---|
| Patch the JS service | Smallest diff, P2P stays as is | Keeps Notifee's non-sticky service, needs the JS runtime up to restart, and leaves timers and network awareness in JS |
| **Native Kotlin service + RN UI (recommended)** | Direct control of stickiness, restart, `NetworkCallback`, OkHttp pings. Protocol is small and fully documented above. Testable on the JVM | P2S client reimplemented, so Phase 1's compatibility tests matter |
| Full native rewrite | No RN at all | Unnecessary UI work |

**Shape:**
- Kotlin `SyncService`, `ClipCascadeClient`, `ClipboardSource` providers.
- RN keeps login, settings and status, through a small bridge.
- **P2S** does not load JS at all in the background.
- **P2P** stays on the existing JS service, unchanged apart from restart hardening. Porting WebRTC to native would add a heavy dependency for a mode you don't use.
- OkHttp already comes with React Native **[UNVERIFIED until the first build]**, so no heavy new dependency is expected.

---

## 9. Other changes to the plan

1. **Credentials:** auto re-login needs only the SHA3-512 login hash, and decryption needs only the derived key. Store those two in Keystore-backed storage, **never the raw password**. Existing `hashed_password` handling migrates to it. Changing the password means logging in again.
2. **"Never stop retrying" needs an exception for bad credentials.** The server has brute-force lockouts (`CC_MAX_ATTEMPTS_PER_IP` 30, `CC_LOCK_TIMEOUT_SECONDS` 60, scaling up). On "bad credentials", retry slowly, on the order of every 30 min, and show a notification. Network and server errors keep the 60 s cap.
3. **Session detection:** treat a redirect to `/login` on discovery or the WS handshake as "re-login", with redirects disabled for those calls. The plan's 401/403 trigger would never fire.
4. **Dead-connection detection under 30 s:** a read deadline of about 30 s on the server's 20 s heartbeat, plus OkHttp `pingInterval`. Needs testing through a reverse-proxy tunnel **[UNVERIFIED]**.
5. **Missed clips:** keep the latest unsent phone clip and send it on reconnect, unless a newer clip has arrived from the server.
6. **Phase 6 needs you:** I can run the server-side integration tests, but every phone test needs you or a connected device.

---

## 10. Decisions (answered 2026-11-05)

- **Architecture:** approved. Native Kotlin service plus the RN UI, with P2P left on the JS code.
- **Test phone:** Samsung Galaxy A54, One UI 8.5, Android 16. So the Android 16 logcat filter question is live and must be tested on the phone.
- **Sensitive clips:** the owner wants them to last 5 minutes on every device, "if possible". See section 11.
- **Server:** `CC_SESSION_PERSISTENCE_ENABLED=true` applied by the owner. Tested first on a throwaway container: a session and its STOMP connection survive a restart. Enabling it logs clients out once, because in-memory sessions aren't migrated.
  - Saved sessions sit in the H2 database. Unless `CC_SERVER_DB_PASSWORD` was set before first start, that database uses the public default password, so its files should be treated as credentials.

## 11. Sensitive clips: 5-minute lifetime

The owner asked for sensitive clips to last 5 minutes on every device. This is what's possible without changing the protocol or the desktop clients.

**Design:** the phone sends the clip as usual and starts a 5-minute timer. When it fires, if that clip is still the newest one seen from any device, the phone clears its own clipboard and sends an **ordinary empty text clip**. Unmodified clients treat it like any other clip and overwrite their clipboard with it. The timer stores a hash and a deadline, never the content, and survives restarts.

**To verify:** `ServerIntegrationTest` sends an empty clip through the real server to code from the desktop client.

**Limits:**
- Only clips copied **on the phone** can be recognised. Android marks them with `ClipDescription.EXTRA_IS_SENSITIVE`, if the source app sets it. A password copied on the PC has no such marker on the wire, and adding one would change the protocol.
- If the phone is offline when the timer fires, the clear goes out when it reconnects. If it's off, the other devices keep the clip until then.
- Windows clipboard history (Win+V), Mac clipboard managers and Samsung's clipboard panel keep their own copies, which this can't clear.
- **Unverified:** that `pyperclip.copy("")` on Windows and `write_to_pasteboard("")` on macOS actually empty the clipboard. To check in Phase 6.

## 12. Later decisions

- **`minSdk` rises from 24 to 26.** `java.util.Base64` needs it, and no intended device is older.
- **PBKDF2 and SHA3-512 are written against raw bytes in Kotlin**, instead of `PBEKeySpec` (whose password encoding varies by provider) and `MessageDigest` (SHA-3 isn't on older API levels). Both are checked against fixtures from the desktop code.
- **Integration tests and APK builds belong on GitHub Actions.** A Gradle run with a test server on the 3.7 GB Pi that hosts the real server left under 200 MB free and timed out, so it was stopped.

## Phase 0 test note

The server test used a temporary container, `clipcascade-recon-tmp`, bound to `127.0.0.1:18091` with default credentials, removed afterwards. The real server container and its data were only read.
