# WiFi Card Master Pro

Android application for managing router profiles and running card checks against supported captive portals. The Android client is built with Kotlin, Android Views, Jetpack Compose, Room, and Kotlin coroutines.

## Project layout

- `app/src/main/java/` — application source code.
- `app/src/main/res/` — Android layouts, drawables, translations, and other resources.
- `app/schemas/` — Room database schema snapshots used by the database build configuration.
- `PHASES/` — retained project phase documents and technical notes.
- `public/`, `server.cjs`, `server.js`, `index.html`, and the Node package files — optional browser preview/development server assets.

## Build the Android app

Requirements: JDK 17 and an Android SDK compatible with the project's configured compile SDK.

```bash
./gradlew assembleDebug
```

The debug APK is generated under `app/build/outputs/apk/debug/`. The Gradle build also copies it to `WIFI-CARD.apk` in the same directory when the copy task runs.

On Windows, use `gradlew.bat assembleDebug`.

## Run the optional web preview

Install Node.js, then run:

```bash
npm install
npm run dev
```

The development server is configured to listen on port `3000`. The browser preview is separate from the Android APK.

## Notes

- Do not commit `local.properties`, `.env` files, signing keystores, or other local secrets.
- `PHASES/` is documentation; its contents are not application runtime resources.
