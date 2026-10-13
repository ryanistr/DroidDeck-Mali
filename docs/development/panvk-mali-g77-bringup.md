# PanVK / Mali-G77 bring-up on X6739 (GT 10 Pro) — everything we learned

Status as of 2026-10-13: **blank screen FIXED, durable in APK**. Rebuilt via
`./gradlew :app:assembleRelease` (with `b5c77f0d` + `droiddeck-session` removing
`XWAYLAND_NO_GLAMOR=1` fallback), verified on device `102562537K104189` in session
`2026-10-13-20-steam` (`READY`, `firstFrame: True`, `-cef-force-gpu` live in cmdline,
GPU util 45–55%). Remaining QoS defects documented in
`docs/development/steam-ui-logo-pingpong-bug.md`: Defect A (CEF startup latency /
stuck at logo until dpad interaction) and Defect B (2-frame ping-pong on MIT-SHM
present path).

This doc exists so none of this is ever re-derived from scratch. Every claim below was
observed on-device or in CI logs on 2026-10-13 unless marked otherwise.

---

## 1. The driver pipeline (how a working Valhall v9 driver exists at all)

Stock upstream Mesa 24.3/26.x has **no Valhall arch-9 JM backend** — it enables PanVK
only on arches 6, 7, 10. The in-repo `tools/panvk/mesa-patches` v9 attempt
(`.mesa-v9/` scratch: 19 objects / 115 errors) **never compiled**. The only working v9
source is the third-party fork:

- Fork: `apexspan-svg/mesa-panvk-mali-g57` (Mesa 26.3.0-devel), real
  `src/panfrost/vulkan/jm/` v9 backend + C kbase glue. Configured
  `-Dpanfrost-kmds=kbase,panthor`.
- Our CI: `.github/workflows/build-panvk-g57.yml` on `ubuntu-24.04-arm` →
  `tools/panvk-g57/build-panvk-g57.sh` → glibc release `panvk-linux-g57-1.2.0-pN`.
- Ubuntu 24.04 dep set that actually works (in this exact form):
  `pkg-config llvm-18-dev libclc-18-dev libllvmspirvlib-18-dev spirv-tools
  libclang-18-dev libudev-dev wayland-protocols libdisplay-info-dev`.
  `libspirv-tools-dev` DOES NOT EXIST (CI exit 100). `libclang-18-dev` is required for
  `clangBasic` (meson.build:2094). `mesa_clc`/`poly` are build-time only — the shipped
  .so must contain zero LLVM/SPIRV/clc strings (asserted in the build script, replacing
  `strip --strip-unneeded`).
- Release pin: `tools/panvk/release.env` (`PANVK_REPO=ryanistr/DroidDeck-Mali`,
  `PANVK_TAG=panvk-linux-g57-1.2.0-p3`,
  `PANVK_SHA256=ea34e92717f3fbfc584616878489177fb212f91fb8d98fd89c9ebe0592472afc`).
  `build_local.sh` fetches by tag+sha and unpacks into staged `linuxfs/` — pinning the
  tag IS the durable glibc path.

**CI gotcha:** the workflow runs on the **pushed** `main`. A commit that was local-only
produced release p2 built on `headSha 424cd16` WITHOUT the dma-heap patch (no
"patching file" lines in the log; binary had the old BuildID). Always confirm the CI
run's `headSha` == your commit before trusting the artifact.

Release history: p1 = no dma-heap fix (md5 9811f02f). p2 = effectively same as p1
(built before push). **p3 = the good one** (BuildID 8b7fa992, CI log shows
`patching file src/panfrost/lib/kmod/kbase_kmod.c`) — use p3 or newer.

## 2. dma-heap 0444 bug → SIGABRT 134 (fixed in fork patch 0002)

- Symptom before fix: `vkAllocateMemory failed (VkResult: -1000072003)` =
  `VK_ERROR_INVALID_EXTERNAL_HANDLE`, then gamescope `vulkan_create_flat_texture`
  assert → session **status 134 SIGABRT** (the old 139 SIGSEGV was the earlier
  missing-FD_INTEROP era).
- Root cause: the fork opens `/dev/dma_heap/system` **O_RDWR** (`kbase_kmod.c:1342`),
  but on X6739 every `/dev/dma_heap/*` node is `cr--r--r-- system:system` (**0444**)
  while `/dev/mali0` is 0666. The app uid (10311) cannot open 0444 O_RDWR.
