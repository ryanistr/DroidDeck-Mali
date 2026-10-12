# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

DroidDeck brings the SteamOS experience to Android: it ships (and installs at runtime) an ARM64 Linux rootfs, runs Valve's Steam client in Big Picture on an Adreno handheld through **proot**, and runs Windows games through **Valve's ARM64 Proton** under a **gamescope** compositor. No root required.

Single Gradle module `:app`, package `com.droiddeck.launcher` (was `com.steamdeck.launcher` until 2026-09-23).

The APK is mostly a launcher plus native/session supporting code; the actual Steam client, desktop, Proton, and games all live in the downloaded Linux runtime, not in this repo.

### Fork mission: Mali GPU support

This tree is the **Mali fork** of DroidDeck (`ryanistr/DroidDeck-Mali`; upstream `Droid-Deck/DroidDeck`). Upstream only targets an Adreno (Qualcomm) GPU; the point of this fork is to make the same Steam-in-a-rootfs stack run on ARM **Mali** GPUs, and to get there by **rebuilding the GPU driver stack from the ground up** from source instead of shipping the vendor blob. The reference target is MediaTek MT6893 (Dimensity 1200/8050), Mali-G77 MC9 (Valhall, arch 9), on a rooted Infinix GT 10 Pro (X6739); `GpuInfo`/`DeviceReport` special-case that SoC by name.

