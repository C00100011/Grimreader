# Grimreader

An Android e-book reader that keeps your reading life in one place: a fast EPUB reader, read-aloud with natural voices, reading statistics and streaks, and optional sync with your own self-hosted library.

[![Test and Build](https://github.com/C00100011/Grimreader/actions/workflows/build-apk.yml/badge.svg)](https://github.com/C00100011/Grimreader/actions/workflows/build-apk.yml)
[![License: PolyForm Noncommercial](https://img.shields.io/badge/license-PolyForm%20Noncommercial%201.0.0-blue)](LICENSE)
![Android 12+](https://img.shields.io/badge/Android-12%2B-3ddc84)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7f52ff)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285f4)

Grimreader works with no account and no server: open your own EPUB files, or try the two bundled demo books. If you run a library server, it can sync with that as well. Everything is stored on your device first, so the app keeps working offline.

> **Not affiliated.** Grimreader is an independent project. It is not affiliated with, endorsed by or sponsored by Grimmory, Shelfmark, Hardcover, Open Library, Project Gutenberg, Kobo or KOReader. Those names are used only to describe what the app can talk to.

---

## Contents

- [Features](#features)
- [Where your books come from](#where-your-books-come-from)
- [Privacy](#privacy)
- [Getting started](#getting-started)
- [Build from source](#build-from-source)
- [Testing](#testing)
- [Project structure](#project-structure)
- [Architecture](#architecture)
- [Releases, versioning and signing](#releases-versioning-and-signing)
- [Contributing](#contributing)
- [Third-party software and licenses](#third-party-software-and-licenses)
- [License](#license)

---

## Features

### Reading
- **EPUB reader** built on [foliate-js](https://github.com/johnfactotum/foliate-js) in a sandboxed WebView, with paginated and scrolling modes, one or two pages depending on the screen, and tap zones to turn pages.
- **Highlights and notes**, in-book search, table of contents, and a configurable header and footer (page number, chapter or book progress, time left).
- **Typography**: bundled fonts (Literata, Lora, Merriweather, Atkinson Hyperlegible, OpenDyslexic), themes including a night theme, and adjustable size, spacing and margins.
- **Reading pace per book**: how long you took, over how many days, your words per minute, compared with the average adult reader (238 wpm, Brysbaert 2019) or a preset or speed of your own.

### Read aloud
- Android text-to-speech with sentence highlighting and a media notification.
- **Optional natural voices**: an offline neural voice (Supertonic 3, run with ONNX Runtime) that you download on demand. It is only offered on phones that can run it (64-bit CPU, enough memory and free space).

### Statistics and motivation
- Active reading time, daily goal, **streaks** with freezes, achievements and levels.
- A home-screen **streak widget** (Jetpack Glance).

### Library
- Library tab with filters, search, multi-select, favourites and a list-detail layout on large screens.
- Downloads, "download all", covers kept on the device so the library looks the same offline.
- **Import your own books** and open files straight from other apps ("Open with Grimreader").
- Pull to refresh; with a server connected it also synchronises.

### Discover
- **New books in your genres and languages** (from Open Library) and **free public-domain classics** (Project Gutenberg).
- **This week's picks**: a deck of book ideas every Monday. Swipe right for "want to read", up for "love it", left for "no". Swiped books land in *Want to read* and *Want to buy* lists.
- Optional trending books and suggestions from Hardcover with your own free API key.

### Sync and integrations (all optional)
- **Grimmory**: library, reading progress, reading sessions, favourites shelf and similar books. Two-way progress with a Kobo or KOReader: the furthest position wins, and the reader offers to jump when another device is further along.
- **OPDS catalogs**: browse any OPDS catalog and add books to your device.
- **Book search** through a Shelfmark-compatible server, with login detection (password, OIDC or API key).
- Libraries are checkboxes: use your server, an OPDS catalog and this device together.

### Made for every screen
- Phones, foldables (list-detail on the hinge, tabletop posture) and tablets, including the 600-720 dp range.
- English and Dutch, per-app language on Android 13+ and a fallback on Android 12.

---

## Where your books come from

Grimreader ships no copyrighted books. The only bundled books are two public-domain titles from [Standard Ebooks](https://standardebooks.org) (CC0), used for the demo mode. Everything else comes from files you add, a library or OPDS catalog you point the app at, or the public-domain catalogs above.

You are responsible for having the rights to anything you download or import, including through servers you configure yourself.

---

## Privacy

- There is **no Grimreader backend**, no analytics, no advertising and no tracking.
- Server addresses, logins and API keys are stored encrypted on the device and are only sent to the services *you* configure.
- Reading progress, highlights and statistics stay on the device unless you connect your own server.
- Optional features contact third parties only when you turn them on: Open Library and Project Gutenberg (a genre and a language, plus your IP address), Hardcover (your own key, plus the title and first author of a few books you read), and Hugging Face (one-time download of the voice model).
- Cleartext HTTP and user-installed certificate authorities are allowed so the app works with home servers. For self-signed servers the app uses *certificate pinning on confirmation* (you see the SHA-256 fingerprint and approve it) instead of trusting everything.
- Backups exclude tokens and downloaded books.

Permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`, and foreground services for read-aloud (media playback) and downloads/sync (data sync). No storage, location, contacts or microphone access.

---

## Getting started

### Install

- **Google Play**: Grimreader is currently in closed testing.
- **APK / AAB**: download them from the [Releases](https://github.com/C00100011/Grimreader/releases) page. Each release has a signed `Grimreader-<version>.apk` and `.aab`.
- **Build it yourself**: see below.

Requirements: Android 12 (API 31) or newer.

### First run

The welcome tour asks how you read:

1. **Sync everything with your own library server** (Grimmory),
2. **Browse an OPDS catalog**, or
3. **Just read your own EPUB files**, or try the demo books.

You can change this later under *Settings > Integrations*.

---

## Build from source

### Requirements

- **Android SDK** with platform 37 and recent build-tools (Android Studio installs these).
- **JDK 17 or newer** to start the Gradle wrapper. The project asks Gradle for a **JDK 25** daemon (`gradle/gradle-daemon-jvm.properties`) and downloads it automatically through the Foojay toolchain resolver, so you do not need to install it yourself.
- An internet connection for the first build.

Create `local.properties` (Android Studio does this for you) if the SDK is not found:

```properties
sdk.dir=/path/to/Android/sdk
```

### Commands

```bash
# Debug APK (all ABIs, around 210 MB)
./gradlew assembleDebug

# Small APK for sideloading on a modern phone
./gradlew assembleDebug -PabiOnly=arm64-v8a

# Unit tests
./gradlew testDebugUnitTest

# Release APK and Play bundle (R8 + resource shrinking)
./gradlew assembleRelease bundleRelease
```

Outputs:

| Artifact | Path |
|---|---|
| Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| Release APK | `app/build/outputs/apk/release/app-release.apk` |
| Release bundle | `app/build/outputs/bundle/release/app-release.aab` |

You can pass a version explicitly, which is what CI does:

```bash
./gradlew assembleRelease -PappVersionName=1.2.3 -PappVersionCode=10203
```

Without those properties the fallbacks in `app/build.gradle.kts` apply.

### Signing your own build

Release builds are signed with the key described in `~/.config/grimreader/signing.properties` (outside the repository):

```properties
storeFile=/absolute/path/to/your-upload-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> **Important:** if that file is missing or incomplete, the release build is signed with the **debug key**. It will install for testing but cannot be uploaded to Google Play.

A build you sign yourself will not install over an official one (different signature); uninstall first.

### Try it without a real server

`tools/mock-grimmory/server.py` is a small stand-in that implements what the app calls: a Grimmory-style login and catalogue, covers, EPUB downloads, an OPDS catalog (open at `/opds`, private at `/opds-private` with `reader` / `secret`), a bare-bones Shelfmark and a fake Hardcover GraphQL endpoint.

```bash
python3 tools/mock-grimmory/server.py          # default port 8765
```

From the Android emulator the host is `http://10.0.2.2:8765`. Any username and password work. Unknown paths answer 404 and are printed, so gaps are easy to spot. For the fake Hardcover endpoint, use the key `http://10.0.2.2:8765/hardcover/graphql|mocktoken` in a debug build.

---

## Testing

```bash
./gradlew testDebugUnitTest
```

Unit tests cover the pure logic (for example the streak calculator, reading pace, progress reconciliation and formatting). CI runs them on every push and pull request. There are no instrumented tests yet.

---

## Project structure

```
Grimreader/
├── app/
│   ├── src/main/java/com/vdelaar/mylibby/   # the app (package name is historical)
│   │   ├── core/            # database (Room), datastore, models, network, security
│   │   ├── data/            # repositories: library, sync, reading, stats, discover, OPDS, ...
│   │   ├── di/              # AppContainer: hand-written dependency injection
│   │   ├── notifications/
│   │   ├── tts/             # read-aloud, plus tts/neural for the offline voice
│   │   ├── ui/              # Compose screens, one folder per area, plus theme and adaptive layout
│   │   └── widget/          # streak widget (Glance)
│   ├── src/main/assets/
│   │   ├── reader/          # foliate-js based reader (HTML/JS/fonts)
│   │   ├── demo/            # two public-domain demo books
│   │   └── licenses/        # full third-party license texts shown in the app
│   ├── src/main/res/        # resources: values (English), values-nl (Dutch)
│   ├── src/test/            # unit tests
│   └── schemas/             # exported Room schemas (migrations)
├── tools/mock-grimmory/     # mock server for development
├── gradle/                  # version catalog (libs.versions.toml) and wrapper
└── .github/workflows/       # CI: test, version, build, release
```

---

## Architecture

- **Kotlin, Jetpack Compose and Material 3** (with Material 3 Adaptive for list-detail and navigation suites).
- **UI → ViewModel → repository → Room / DataStore / network.** One ViewModel per screen; screens never talk to the server directly.
- **Offline-first with an outbox:** changes are written locally first and sent when a connection is available (WorkManager).
- **Dependency injection** is a small hand-written container (`AppContainer`), not a framework.
- **Networking:** Retrofit, OkHttp and kotlinx.serialization.
- **Persistence:** Room (schemas exported to `app/schemas/`; every database change needs a version bump and a `Migration`), Preferences DataStore, and encrypted storage for credentials.
- **Reader:** foliate-js runs in a WebView that only loads the app's own assets (`WebViewAssetLoader`, file access disabled), with a Kotlin to JavaScript bridge for positions (CFI), highlights and search.
- **Read-aloud:** the text is turned into sentence segments, spoken by Android TTS or the optional neural engine, and mirrored back as a highlight; playback runs in a media foreground service.
- **Images:** Coil.

New user-visible text always goes into both `values/strings.xml` and `values-nl/strings.xml`.

---

## Releases, versioning and signing

Releases are built by GitHub Actions (`.github/workflows/build-apk.yml`).

| Job | Runs on | What it does |
|---|---|---|
| `test` | every run | Unit tests, report uploaded as an artifact |
| `version` | pushes to `main` and `development` | Reads the commits since the last tag and creates the next tag |
| `build` | pull requests, `development`, manual runs | Debug APK as a downloadable artifact |
| `release` | pushes to `main` that produced a new version | Signed release APK and AAB attached to a GitHub release |

### Versioning with Conventional Commits

Versions come from git tags and your commit messages ([Conventional Commits](https://www.conventionalcommits.org)):

| Commit | Bump |
|---|---|
| `fix: ...` | patch (0.9.5 → 0.9.6) |
| `feat: ...` | minor (0.9.5 → 0.10.0) |
| `feat!: ...` or `fix!: ...` | major (0.9.5 → 1.0.0) |
| `chore:`, `docs:`, `refactor:`, ... | no release |

`versionCode` is derived as `major*10000 + minor*100 + patch`, so it always increases (0.9.5 gives 905).


---

## Contributing

Issues and pull requests are welcome.

1. Fork the repository and create a branch from `development`.
2. Make your change, with unit tests where it makes sense.
3. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`.
4. Use Conventional Commit messages (`feat:`, `fix:`, `docs:`, ...); they decide the next version.
5. Open a pull request. A maintainer has to approve the CI run for first-time contributors.

Guidelines:

- Keep the UI → ViewModel → repository layering (nothing goes to a server straight from a screen).
- Add new texts in English and Dutch. (new langs are always welcome)
- For database changes, bump the Room version, write a `Migration` and commit the exported schema. (just be sore this is align with the last 3 versions at least, don't want to f**k anyones library)
- Do not commit keystores, tokens or API keys. Everything compiled into an APK can be extracted by anyone (and if you ever set an AI api key, i will definitly max that key out).
- Security issues: please do not open a public issue; contact the maintainer privately through GitHub (once we have a bigger community you can open a public issue).

---

## Third-party software and licenses

Grimreader is built on open-source components. The full license texts are in `app/src/main/assets/licenses/` and are shown inside the app.

| Component | License |
|---|---|
| AndroidX, Jetpack Compose, Room, WorkManager, DataStore, Media3, Glance, Navigation, Lifecycle | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization, Coil, Retrofit, OkHttp / Okio | Apache-2.0 |
| [foliate-js](https://github.com/johnfactotum/foliate-js) | MIT |
| fflate | MIT |
| zip.js | BSD-3-Clause |
| ONNX Runtime | MIT |
| Supertonic code | MIT |
| Supertonic 3 voice model (downloaded by the user, not bundled) | OpenRAIL-M, including its use restrictions |
| Literata, Lora, Merriweather, Atkinson Hyperlegible, OpenDyslexic | SIL Open Font License 1.1 |
| Demo books (Standard Ebooks) | CC0 1.0 |

Grimmory and Shelfmark are separate servers that the app reaches over their HTTP APIs; no code from them is included (you bring them in the game).

---

## License

Grimreader's own source code is licensed under the [PolyForm Noncommercial License 1.0.0](LICENSE). In short:

- You may use, study, modify and share the code for any **noncommercial** purpose: personal use, hobby projects, research, education, and use by charities, schools and similar organizations.
- You may **not** sell it or use it commercially, and you may not distribute your own paid or ad-supported version of it. Keep the `Required Notice:` line from `LICENSE` with any copy you share.
- The copyright holder keeps all rights and can, for example, publish and sell the official app on Google Play.

Only the license text in `LICENSE` is legally binding; the summary above is not. For commercial use, contact the maintainer i'll surely deny that request (this will be free of charge forever).

This is a **source-available** license, not an [OSI-approved open-source](https://opensource.org/osd) one, because it restricts commercial use. The third-party components above keep their own licenses, which are not affected by this one.

---

## Acknowledgements

- [foliate-js](https://github.com/johnfactotum/foliate-js) for the reader engine.
- [Standard Ebooks](https://standardebooks.org) for the demo books.
- [Open Library](https://openlibrary.org) and [Project Gutenberg](https://www.gutenberg.org) for open catalogs.
- [Hardcover](https://hardcover.app) for book data (shown as "Data from Hardcover").
- [Supertone](https://huggingface.co/Supertone/supertonic-3) for the Supertonic voice model.

And ofcourse a little bit of AI to clarify some strange issues i've had in developing, and preparing this repo for open sourcing since it was just a private project for friend and family on my own Grimmory, Shelfmark etc. So i had to clean a lot of hardcoded s**t. And addressed security issues.