- Proof (cross-compiled static binary, run as `su 10311`):
  O_RDWR → EACCES; **O_RDONLY → OK**, and `DMA_HEAP_IOCTL_ALLOC` + `mmap` both
  succeed on the O_RDONLY fd. `/dev` is bound into the guest (LinuxRuntime.java:257).
- Fix: `tools/panvk-g57/patches/0002-dma-heap-rdonly.patch` — O_RDONLY fallback when
  O_RDWR is denied. Committed `29f09cb7` (also pins p3 + `PANVK_FD_INTEROP=1`).
- Verification: once fixed, the compositor log shows real swapchains with AFBC
  modifier imports (`modifier=0800000000000341`, ...) instead of dying.

## 3. The bionic (compositor) half — auto-install fix

- The app's compositor (waylandcomp) uses **adrenotools** to load the driver:
  `adrenotools_open_libvulkan(customDriverDir=driverPath, customDriverName=libraryName)`
  (`cpp/waylandcomp/src/vk_loader.c:19`). It needs a **FLAT** dir: `meta.json` +
  `libvulkan_panvk.so` at the root — NOT `usr/lib/…` nested.
- Fork's bionic release: `panvk-mali-g57-v1.2.0-ahb` (Android 24, NDK r29, 20,941,360 B,
  **718 `panvk_v9_` symbols** (internal, so `nm -D` misses them — use plain `nm`),
  `PANVK_FD_INTEROP` gate present, AHB symbols, zero glibc leak). The glibc half has
  0 AHB symbols — AHB is bionic-only.
- Repacked flat → `app/src/main/assets/graphics_driver/panvk-android-24.3.0.tzst`
  (2,679,487 B, deterministic tar: `--owner=0 --group=0 --mtime=@0 --sort=name`,
  zstd -19). The Turnip tzsts are flat too and ARE tracked in git; the PanVK tzst was
  untracked before — it should be added.
- `PanvkDriver.java` was the silent trap: `BUNDLED` was `Collections.emptyList()` and
  `install()` never auto-extracted, so the on-device copy stayed a stale manual import
  (24.3.0, v9=1). Fix (uncommitted at doc time):
  - `BUNDLED_PANVK = "panvk-android-24.3.0"`, `BUNDLED = singletonList(...)`
  - `BUNDLED_VERSION = "1.2.0"` + stale check: bundle is re-unpacked when
    `driverVersion(id)` != BUNDLED_VERSION (the id never changes across rebuilds)
  - `install()` auto-extracts `graphics_driver/<id>.tzst` via `TarZst.extractAsset`
  - `chooseByDevice()` returns `BUNDLED_PANVK` for any Mali family instead of the
    phantom `"system-mali"`
  - meta.json in the tzst MUST carry `libraryName` (the old one only had
    `sourceLibraryName`; `libraryName()` reads `meta.libraryName`).
- On-device verified: after next session start, the stale 8,536,312 B copy became the
  20,941,360 B v1.2.0 with `driverVersion 1.2.0`.

## 4. WSI present path — the fork's raw-DRI3 knob KILLS Steam (do not use)

Measured on device, WSI gate `PANVKDBG WSI: uses_kbase=1 termux_raw=0 raw_dri3=0 kbase_dmabuf=0 sw_device=1 raw_fd_modifier=0`
is what the committed config produces. Two knobs were tested and both are **rejected**:

| Experiment | Gate result | Outcome |
|---|---|---|
| (baseline) | `sw_device=1` | Steam UI renders; present is a CPU MIT-SHM blit → idle screen repeats two stale frames, refreshes on touch |
| `PANVK_KBASE_DRI3=raw` | `kbase_dmabuf=1 sw_device=0 raw_fd_modifier=1` | zink dies: `MESA: error: zink: could not create swapchain`; `GLXBadCurrentWindow`; 0 windows |
| `PANVK_AHB_WSI=1` | gate UNCHANGED (`sw_device=1`) | same zink swapchain failure as DRI3=raw → stuck "waiting for Steam's first frame" |
| raw + `MESA_VK_WSI_PRESENT_MODE=mailbox` | — | FINAL IDLE, firstFrame False |

Why AHB is a no-op here: `panvk_wsi.c:124` sets `ahb_wsi = uses_kbase && getenv("PANVK_AHB_WSI")`,
and `panvk_device_memory.c:119` implements the allocation by `dlopen("libandroid.so")` +
`dlsym(AHardwareBuffer_allocate/…)`. In the **glibc** guest there is no `libandroid.so`, so the
AHB allocator cannot be reached. Neither shipped half exports any `AHardwareBuffer_*` symbol
(verified with `nm` on both the glibc lib and the bionic tzst lib). The gate then flips
`wait_present_before_queue=1` without a working allocator → zink swapchains fail.

