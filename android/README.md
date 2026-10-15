# Desk Alert — Android companion app

Runs on your phone and feeds the Desk Alert dashboard:

- **Forwards WhatsApp / WhatsApp Business** messages and incoming calls to the backend (`/api/noti`).
  Group-summary and ongoing notifications are ignored to avoid duplicates; incoming calls are sent as `CALL` (flashing red on the dashboard).
- **Replies from the dashboard:** when a message has Android's inline reply action, the dashboard shows a reply box. The app polls `/api/replies` only while a replyable notification is held, and sends the reply through that notification.
- **Test buttons** for a message and a call alert.
- A status box shows listener state, the last notification seen from any app, and what happened to the last WhatsApp one (for troubleshooting).

Package `com.deskalert.trigger` · min SDK 24 · target SDK 34 · plain Java, no third-party dependencies.

## Setup on the phone
1. Install the APK, open it, enter your `APP_PASSWORD` (stored on the device only).
2. Tap **Open notification access settings** and enable Desk Alert. On Xiaomi/HyperOS also allow WhatsApp under "See all apps", turn on Autostart, and set battery to "No restrictions".
3. Disable any old MacroDroid WhatsApp macro (it would cause duplicates).

The backend URL is the `BASE` constant in `app/src/main/java/com/deskalert/trigger/Api.java`.

## Build
**Android Studio:** open this `android/` folder, let it sync, then Build → Build APK(s). For a signed release: Build → Generate Signed App Bundle / APK. (No Gradle wrapper is committed; Android Studio will offer to create one.)

**Command line (no Gradle):** `KS_PASS=yourpassword ./build-apk.sh` (see the script header for the apt packages). It creates a signing key at `~/.deskalert.jks` the first time. Keep that key: updates must be signed with the same one.

Never commit keystores or APKs (see `.gitignore`).
