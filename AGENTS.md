# AGENTS.md

Guidance for agents working in the DroidDeck repository.

## What this is

DroidDeck brings the SteamOS experience to Android: it ships (and installs at runtime) an ARM64
Linux rootfs, runs Valve's Steam client in Big Picture on an Adreno handheld through **proot**, and
runs Windows games through **Valve's ARM64 Proton** under a **gamescope** compositor. No root
required. Single Gradle module `:app`, package `com.droiddeck.launcher` (was `com.steamdeck.launcher`
until 2026-09-23; older log entries keep the old name).

The APK is mostly a launcher plus a large amount of native/session supporting code: the actual
Steam client, desktop, Proton, and games all live in the downloaded Linux runtime, not in this repo.

Read `README.md` for the user-facing "what it does", `docs/progress-log.md` for the running
engineering history (newest first, with lessons/backlog), and `docs/development/*.md` for deep dives
on specific subsystems.

## Fork mission: Mali GPU support

This tree is the **Mali fork** of DroidDeck (`ryanistr/DroidDeck-Mali`; upstream `Droid-Deck/DroidDeck`).
Upstream only targets an Adreno (Qualcomm) GPU. The point of this fork is to make the same
Steam-in-a-rootfs stack run on ARM **Mali** GPUs, and to get there by **rebuilding the GPU driver
stack from the ground up** from source instead of shipping the vendor blob.

- **Reference target:** MediaTek MT6893 (Dimensity 1200/8050), **Mali-G77 MC9** (Valhall, arch 9), on a
  rooted Infinix GT 10 Pro (X6739). `GpuInfo`/`DeviceReport` special-case this SoC by name.