**Conclusion:** the residual flicker / stale-frame / "only updates on touch" symptom is the
CPU-blit present path (`sw_device=1`) and is NOT fixable with these env knobs. A real fix requires
either (a) a kbase present path that works from glibc without libandroid (dma-buf from
`/dev/dma_heap/system` + a DRI3-capable X server, cf. the `libmali-…-chroot` raw-FD protocol in §8a),
or (b) an app-side compositor that imports gamescope's dma-buf zero-copy instead of copying it into
the screen swapchain. The baseline remains the best known state: **UI visible, flickering.**

### 4a. Summary of raw-path / WSI gates

`panvk_wsi.c` gates: `PANVK_KBASE_DRI3=raw|termux` (or `WSI_X11_TERMUX=1`) →
`kbase_dmabuf=1 sw_device=0 raw_fd_modifier=1` (native dma-buf/DRI3 present).
Without it: `kbase_dmabuf=0 sw_device=1` — CPU blit through MIT-SHM (this is what
produced the black/logo flicker at ~8 fps we saw BEFORE the real fix, and it appears in
the log as `PANVKDBG WSI: uses_kbase=1 ... kbase_dmabuf=0 sw_device=1`).

**BUT**: with the raw path on this device, Steam dies — `MESA: error: zink: could not
create swapchain` + `X Error of failed request: GLXBadCurrentWindow`, 0 windows, no
first frame, session ends IDLE. Even with the fork's own recipe
(`MESA_VK_WSI_PRESENT_MODE=mailbox`). The fork's README combo
(`PANVK_NO_AFBC=1 PANVK_ASYNC=1 PANVK_OVERLAP=1 PANVK_AHB_WSI=1`) is Termux:X11
specific and does not survive Steam's zink clients (+ Xwayland GLX) here.
**Decision: keep the session at baseline (`PANVK_FD_INTEROP=1` only).**

## 5. THE actual blank/stuck screen cause — Chrome UI had GPU disabled

- `SessionService.kt:602` sets `BL_GPU_FAMILY=mali`. In `droiddeck-session`, the
  Steam CEF arg block was:
  `if XWAYLAND_NO_GLAMOR=1 || GALLIUM_DRIVER=llvmpipe || BL_GPU_FAMILY=mali →
  -cef-disable-gpu`. On Mali that fired → Steam Big Picture (Chromium) rasterized
  every frame on the CPU.
- Measured while stuck: `gpu_utilization = 0 0 100` (GPU idle), screen static
  (lum 9.9, spread 0.0 across samples), ~8 fps, and the "DroidDeck logo" the user saw
  was the APP's own loading overlay ("Step 4 of 4 · Starting Steam · Downloading the
  Steam client update · 100%" — visible via `droiddeckctl ui dump`), which hides on
  `SessionState.firstFrameSeen`. The Steam login screen NEVER painted because the
  client had no GPU.
- Fix (in `droiddeck-session`, near "starting the Steam client"): Mali is now allowed
  the GPU — default is `-cef-force-gpu -cef-ozone-platform=x11 -cef-use-gl=angle
  -cef-use-angle=vulkan`. Commit `b5c77f0d` removed `BL_GPU_FAMILY=mali`, but on Mali
  Xwayland runs without glamor acceleration (`XWAYLAND_NO_GLAMOR=1`), which was still
  triggering the software fallback. Because CEF renders through ANGLE-on-Vulkan directly
  and requires no Xwayland GL, `XWAYLAND_NO_GLAMOR=1` was also removed from the fallback
  condition, leaving only `GALLIUM_DRIVER=llvmpipe`. `BL_CEF_GPU=disable|off|force|angle`
  overrides for bring-up. (bash syntax-checked.)
- On-device verification: session `2026-10-13-20-steam` on `102562537K104189` started
  `READY`/`firstFrame: True` with `-cef-force-gpu` active in cmdline with zero env
  overrides. GPU utilization reached **45–55%** during active UI painting.
  Remaining quality-of-service issues (startup logo stall and 2-frame ping-pong) are
  tracked in `docs/development/steam-ui-logo-pingpong-bug.md`.

## 6. Also measured, not the blocker

