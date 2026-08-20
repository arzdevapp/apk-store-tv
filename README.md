# App Updater

A native Android TV **store / updater** app — the user's own "Unlinked". It browses
the APK library served by the server (`apk-installer.tailc8cd81.ts.net`) and lets
anyone on the TV **download + install apps directly onto the device**, no ADB, no
phone, no asking the admin.

## How it works

1. On launch it fetches `/api/apks` from the backend (same library the web tool
   and field app use), with the `X-Api-Key` header.
2. Each app shows its state by comparing the backend's `versionCode` against the
   version installed on the box:
   - **INSTALL** — not installed yet
   - **UPDATE** — a newer build is in the library
   - **OK** — already up to date
3. Tap a row → the app downloads the APK to the TV's own storage, then installs it
   directly via the **PackageInstaller API** (on-device, no ADB). First use prompts
   once to allow "install unknown apps from this source".

Because it compares versions against the live library, users can **self-update**
their apps (and the installer itself) the moment a new build is pushed — no need to
ask the admin to re-install anything.

## Backend dependency

The backend must return per-APK metadata (`package`, `versionCode`, `versionName`,
`label`) in `/api/apks`. That was added to the server for this app (aapt-based,
cached). If a field is null the app degrades gracefully (INSTALL/OK only).

## Build

Signed release via GitHub Actions on `main` (fixed keystore in repo secrets).
Known-good workflow + secrets recipe live in the `android-tv-ops` skill
(`references/android-apk-ci-build.md`). Ship the release APK to the library with:

```
scp path/to/AppUpdater-release.apk root@192.168.0.134:/tmp/
ssh root@192.168.0.134 "pct push 122 /tmp/AppUpdater-release.apk /opt/apk-installer/public/uploads/AppUpdater_vX.Y.Z.apk"
```

## Stack

Native Kotlin + Android platform APIs only (zero androidx deps), same pattern as the
`apk-installer-app` field kit. PackageInstaller-based install, no ADB library here.
