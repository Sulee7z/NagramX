# Nagram X

> [!IMPORTANT]
> This project is archived and no longer maintained. No further updates, bug fixes, or support will be provided. The source code and existing releases remain available for reference and for anyone who wishes to continue development in a fork.

## Archived Downloads

Previously published versions remain available through:

* [Telegram Channel](https://t.me/NagramX)
* [GitHub Releases](https://github.com/risin42/NagramX/releases)

## Verify APK

Official APKs use the following Android signing certificate:

* Package name: `nu.gpu.nagram` / `nu.gpu.nagramx` (base version)
* SHA-256: `0D:51:91:56:E8:0C:91:8C:28:C4:80:BF:D1:3F:31:6A:3B:3B:F7:22:DB:53:2F:AB:74:66:0E:C8:E5:C5:06:A1`

## Compilation Guide

1. Clone the repository with its submodules:

    ```bash
    git clone --recursive --shallow-submodules https://github.com/risin42/NagramX.git NagramX
    ```

    If you already cloned the repository without submodules, run:

    ```bash
    git submodule update --init --recursive --depth=1
    ```

2. Obtain API credentials (`TELEGRAM_APP_ID` and `TELEGRAM_APP_HASH`) from [Telegram Developer Portal](https://my.telegram.org/auth). Create `local.properties` in the project root with:

   ```properties
   TELEGRAM_APP_ID=<your_telegram_app_id>
   TELEGRAM_APP_HASH=<your_telegram_app_hash>
   ```

3. For APK signing: Replace `release.keystore` with your keystore and add signing configuration to `local.properties`:

   ```properties
   KEYSTORE_PASS=<your_keystore_password>
   ALIAS_NAME=<your_alias_name>
   ALIAS_PASS=<your_alias_password>
   ```

4. For FCM support: Replace `TMessagesProj/google-services.json` with your own configuration file.

5. Replace project-specific metadata:

    - Set your Google Maps API key in the `com.google.android.maps.v2.API_KEY` meta-data entry in `TMessagesProj/src/main/AndroidManifest.xml`.
    - Set `BaseRemoteHelper.CHANNEL_METADATA_ID` in `TMessagesProj/src/main/java/tw/nekomimi/nekogram/helpers/remote/BaseRemoteHelper.java` to your metadata channel's numeric ID, without the `-100` prefix.

6. Open the project in Android Studio to start building.

## FCM Push Notifications (Important)

FCM push **cannot work with the default `TELEGRAM_APP_ID = 6` (the official Telegram app id)**, even with a
correct `google-services.json`. Telegram's servers deliver FCM pushes using the credentials uploaded for
**your own** app in [my.telegram.org](https://my.telegram.org), and the official app id has no such credentials
for your Firebase project — the push will silently never arrive.

To get working FCM push:

1. Create your own app at [my.telegram.org → API development tools](https://my.telegram.org/apps) (choose **Android** as platform).
2. Put your own `api_id` / `api_hash` into `local.properties`:
   ```properties
   TELEGRAM_APP_ID=<your_api_id>
   TELEGRAM_APP_HASH=<your_api_hash>
   ```
3. Create a Firebase project at [Firebase Console](https://console.firebase.google.com) and add an Android app with
   the exact package name from `gradle.properties` (`APP_PACKAGE`). Download its `google-services.json` and replace
   `TMessagesProj/google-services.json` (the one in the repo is only a placeholder).
4. In your Firebase project open **Project settings → Service accounts → Generate new private key** (JSON file).
5. On your app's page in my.telegram.org, click **Edit** → **FCM credentials** and upload that service-account JSON.
6. Rebuild. After login, push should arrive via FCM.

If your device has no Google Play Services (e.g. most Chinese ROMs), or FCM fails, the app automatically falls back
to the **local keep-alive push service** (a background MTProto connection). That service now runs as a foreground
service with a low-importance notification, restarts itself after being killed (15-min guard alarm), and is restored
after reboot / app update. You can enable/disable it in Settings → Notifications → keep-alive service; disable it if
you rely on FCM to save battery.

**How the tombstone resurrection works:** the local push stack now runs in **two processes**:

- The **main process** hosts `NotificationsService` and the actual MTProto push connection
  (it must, because the connection and the SQLite database live in one process - running two
  instances would corrupt the DB and create a duplicate Telegram session).
- A lightweight **`:push` process** (`PushProcessService`) runs as a foreground service and
  is not affected by the main process being killed. It binds to the main process; the moment
  the main process dies (bind disconnects), it wakes it back up **within seconds**. A 15-minute
  `setExactAndAllowWhileIdle` alarm remains as the final fallback in case both processes are
  killed, and `onTaskRemoved` re-arms it immediately when the app is swiped away.

On aggressive ROMs (MIUI/HyperOS/ColorOS etc.) additionally allow the app in *Auto-start* and
make it not kill it in *Battery → Background apps* — the notification channels `NagramX Push
Service` can be muted but must stay enabled for the FGS to protect the services.

### Cirno (tombstone freezer) compatibility

NagramX works with [Cirno](https://github.com/Adkimsm/Cirno) exactly like WeChat's tombstone:
frozen in background (zero CPU) and **network-unfrozen on incoming messages** to show the
notification.

Setup in Cirno:

1. Open Cirno → app list → **NagramX**.
2. Enable **NetReceive unfreeze** (网络解冻) — this is the "keep connection + unfreeze on
   network message" switch (it maps to the `ALLOW_NETWORK_MESSAGE` capability). While enabled,
   Cirno keeps the app's MTProto push socket alive during freezing, and an incoming message
   temporarily unfreezes the app so the notification is shown, then freezes it again.
   Note: this requires kernel support (ReKernel, or Hans/Millet on Xiaomi/Huawei).

Push is **purely event-driven**: while frozen, the process is SIGSTOP-ed (zero CPU, no
heartbeat, no alarms — nothing runs). Only an incoming network message unfreezes the app.
The one-shot resurrection alarm is armed **only** when a process is really killed (swiped
away from recents / OS kill), never during normal freezing.

Caveat: while frozen, no keep-alive pings are sent, so on mobile networks a NAT/operator
idle timeout can drop the TCP connection after a long freeze. In that case open the app
once to reconnect (Wi-Fi NAT timeouts are typically hours, so this mostly affects
cellular data with very long freezes).

## GitHub Actions Build

The workflow can be used from a fork of this repository.

1. Replace `TMessagesProj/release.keystore` with your keystore file.

2. Configure `local.properties` with the following:

   ```properties
   KEYSTORE_PASS=<your_keystore_password>
   ALIAS_NAME=<your_alias_name>
   ALIAS_PASS=<your_alias_password>
   TELEGRAM_APP_ID=<your_telegram_app_id>
   TELEGRAM_APP_HASH=<your_telegram_app_hash>
   ```

   Base64 encode the contents of this file.

3. Configure GitHub Action secrets:
   - `LOCAL_PROPERTIES`: Base64-encoded content from step 2
   - `HELPER_BOT_TOKEN`: Telegram bot token from [@Botfather](https://t.me/Botfather) (e.g., `1111:abcd`)
   - `HELPER_BOT_TARGET`: Primary Telegram chat ID (e.g., `777000`)
   - `HELPER_BOT_CANARY_TARGET`: Chat ID for test builds and metadata (can match `HELPER_BOT_TARGET`)

4. Trigger the Release Build workflow.

## Acknowledgments

- [AyuGram](https://github.com/AyuGram/AyuGram4A)
- [Cherrygram](https://github.com/arsLan4k1390/Cherrygram)
- [Dr4iv3rNope](https://github.com/Dr4iv3rNope/NotSoAndroidAyuGram)
- [exteraGram](https://github.com/exteraSquad/exteraGram)
- [Nagram](https://github.com/NextAlone/Nagram)
- [Nekogram](https://github.com/Nekogram/Nekogram)
- [OctoGram](https://github.com/OctoGramApp/OctoGram)