- Mali devfreq `13000000.mali`: governor `dummy`, cur 350 MHz, max 886 MHz. Root
  (su) gets Permission denied writing governor; no `userspace/set_freq` node. GED
  `custom_boost_gpu_freq` (world-writable, default 40) + `custom_upbound_gpu_freq`
  (default 0) did NOT move `current_freqency` when set to 886 MHz → after the CEF fix
  utilization was ~47% at 350 MHz, so clock is NOT the blocker; GED owns DVFS.
  Restore defaults after probing: `echo 40 > custom_boost_gpu_freq; echo 0 >
  custom_upbound_gpu_freq`.
- `mtk-dvfsrc-devfreq` was left at userspace 800 MHz by an earlier session's probing —
  restore with `echo 2366000000 > .../userspace/set_freq; echo userspace > .../governor`
  if it persists.

## 7. Session script staging (the durable-fix path)

- `SessionFiles.kt` (stage()) copies the session scripts from APK assets into
  `files/linuxfs/usr/local/bin/` **every session** (rename, so a mapped file keeps its
  inode). Anything fixed must land in `tools/linuxfs/overlay/...` + be staged via
  `tools/build_local.sh` → the APK asset is the source of truth across
  update/reinstall/uninstall.
- Fast on-device A/B without an APK build: `adb push` the edited script to
  `/data/local/tmp`, then as root `cp` over
  `/data/data/com.droiddeck.launcher/files/linuxfs/usr/local/bin/droiddeck-session`,
  chmod 755, chown 10311:10311. Byte-identical md5 confirmed (6b7f98b6).
  Any session restart re-stages the APK copy over it — durable work still needs the
  rebuild.

## 8. GameHub GPU passthrough — analysed 2026-10-13, and what actually adapts

Evidence: `/mnt/project/Misc/ReverseEngineer/GameHub/GameHub-6.3.1-GPU-PASSTHROUGH-AND-0-GPU-FORENSICS.md`
(every claim there is `[E]` with the command and output quoted) plus the measurements below.

**What GameHub's "GPU Passthrough" (显卡直通模式) actually is.** Not a driver switch, not a
driver load. It switches how the guest's finished frame reaches the Android Surface:

| mode | path | cost |
|---|---|---|
| OFF | guest frame → POSIX shm file (`shmfd-XXXXXX`, MIT-SHM) → `memcpy` → upload | one full-frame CPU copy per present |
| ON  | guest frame → **AHardwareBuffer** → imported into Vulkan (`VK_ANDROID_external_memory_android_hardware_buffer`) → direct present | no CPU copy |

Their live heartbeat: `x11vk heartbeat: passthrough=1 kind=user directPresents=2387 (+63) import=1
src=1280x720` — ~60 direct presents/s. Toggled by `XServer.setGpuPassthroughEnabled(boolean)` on
`libxserver.so`; stored per game as `key_native_rendering_enabled`.

**Their driver path never changed.** DXVK → winevulkan → `GameScopeVK_icd.json` →
`libGameScopeV2.so` (their own **bionic** ICD shim) → `dlopen("vulkan."+ro.hardware.vulkan+".so")`
→ `/vendor/lib64/hw/vulkan.mali.so` → Mali. They kept the vendor blob and worked around Mali with
`dxvk-v1.11.1-mali-fix`, SPIR-V passes inside the shim (`MaliCompositeConstantFixPass`,
`MaliArithmeticShiftFixPass`) and BCN emulation. DroidDeck deliberately does NOT do this — we
rebuilt the driver as PanVK, and PanVK already renders Steam (§5). Nothing to adopt here.

**The geometry is already in DroidDeck.** Our compositor has the same two paths, chosen per frame:
- copy path — import the guest dma-buf as a `VkImage` and blit it into the screen swapchain
  (`compositor.c` "dma-buf: copied into the screen swapchain"),
- zero-copy — put the guest's buffer straight on an `ASurfaceControl` layer (`sc_layer.c`), no blit.
The switch is `ahb_swapchain_has_ahb(b)` (`compositor.c:863-868`): the guest must present **gralloc
AHB** buffers through our private `banner_ahb_v1` protocol. Raw dma-bufs always take the copy path.
**DroidDeck's zero-copy layer path *is* GameHub's "passthrough=1".**

