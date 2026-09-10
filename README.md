# JohnnyTV — a white-label Xtream portal player for Android & Android TV

A complete, buildable Android app that signs in to an Xtream Codes portal
(server + username + password), lists Live TV and Movies by category, lets you
search, and plays streams with ExoPlayer. One APK works on phones, tablets and
Android TV / Nvidia Shield (full D-pad navigation).

You own this source. Rebrand it as many times as you like — no licence, no
per-app fee, no third-party build server, and you hold your own signing key.

---

## Part 1 — Get an APK in about 10 minutes (no software to install)

You never have to open or edit a single line of code for this part.

### Step 1 — Make a GitHub account
Go to **github.com** and sign up. It's free.

### Step 2 — Make a new repository
1. Click the **+** in the top-right → **New repository**.
2. Name it anything (e.g. `my-player`).
3. Choose **Private**.
4. Click **Create repository**.

### Step 3 — Upload this project
1. On the new empty repo page, click **uploading an existing file**.
2. Unzip the project folder on your computer.
3. Select **everything inside** the `streamplayer` folder (not the folder
   itself) and drag it into the browser window.
4. Wait for the file list to finish, then click **Commit changes**.

> **If the `.github` folder doesn't upload** (some browsers skip folders
> starting with a dot), do this instead: click the **Actions** tab →
> **set up a workflow yourself** → delete everything in the editor → paste the
> contents of `.github/workflows/build-apk.yml` from the project → **Commit changes**.
> That single step both creates the file and starts the first build.

### Step 4 — Download your APK
1. Click the **Actions** tab.
2. Click the run at the top of the list (it takes ~3 minutes; a green tick
   means it worked).
3. Scroll to the bottom to **Artifacts** → click **app-apk** to download.
4. Inside the zip is `app-debug.apk`. That's your app.

### Step 5 — Install it
- **On a phone:** copy the APK across, tap it, allow "install from unknown
  sources" when prompted.
- **On an Nvidia Shield / Android TV:** install *Downloader* (by AFTVnews) or
  *Send Files to TV* from the Play Store on the Shield, move the APK over, and
  open it. Enable **Settings → Device Preferences → Security → Unknown sources**
  first.

Every time you change something and commit, GitHub builds a fresh APK
automatically. Downloading the new one from **Actions** is the whole workflow.

---

## Part 2 — White-labelling it (the five things you change per client)

### 1. App name
`app/src/main/res/values/strings.xml` → the `app_name` line.

### 2. Colours
`app/src/main/res/values/colors.xml` → the five `brand_*` / `bg_*` values at the
top. Everything in the app is themed from those, so changing them restyles the
whole app.

