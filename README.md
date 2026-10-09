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
- **Live conversations** — full v4 conversation streaming: assistant output,
  tool activity, row-level edits, message retry and edit, slash commands.
- **Approvals on the go** — permission requests land as interactive cards and
  notifications; approve or reject from the lock screen if you like.
- **Tasks & queues** — bootstrap, browse, queue, and manage every workspace
  the desktop can see, with reconnect-and-recover that survives tunnels,
  airplain mode, and process death.
- **Side views that earn their keep** — files and git for every workspace,
  a view-only terminal, usage analytics, coding-plan quota meters,
  automations, plugins, skills, and MCP server status.
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
./gradlew test                    # full unit suite (846 tests)
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