**Why it does not light up on Mali (the real blocker).** The glibc guest cannot allocate gralloc
AHBs: `AHardwareBuffer_*` lives in bionic `libnativewindow.so`, and PanVK's AHB path `dlopen`s
`libandroid.so` (`panvk_device_memory.c:119`) — absent in the guest. Neither shipped half exports any
`AHardwareBuffer_*` symbol, and `PANVK_AHB_WSI=1` is a no-op that then breaks zink (§4).

**The one viable adaptation — compositor-owned AHB pool (spike-doc option (c)).** The app side is
bionic and *can* allocate gralloc AHBs, so invert who allocates:
1. the compositor allocates the AHBs for the guest's swapchain (`ahb_swapchain.c` already carries a
   pool; `ZERO_COPY_SPIKE.md` option (c)),
2. hands `fd + modifier + pitch + size` over a protocol before `vkCreateSwapchainKHR`,
3. the guest WSI **imports** them (PanVK already does `VK_EXT_external_memory_dma_buf`, proven every
   frame) instead of exporting its own,
4. the compositor puts them straight on the SurfaceControl layer → zero copy.
Cost per the spike doc: ~2-4 days (a `create_mem` that imports + the protocol + realloc on swapchain
rebuild). This is the genuine "adapt GameHub's passthrough" work; everything cheaper is already done.

**Exact state of the two halves (measured this session).**
- *App side: done.* `compositor.c:3590` calls `ahb_swapchain_init()` — the `banner_ahb_v1` global
  (version 2) is advertised whenever a display layer is possible; `ahb_swapchain.c`+`sc_layer.c`
  implement `present` / `defer_release` / fences; `protocols/banner-ahb-v1.xml` + generated stubs
  exist. `ahb_swapchain_set_mode()` is the live zero-copy switch (drawer / `DROIDDECK_WAYLAND_ZERO_COPY=1`).
- *Guest side: Turnip only.* `ahb_swapchain.h:5-16` names the WSI patch —
  `banners-turnip-wayland, patches/wayland/banner_ahb_wsi.py` — which binds `banner_ahb_v1` v2 and
  makes **Turnip** allocate each swapchain image as a gralloc AHardwareBuffer. There is **no PanVK
  equivalent**, and the glibc guest cannot allocate gralloc AHBs at all (`libnativewindow.so` is
  bionic). So on Mali the guest never attaches an AHB, `ahb_swapchain_has_ahb()` is false, and the
  compositor takes the copy path.
- **Therefore the adaptation = write the PanVK/glibc half as option (c), not (a)**: the compositor
  sends the AHB's dma-buf **to** the guest (direction inverted vs the Turnip patch) and the guest
  imports it. That is the single missing piece; the app side needs only the send-side plumbing.

**DXVK: do NOT overlay GameHub's build.**
- GameHub ships `dxvk-v1.11.1-mali-fix` (a **v1.11.0-line** build of `zeyadadev/DXVK-Sarek`); its
  Mali fix is a code-level `0x13B5` (ARM vendor id) gate in DXVK's DXBC compiler.
- DroidDeck's Proton (`proton-cachyos-11.0-20261005-slr-arm64`) ships **dxvk v3.1.1**
  (`files/lib/wine/dxvk/version` → `dxvk (v3.1.1-47-g6853015)`), three arch dirs
  (`x86_64-windows/`, `i386-windows/`, `aarch64-windows/`). An x86_64 game loads
  `files/lib/wine/dxvk/x86_64-windows/`.
- Measured 2026-10-13: Proton's x86_64 `dxgi.dll` (4,460,544 B) and `d3d11.dll` (6,291,456 B) hold
  **no** `mali`/`Mali` string and **no** u32 `0x13B5` constant — the Sarek Mali gate is not in them.
  (u16 `0x13B5` occurs 19x in d3d11.dll: coincidental byte pairs, not a vendor-id compare.)
- Overlaying the 1.11.1-line DLLs would drop DXVK three major lines for one workaround. Not worth it
  without a demonstrated game-side failure. If one appears, build DXVK-Sarek ≥ **v1.14.1** (Mali fix
  merged upstream) and overlay only `x86_64-windows/` + `i386-windows/`, backing up first.
- Artifacts already on hand: `~/.hermes/cache/scratch/ghsrc/rel/dxvk-sarek-v1.11.1-mali-fix.tar.gz`
  (7.9 MB, `x64/`+`x32/`: d3d8/9/10core/11 + dxgi) and the deploy plan
  `~/.hermes/cache/scratch/dxvk-deploy-plan.md`.

### 8a. Related repos surveyed 2026-10-13 (do not re-discover)

