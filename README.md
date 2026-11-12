# Consent Monitor

Consent Monitor is a Kotlin/Android reference application for a device owner or administrator who has informed consent from every affected user. It records notification text and network connection metadata in an encrypted Room database, then uses WorkManager to upload a JSON file to a configured Telegram bot every four hours.

The app is deliberately transparent. Notification access and VPN consent are granted through Android Settings, the VPN uses Android's persistent foreground notification and VPN indicator, and the UI provides a stop control. It does not attempt to hide monitoring, bypass privacy indicators, decrypt TLS, install a certificate authority, or inspect payloads.

## Feasibility and Android restrictions

| Requirement | Feasibility and implementation |
|---|---|
| Notification monitoring | Supported by `NotificationListenerService` after the user explicitly enables notification access in system Settings. Android can block access for work-profile notifications, policy-managed profiles, or some low-RAM Android 10 devices. The service cannot grant itself access. |
| App, title, text, time | Available for most ordinary notifications through `Notification.extras`; apps may omit, redact, or use custom content. The package label is resolved locally. |
| Passive network monitoring | Android has no supported API that gives an ordinary app a passive list of every destination. `VpnService` provides packets only after user consent and requires the VPN app to forward captured traffic. |
| Implemented network scope | This project creates IPv4/IPv6 default VPN routes. HevSocks5Tunnel forwards TUN packets to an in-app SOCKS5 relay, which records TCP connection attempts, UDP flows, destination IP/port/protocol, conventional DNS queries, and timestamps. The app excludes itself from its VPN to avoid delivery loops. |
| HTTPS inspection | Not implemented and generally unavailable without deliberately installing/trusting a CA and performing interception. TLS hides URL paths, headers, bodies, and request methods. Certificate pinning, QUIC, Encrypted Client Hello, DoH, and DoT further restrict inspection. This app never attempts decryption. |
| Metadata limitations | HTTPS/QUIC encryption prevents URL-path, header, method, and body capture. HTTP/2 and HTTP/3 can carry many requests over one logged connection, so a connection event is not necessarily one web request. Domains are attached when conventional DNS makes the mapping observable; encrypted DNS, cached lookups, ECH, and direct-IP traffic can leave only an IP address. |
| Four-hour uploads | `PeriodicWorkRequest` is appropriate but inexact. Four hours is a minimum interval; Doze, battery policy, OEM restrictions, and network constraints can delay execution. Exact four-hour delivery is not guaranteed by Android. |
| Background operation | The notification listener is system-bound. The VPN is a foreground service with an ongoing notification and visible Android VPN indicator. Uploads and rotation are deferrable WorkManager jobs. |
| Encryption | Room is backed by SQLCipher. Its random database passphrase and Telegram token are wrapped with an Android Keystore AES-GCM key. Temporary JSON exists only in the app-private cache during upload and is deleted afterward. |
| Telegram privacy | Bot API transport uses HTTPS, but ordinary Telegram bot chats are not end-to-end encrypted. Telegram receives and stores the uploaded content. Use a dedicated private chat/bot and consider a private server instead for highly sensitive data. |

## Architecture

- **UI/MVVM:** `MainActivity` + `MainViewModel` display permission state, pending counts, and configuration.
- **Capture:** `NotificationCaptureService` and `TrafficMonitorVpnService` normalize bounded records. `LocalSocks5Server` forwards protected TCP/UDP sockets and records connection metadata; HevSocks5Tunnel translates TUN traffic to SOCKS5.
- **Storage:** Room entities/DAO/repository use an encrypted SQLCipher database.
- **Delivery:** `UploadWorker` exports a bounded pending batch and sends it through Telegram `sendDocument`. Rows are marked delivered only after Telegram accepts the message.
- **Retries:** transient I/O and Telegram HTTP 429/5xx failures return `Result.retry()` with exponential WorkManager backoff. Invalid credentials fail permanently until configuration is corrected. A crash after Telegram accepts a file but before the local acknowledgement can cause a duplicate.
- **Retention:** every worker run deletes notification and network-event rows older than the configured 1–365 day retention window, including undelivered rows so the database cannot grow without bound.

## Run on a Windows PC with Android Studio

The PC runs an Android emulator; this app does **not** monitor Windows notifications or Windows network traffic.

