# ZCode Companion

**Your ZCode agent, in your pocket.**

An unofficial Android client for the [ZCode](https://zcode.z.ai) desktop app.
Pair it with your machine once, and your phone becomes a live remote control
for the agent already running on your desk — same relay, same workspaces,
same approvals. Leave the laptop closed; the work keeps moving.

> This is a personal, unofficial project. It is not built by, endorsed by,
> or affiliated with Z.ai.

## What it does

- **Pair by QR** — scan the code from the desktop's *Mobile Remote Control*
  dialog once; credentials are sealed with the Android Keystore and never
  leave your devices.
- **Live conversations that read like the desktop** — full v4 streaming with
  real markdown (nested lists, tables, strikethrough, syntax-highlighted
  code), per-message **Retry / Fork / Rewind files / Like–Dislike** row
  actions, conversation search, plans and trajectory viewers, artifact
  preview cards, compaction notices, a turn navigator, and @-mentions.
- **A composer that keeps up** — photo, file, camera, clipboard-paste and
  voice input; attachments render as thumbnails in the thread; the whole
  transcript shares out through the Android share sheet.
- **Approvals on the go, safely** — permission requests land as interactive
  cards and shade notifications with **Approve / Deny buttons**, every card
  shows the command you are actually approving, and a request that was
  already handled on the desktop says so instead of silently doing nothing.
- **Real task management** — rename, archive, delete, pin, unread dots,
  groups, and search; queue reorder, auto-drain and follow-up steering;
  saved-workflow hub with run history and run-now.
- **Side views that earn their keep** — files with line numbers and image
  previews, a git panel with tree view, search and **write actions**
  (stage/unstage one-tap; commit, push, branch and discard behind typed
  confirmations), a terminal with **opt-in input** (layered arming,
  auto-disarm) and copy, usage charts, coding-plan quota meters, automations
  with a desktop-shaped cron editor, and plugins, skills and MCP status.
- **Yours to shape** — light/dark theme override, full English and Chinese
  localization, home-screen shortcuts, an approvals widget, onboarding, and
  a feedback form straight to the developer.
- **Update on your terms** — the app checks GitHub Releases when your desktop
  is newer than what it was tested against; downloads, verifies the SHA-256,
  and hands off to the system installer. No accounts, no third parties.

## Install

Grab the latest APK from
[Releases](https://github.com/ErnestHysa/zcode-companion/releases) — every
release ships the APK plus its SHA-256. Requires Android 8.0+ and a
**ZCode desktop 3.14.x** running on the same ZCode account.

For background updates, [Obtainium](https://github.com/ImranR98/Obtainium)
works out of the box: point it at this repo and it will follow Releases.

The app keeps an explicit compatibility matrix: desktops inside the tested
range connect silently; a newer desktop gets a clear warning and an update
path, never a silent failure.

## How it stays compatible

ZCode moves fast, so the repo carries its own drift radar:

- a committed **surface inventory** of the upstream protocol is diffed against
  every upstream release on a schedule (`.github/workflows/open-repo-drift.yml`),
- changes are classified **ADDITIVE** (tolerated by design — unknown fields
  and message types are preserved, never guessed) or **BREAKING**,
- a breaking upstream change fails CI with a report and is routed to a
  human-reviewed patch — the release pipeline is hard-wired to *never*
  auto-ship one,
- clean releases verify, version-bump, build, sign, and land as draft
  releases automatically (`tools/ci`, `tools/release`; see
  `docs/runbooks/drift.md` and `docs/runbooks/release.md`).

## Build it yourself

```bash
./gradlew :app:assembleDebug      # debug APK
./gradlew :app:assembleRelease    # release build (unsigned without a keystore)
./gradlew test                    # full unit suite (1,475+ tests)
```

Android Studio (or the command-line SDK) with JDK 17. Release signing reads a
keystore from `~/.zcode-android-release-keys/keystore.properties` when present
and stays unsigned otherwise — see `docs/runbooks/release.md`.

## Repository layout

| Path | What lives there |
|---|---|
| `app/` | the Android application: screens, navigation, DI |
| `core/` | protocol (v4 wire + RPC), transport (relay + LAN direct), security, notifications, UI kit |
| `domain/` / `data/` | pure state machines and their persistence |
| `features/` | conversation, tasks, files, git, terminal, pairing screens |
| `fixtures/` | golden wire fixtures generated from the upstream schemas |
| `tools/` | drift radar, release pipeline, mock relay, fixture generators |
| `.github/workflows/` | CI, drift watch, protocol verification, benchmarks |

## License

MIT — see [LICENSE](LICENSE). ZCode is a trademark of its respective owner;
this project just talks to it.