- **The stack rebuilt here:**
  - **Mesa PanVK** (the panfrost Vulkan driver), built two ways from one source tree:
    `tools/panvk/build-panvk.sh` (glibc, for the runtime) and `tools/panvk/build-panvk-android.sh`
    (bionic, for the app's compositor). Both apply the patches in `tools/panvk/mesa-patches/`.
  - **A kbase backend written in Rust** (`app/src/main/rust/panvk-kbase-jm/`, `libpanvk_kbase_jm.so`)
    that drives ARM's proprietary `mali_kbase` kernel module directly over `/dev/mali0`. Stock
    upstream Mesa has no way to reach this kernel driver; this is the "from the ground up" part.
- **Compatibility layers** for Mali that upstream does not provide: DRM-node emulation for the
  KMS-only MediaTek display controller, GPU-family classification (Midgard/Bifrost/Valhall/unknown),
  and driver selection that switches the whole app onto the PanVK path for any Mali family.
- This is experimental, device-driven bring-up.

## Essential commands

Everything native is prepared by the local helper; plain Gradle alone is not enough for an APK.

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

### Environment variables worth knowing

| Variable | Effect |
|---|---|
| `DROIDDECK_BUILD_VARIANT` | `debug` or `release` (default `release`) |
| `DROIDDECK_SKIP_RUST` | `1` omits `libdroiddeckstores.so` (cached copies too) |
| `DROIDDECK_PA13_SOURCE_DIR` | Skip the PulseAudio 13.0 download |
| `DROIDDECK_SIGNING_ENV` | Signing env file so the APK installs over a release |
| `DROIDDECK_BUILD_IMAGE` | Docker image for the glibc preload cross-build |
| `DROIDDECK_BUILD_CACHE` | Pinned-download cache (default `~/.cache/droiddeck-build`) |
| `DROIDDECK_NDK_VERSION` | Force an NDK instead of the newest complete one under `$SDK/ndk` |
| `DROIDDECK_GITHUB_REPOSITORY` | Override `origin` when fetching pinned release assets |

## Architecture and how it fits together

### Android side (Kotlin / Compose)

- `App` (Application) sets up the crash handler, GPU clock pin cleanup, the Flatpak bwrap socket, the game-file sync, and registers the three store backends.
- `HomeActivity` routes the system Home launch to `SessionActivity` (a Steam session is active) or `MainActivity` (the launcher) without consuming `KEYCODE_HOME`.
- `MainActivity` is everything outside a running session: runtime install, updates, GPU drivers, frame generation, controller mapping, performance settings, and one Play button.
- `SessionActivity` hosts the running session: a `SurfaceView` for the guest frames plus a Compose overlay (drawer, on-screen controls, second screen).
- `SessionService` (foreground service) owns the session lifecycle. Phases: `IDLE → PREPARING → INSTALLING_RUNTIME → STARTING_COMPOSITOR → STARTING_GUEST → STARTING_STEAM → READY` (plus `SUSPENDED`, `STOPPING`, `FAILED`). The app and the guest agent exchange files under `files/session/agent`.
- Packages under `app/src/main/java/com/droiddeck/launcher/`: `core` (device/report/log/session plumbing), `session` (lifecycle, prefs, logs, components), `ui` (all Compose screens), `runtime` (rootfs install, proot/appimage/flatpak/decky), `stores` (GOG/Epic/Amazon), `frontend` (Games tab, added games, Steam shortcuts/art), `files` (file manager), `input` (controller, on-screen pad, touch), `gpu` (drivers, frame gen, screen effects), `audio`, `update`, `agent`, `wayland`.
- Java is used where it predates the Kotlin code (`WaylandCompositor`, `LinuxRuntime`, store download managers, file utils); keep those as Java rather than rewriting in passing.
- Compose test tags are exposed as resource ids via `Modifier.exposeTestTags()` (`ui/TestTags.kt`) so both `droiddeckctl ui dump` and UI Automator see stable ids. Tags are catalogued in `docs/agent-control.md`; apply `exposeTestTags()` at the root of any new screen.

#### Session lifecycle details

- `SessionState` (object, `session/SessionState.kt`) holds the shared state between the service and activity: `phase`, `sessionId`, `failureCode/Message/Status`, `logDirectory`, `eventsFile`, `guestPid`, `program`, `steamUi`, `steamUrl`, `mode` (`steam`/`desktop`/`run`), `outputSize`, `followScreen`, `refreshHz`, `upscaleRatio`, `fpsLimit`, `firstFrameSeen`, `deckPad`, `secondScreenMode`, `padBridge`, `fakeInputDir`.
- The service holds a partial wake lock, a WiFi lock, and runs as a foreground service with a persistent notification to keep the process at perceptible priority and prevent the low-memory killer from reaping the guest.
- `SessionSuspendController` (`session/SessionSuspendController.kt`) handles background suspend/resume: Steam sleep (via CDP), native sleep (guest `systemctl suspend`), download monitoring, and activity/screen visibility changes.
- A queued Steam game launch (`ACTION_LAUNCH_GAME`) is flushed once the session reaches `READY`.

#### UI test tags

- Tags are applied with `Modifier.exposeTestTags()` at screen roots and key interactive elements.
- Standard tags: `rail-steam`, `rail-games`, `rail-desktop`, `rail-store`, `rail-components`, `rail-android-apps`, `rail-setup`, `rail-updates` (rail tabs); `play-steam` (Play button); `setting-<key>` (every toggle/choice); `tab-<n>` (page tab strips); `drawer-tab-*`, `drawer-stop*`.
- `droiddeckctl ui dump` and the agent bridge read these via Compose semantics (`testTagsAsResourceId = true`).

### Linux runtime side (shell + C preloads)

- Session scripts live in `tools/linuxfs/overlay/usr/local/bin/droiddeck-*` and are staged into `app/src/main/assets/linuxfs/` by the Gradle `stageSessionScripts` task and by `tools/build_local.sh`. **Edit the source under `tools/`, never the staged asset (it is gitignored).**
- `droiddeck-session` is the runtime entry point exec'd by the app under proot. Its first pass starts gamescope, which re-execs it inside the compositor for the actual program (`desktop`, `run <path>`, `steam [url]`, `lxqt`). It emits greppable `== STEP <time> <what>` milestones the app shows.
- The C preloads (`tools/linuxfs/preload/*.c` → `libblsession.so`) are built as glibc aarch64 in Docker, plus x86_64/i386 via `tools/linuxfs/build-x86-preloads.sh` in a Debian bullseye container. `tools/linuxfs/check-preloads.sh` verifies them.
- `tools/proot/` is built with the NDK (`tools/proot/build.sh`) into `app/src/main/jniLibs/arm64-v8a/libproot.so` (gitignored). Its patches and fast-path trampoline are documented in `tools/proot/PATCHES.md`.
- proot is rebuilt only when its sources or the NDK changed; the input/output hashes are cached in `.proot-inputs`/`.proot-outputs` next to the libraries. Delete those to force a rebuild.

#### Session scripts and preloads

- Key scripts: `droiddeck-session` (entry), `droiddeck-clipboard` (guest clipboard bridge), `droiddeck-desktop`/`droiddeck-gpu`/`droiddeck-desktop-gpu` (desktop launchers), `steam-compatibility` (Proton environment), `droiddeck-agent` (guest agent for `droiddeckctl guest/cdp`).
- Preloads (`libblsession.so`): `dircache`, `drm`, `fsync`, `inputudev`, `kgslid`, `kgslpoll`, `links`, `lock`, `net`, `netif`, `ntsync`, `opens`, `pathcache`, `perms`, `robust`, `space`, `storage`, `sysv`, `tracefs`, `tracer`, `udevmon`, `xmode`. They intercept glibc syscalls to translate paths, emulate fstat, handle storage binds, and manage fake evdev input.
- `libfakeinput.so` (built from `fakeinput_steam.cpp`): provides virtual evdev devices (keyboard, mouse, touchpad, gamepad) for the guest; rings in `files/session/fakeinput/`.
- `libblfastpath.so` + `libblaudit.so`: proot fast-path trampoline (`PROOT_FASTPATH=1`); bypasses seccomp stops for translated syscalls. See `docs/development/proot-performance.md`.
- `libssbs.so`: Steam socket bridge shim for the `ssbs_adapter` (session clipboard).

### Native (C/C++ NDK)

- `app/src/main/cpp/` is built by CMake (`CMakeLists.txt`, NDK CMake 3.22.1). It contains:
  - **Wayland compositor** (`waylandcomp/`): per-app window management, dmabuf import/export, HDR compose, Xwayland bridge, `libwaylandcomp.so` loaded by `CompositorHost`.
  - **Frame generation** (`framegen/`): LSFG (Lossless Scaling Frame Gen) and win-fg (FidelityFX Optical Flow). Both expose JNI via `FrameGenEngine` and are selected in `FrameGenMenu`.
  - **Post-processing shaders** (`shaders/`): CAS, FSR, FXAA, NIS, CRT, deband, toon, NTSC, SGsr, color grading, HDR encode. Compiled to SPIR-V headers (`*_frag.h`, `*_vert.h`).
  - **Device info** (`deviceinfo/`): `vkinfo` for GPU enumeration.
  - **Termux PTY helper** (`termux/termux.c`): PTY for the secondary-display Linux shell.
  - **adrenotools** (`adrenotools/`): Adreno GPU driver loader (bionic side), hook for Vulkan ICD selection.
  - **fakeinput_steam.cpp**: Built separately as glibc aarch64 preload (`libfakeinput.so`), not by CMake.
- The compositor's generated Wayland protocol stubs live in `waylandcomp/generated/` from XML in `waylandcomp/protocols/`; regenerate rather than hand-editing (see `waylandcomp/ZERO_COPY_SPIKE.md` for zero-copy dmabuf design).
- `app/src/main/cpp/thirdparty/dxbc/`: DXBC→SPIR-V translator (from DXVK) used by LSFG for shader analysis.

### Rust store engines

- `app/src/main/rust/stores` cross-compiles `libdroiddeckstores.so` via cargo-ndk (Gradle task `buildRustStores`). Ported from Bannerlator's `bl-steam-client`; the exact JNI contract (symbol names, listener signatures, threading, handle lifetime) is `app/src/main/rust/stores/JNI.md`.
- The library is optional: with `-PskipRust=true` it is absent, so **every call site must catch `UnsatisfiedLinkError` and degrade to "store unavailable"**.
- `tools/release/check_android_libraries.py` verifies the exported JNI symbols and the ELF dependency closure in the actual APK.

#### JNI patterns (from `StoresNative.kt`, `GogNative.kt`, `EpicNative.kt`, `AmazonNative.kt`)

- Load once lazily: `System.loadLibrary("droiddeckstores")` in `StoresNative.ensureLoaded()`; cached with `@Volatile` booleans.
- Probe on startup: each engine has `nativeProbe()` (GOG) or relies on `loadLibrary` throw (Epic/Amazon) to set `available` flag.
- Listeners are Kotlin interfaces passed to `nativeStart`; every callback runs on a **native thread** (daemon-attached to JVM). Never touch Views directly — post to main thread.
- Handle lifecycle: `nativeStart` returns `long` handle (0 = not started); `nativeCancel(handle)` is idempotent; `nativeRelease(handle)` called exactly once after `onComplete`. `runCatching` wraps calls to absorb any `UnsatisfiedLinkError` at call sites.
- All `@JvmStatic` in `object` declarations; symbol names are the short JNI form (`Java_<package>_<Class>_<method>`) — no overloads possible.
- `caBundlePath`: PEM bundle for extra TLS roots; `""` = webpki defaults.
- On-disk protocol: files assemble to `.bhtmp`/`.part` then rename on size+hash match; existing matching files skipped (resume support).

### Pinned components (fetched, never committed)

Gamescope, the KDE Plasma desktop package, the DirectAudio relay, droiddeck-esync packs, uruntime, mangoapp, the msitools binaries, and the Mali **PanVK** driver are pinned by checksummed `release.env` / `packages.txt` files under `tools/` and downloaded from GitHub releases during the build (cached by checksum). Component build workflows (`build-gamescope.yml`, `build-desktop-kde.yml`, `build-aaudio-sink.yml`, `build-panvk.yml`) produce those release assets on arm64 runners; they are separate from the APK build.

#### Component details

| Component | Source | Purpose |
|---|---|---|
| gamescope | Valve's fork + DroidDeck patches (`tools/gamescope/patches/`) | Wayland compositor for Steam/Steam games |
| desktop-kde | KDE Plasma + Vapor theme (`tools/desktop-kde/overlay/`) | Linux desktop environment |
| droiddeck-esync | `tools/droiddeck-esync/release.env` | esync/fastsync packs for Proton |
| DirectAudio relay | `tools/directaudio/` | PulseAudio → AAudio sink (`module-aaudio-sink`) |
| uruntime | VHSgunzo/uruntime | AppImage runtime (x86 AppImages) |
| mangoapp | `tools/mangoapp/` | MangoHud overlay |
| msitools | `tools/msitools/` | MSI installer extraction (Windows components) |
| PanVK (Mesa) | `tools/panvk/` (release.env + build scripts + Mesa patches) | Mali Vulkan driver, glibc (runtime) and bionic (compositor); see the Mali/GPU sections |

### Runtime input attestation

`tools/release/runtime_inputs.py` records hashes of the native sources and the prepared outputs into `app/build/runtime-inputs.json`. Gradle's `verifyRuntimeInputs` task runs `verify` before `packageDebug`/`packageRelease`: if native sources, pins, or staged files changed without rerunning `tools/build_local.sh`, packaging **stops with instructions** rather than shipping a stale bundle. `check_session_assets.py` separately checks the session scripts in the APK are byte-identical to `tools/linuxfs/overlay`.

## Testing

- Kotlin unit tests: `app/src/test/java/com/droiddeck/launcher/...`, plain JVM (JUnit 4, Robolectric 4.16.1). `unitTests.returnDefaultValues = true` (a stray `android.util.Log` call returns a default instead of throwing) and `includeAndroidResources = true`. No Android device.
- Python tests: `tools/tests/test_*.py`, `unittest`. Several load the `droiddeck-*` helper scripts with `importlib` `SourceFileLoader` and/or run them with `python3`; they exist to test the session helpers and ELF preloads, so they need Linux semantics (`/proc`, inotify, glibc) and run in Docker on macOS via `tools/test_local.sh`.
- Rust tests: `cargo test --locked` in `app/src/main/rust/stores` (CI runs it `continue-on-error` for now, informational).
- Device tests: `tools/droiddeckctl` and `tools/droiddeck-scenario` (see `docs/agent-control.md`).
- CI (`.github/workflows/build.yml`) additionally enforces: no committed private keys (except `keystore/testkey.p12`), version bumps isolated in their own PR, `Cargo.lock` unchanged by the build, and the APK signed with the AOSP testkey under v1+v2+v3.

#### Test patterns

- **Kotlin tests**: Robolectric with `includeAndroidResources` for string resources. Test tags like `SessionDrawerPageTest`, `AddedGamesTest`, `DriverBundleTest` cover UI logic, store logic, and GPU driver handling.
- **Python tests**: Load helper scripts from `tools/linuxfs/overlay/usr/local/bin/` via `importlib` (`SourceFileLoader`) or execute directly. Tests cover: `droiddeck-session` flow, font config, esync packs, fsync preload, storage preload, Steam installs/shortcuts/games/libraries, game environment, FEX, MSI install, network manager, login1, etc.
- **Scenarios** (`tools/scenarios/`): JSON scripts for `droiddeck-scenario`; `steam-game-focus.json` (launch game, verify focus, quit) and `skyrim-launcher-focus.json` (handles launcher window). Run with `--repeat N --keep-going`.

## Conventions and gotchas

- **`targetSdkVersion 28` is deliberate and structural.** An app on targetSdk 29+ may not execute a file from its own data directory, and executing a rootfs is the whole job. The lint check `ExpiredTargetSdkVersion` is disabled for exactly this reason. Do not raise it.
- **Never commit build outputs.** `app/src/main/assets/linuxfs/`, the proot/directaudio JNI libraries, `app/src/main/rust/stores/target/`, `app/src/main/assets/droiddeck-esync/`, and `app/src/main/assets/directaudio/` are gitignored and produced by the build.
- **The tracked audio bundle is never modified.** `app/src/main/assets/pulseaudio.tzst` is the base; the sinks are added into `app/build/prepared-assets/pulseaudio.tzst`, which is what gets packaged.
- **Keep native copies in sync.** Session scripts and preloads are staged from `tools/`; the APK is checked for byte-equality with the source. A stale cached local bundle omitting a new helper fails the build loudly.
- **The APK is signed with the public AOSP testkey** (`keystore/testkey.p12`, password `android`) so every build installs over the previous one. Release builds are re-signed with DroidDeck's own key (`tools/release/sign-apk.sh`) with a signed lineage from the testkey.
- **`app/build.gradle` generates `BuildConfig` fields** (build label, source commit, PR number, CI flag, release signer, commit time, variant suffixes, SteamGridDB key, desktop-KDE pin) from git and CI env. The desktop-KDE pin is read from `tools/desktop-kde/release.env`; variants from `tools/release/variants.txt` (same APK, different package name — `standard`/`pubg`/`antutu`/`ludashi`).
- **Gradle has `org.gradle.configuration-cache=true`.** Tasks that shell out (e.g. `buildRustStores`) must resolve everything eagerly at configuration time, not in `doFirst`.
- **Localization:** user-facing strings live in `app/src/main/res/values/strings.xml` and are translated in `values-{zh-rCN,zh-rTW,zh-rHK,ru,ko,ja,fr,es}`. Add new strings to `values/` at minimum.
- **Comments explain *why*, one short line, and are omitted when the code is self-evident.** Do not narrate the code or talk to the reader through comments.
- **Commits:** conventional-ish prefixes (`feat:`, `fix:`, `perf:`, or a plain capitalized summary), often with the PR number (`(#NNN)`). No `Co-Authored-By` / agent attribution lines, ever.
- **`droiddeckctl` gating:** `run`, `guest`, `cdp`, `env` mutations, `override`, and `prefs` require **Setup → Session → Debugging tools** (off by default, exit code 7 when refused). The bridge provider answers commands as JSON over `content://com.droiddeck.launcher.agent` and requires `android.permission.DUMP`. See `docs/agent-control.md` for commands, exit codes, and limits.
- **Every new Mali/PanVK/device finding updates `docs/development/panvk-mali-g77-bringup.md` in the same commit/change that produced it.** If a discovery is left only in chat or session logs it is effectively lost; the doc is the durable memory. This covers driver builds, CI gotchas, on-device measurements, dead ends, and fixes.
- **Never work in `/tmp`** (host machine rule: it is RAM-backed; use the repo or `~/` for scratch).

### Storage and paths

- **App files dir** (`context.filesDir`): root for `linuxfs/` (rootfs), `session/` (fakeinput rings, agent exchange), `logs/` (session logs, stores logs, tool logs — app-private, scrubbed on share).
- **External storage** (`Environment.getExternalStorageDirectory()`): user's games, bound into guest at same host path. `Download/DroidDeck/Saves/` for game save backups (public).
- **Internal storage** (anywhere under app's private files): mapped to `/root/Storage` in guest (added games feature).
- **Guest runtime dir**: `/run/droiddeck` (fixed short path for Wayland sockets; avoids 108-byte `sun_path` limit on long adopted-storage paths).
- **Guest system fonts**: `/usr/local/share/fonts/android` (bind from `/system/fonts`).

### GPU driver system

- **DriverBundle** (`gpu/DriverBundle.kt`): matched pairs of Android (bionic) + Linux (glibc) Turnip drivers in one zip with `manifest.json`. Both halves installed together; selecting one sets both.
- **TurnipDriver** (`gpu/TurnipDriver.kt`): manages Android-side `libvulkan_freedreno.so` in app's native lib dir; `meta.json` with build info.
- **LinuxVulkanDriverManager** (`gpu/LinuxVulkanDriverManager.kt`): manages Linux-side driver in rootfs `usr/share/vulkan/icd.d/`.
- **SessionPrefs** stores active `androidDriver` and `linuxDriver` IDs; `DriverBundle.active(context)` returns the matching bundle if both halves are set.
- Default drivers shipped in `assets/graphics_driver/` (`adrenotools-turnip*.tzst`).
- **Mali / PanVK** (this fork's focus) uses the same two-halves split, but on PanVK instead of Turnip:
  - `PanvkDriver` (`gpu/PanvkDriver.java`): the **bionic** driver the app's compositor runs to put a
    finished frame on screen. Bundled zips live in `assets/graphics_driver/` (`panvk-android-*.tzst`);
    imported zips and the choice live under `files/graphics_driver_panvk/` (`PanvkDriver.AUTO` = pick by GPU).
  - `LinuxPanvkDriver` + `LinuxPanvkDriverManager` (`gpu/LinuxPanvk*`): the **glibc** driver the Steam
    client, its games and the desktop render with inside the runtime; installed under
    `files/linux_panvk_drivers/`. The choice is handed to the session as an absolute ICD-manifest path
    in `BL_VK_DRIVER` (`LinuxPanvkDriver.ENV`), which `droiddeck-session` re-exports as `VK_DRIVER_FILES`
    only after checking the manifest and its library are readable.
  - `DriverMenus.kt`, `SessionService.kt`, `SessionActivity.kt` and `DeviceReport.kt` branch on
    `GpuInfo`'s `MALI_*` families and use these PanVK classes in place of the Turnip ones.
- Pinning: `tools/panvk/release.env` (`PANVK_REPO`/`PANVK_TAG`/`PANVK_SHA256`) names the glibc PanVK
  release; `tools/build_local.sh` fetches it and unpacks it into the staged `linuxfs/`.
- CI: `.github/workflows/build-panvk.yml` builds that driver on `ubuntu-24.04-arm` (with a Rust
  toolchain for the kbase cdylib) and publishes the release tag.

#### Mali / PanVK gotchas

- On MT6893, `/dev/dri/card0` is `mediatek-drm` (a **display** controller: no Vulkan, no GBM, and no
  render node). The 3D engine is reached only through ARM's `mali_kbase` at `/dev/mali0`, so anything
  that walks DRM devices must prefer `/dev/mali0` over `/dev/dri/card0`.
- `tools/linuxfs/preload/drm.c` is the compatibility layer for that: it synthesizes a DRM device for
  `/dev/mali0` and makes `gbm_create_device()` return `NULL` for the MediaTek KMS node. Its fallback
  device allocates `dev`, `dev->nodes` and each node separately, because libdrm's `drmFreeDevice()`
  would otherwise free an interior pointer (that mismatch was a glibc heap-corruption crash).
- `droiddeck-session` sets `XWAYLAND_NO_GLAMOR=1` (Xwayland cannot use Gallium 3D on a display-only DRM
  node) plus `PANVK_DEBUG=startup`/`VK_LOADER_DEBUG=all`/`WAYLAND_DEBUG=1` as bring-up instrumentation.
- The Mesa patch makes `libvulkan_panvk.so` `dlopen("libpanvk_kbase_jm.so")`. If that cdylib is not
  beside the driver, `CreateInstance` falls through to `VK_ERROR_INCOMPATIBLE_DRIVER` and gamescope
  reports "failed to find physical device".
- Stock Mesa 24.3 only enables PanVK on arches 6, 7 and 10; **arch 9 (Valhall)** comes from
  `tools/panvk/mesa-patches/0003-panvk-v9-jm.patch`. Without it a Valhall device enumerates no GPU.
- The kbase backend is Rust on **both** halves: `build-panvk.sh` uses a host rustup toolchain for the
  glibc cdylib, and Gradle's `buildPanvkKbaseJm` task cross-compiles it with cargo-ndk for Android. A
  Rust toolchain is required to build either driver.

## Docs map

| Path | Contents |
|---|---|
| `README.md` | User-facing description, requirements, build/limits |
| `docs/progress-log.md` | Running engineering log, newest first, with lessons and backlog |
| `docs/agent-control.md` | `droiddeckctl` commands, state schema, test tags, scenarios |
| `docs/releases/*.md` | Per-version release notes (first line is the release title) |
| `docs/development/*.md` | Deep dives: stores, proot performance, Steam focus/display/touch, added games, flatpak, game environment, Windows components, storage |
| `docs/development/panvk-mali-g77-bringup.md` | Live PanVK/Mali bring-up knowledge: driver pipeline/pins, dma-heap, CEF GPU, WSI dead-ends, hashes. **Update it in the same change as any new Mali/device finding** — never let a discovery live only in session history |
| `docs/testing/*.md` | Device validation notes for specific features |
| `tools/*/PATCHES.md` | What each patch to proot / gamescope does |

## Key files for common tasks

| Task | Files to read/modify |
|---|---|
| Add a new store backend | `stores/Store.kt`, `stores/StoreAccounts.kt`, `stores/gog/`, `stores/epic/`, `stores/amazon/`, `rust/stores/` |
| Modify on-screen controls | `input/OnScreenControls.kt`, `input/PadBridge.kt`, `input/PadState.kt`, `ui/DeckControlsPanel.kt` |
| Change session lifecycle | `session/SessionService.kt`, `session/SessionState.kt`, `session/SessionSuspendController.kt` |
| Add frame generation effect | `framegen/lsfg/`, `framegen/winfg/`, `gpu/FrameGen.kt`, `ui/FrameGenMenu.kt` |
| Modify GPU driver handling | `gpu/DriverBundle.kt`, `gpu/TurnipDriver.kt`, `gpu/LinuxVulkanDriverManager.java` |
| Work on Mali / PanVK | `tools/panvk/` (build scripts, `mesa-patches/`, `release.env`), `app/src/main/rust/panvk-kbase-jm/`, `gpu/PanvkDriver.java`, `gpu/LinuxPanvkDriver*.java`, `tools/linuxfs/preload/drm.c` |
| Add Compose screen | `ui/` + `Modifier.exposeTestTags()` at root, add tags to `docs/agent-control.md` |
| Modify proot behavior | `tools/proot/patches/`, `tools/proot/fastpath/`, `runtime/ProotFastPath.kt`, `runtime/LinuxRuntime.java` |
| Update session scripts | `tools/linuxfs/overlay/usr/local/bin/droiddeck-*`, then `tools/build_local.sh` |
| Add C preload | `tools/linuxfs/preload/*.c`, then `tools/build_local.sh` (Docker cross-compile) |
| Update pinned components | `tools/*/release.env`, then `tools/build_local.sh` (fetches by checksum) |