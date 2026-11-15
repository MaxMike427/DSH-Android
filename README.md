# DSH for Android

An offline, self-contained Android app that runs DeepSeekRuntime (`dsh`) inside a
PRoot-managed Ubuntu 24.04 arm64 userland and shows its Web UI on launch.

No Termux installation, no root, no on-device toolchain download: the Ubuntu
rootfs and the whole Node.js toolchain are packaged into the APK.

## Status

| Piece | State |
| --- | --- |
| Android app (Compose UI, WebView, foreground service) | builds |
| PRoot prefix rewrite (`com.termux` → `com.dshapp`) | CI-verified |
| Runtime payload (Ubuntu 24.04.5 + git/nvm/node/npm/pnpm/dsh) | CI-verified through chroot |
| PRoot composition (local bash executor) | CI-verified |
| Launcher icon, shared-storage mounts | implemented, not yet device-tested |
| On-device validation | not yet run (needs a physical arm64 device) |

## Why targetSdk 28

Android 10 (API 29) forbids `exec()` on files in app-private storage. PRoot has
to execute `proot`, its loader, and every guest binary from there, so the app
declares `targetSdk = 28` — the same constraint Termux operates under. Play
Store distribution is not possible with that target; sideload the APK.

## Package name is load-bearing

The Termux build of PRoot hardcodes `/data/data/com.termux/files/usr` in
`DT_RUNPATH` and in `PROOT_UNBUNDLE_LOADER`. The app keeps an independent
package name by rewriting those strings in place during the payload build. The
replacement must be byte-for-byte the same length, so the application id is
fixed to the 10-character `com.dshapp` (same length as `com.termux`). Changing
the application id without updating `scripts/build-rootfs.sh` breaks PRoot at
runtime.

## Build

The runtime payload must be produced on native ARM64 Linux; the Android APK can
be built anywhere with JDK 21 and Android SDK 37.

```sh
# payload + APK, on GitHub Actions
gh workflow run build-runtime

# APK only, against an already-built payload
./gradlew :app:assembleDebug
```

Payload build (requires an arm64 Linux host):

```sh
bash scripts/build-rootfs.sh   # writes app/src/main/assets/*
bash scripts/smoke-rootfs.sh   # unpacks it and runs dsh through PRoot
```

## Layout

```
app/src/main/java/com/dsh/android/
  runtime/DshPaths.kt      private-storage layout + install marker
  runtime/TarExtractor.kt  streaming ustar/pax reader with POSIX modes and links
  runtime/DshRuntime.kt    extraction, PRoot argv, dsh web lifecycle
  service/RuntimeService.kt foreground service keeping the server alive
  ui/DshRoot.kt            install/start progress, WebView, log overlay
scripts/build-rootfs.sh    Ubuntu + toolchain + PRoot payload (arm64 host)
scripts/patch-proot-prefix.sh  in-place ELF prefix rewrite
scripts/smoke-rootfs.sh    end-to-end payload check through PRoot
```

## Runtime layout on device

```
files/usr/            PRoot binary, loader, Termux shared libraries
files/rootfs/         Ubuntu 24.04.x arm64
files/home/           persisted guest /root
files/logs/           dsh web output
files/state/          install marker, resolv.conf, hosts
```

Inside the guest, shared storage appears under `/mnt/android`:

| Guest path | Android path | Requires |
| --- | --- | --- |
| `/mnt/android/android-shared` | `Android/data/com.dshapp/files` | nothing |
| `/mnt/android/download` | `/storage/emulated/0/Download` | all-files access |
| `/mnt/android/documents` | `/storage/emulated/0/Documents` | all-files access |
| `/mnt/android/dcim` | `/storage/emulated/0/DCIM` | all-files access |
| `/mnt/android/pictures` | `/storage/emulated/0/Pictures` | all-files access |
| `/mnt/android/movies`, `music`, … | matching public folders | all-files access |
| `/mnt/android/shared` | `/storage/emulated/0` | all-files access |

Only existing directories are bound; a missing folder or a denied permission
never aborts the PRoot spawn. The in-app card on the launch screen opens the
system "All files access" screen on Android 11+, and requests the legacy
read/write permissions on older releases.

Guest commands still run as a fake root, but `-i`/`-k` map that identity onto the
app's real uid, so files written to shared storage stay readable by the apps that
own those folders.

## Launcher icon

Generated from the supplied character sheet by `scripts/make-icons.py`
(legacy mipmaps plus an adaptive icon). Re-run it after replacing the source
image.

## Known constraints

- **Sandboxing**: DeepSeekRuntime's default composition mounts
  `dsh-sandbox-local`, which needs `bwrap` or Landlock. PRoot provides neither,
  and the plugin fails closed (`SANDBOX_UNAVAILABLE`) instead of running
  unconfined. A profile that replaces the sandboxed bash executor is required;
  that composition is not wired yet.
- **Process lifetime**: Android may kill background processes. The server is
  restartable and all state is on disk, but long agent runs can be interrupted.
- **Architecture**: arm64-v8a only.
- **Network**: the Web UI and tools run offline; model calls need connectivity
  and a DeepSeek API key configured in the UI.