1. Install the current stable [Android Studio](https://developer.android.com/studio).
2. Open the `ConsentMonitor` folder as a project.
3. In **SDK Manager**, install Android SDK Platform 36 and the matching build tools. In **Settings > Build, Execution, Deployment > Build Tools > Gradle**, select/download a JDK 17 Gradle runtime. Do not select JDK 25 for this Gradle/Kotlin version.
4. In **Device Manager**, create a phone emulator with Android 10/API 29 or newer. An API 35 or 36 Google APIs image is a practical test target.
5. Let Gradle sync and download dependencies. The included wrapper uses Gradle 8.13.
6. Select the emulator and click **Run app**. From a terminal in this folder, the equivalent build commands are:

   ```powershell
   .\gradlew.bat testDebugUnitTest
   .\gradlew.bat assembleDebug
   ```

   The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

7. If installing manually, use:

   ```powershell
   adb install -r .\app\build\outputs\apk\debug\app-debug.apk
   ```

If Android Studio reports an SDK path error, create `local.properties` in the project root with your actual SDK path, for example:

```properties
sdk.dir=C\:\\Users\\YOUR_NAME\\AppData\\Local\\Android\\Sdk
```

## Initial device/emulator setup

1. Open Consent Monitor and read the disclosure.
2. Check the informed-consent box.
3. Tap **Open notification access settings**, select Consent Monitor, review Android's warning, and enable access.
4. Tap **Start traffic monitoring VPN**, approve Android's VPN dialog, and keep the foreground notification enabled. Android shows a VPN indicator while it is active.
5. Create a bot with the official `@BotFather`, keep the token secret, open the new bot chat, and send `/start`.
6. Enter the token and tap **Detect latest Telegram chat ID**. For a group/supergroup, add the bot and send a message in that group first; group IDs are commonly negative.
7. Set retention/network options and tap **Save encrypted settings**.
8. **Send pending logs now** queues an immediate WorkManager test. Periodic work becomes eligible approximately every four hours after configuration.

Bot tokens are credentials. Revoke/regenerate the token with BotFather if it is exposed. This project never hard-codes the token into source or build output.

The application intentionally has no hide/unhide feature. Android's launcher entry, notification-access listing, active VPN indicator, and required foreground-service notification provide user control and must not be concealed. Stop the traffic VPN if a persistent VPN notification is not acceptable; doing so also stops network metadata collection.

## Testing procedures

The delivered revision was verified with `testDebugUnitTest` and `assembleDebug` using Gradle 8.13, JDK 17.0.21, and Android SDK Platform 36. Both tasks completed successfully. Gradle may report that the debug `libsqlcipher.so` files cannot be symbol-stripped; packaging them unchanged is expected for this debug dependency and does not fail the build.

### Unit test

`DnsCodecTest` verifies DNS question parsing and A/AAAA response extraction:

```powershell
.\gradlew.bat testDebugUnitTest
```

### Notification capture

1. Enable notification access.
2. Generate a notification inside the emulator—for example, enable a browser download notification or use this API 30+ emulator command:

   ```powershell
   adb shell cmd notification post -S bigtext -t "Consent Monitor test" test-tag "Test notification body"
   ```

3. Return to the app. The pending notification count should increase. The app intentionally does not render captured sensitive text on screen.

### Browser/network capture

1. Start the traffic VPN and confirm Android's VPN icon/foreground notification.
2. Open a previously unused host in the emulator browser, or run:

   ```powershell
   adb shell am start -a android.intent.action.VIEW -d "https://example.com"
   ```

3. Return to the app. The network-event count should increase. A conventional lookup produces a DNS event; TCP/HTTPS produces a destination connection event, and QUIC produces a UDP-flow event. The page should still load.

### Telegram delivery and retry

1. With valid credentials, tap **Send pending logs now** and inspect the private Telegram chat for a JSON document.
2. Disable connectivity, create another record, and queue delivery. Re-enable connectivity and inspect WorkManager in Android Studio's App Inspection tools; the job should run when its constraint is met.
3. For an HTTP retry test, temporarily use a controlled mock endpoint in a debug-only branch. Do not deliberately flood Telegram. Authentication errors are treated as configuration failures rather than retried forever.

### Retention and encryption

- Set retention to one day and seed old rows in a debug test to verify deletion on the next worker run.
- Pulling `monitor.db` from a debuggable emulator should not yield readable SQLite content because SQLCipher encrypts the database. The Android Keystore key is non-exportable, but rooted/compromised devices and unlocked app sessions remain outside the protection boundary.

## Production considerations

- Review Google Play's monitoring, prominent-disclosure, User Data, VPN service, and foreground-service policies before distribution. A private/admin deployment still needs a clear privacy notice and applicable legal review.
- Replace the fixed Cloudflare resolver with an administrator-configurable resolver or your managed DNS endpoint if policy requires it.
- Keep the bundled HevSocks5Tunnel AAR current, audit native dependency updates, and review `THIRD_PARTY_NOTICES.md` before redistribution.
- Add Room migrations before changing the schema, instrumented tests on each supported API level, certificate/network failure tests, and an administrator-controlled export/deletion flow.
- WorkManager and foreground-service behavior varies by OEM. Test the exact managed-device models and document any battery-optimization settings instead of trying to bypass the OS.

## References

- [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [Android VPN developer guide](https://developer.android.com/develop/connectivity/vpn)
- [HevSocks5Tunnel](https://github.com/heiher/hev-socks5-tunnel)
- [WorkManager periodic work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android)
