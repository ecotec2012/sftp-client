# SFTP Client for Android

A small native Android app (Kotlin + Jetpack Compose) for working with files on any SSH server over SFTP.

## Features

- **Saved servers**: host, port, username, and either password or private-key login.
- **Private keys**: import OpenSSH, PEM or PuTTY key files (RSA, ECDSA, Ed25519). Passphrase-protected keys work.
- **Browse** remote folders: folders listed first, sizes and dates, symlinks to folders can be opened, hidden files can be shown or hidden, and you can type a path to jump to it.
- **Download** files to the phone's `Download/SFTP Client` folder, then open them straight from the app.
- **Upload** one or many files from the phone. The app asks before replacing a file that already exists.
- **Manage files**: new folder, rename, delete (folders are deleted with everything inside them).
- **Transfer panel** with progress and cancel. Up to 3 transfers run at once, and you can keep browsing while they run.
- **Host key checking**: on first connect you're shown the server's fingerprint and asked to trust it, and you get a clear warning if the key later changes.

## Security notes

- Passwords and key passphrases are **never stored**. They are asked for each time you connect.
- Imported keys and trusted host keys live in the app's private storage, which is excluded from backups and device transfer.

## Build and install

You need **Android Studio** (2025.1 or newer) or JDK 17 with the Android SDK (platform 36).

**Android Studio:** File → Open → choose this folder. Wait for Gradle sync, plug in your phone (with USB debugging on), then press Run.

**Command line:**

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Copy it to the phone and open it to install. You may have to allow installs from that source.

**GitHub Actions:** pushing this project to a GitHub repository runs `.github/workflows/build.yml`. Download the APK from the run's *Artifacts* section.

Requires Android 10 (API 29) or newer.

## Project layout

```
app/src/main/java/org/ecotecworld/sftpclient/
├── MainActivity.kt          app entry point and theme
├── sftp/SftpConnection.kt   SSH/SFTP logic (JSch); no Android dependencies
├── data/ProfileStore.kt     saved servers and imported keys
├── data/DeviceFiles.kt      saving to Downloads and reading picked files
└── ui/
    ├── MainViewModel.kt     app state, connecting, file operations and transfers
    ├── ProfilesScreen.kt    saved server list and the server editor
    ├── BrowserScreen.kt     remote file browser and transfer panel
    └── Dialogs.kt           shared dialogs, including host key confirmation
```

## Known limitations / ideas for next steps

- Transfers run while the app is open. If Android kills the app in the background, transfers in progress stop. A foreground service would fix this.
- Folders can't be downloaded or uploaded as a whole yet; only files.
- An optional "remember password" setting could use the Android Keystore.