- **funnymdzz/libmali-g610-g710-chroot** — proprietary Mali **g24p0** userspace as a
  glibc `.deb` for ARM64 chroots on Android kbase devices (NOT bionic!). Ships a
  modified Sky1/Arm Vulkan WSI layer (XCB/Xlib surface support) using the same
  raw-FD DRI3 protocol as PanVK-Termux: alloc dma-buf from `/dev/dma_heap/system`,
  `xcb_dri3_pixmap_from_buffers`, modifier 1274 (0x4fa), Present ext; launchers
  `mali-vulkan-run` / `mali-zink-run`, isolated under `/opt`, VK_IMPLICIT_LAYER_PATH
  for compat layers. vkmark vs PanVK kbase on G710: **~2.09x faster** (clear 4.2x).
  **Caveat:** tested kernel pair is kbase r54p3 / userspace API 1.38; our MT6893
  stock kernel is r32p1 / JM 11.38 — userspace/kernel ABI mismatch likely fatal
  without a newer kbase module. If a newer mali kbase .ko ever becomes available
  (custom kernel work), this is the best vendor-blob-in-guest route (glibc, no
  bionic gap), stronger than GameHub's blob.
- **zeyadadev/mali-vulkan-icd-wrapper** — RK3588/G610-only: an ICD wrapper sitting
  between loader and `libmali.so`, adding X11/Wayland/headless WSI + vkd3d
  compat. WSI derived from Arm's layer + ginKage vulkan-wsi-layer / patched
  Xwayland / libmali-rockchip. Not MTK-applicable as-is; same WSI family as
  funnymdzz's.
- **tokokudo/mesa-Panfork-android** — Panfork-based Mesa for Termux (branches
  `Panfork-kbase`, `csf`). README's own build sets `-Dvulkan-drivers=` empty →
  builds gallium Panfrost **GL only, no Vulkan**. Targets G610/G710 (Valhall arch
  10, CSF kbase) — neither our arch 9 JM nor our need (Vulkan). Data point only:
  a Panfork-kbase ecosystem exists (anonymususer04 origin), but it does not
  supersede the apexspan v9 JM fork we use.

## 9. Useful on-device commands (all via `./tools/droiddeckctl` unless noted)

- `./tools/droiddeckctl state` — phase, firstFrame, failure.
- `./tools/droiddeckctl start steam --wait --timeout 300` / `stop --timeout 90`.
- `./tools/droiddeckctl ui dump` — shows the app overlay's exact text
  (proves the "logo" is the loading overlay, not Steam).
- `./tools/droiddeckctl screenshot <path>` — `screencap -p` (valid for pixel checks).
- `adb shell su -c "grep -a 'stats ' $LOG/app.log | tail"` — compositor presented-fps;
  "N GPU frames from games | M windows open" is the render-health line.
- `adb shell su -c "grep -a 'Launched with:' $LOG/session.log"` — the exact steam
  command line (confirm -cef-force-gpu vs -cef-disable-gpu).
- `adb shell su -c "cat /sys/kernel/ged/hal/gpu_utilization"` — "busy idle full" %
  triple; 0 = GPU idle = software rendering.
- Pixel flicker check: screenshot every ~4 s, crop a region, count changed pixels /
  luminance spread (PIL). spread ~0 = static = stuck.

## 10. Commits / releases / hashes (reference)

- `9e88ea1d` fix(preload): drmFreeDevice must hand real libdrm devices back (the
  `free(): invalid pointer` crash — dlsym(RTLD_NEXT) can't see a dlopen'd ICD's libdrm).
- `29f09cb7` fix(panvk): dma-heap O_RDONLY patch 0002 + release.env p3 pin + session
  PANVK_FD_INTEROP. FIRST push that actually built the fixed glibc driver (CI
  38333215261, headSha 29f09cb7, release p3).
- CI history: 38318053922 (prog_mesa_clc missing) → 38328939028 (apt exit 100,
  libspirv-tools-dev doesn't exist) → 38329284473 (clangBasic → +libclang-18-dev) →
  38329773773 ([1082/1082] SUCCESS, p1) → 38330129260 (publish) → 38332566666 (p2,
  WRONG head) → **38333215261 (p3, good)**.
- On device 102562537K104189 (X6739, MT6893, Mali-G77 MC9, Android 16, slot _a):
  app uid 10311, pkg com.droiddeck.launcher, signed AOSP testkey (CN=Android,
  sha256 a40da80a…) — install -r preserves data (firstInstallTime 2026-10-10).