- The stack rebuilt here is **Mesa PanVK** built two ways from one source tree: `tools/panvk/build-panvk.sh` (glibc, for the runtime) and `tools/panvk/build-panvk-android.sh` (bionic, for the app's compositor), both applying `tools/panvk/mesa-patches/`.
- A **Rust kbase backend** (`app/src/main/rust/panvk-kbase-jm/`, `libpanvk_kbase_jm.so`) drives ARM's proprietary `mali_kbase` kernel module directly over `/dev/mali0`; stock upstream Mesa cannot reach that kernel driver.
- **Compatibility layers** for Mali that upstream lacks: DRM-node emulation for the KMS-only MediaTek display controller, GPU-family classification (Midgard/Bifrost/Valhall/unknown), and driver selection that switches the whole app onto the PanVK path for every Mali family.
- This is experimental, device-driven bring-up.

## Essential Commands

```sh
# Full CI-equivalent build (Docker + Java 17 + Android SDK/NDK + rustup/cargo-ndk + gh + python3 + zstd)
tools/build_local.sh                       # → app/build/outputs/apk/release/app-release.apk
DROIDDECK_BUILD_VARIANT=debug tools/build_local.sh
DROIDDECK_SKIP_RUST=1 tools/build_local.sh # APK without the store engines (no Rust toolchain)
DROIDDECK_PA13_SOURCE_DIR=/path tools/build_local.sh   # reuse an existing PulseAudio 13.0 source
DROIDDECK_SIGNING_ENV=.signing.env tools/build_local.sh # sign with DroidDeck's own release key

# After one preparation, an ordinary build reuses the verified runtime inputs
./gradlew :app:assembleRelease

# Unit tests with no native preparation needed
./gradlew -PskipRust=true :app:testDebugUnitTest   # README's documented form
./gradlew testReleaseUnitTest                       # what CI runs

# Python regression suite for the session helpers and ELF preloads (needs Linux /proc, inotify, glibc)
bash tools/test_local.sh                            # runs in Docker on macOS, natively on Linux
python3 -m unittest discover -s tools/tests         # on Linux directly

# Rust store-engine host tests (pure logic; the Android build compiles none of them)
cd app/src/main/rust/stores && cargo test --locked

# Install a built APK on an attached device
tools/deploy_local.sh [path/to/app.apk]

# Drive the app on a real device over ADB (see docs/agent-control.md)
tools/droiddeckctl <state|start|stop|launch|input|ui|logs|screenshot|...>
tools/droiddeck-scenario tools/scenarios/steam-game-focus.json --var appId=489830 --repeat 10
```

## Architecture

### Android Side (Kotlin / Compose)

- **App** (`App.kt`): Application class; sets up crash handler, GPU clock pin cleanup, Flatpak bwrap socket, game-file sync, registers three store backends
- **HomeActivity** (`HomeActivity.kt`): Routes system Home launch to `SessionActivity` (session active) or `MainActivity` (launcher) without consuming `KEYCODE_HOME`
- **MainActivity** (`MainActivity.kt`): Everything outside a running session — runtime install, updates, GPU drivers, frame generation, controller mapping, performance settings, Play button
- **SessionActivity** (`SessionActivity.kt`): Hosts running session — `SurfaceView` for guest frames + Compose overlay (drawer, on-screen controls, second screen)
- **SessionService** (foreground service): Owns session lifecycle. Phases: `IDLE → PREPARING → INSTALLING_RUNTIME → STARTING_COMPOSITOR → STARTING_GUEST → STARTING_STEAM → READY` (plus `SUSPENDED`, `STOPPING`, `FAILED`). App and guest agent exchange files under `files/session/agent`

Packages under `app/src/main/java/com/droiddeck/launcher/`:
- `core` — device/report/log/session plumbing
- `session` — lifecycle, prefs, logs, components
- `ui` — all Compose screens
- `runtime` — rootfs install, proot/appimage/flatpak/decky
- `stores` — GOG/Epic/Amazon
- `frontend` — Games tab, added games, Steam shortcuts/art
- `files` — file manager
- `input` — controller, on-screen pad, touch
- `gpu` — drivers, frame gen, screen effects
- `audio`, `update`, `agent`, `wayland`

Java is used where it predates Kotlin (`WaylandCompositor`, `LinuxRuntime`, store download managers, file utils); keep those as Java rather than rewriting.

Compose test tags exposed as resource ids via `Modifier.exposeTestTags()` (`ui/TestTags.kt`) so both `droiddeckctl ui dump` and UI Automator see stable ids. Tags catalogued in `docs/agent-control.md`; apply `exposeTestTags()` at root of any new screen.

### Linux Runtime Side (shell + C preloads)

- Session scripts in `tools/linuxfs/overlay/usr/local/bin/droiddeck-*` staged into `app/src/main/assets/linuxfs/` by Gradle `stageSessionScripts` task and `tools/build_local.sh`. **Edit source under `tools/`, never the staged asset (gitignored).**
- `droiddeck-session` is runtime entry point exec'd by app under proot. First pass starts gamescope, which re-execs it inside compositor for actual program (`desktop`, `run <path>`, `steam [url]`, `lxqt`). Emits greppable `== STEP <time> <what>` milestones the app shows.
- C preloads (`tools/linuxfs/preload/*.c` → `libblsession.so`) built as glibc aarch64 in Docker, plus x86_64/i386 via `tools/linuxfs/build-x86-preloads.sh` in Debian bullseye container. `tools/linuxfs/check-preloads.sh` verifies them.
- `tools/proot/` built with NDK (`tools/proot/build.sh`) into `app/src/main/jniLibs/arm64-v8a/libproot.so` (gitignored). Patches and fast-path trampoline documented in `tools/proot/PATCHES.md`.
- proot rebuilt only when sources or NDK changed; input/output hashes cached in `.proot-inputs`/`.proot-outputs` next to libraries. Delete to force rebuild.

### Native (C/C++ NDK)

- `app/src/main/cpp/` built by CMake (`CMakeLists.txt`, NDK CMake 3.22.1). Contains:
  - Wayland compositor (`waylandcomp/`) — per-app window management, dmabuf, HDR compose
  - Frame generation engines (`framegen/`) — LSFG and win-fg
  - Post-processing shaders (`shaders/`)
  - Device info
  - Termux PTY helper
  - `fakeinput_steam.cpp` — built separately as glibc aarch64 preload by helpers, not by CMake
- Compositor's generated Wayland protocol stubs in `waylandcomp/generated/` from XML in `waylandcomp/protocols/`; regenerate rather than hand-editing.

### Rust Store Engines

- `app/src/main/rust/stores` cross-compiles `libdroiddeckstores.so` via cargo-ndk (Gradle task `buildRustStores`). Ported from Bannerlator's `bl-steam-client`; exact JNI contract (symbol names, listener signatures, threading, handle lifetime) in `app/src/main/rust/stores/JNI.md`.
- Library is optional: with `-PskipRust=true` it is absent, so **every call site must catch `UnsatisfiedLinkError` and degrade to "store unavailable"**.
- `tools/release/check_android_libraries.py` verifies exported JNI symbols and ELF dependency closure in actual APK.

### Pinned Components (fetched, never committed)

Gamescope, KDE Plasma desktop package, DirectAudio relay, droiddeck-esync packs, uruntime, mangoapp, msitools binaries, and the Mali **PanVK** driver pinned by checksummed `release.env` / `packages.txt` under `tools/` and downloaded from GitHub releases during build (cached by checksum). Component build workflows (`build-gamescope.yml`, `build-desktop-kde.yml`, `build-aaudio-sink.yml`, `build-panvk.yml`) produce those release assets on arm64 runners; separate from APK build.

### Runtime Input Attestation

`tools/release/runtime_inputs.py` records hashes of native sources and prepared outputs into `app/build/runtime-inputs.json`. Gradle's `verifyRuntimeInputs` task runs `verify` before `packageDebug`/`packageRelease`: if native sources, pins, or staged files changed without rerunning `tools/build_local.sh`, packaging **stops with instructions** rather than shipping stale bundle. `check_session_assets.py` separately checks session scripts in APK are byte-identical to `tools/linuxfs/overlay`.

## Testing

- **Kotlin unit tests**: `app/src/test/java/com/droiddeck/launcher/...`, plain JVM (JUnit 4, Robolectric 4.16.1). `unitTests.returnDefaultValues = true` (stray `android.util.Log` call returns default instead of throwing) and `includeAndroidResources = true`. No Android device.
- **Python tests**: `tools/tests/test_*.py`, `unittest`. Several load `droiddeck-*` helper scripts with `importlib` `SourceFileLoader` and/or run them with `python3`; test session helpers and ELF preloads, need Linux semantics (`/proc`, inotify, glibc) and run in Docker on macOS via `tools/test_local.sh`.
- **Rust tests**: `cargo test --locked` in `app/src/main/rust/stores` (CI runs it `continue-on-error` for now, informational).
- **Device tests**: `tools/droiddeckctl` and `tools/droiddeck-scenario` (see `docs/agent-control.md`).
- **CI** (`.github/workflows/build.yml`) additionally enforces: no committed private keys (except `keystore/testkey.p12`), version bumps isolated in their own PR, `Cargo.lock` unchanged by build, APK signed with AOSP testkey under v1+v2+v3.

## Key Conventions and Gotchas

- **`targetSdkVersion 28` is deliberate and structural.** App on targetSdk 29+ may not execute file from its own data directory; executing a rootfs is the whole job. Lint check `ExpiredTargetSdkVersion` disabled for exactly this reason. Do not raise it.
- **Never commit build outputs.** `app/src/main/assets/linuxfs/`, proot/directaudio JNI libraries, `app/src/main/rust/stores/target/`, `app/src/main/assets/droiddeck-esync/`, `app/src/main/assets/directaudio/` are gitignored and produced by build.
- **Tracked audio bundle is never modified.** `app/src/main/assets/pulseaudio.tzst` is base; sinks added into `app/build/prepared-assets/pulseaudio.tzst`, which gets packaged.
- **Keep native copies in sync.** Session scripts and preloads staged from `tools/`; APK checked for byte-equality with source. Stale cached local bundle omitting new helper fails build loudly.
- **APK signed with public AOSP testkey** (`keystore/testkey.p12`, password `android`) so every build installs over previous. Release builds re-signed with DroidDeck's own key (`tools/release/sign-apk.sh`) with signed lineage from testkey.
- **`app/build.gradle` generates `BuildConfig` fields** (build label, source commit, PR number, CI flag, release signer, commit time, variant suffixes, SteamGridDB key, desktop-KDE pin) from git and CI env. Desktop-KDE pin from `tools/desktop-kde/release.env`; variants from `tools/release/variants.txt` (same APK, different package name — `standard`/`pubg`/`antutu`/`ludashi`).
- **Gradle has `org.gradle.configuration-cache=true`.** Tasks that shell out (e.g., `buildRustStores`) must resolve everything eagerly at configuration time, not in `doFirst`.
- **Localization:** user-facing strings in `app/src/main/res/values/strings.xml` translated in `values-{zh-rCN,zh-rTW,zh-rHK,ru,ko,ja,fr,es}`. Add new strings to `values/` at minimum.
- **Comments explain *why*, one short line, omitted when code is self-evident.** Do not narrate code or talk to reader through comments.
- **Commits:** conventional-ish prefixes (`feat:`, `fix:`, `perf:`, or plain capitalized summary), often with PR number (`(#NNN)`). No `Co-Authored-By` / agent attribution lines, ever.
- **`droiddeckctl` gating:** `run`, `guest`, `cdp`, `env` mutations, `override`, `prefs` require **Setup → Session → Debugging tools** (off by default, exit code 7 when refused). Bridge provider answers commands as JSON over `content://com.droiddeck.launcher.agent` and requires `android.permission.DUMP`. See `docs/agent-control.md` for commands, exit codes, limits.
- **Never work in `/tmp`** (host machine rule: it is RAM-backed; use repo or `~/` for scratch).
- **Mali / PanVK gotchas** (this fork's focus): on MT6893 `/dev/dri/card0` is `mediatek-drm` (a display controller with no Vulkan, no GBM, no render node) - the 3D engine is only reachable through `mali_kbase` at `/dev/mali0`, so DRM walks must prefer `/dev/mali0`. `tools/linuxfs/preload/drm.c` synthesizes a DRM device for `/dev/mali0` and makes `gbm_create_device()` return `NULL` for the MediaTek KMS node (its fallback device allocates `dev`, `dev->nodes` and each node separately, since libdrm's `drmFreeDevice()` would otherwise free an interior pointer). `droiddeck-session` sets `XWAYLAND_NO_GLAMOR=1` (Xwayland cannot use Gallium 3D on a display-only node). The Mesa patch makes `libvulkan_panvk.so` `dlopen("libpanvk_kbase_jm.so")`; if that cdylib is missing, `CreateInstance` returns `VK_ERROR_INCOMPATIBLE_DRIVER` and gamescope reports "failed to find physical device". Stock Mesa 24.3 only enables PanVK on arches 6/7/10; **arch 9 (Valhall)** comes from `tools/panvk/mesa-patches/0003-panvk-v9-jm.patch`. The kbase backend is Rust on **both** halves (host rustup for `build-panvk.sh`, cargo-ndk via Gradle `buildPanvkKbaseJm` for Android), so a Rust toolchain is required either way. The glibc driver is pinned by `tools/panvk/release.env` and fetched by `tools/build_local.sh`; `.github/workflows/build-panvk.yml` builds it on `ubuntu-24.04-arm`.

## Docs Map

| Path | Contents |
|---|---|
| `README.md` | User-facing description, requirements, build/limits |
| `docs/progress-log.md` | Running engineering log, newest first, with lessons and backlog |
| `docs/agent-control.md` | `droiddeckctl` commands, state schema, test tags, scenarios |
| `docs/releases/*.md` | Per-version release notes (first line is release title) |
| `docs/development/*.md` | Deep dives: stores, proot performance, Steam focus/display/touch, added games, flatpak, game environment, Windows components, storage |
| `docs/testing/*.md` | Device validation notes for specific features |
| `tools/*/PATCHES.md` | What each patch to proot / gamescope does |