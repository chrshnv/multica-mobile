# Multica for Android

Kotlin / Jetpack Compose client for a self-hosted Multica server. Android 8.0
(API 26) or newer. English and Russian follow the device language. The app uses
Material 3 Expressive with system, light and dark appearance.

## Build

Pinned toolchain used for the debug APK:

| Tool | Version |
| --- | --- |
| JDK | Eclipse Temurin 17.0.20.1+1, Linux x64 |
| Gradle Wrapper | 8.13 (distribution SHA-256 in wrapper properties) |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose compiler plugin | 2.2.20 |
| Compose UI / foundation | 1.9.3 |
| Material 3 | 1.5.0-alpha07 |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| Android build tools | 35.0.0 |
| Android command line tools | 23.0 (16111833) |
| UnifiedPush connector | 3.3.5 |
| Spotless / ktlint | 7.2.1 / 1.7.1 |
| PMD / Kotlin module | 7.28.0 / 7.28.0 |

Install the pinned JDK from [Eclipse Adoptium's release](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.20.1%2B1).
Download [Android command line tools](https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip)
and extract them to `$ANDROID_HOME/cmdline-tools/23.0`. Keep JDK, SDK and caches
outside the repository. Accept the Android SDK licenses, then run:

```sh
export JAVA_HOME=/path/to/jdk-17.0.20.1+1
export ANDROID_HOME=/path/to/android-sdk
export PATH="$JAVA_HOME/bin:$PATH"
"$ANDROID_HOME/cmdline-tools/23.0/bin/sdkmanager" --licenses
"$ANDROID_HOME/cmdline-tools/23.0/bin/sdkmanager" 'platforms;android-36' 'build-tools;35.0.0'
./gradlew :app:assembleDebug
./gradlew spotlessCheck pmdKotlin
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. It is signed with the local
Android debug key, for sideloading, not store distribution. `adb install -r`
installs it on a connected device. Release signing is intentionally unconfigured.

`./gradlew spotlessApply` formats product Kotlin and Gradle Kotlin scripts with
the same ktlint version. `pmdKotlin` checks nonempty `app/src/**/*.kt` with the
actual PMD Kotlin language module, fails on violations and parser errors, and
writes `build/reports/pmd/kotlin.xml`. The ruleset explicitly selects function
name length, parameter shadowing and equals/hashCode consistency. Generated code
and Gradle scripts are not submitted to the PMD Kotlin parser.

Material 3 1.4.0 has internal Expressive theme APIs; this app deliberately pins
an alpha that exposes `MaterialExpressiveTheme`, expressive colors and motion.
See the [Android release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.5.0-alpha07)
and [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes).

## Connect

The default server is `https://multica-api.eblans.tech`. Enter your own `mul_`
personal access token created in Multica. Do not use an agent/task/runtime token.
The app validates `/api/me` and loads your workspace memberships. A saved token
is revalidated on startup. Sign out before changing HTTPS server. Logout does
not revoke your PAT on other devices.

The PAT and private journal are encrypted with AES-GCM using an Android Keystore
key. UnifiedPush's default key manager protects its Web Push private keys with
Keystore-backed encryption on all supported Android versions. Backup and device
transfer are excluded, screenshots/recents previews are protected, and no HTTP
logging or analytics SDK is installed. HTTPS redirects and transport-level
write retries are disabled. Error messages never echo server bodies or tokens.

Task creation includes project, priority and member/agent/squad assignment.
Agent and squad assignments can start work according to server permissions and
workflow. Replies support exact comment parents. An uncertain write leaves the
text intact; refresh to check whether it succeeded before manually resubmitting.
No offline write queue or automatic replay of forms exists. Navigation, drafts
and scroll positions survive Activity recreation; drafts are memory-only and
are cleared on account/workspace changes and process death.

Task paging uses server offsets with ID deduplication. Comment paging uses
`recent`, paired `before`/`before_id` cursor headers, and separate per-thread
`tail` cursors. Parent IDs and deleted comment placeholders are retained. Current
inbox is the complete server array, filtered and displayed in local batches.
Archive paging follows the server's **grouped** cursor contract: newest item per
issue group and comment anchors, not every historical raw event. Unknown inbox
event types and details remain visible. Receiving push never marks an event read;
opening it deliberately does.

## Push

Install the [ntfy Android distributor](https://ntfy.sh/docs/install/), configure
it for `https://ntfy.sh`, then use Settings → Enable / reconnect push. The app
selects the `io.heckel.ntfy` UnifiedPush distributor, not an embedded FCM service.
Android 13+ notification permission and the inbox channel must be enabled.

The server must deploy and enable the separate Multica account-level
`unifiedpush-webpush-v1` backend (migrations through 590, server VAPID and at-rest
keys). `/api/push/config` and `/api/push/subscriptions/{installation_id}` are
required. A v0.6.3 server without that extension can still serve tasks and inbox;
push API errors are surfaced. The app does not deploy or configure the server.

The connector handles RFC8291 decryption and transport acknowledgement. Only
ntfy.sh HTTPS endpoints and encrypted payloads matching the active registration
and generation are accepted. An account session has a fresh installation UUID;
endpoint changes rotate generation. WorkManager synchronizes registrations and
retries narrow revoke credentials after offline logout without retaining the
old PAT. Account/workspace membership and the recipient-owned read-only
`GET /api/inbox/{id}` endpoint are checked before notifications and after a cold
tap. Deduplication persists by server, user, generation and inbox ID. A stable
notification tag replaces a notification if a crash interrupts journal commit.
Lock-screen content is generic. Signing out immediately invalidates local
registration, unregisters the connector and clears notifications; delayed old
messages cannot cross into the next account.

Notifications are best effort under Android and distributor background limits.
Force stopping either app prevents normal delivery until restart. Refresh inbox
to recover missed events. No perpetual Activity socket or polling replaces push.
See [UnifiedPush Android protocol](https://unifiedpush.org/developers/spec/android/)
and [connector APIs](https://unifiedpush.org/kdoc/connector/).

## Verification limits

Unit, instrumentation, end-to-end and regression tests are deferred by the
requester. A successful APK build and static analysis are not live verification.
No user PAT, emulator, production writes, ntfy publications, deployment or
real-agent smoke run is required or claimed by this implementation. Device
accessibility, rotation, cold-start delivery and actual server deployment still
need device/live verification when testing resumes.