### 3. Icon and TV banner
Replace the PNGs in `app/src/main/res/mipmap-*/` (the launcher icon, five
sizes) and `app/src/main/res/drawable-nodpi/tv_banner.png` (must stay exactly
320×180 — that's the tile on the Android TV home screen).

### 4. Package ID — important
`app/build.gradle.kts` → change **both** `namespace` and `applicationId` from
`com.johnnytv.player` to something unique, e.g. `com.yourbrand.player`.
Two apps with the same applicationId can't be installed side by side, so each
client build needs its own.

*(If you change it, also rename the folder
`app/src/main/java/com/example/streamplayer` to match, and update the
`package com.johnnytv.player` line at the top of each `.kt` file. If that
sounds fiddly, leave it — the app works fine as-is; you only need a unique ID
when you're shipping more than one build.)*

### 5. Portal behaviour
`app/src/main/java/com/example/streamplayer/Config.kt`:

| Setting | Effect |
|---|---|
| `CONFIG_URL` | The raw URL of your hosted config.json — see the control panel section below. |
| `DEFAULT_SERVER = ""` | Only used if config.json is unreachable and nothing is cached. |
| `PRESET_USERNAME` / `PRESET_PASSWORD` | Set both (plus `DEFAULT_SERVER`) and the login screen is skipped entirely — the app opens straight into the channel list. |
| `LIVE_CONTAINERS` | Which stream formats to try, in order. Default `m3u8` then `ts`, which covers nearly every panel. |

---

## Your control panel (this is the important bit)

The app does **not** have your portal address baked in. Every time it launches it
reads a small file you host, and that file tells it which server to use. So when
your DNS changes you edit one line and every installed copy follows — no rebuild,
no reinstall, nobody has to download anything.

That hosted file is your panel. Hosting it is free.

### Setting it up once

1. Create a **second** GitHub repo, and make this one **Public** (the app has to
   read it without logging in). Suggested name: `johnnytv-config`.
2. In it create a file named `config.json` containing:

```json
{
  "server": "http://your-portal.com:8080",
  "notice": "",
  "latest_version_code": 1,
  "download_url": "",
  "force_update": false
}
```

3. In `Config.kt`, `CONFIG_URL` must point at that file's **raw** address:
   `https://raw.githubusercontent.com/YOUR-USERNAME/johnnytv-config/main/config.json`

### What each field does

| Field | What it does |
|---|---|
| `server` | The portal address the app connects to. Change it here when your DNS moves. |
| `notice` | Any text here appears on the sign-in screen for every user. Leave `""` for nothing. Good for "maintenance tonight 11pm". |
| `latest_version_code` | When this is higher than the installed app's version, users get an "Update available" prompt. |
| `download_url` | Where that prompt sends them to get the new APK. |
| `force_update` | `true` makes the update prompt un-dismissable — for when an old version is genuinely broken. |

Changes take up to about five minutes to reach everyone (GitHub caches raw files
briefly), then apply on each user's next app launch.

### Shipping an update

1. Change whatever you want in the code, commit — GitHub builds the new APK.
2. Bump `versionCode` in `app/build.gradle.kts` (1 → 2) before you commit.
3. Put the new APK somewhere downloadable, set `download_url` to it, and set
   `latest_version_code` to match.

### If config.json is ever unreachable

The app falls back to the last server it successfully used, so a GitHub outage
doesn't take your customers offline. If a user has never signed in and there's no
config, they get a "service unavailable" message rather than a dead screen.

### Support override

Long-press the logo on the sign-in screen and the server field appears, so you can
point a single device at any portal by hand without touching the config. A server
typed in this way overrides config.json on that device only.


---

## What's in the app

| File | What it does |
|---|---|
| `Config.kt` | Every white-label setting, in one place |
| `RemoteConfig.kt` | Reads your hosted config.json (plain or base64 server) |
| `Prefs.kt` | Credentials, favourites, resume points, recent searches |
| `Models.kt` | Shared data shapes |
| `XtreamClient.kt` | Talks to `player_api.php` - login, categories, live, movies, series, episodes |
| `Catalog.kt` | Downloads the catalogue once and caches it on the device |
| `LoginActivity.kt` | Sign-in: username and password only |
| `SyncActivity.kt` | The one-time content sync screen |
| `HomeActivity.kt` | Clock, date, and the Live TV / Movies / Series tiles |
| `BrowseActivity.kt` | Sidebar categories with counts, artwork grid, search, sort, favourites |
| `SeriesActivity.kt` | Series cover, seasons, episode list |
| `SettingsActivity.kt` | Account, refresh content, sign out, support override |
| `PlayerActivity.kt` | Fullscreen ExoPlayer, `.m3u8` to `.ts` fallback, resume |
| `Adapters.kt` | The lists and grids, focus-aware so a TV remote works |

**Address formats accepted at sign-in:** `http://portal.com:8080`,
`portal.com:8080`, or a pasted `.../player_api.php` URL — the app cleans it up.

---

## Not included yet

EPG / TV guide, catch-up, parental PIN, picture-in-picture, multi-screen,
recording. The guide is the big one and is a project of its own - the portal
serves programme data as XMLTV, which needs parsing and a grid to display.

## Shipping a new version

Bump `versionCode` in `app/build.gradle.kts` before you commit, then set
`latest_version_code` in config.json to match and point `download_url` at the
new APK. Installed copies will prompt their users to update.

## Notes

- The build produces a **debug-signed** APK. It installs and runs normally on
  any device. If you later want to publish on the Play Store you'll need a
  release keystore — ask and it's a 15-minute addition to the workflow.
- `usesCleartextTraffic` is enabled because most Xtream panels serve over plain
  `http`. If your portal is `https`-only you can turn it off in the manifest.
- The app is a player. Whether the content behind a given portal is licensed is
  down to the provider, not the app.
