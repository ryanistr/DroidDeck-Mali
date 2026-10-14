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
- **FristOneRR/FristOneRR-Panvk-Driver** (surveyed 2026-10-13 on request) — README + LICENSE
  only; the download is `v2.0-rc1` → `Panvk-Mali-G57-2.0-rc1.zip` (2,922,415 B, sha256
  `2ad19e7b97f7cdb57cddee96d476400bee0aad77486cec6b2434ebc537089d53`). Inside: a single
  `libvulkan_panfrost.so` (15,640,776 B, sha256
  `eb7e56c3125e4a1dab3800d5fa27f5d8956d5861a23dbaa1fd76279422a1babb`), `meta.json`
  (`Mesa 26.3.0-devel`, `Mali-G57-2.0-rc1`, `minApi 36`) and `LICENSE-Mesa.txt`. It is
  **bionic**: NEEDED is
  liblog/libhardware/libnativewindow/libsync/libdrm/libz/libzstd/libc++_shared, no GLIBC_
  symbols, so it cannot replace the guest's glibc driver at all. Its own compatibility table
  claims Valhall v9 JM (G57/G68/G77/G78) and Bifrost v6/v7 and **excludes the 5th-gen CSF
  parts (G610/G710/G615/G715)** — i.e. our GT 20 Pro and GT 30 Pro testers' GPUs — so it
  cannot help either of them. Its declared main target is our own part (G77 MC9; their
  "Dimensity 8050" is MT6893), with a 56-byte atom-stride auto-detect and `PANVK_HEAP_MB` /
  `PANVK_TILER_HEAP_MB` / `PANVK_POLY_HEAP_MB` / `PANVK_STENCIL_LATE` / `PANVK_TRACE` knobs;
  it admits `kbase: bo_import not yet implemented (dma-buf import requires
  KBASE_IOCTL_MEM_IMPORT)`. **Verdict: not adoptable** — bionic-only, no arch 11, no dma-buf
  import. Their release hashes match their published table, so the artifact is authentic.
  - **Re-assessment 2026-10-14 — the "not adoptable" verdict above is too broad.** It is true for
    the *guest* half and for the CSF testers, but as the ***bionic/compositor* half** this driver is
    a legitimate candidate on arch-9 JM Mali, which is the X6739.
    - It is the **same Meson target we build**: `src/panfrost/vulkan/libvulkan_panfrost.so`, which
      `tools/panvk/build-panvk-android.sh:99` also produces and only renames to
      `libvulkan_panvk.so` (line 106). Theirs keeps the upstream name.
    - It satisfies **every** gate in `PanvkDriver.rejectionReason()`
      (`app/src/main/java/com/droiddeck/launcher/gpu/PanvkDriver.java:219-243`): a `meta.json`, a
      non-empty `libraryName` (`libvulkan_panfrost.so`), an aarch64 ELF, and no `libc.so.6`. Note
      their `meta.json` has **no `kind`** key, so the `linux-vulkan-icd` rejection does not fire.
    - The compositor loads its driver through **adrenotools** as `<driverPath>/<libraryName>`
      (`WaylandCompositor.nativeStartWithSurface`, `wayland/WaylandCompositor.java:201`), and both
      parts come from `PanvkDriver.driverPath()`/`libraryName()`, i.e. from the `meta.json`. Any
      declared name works. Their zip is therefore importable **as-is** via the app's PanVK import
      (`installFromZip` → `adopt`, `PanvkDriver.java:154`), which flattens the zip to base names, so
      the three top-level files land correctly.
    - **Why it is more than an equivalent.** Its kbase support is **in-tree C** — `strings` shows
      `/dev/mali%d`, `failed to open kbase device %s`, `Found kbase device '%s'.` — with **no
      `libpanvk_kbase_jm.so`** dependency. This fork instead patches Mesa to
      `dlopen("libpanvk_kbase_jm.so")` and must keep that Rust cdylib beside the driver, which
      AGENTS.md records as a hard failure mode (`VK_ERROR_INCOMPATIBLE_DRIVER` when it is absent).
      It gates itself on `kbase: legacy JM driver version %d.%d not supported (need >= 11.0)`; this
      device's own kbase reports **`kbase uAPI: JM 11.38`** (`dd06-session.log`), so it clears that
      with margin.
    - **Risks to test, not assume.** (a) It states `kbase: bo_import not yet implemented (dma-buf
      import requires KBASE_IOCTL_MEM_IMPORT)`, while the compositor's zero-copy path is
      `vkp_image_from_dmabuf()`; our current bionic driver imports fine (`framedbg DMABUF … img=0x…`
      with no "dmabuf import failed"), so this could force the mmap-upload fallback. (b) It is Mesa
      **26.3.0-devel** against our 1.2.0/24.3-era tree and carries no `PANVK_FD_INTEROP` string (the
      gate our fork added for gamescope), though the external-memory extensions are present under
      other names. (c) It cannot help GT 20 Pro (arch 10) or GT 30 Pro (arch 11) testers.
    - **Verdict (corrected): worth testing as the compositor driver on arch-9 JM Mali (X6739); not
      adoptable for the guest half, and not for CSF devices.**
- **FristOneRR-Admin/FristOneRR-Wrapperv1** — a fork of `leegao/bionic-vulkan-wrapper` at tag
  `v0.0.5r5` (branch `fristonerr-wrapper-v1`): a Vulkan-on-Vulkan **ICD wrapper**
  (`libvulkan_wrapper.so`) that bridges a glibc Wine/Winlator process to a bionic driver,
  installed by copying it into `Z:\usr\lib\`. DroidDeck does not need a wrapper because it
  builds PanVK twice from one tree (glibc for the guest, bionic for the compositor) instead of
  shipping a bionic-only driver plus a translation layer. **Verdict: not needed** (section 3).
- **FristOneRR-Admin/FristOneRR-Panvk-Source** — the one worth remembering: a Mesa 26.3.0-devel
  tree whose `src/panfrost/lib/kmod/kbase_kmod.c` is 93,829 B with 12 job-submit markers
  (`kbase_kmod_job_submit`, `..._dep`, `..._retry`, a JOB_SUBMIT stride probe), i.e. it completes
  Job Manager submission **inside Mesa**, where DroidDeck instead patches Mesa to
  `dlopen("libpanvk_kbase_jm.so")` (`tools/panvk/mesa-patches/0002-kbase-support.patch:245`) and
  drives kbase from that Rust cdylib. Our inherited header still says submission "is not wired up
  yet" and dma-buf/WSI is "left for future work". Replacing the Rust shim with in-tree submission
  is a large refactor, not a drop-in; revisit only if the Rust layer becomes the blocker.
  **Added 2026-10-14 — it does carry a rebuild recipe, and it can produce both halves.** The exact
  configure line is checked in as `tools/fristonerr/build-options.txt` (`-Dpanfrost-kmds=kbase`,
  `-Dplatforms=android`, `-Dandroid-stub=true`, `-Dandroid-strict=true`, `-Dvulkan-drivers=panfrost`,
  `-Dpanfrost-rust=false`, `-Dplatform-sdk-version=35`, tests off), with the cross file
  `android-cross.txt` (`clang --target=aarch64-linux-android35`). CI is
  `.github/workflows/kbase-release.yml` (439 lines, `runs-on: ubuntu-24.04-arm`, tag `kbase-v*`,
  `meson setup … -Db_ndebug=true -Dpanfrost-kmds=kbase,panthor`) and it stages
  `libvulkan_panfrost_kbase.so` + `panfrost_kbase_icd.aarch64.json` into **arm64 Linux** packages
  (debian13 / ubuntu2604 / archlinux / generic `tar.gz`) — i.e. a **glibc** kbase PanVK, which is the
  shape the *guest* half needs. So this tree could supply both halves from one source, unlike our
  current split (glibc from `mesa-panvk-mali-g57`, bionic from `tools/panvk/`).
  **Updated 2026-10-14 — its released bionic driver was actually run in our compositor.** With the
  dependency libraries staged (§16) it loads and renders real Mesa PanVK 26.3.0-devel on this
  device, and then fails to present: `vkCreateSwapchainKHR` -> `VK_ERROR_INVALID_EXTERNAL_HANDLE`,
  i.e. its unimplemented kbase dma-buf import. So it is not adoptable as the compositor driver for
  the reason predicted here, and the bionic half is still the vendor blob in every shipped build.

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

## 11. Session 27/28 findings: Boot logo stall & 2-frame ping-pong reproduction

- **The "stuck logo" is Steam's StartupMovie (`bigpicture_startup.webm`):**
  Staged to `root/.local/share/Steam/config/uioverrides/movies/bigpicture_startup.webm` and
  configured in `config.vdf`. Steam plays this 6.35s video while CEF initializes.
  Because CEF on Mali takes ~60s, the video completes early and freezes on its last frame.
  Once CEF reaches `https://steamloopback.host/index.html`, Steam does NOT auto-dismiss
  the finished movie layer until an input event (D-pad/button) is received.
- **Empirical 2-frame ping-pong proof (Session 28):**
  Four consecutive screenshots taken at 200ms intervals without user input:
  `frame2.png` (md5 `f22fb099b9fdc7e6bf01a52d003dd033`, sign-in without keyboard) and
  `frame4.png` (md5 `f22fb099b9fdc7e6bf01a52d003dd033`, sign-in without keyboard) were
  byte-for-byte identical, alternating with `frame3.png` (sign-in with keyboard open).
  The display swapchain oscillates strictly between two buffer states. Full details in
  `docs/development/ping-pong-frame-handoff.md`.

## 12. GT 20 Pro (G610 v10) and GT 30 Pro (G615 v11) testers — two distinct failures

Tester bundles: `DroidDeck-2026-10-13-01-steam-GT-20.zip` (X6871) and
`DroidDeck-2026-10-13-05/07-steam-GT30-Pro.zip` (X6873). Both report app 0.3.2 (12).
GT-20 has the post-`2d2cd010` detection; GT 30 Pro does not. [E]

### 12a. GT 30 Pro still prints "Mali-G77 MC9 · Mali" — stale APK, already fixed in HEAD

`2d2cd010` (2026-10-12) is the fix: before it `MTK_PLATFORMS` mapped `mt6897` to model 77
and `familyOf()` compared Mali numbers against Adreno ranges, so X6873 reported
`Mali-G77 MC9 · Mali` (`MALI_UNKNOWN`). Session 07 (2026-10-13 20:11) still prints exactly
that string, and the installed native lib dir is identical in sessions 05 and 07
(`~~GmVbNfU1Rc3oW30o56m3Uw==/...-1gU83qVdpNW-JrgSJCVX5w==`), so the tester never installed a
post-fix APK. Current HEAD prints `Mali-G615 MC6 · Mali Valhall · No PanVK backend for this
Mali generation (v11)`. Redistribute a freshly built APK; the detection defect is not in HEAD.

### 12b. GT 30 Pro: "failed to find physical device" (gamescope exit 1)

The stale APK classes the GPU as an unknown Mali, and `LinuxRuntime.vulkanIcd()` takes its
`arch == 0 -> panvk` branch, so the guest loads `panvk_icd.aarch64.json`. Session 07:
`vulkan: failed to find physical device` -> `SDL_Vulkan_CreateSurface failed:
VK_KHR_wayland_surface extension is not enabled` -> `gamescope exited with code: 1`. The
pinned driver (`panvk-linux-g57-1.2.0-p3`) carries only a **v9** JM backend
(`build-panvk-g57.sh` fails the build unless `panvk_v9_` is present), and X6873 is MT6897 =
Mali-G615 (Valhall **v11**, CSF), so that driver can never create a physical device here.
Fixed by §13 (`panvk-linux-g57-1.2.0-p4`).

### 12c. GT 20 Pro: `vkCreateInstance failed (VkResult: -9)` -> SIGABRT 134

X6871 is MT6896 = Mali-G610 MC6 (**v10**). Detection is correct (session 01: `Mali-G610 MC6 ·
Mali Valhall · No PanVK backend for this Mali generation (v10) yet: the session runs on
software rendering`), so `SessionService` routes the guest to the software path and sets
`VK_ICD_FILENAMES=/usr/share/vulkan/icd.d/lvp_icd.aarch64.json`; gamescope then dies at
`vkCreateInstance failed (VkResult: -9)` = `VK_ERROR_INCOMPATIBLE_DRIVER` (rendervulkan.cpp:299
assert) and the guest exits 134. `-9` at **instance creation** means the loader loaded **zero**
ICDs: the lvp manifest is present, but `libvulkan_lvp.so` is neither in
`app/src/main/assets/linuxfs/` nor in `SessionFiles.stage()`'s list nor built by any `tools/`
script, and `LinuxRuntime.ensureLavapipeIcd()` writes the manifest only when the library
already exists. Hypothesis: the downloaded r9 base rootfs ships no `libvulkan_lvp.so`. The
app-side compositor is unaffected — GT-20's wayland.log shows it rendering on Mali-G610
through the system Vulkan driver.

### 12d. What the APK carries (and what it does not)

The APK **does** bundle the updated drivers: the built `app-release.apk` contains
`assets/linuxfs/usr/lib/libvulkan_panvk.so`, `.../libpanvk_kbase_jm.so`,
`usr/share/vulkan/icd.d/{panvk,lvp}_icd.aarch64.json`, `usr/local/bin/{gamescope,droiddeck-session}`
and every preload, and `SessionFiles.stage()` refreshes all of them into `files/linuxfs` at
**every launch** through an atomic rename. An unrooted tester therefore needs no `su` or adb to
pick up a new PanVK/kbase/compositor/script. The one driver the APK does **not** carry is
`libvulkan_lvp.so` — the exact library the unsupported-generation fallback needs.

Checked live on X6739 2026-10-13 (rooted, `adb shell su -c`): the installed r9 rootfs **does**
have `files/linuxfs/usr/lib/libvulkan_lvp.so` (14,435,320 B, `root:root`, mtime 2026-10-11
23:03) and `libLLVM.so.23.1` (171,229,352 B, same date), both **manually installed** — the base
rootfs only ships `libLLVM.so.22.1` (u0_a311, 2026-10-10). `strings` on the library reports
`mesa26.2.4-arch1.1` / `llvmpipe (LLVM 23.1.1)`. So the by-hand install is what makes lavapipe
work here; a tester who never did it, or whose install predates it, reaches the GT-20 `-9` in
12c with the lvp manifest present but its library absent. `LinuxRuntime.icdIsUsable()` now
refuses a manifest whose `library_path` does not resolve under the rootfs, so the loader gets
`null` (caller falls back) instead of a manifest pointing at nothing.

Caveat: lvp being present on X6739 says nothing about a GT 20 Pro's rootfs. That the `-9` is a
loader-with-zero-ICDs result is inference from the value (`VK_ERROR_INCOMPATIBLE_DRIVER` at
`vkCreateInstance`), not a measurement on the tester's device. [I]

## 13. Valhall gen3 (arch 11) ported into the pinned driver — `panvk-linux-g57-1.2.0-p4`

Why a patch and not a fork bump: `apexspan-svg/mesa-panvk-mali-g57` is not ours (read-only), and
`tools/panvk-g57/patches/0003-enable-valhall-gen3-arch-11.patch` is exactly the mechanism the
component already uses for its other two fixes, so the pinned tag stays untouched.

What the fork's v1.2.0 (Mesa 26.3.0-devel, commit `66306ebd`) actually lacked — this is the whole
list, and it is *only* wiring, not code: `src/panfrost/model/meson.build`-side tables were fine,
but the **genxml schema** (`genxml/meson.build`, `gen_macros.h`), the **per-arch build lists**
(`vulkan/meson.build` device-prefix, `valhall_archs`, `csf_archs`, `foreach arch`,
`lib/meson.build`, `libpan/meson.build`), the **arch dispatch** (`panvk_macros.h`,
`panvk_physical_device.c`'s two `switch (arch)` gates and its `PER_ARCH_FUNCS` list), the
**pandecode entry points** (`genxml/decode.h`, `decode_common.c`), the **libpan/include chains**
(`libpan.h`, `libpan_shaders.h`) and the **model table** (`pan_model.c`, `pan_get_fallback_model`
+ the G615/G715 `VALHALL_MODEL` rows) all omitted 11. `csf/*.c`, `genxml/cs_builder.h`,
`decode_csf.c`, `lib/pan_format.c` and `compiler/pan_compiler.c` already carry the
`PAN_ARCH >= 11` branches, which is what made this a wiring job.

Evidence used: upstream Mesa main's `src/panfrost/genxml/v11.xml` (the fork has no `v11.xml` at
all; its `v10.xml` differs from upstream main's by only 55 line-level edits, so main's v11 schema
is compatible with the fork's 26.3.0-devel code) and main's `pan_model.c` rows

```
VALHALL_MODEL(PAN_PROD_ID(11, 8, 2), 4, "G715", "G715", ... MODEL_RATES_X(4, 8, 32, 64, 128, 16))
VALHALL_MODEL(PAN_PROD_ID(11, 8, 3), 4, "G615", "G715", ... MODEL_RATES_X(4, 8, 32, 64, 128, 16))
```

(pinned from `https://gitlab.freedesktop.org/mesa/mesa/-/raw/main/src/panfrost/...`; note
`https://github.com/Mesa3D/mesa` does not carry `panfrost` at those paths).

Local iteration is possible and worth it: `src/panfrost/model/meson.build` never needed it, but the
build needs Python `mako`, which this host no longer has installed for python3.14. It *is* in the
uv cache, so no install is needed, e.g.
`PYTHONPATH=$(fd -t d -i '^mako-[0-9]' ~/.cache/uv/archive-v0 | head -1 | xargs dirname) ninja -C builddir src/panfrost/vulkan/libvulkan_panfrost.so`.
A host x86_64 build reaches the shipped driver and caught both `-Werror=missing-prototypes`
failures before CI did. The local build links `libSPIRV-Tools.so` (CachyOS ships it shared) while
the CI driver does not (Ubuntu's copy is static there), so `build-panvk-g57.sh`'s "must not link
LLVM/SPIRV" guard only holds on the CI runner — do not read its local failure as a regression.

Shipped `panvk-linux-g57-1.2.0-p4` (sha256 `dc6503e3e9aa649387e01022c6337276c6e150ca4093b05c07f3226fef97fd88`):
21,871,824 B, a build-time assertion proves `panvk_v11_` is present pre-strip (like the existing
`panvk_v9_` check; `--strip-unneeded` removes both from the shipped library afterwards),
`Mali-G615`/`Mali-G715` are in the model table, and its NEEDED list is unchanged
(libdrm/libz/libzstd/libwayland-client/libxcb*/libexpat/libstdc++/libm/libc, no LLVM family).
Build workflow runs: `38411817925` (compile) and `38412415983` (publish p4).

## 14. 0.3.3 verified on X6739; the bionic half has no arch 11 yet

Verified on the rooted X6739 (MT6893, G77 MC9) 2026-10-13, app **0.3.3 (13)**, build
`remote clean · main at f3f57c2` (CI artifact of run `38427278217`), runtime r9. Session
`2026-10-13-38-steam` reached `phase READY`, `firstFrame true`, `guestPid 4732`, output
1600x720, `focusedApp 769`, `refreshHz 120`. The guest ran the GPU, not software:

```
== uid=10311 size=1600x720 fps=60 icd=/usr/share/vulkan/icd.d/panvk_icd.aarch64.json
PANVKDBG kbase uAPI: JM 11.38
MESA: warning: panvk: unknown gpu_id (0x90800011) or variant (0); continuing with conservative "Mali (unknown Valhall)" defaults
selecting physical device 'Mali unknown 0x90800011 MC9'
CREATE_DEVICE EXT: VK_EXT_image_drm_format_modifier, VK_EXT_queue_family_foreign,
  VK_KHR_external_memory_fd, VK_EXT_external_memory_dma_buf, VK_KHR_external_semaphore_fd,
  VK_EXT_robustness2
```

`wayland.log`: `compositor renders on Mali-G77 MC9 with libvulkan_panvk.so`, driver folder
`graphics_driver_panvk/panvk-android-24.3.0/`; `"Steam Big Picture Mode" (gamescope) is presenting
GPU frames through Wayland: 1600x720, format XR24`. `device.txt` prints
`Mali-G77 MC9 · Mali Valhall · Experimental: Mali/PanVK support is early`. On-screen rate is
~5-7 fps, which is the known Steam-BPM cost (sections 1 and 5), not a bring-up failure.

**Arch coverage of the two halves, measured from the shipped artifacts** (the only honest source
without the tester's device):

| Half | Artifact | Model strings in the library | Arches |
|---|---|---|---|
| glibc (guest) | `panvk-linux-g57-1.2.0-p4` | `Mali-G57`, `Mali-G610`, `Mali-G615`, `Mali-G715`, `Mali-G310v1..v5` | 9, 10, 11 |
| bionic (compositor) | `graphics_driver/panvk-android-24.3.0.tzst` (repacked from the fork's `panvk-mali-g57-v1.2.0-ahb`) | `Mali-G57`, `Mali-G610`, `Mali-G310v1..v5` — **no `Mali-G615`/`G715`** | 9, 10 |

So a GT 20 Pro (G610/v10) is covered by both halves, and a GT 30 Pro (G615/v11) is covered by the
guest half only. `PanvkDriver.chooseByDevice()` returns `BUNDLED_PANVK` for **every** Mali family,
so the compositor on a GT 30 Pro loads an arch-9/10 driver. panvk's unknown-gpu_id path keeps
conservative defaults and does initialise on this hardware (X6739 above proves it for an unknown
`gpu_id`), so the compositor may still come up; this is the one half the X6739 cannot answer for.
If a GT 30 Pro's compositor fails, the fix is to build the fork's panvk for **bionic** with patch
`0003` — the glibc script is `tools/panvk-g57/build-panvk-g57.sh`, and the fork's own bionic build
(section 3) used NDK r29; `tools/panvk/build-panvk-android.sh` pins NDK r27c and upstream Mesa
24.3.0, which has no v11 schema at all, so it cannot be patched into shape.

CI state the same day: `Build APK` is green (runs `38424543841`, `38427278217`; the job had never
passed in this fork before), but the four `sign / sign <variant>` jobs failed with
`environment 'signing-main' has no signing key` — the fork has the `signing-main` environment but no
`RELEASE_KEYSTORE_B64`, so `build.yml`'s overall conclusion was red and `release.yml` (which greps
`gh run list --status success`) could not find a successful run to sign. That is now unblocked:
`tools/release/sign-apk.sh` and `.github/workflows/sign.yml` fall back to the debug key
(`keystore/testkey.p12`) when no release keystore is configured, signing each variant with the
testkey alone exactly as every local `tools/build_local.sh` build already does. Verified locally for
`standard` and `pubg` (both `v3 signer a40da80a…`, `META-INF/TESTKEY.SF`/`TESTKEY.RSA` present, v1
under `--min-sdk-version 21`); the fix still needs a CI run to be seen green. The release-key path is
untouched, so adding a key later via a secret restores the lineage sign. `keystore/release-signer.sha256`
still names upstream's key (`b241ea7d…`) and the app's update catalog is upstream's
(`AppUpdates.kt` hardcodes `Droid-Deck/DroidDeck` / `Droid-Deck/DroidDeck-CI`), so in-app/OTA updates
remain upstream-bound; nothing in the tree changed for that. The rest of the chain is
fixed: the stray `mesa` gitlink that broke every `actions/checkout`, `check_session_assets'`
missing `usr/share/vulkan/icd.d/**` glob, `verifyRuntimeInputs` racing `stageSessionScripts`, the
unquoted `PANVK_SOURCE` in `tools/panvk/release.env`, the absent PanVK staging step, samba.org
mirrors for talloc, ECR throttle fallback, and the session log's byte cap.

## 15. Frame ping-pong — compositor content gate (measured fix, 2026-10-14)

The user-visible ping-pong (§11) is a **guest** defect and was confirmed by measurement, not
inference: gamescope's three output images are linear and therefore CPU-readable, so the compositor
was instrumented to `mmap` each committed buffer and sample a 64x36 grid. With the Steam QAM open
(session `2026-10-14-02-steam`, guide button) the guest alternates on a strict period of 3 — one
bright image (`mean=91`, `hi=1118/2170`) and two partial blanks (`mean=12`, and in the earlier
threshold-16 run a second partial frame at `mean=25`, `hi=282/2170`). The screen tracks whichever
buffer it is handed, so the compositor is faithful and the guest is handing over blanks.

The fix shipped in `app/src/main/cpp/waylandcomp/src/compositor.c` is a compositor-side **content
gate**: `dmabuf_sample()` (the same mmap sampler) plus `blank_frame_hold()`, wired into
`take_dmabuf`'s `if (b->img)` branch so it gates only the buffer *swap* (the `g_stat_dmabuf` count,
the size-change log and the HUD tick at the tail all still run). It holds a commit whose mean is
below `BLANK_MEAN` while a content frame arrived within `BLANK_HOLD_NS`, and releases the held
buffer to the client like any replaced one. Values: `BLANK_MEAN 40`, `BLANK_HOLD_NS 1200000000`.
`BLANK_MEAN 16` was insufficient — the `mean=25` partial frame passed as content and still flickered.

Measured on device, hot-swapping the rebuilt `libdroiddeckwayland.so` into the installed 0.3.3
(session `2026-10-14-03-steam`, 20 frames each):

| Build | Screen R | Spread |
|---|---|---|
| gate on | 82.88 only | 0.00 |
| gate off (`<session log>.no-blank-hold`) | 14.29 <=> 82.88 | 68.59 |
| gate on again | 82.88 only | 0.00 |

The guest keeps alternating throughout (same `mean=12/91/12` period 3), 122 `HOLD-BLANK` lines fired
and every hold reports a content age <= 946 ms, under the 1.2 s bound. This **hides** the guest bug
rather than fixing it: the bound must exceed the client's content-frame interval, and a genuinely
dark scene can be held for up to the bound before it reaches the screen. The guest-side root cause
(why only one of three swapchain images receives the scene — the PanVK WSI's `WSI_BLIT_SHM` slot
handling) remains open; see `docs/development/ping-pong-frame-handoff.md` §7.5 and §9.1a. The fix
only reaches users through a rebuilt `libdroiddeckwayland.so`, i.e. a new APK.

### 15a. Ring regime and gate hole measured on the released build (2026-10-14)

Re-measured on the **released** test APK (`test-0.3.3-mali-frame-gate-20261014`, sha256
`10f842416f842acdba9069dbfcee893dd063f347a26dcea5c85bc5c6171f0702`) rather than by hot-swapping the
`.so`: the installed `base.apk` was verified by `sha256sum` in place. Session
`2026-10-14-04-steam`, 2,413 commits, `frame-probe` on.

- The ring does **not** stay on "1 bright + 2 blank". Three regimes are measured: all-equal
  (invisible), 1 bright + 2 dark (hard flicker), 1 bright + 2 mid (soft flicker). The odd image is
  not a fixed slot — it moved `327` -> `326` -> `325` within one session.
- **All three images are updated over time** (`fd327` took `6,11,13,37,67,90`), so there is no
  permanently-unwritten swapchain image. Updates arrive in **bursts of 1-2**, so the ring rarely
  converges (one 3-way convergence in 2,413 commits).
- The guest commits at ~4 fps **continuously while rendering nothing**: a frozen `85/67/67` ring was
  presented for 292 s / 1,046 commits. That is the ping-pong in its purest form, and it is
  guest-side — our compositor only draws what it is handed.
- The content gate is **inert when every image is `>= BLANK_MEAN`** (in the `85/67/67` regime, marker
  on and off both give screen spread 17.93) and **can freeze the display on a stale bright frame**
  when the stale image is the bright one. So the gate damps the loud regime only; it is not a fix.
- Guest-side evidence from the same session: `sw=1` present path, `minImageCount: 3`, three
  `WSI_BLIT_SHM` allocations at `size=4608000` (1600x720x4) with three distinct `sw_host_ptr`s and no
  NULL. The earlier "12 of 27 allocations returned NULL" note was **not reproducible** and is
  withdrawn.

Full detail: `docs/development/ping-pong-frame-handoff.md` §3.8a and §7.5a.

## 16. The bionic half was never loading — the compositor ran the vendor blob (measured 2026-10-14)

This supersedes any earlier reading of `compositor renders on Mali-G77 MC9 with libvulkan_panvk.so`.
That line prints `g_library_name`, the name we *asked* for, not the driver that answered
(`app/src/main/cpp/waylandcomp/src/vk_present.c:489`), so it never was evidence of PanVK. The device
name it also prints is the same string the vendor blob reports (`Mali-G77 MC9`), so it cannot
discriminate either.

**What actually happens, from logcat (`hook_impl`):**

```
I hook_impl: hook_android_dlopen_ext: filename: vulkan.mali.so
I hook_impl: hook_android_dlopen_ext: loading custom driver: <driver>/libvulkan_panvk.so
I hook_impl: hook_android_dlopen_ext: hook failed: failed to load custom driver:
    dlopen failed: library "libandroid-shmem.so" not found: needed by <driver>/libvulkan_panvk.so
I hook_impl: hook_android_dlopen_ext: falling back!
```

`falling back!` loads the vendor ICD. `/proc/<app>/maps` then holds
`/system/lib64/libvulkan.so` + `/vendor/lib64/hw/mt6893/vulkan.mali.so`, and nothing from
`files/graphics_driver_panvk/<id>/` except `temp/0_patched.so` (a 251 KB copy of `libvulkan.so`).
The compositor's Vulkan on this device is ARM's blob. The same holds for FristOneRR's driver, which
fails one library later on `library "libz.so.1" not found`.

**Two separate mechanisms, both proven:**

1. **Missing dependency libraries.** `adrenotools` loads the driver through
   `hook_impl.cpp`'s `android_create_namespace(filename, customDriverDir, hookLibDir, …)`, so the
   driver folder is the namespace's `ld_library_path` and files placed *in that folder* do satisfy
   NEEDED — that is how `libz.so.1`/`libzstd.so.1` resolved once copied in.
2. **Soname shadowing beats the search path.** An already-loaded library with a matching SONAME wins
   over the driver folder. `/vendor/lib64/libdrm.so` is already loaded (gralloc), it declares
   `SONAME libdrm.so`, and it **lacks `drmCloseBufferHandle`**, which the driver needs
   (`/system/lib64/libdrm.so` has it; the vendor one does not). Proof: putting a full
   `/system/lib64/libdrm.so` copy in the driver folder changed nothing, and replacing it with a
   deliberate 1-byte bogus file produced the *identical* symbol error — the copy is never consulted.

**Fix that works (verified on device):** ship the two zlibs, and give the driver its own
uniquely-named full libdrm so it cannot collide:

```sh
patchelf --set-soname libdrm_full.so  <staged>/libdrm_full.so     # a full libdrm
patchelf --replace-needed libdrm.so libdrm_full.so <driver>/libvulkan_panfrost.so
```
With `libz.so.1`, `libzstd.so.1` and `libdrm_full.so` beside the driver, the hook no longer logs a
failure and `maps` shows `<driver>/libvulkan_panfrost.so` + `libdrm_full.so` with
`/vendor/lib64/hw/mt6893/vulkan.mali.so` **absent** — real Mesa PanVK in the compositor for the
first time.

**Then it hits a wall that is not ours to patch.** PanVK renders offscreen but cannot take the
screen surface:

```
error  present: vkCreateSwapchainKHR failed (-1000072003)
error  present: no swapchain on the screen surface; retrying every 0.5 s
```

`-1000072003` is `VK_ERROR_INVALID_EXTERNAL_HANDLE`. The session renders (`433 scenes / 10 s`) and
presents **0 on screen** — a black screen and a `STARTING_STEAM` that never finishes. This is the
predicted consequence of FristOneRR's own admission in §8a, `kbase: bo_import not yet implemented
(dma-buf import requires KBASE_IOCTL_MEM_IMPORT)`: its in-tree kbase backend cannot import the
gralloc buffers the Android swapchain hands it. FristOneRR's bionic driver therefore **cannot** be
DroidDeck's compositor driver on MT6893 — not because it is a bad build, but because its
memory-import path is unimplemented where the vendor blob's is not.

**Consequences to carry forward:**

- Any *compositor-side* measurement in this doc or in `ping-pong-frame-handoff.md` taken before
  2026-10-14 was taken on the vendor blob, not on PanVK.
- Our own bionic driver has the same latent bug and additionally needs `libandroid-shmem.so` (not
  present anywhere on the device) plus `libxcb*.so`/`libexpat.so.1` — `tools/panvk/build-panvk-android.sh`
  installs `libxcb1-dev`/`libxshmfence-dev` in its container, which is what pulls the xcb WSI and its
  expat dependency into the driver's NEEDED. A bionic rebuild must drop those and stage Mesa's
  `android_stub` libraries, with the same `patchelf` rename for `libdrm.so`.
- The misleading log line should be replaced by the real driver identity
  (`VkPhysicalDeviceDriverProperties.driverName`), so a silent fallback can never masquerade as
  PanVK again.

Reproduce: select a driver via `session.xml` `androidDriver` (the app must be stopped first — the
compositor loads its driver once per process), then
`adb logcat | rg 'hook_impl|graphics driver'` and
`cat /proc/$(pidof com.droiddeck.launcher)/maps`.

## 17. GT 30 Pro (G615, arch 11) is given a Job Manager driver on a CSF-only GPU (2026-10-14)

From the GT 30 Pro's own session bundle (`DroidDeck-2026-10-14-03-steam.zip`, X6873 / MT6897 /
`Mali-G615 MC6`, Android 16, app 0.3.3, runtime r9), the guest's PanVK prints its kbase
capabilities. Side by side with the X6739's:

| | X6739 (G77, arch 9) | GT 30 Pro (G615, arch 11) |
|---|---|---|
| `PANVKDBG kbase uAPI` | `JM 11.38` | **`CSF 1.21`** |
| `PANVKDBG kbase props` | `js_present=0x7 jsf=20e,1c9e,1e … shader_present=0x660067` | **`js_present=0x0 jsf=0,0,0 num_cg=1 cg0=0x130013 shader_present=0x130013`** |
| WSI line | `uses_kbase=1 … sw_device=1` | same |
| driver in use | our JM build | our **JM** build |

`js_present=0x0` means the G615 exposes **no Job Manager slots at all**, and its kbase is CSF. Our
pinned guest driver (`panvk-linux-g57-1.2.0-p4`) is the Mali-G57 fork's **Job Manager** backend -
`app/src/main/rust/panvk-kbase-jm` is the JM one, and `release.env` says p4 "ports Valhall gen3
(arch 11) back into the fork" - so the arch is *enumerated* (gamescope selects
`Mali-G615 MC6`, `device.txt` shows the session running) while submission goes down the JM path the
GPU does not offer. That is a strong candidate for the GT 30 Pro's ping-pong, and it is *not* the
same bug as the X6739's (whose GPU does offer JM slots).

**Corrected 2026-10-14 (§18):** `kbase_stub=1` and `sw_device=1` are *not* evidence of a stub or
software device - the X6739's sessions print exactly the same two values (`art/dd01-session.log`,
`art/dd06-session.log`) while its GPU does offer JM slots, so they distinguish nothing.

**So the GT 30 Pro does not need a different pick from the set we publish - it needs a build we do
not publish.** Every released guest build is JM-family (`panvk-linux-24.3.0-p1..p13`,
`panvk-linux-g57-1.2.0-p1..p4`); there is no CSF guest build. `GpuInfo.PANVK_ARCHS =
{9, 10, 11}` therefore over-claims: it makes a G610/G615 session run PanVK with a JM backend, where
`SessionService`'s own fallback comment (`arch v10+ submits through CSF, which the kbase layer here
does not implement`) says it should not. Adding more driver options to the menus only helps once a
CSF build exists to select.

Open questions, not yet decided (they change behaviour, so they are not being guessed):

1. Until a CSF guest build exists, should arch 10/11 fall back to `llvmpipe` (honest, slow, and
   what `SessionService` already does when `panvkBackend` is false) instead of running PanVK on a
   submission path the GPU lacks?
2. Build the CSF guest driver from `FristOneRR-Panvk-Source` (§8a: `-Dpanfrost-kmds=kbase`, CI
   already stages kbase-CSF packages), then auto-pick it for arch 10/11 and keep the JM build for
   arch 9? That is what "use the driver this GPU should use" actually requires.

**Decided 2026-10-14:** keep arch 10/11 on PanVK as they are today (no move to `llvmpipe`), and
build the CSF guest driver later. What was done instead is §18 below.

## 18. Every published PanVK build is an option now, and the pick is by Mali generation (2026-10-14)

The GT 30 Pro's bundle (`DroidDeck-2026-10-14-03-steam.zip`) showed which build each half was given:
the display half `panvk-android-24.3.0` ("Mesa PanVK 1.2.0 (Mali-G57 fork, v9 JM kbase, AHB)",
`device.txt`), the guest half the runtime's pinned build. What each of those can actually drive is
now measured, not assumed:

| Build | Mali generations in it | Evidence |
|---|---|---|
| `panvk-android-24.3.0` (bundled, bionic) | **v9 only - no v11** | `strings -a libvulkan_panvk.so \| grep -c panvk_v11` = **0**, `panvk_v10` = 729 (v6/v7/v9/v10/v12/v13/v14 present). Committed 2026-10-13 10:17 +0700, before 0003 landed (21:06 +0700). |
| `panvk-linux-24.3.0-p1..p13` (glibc) | v9 | `tools/panvk/mesa-patches/` = `0002-kbase-support` + `0003-panvk-v9-jm`. |
| `panvk-linux-g57-1.2.0-p1..p3` (glibc) | v9 | the fork line before 0003. |
| `panvk-linux-g57-1.2.0-p4` and later (glibc, **pinned**) | v9 + **v11** | `tools/panvk-g57/build-panvk-g57.sh` fails the build unless `readelf -sW` finds both `panvk_v9_` and `panvk_v11_`; p4 was tagged 14:23:54Z, after 0003. |

So on a G615 the guest half has a v11 backend and the compositor half has none - and no build
covering v11 is published for the bionic half at all. The GT 30 Pro's ~3 fps is real and measured
(its `wayland.log` 10 s line: "30 frames on screen (3.0 fps) | 30 GPU frames from games", "30
scenes, 30 on screen" - the compositor presented every scene it was handed), but which layer loses
the time is still open; §17's JM-submission reading is a hypothesis, not a measurement.

What changed in the app (the "add all drivers as an option, and use the one this GPU should use"
work):

- `gpu/PanvkReleases.kt` (new): the fork's own releases (`ryanistr/DroidDeck-Mali`, `panvk-*.tzst`)
  as a download source for the Mali driver menus, the shape `TurnipReleases` has for Adreno - asset
  digest from GitHub, sha256 checked after download, cached check, installed through the same
  importers a picked file uses. Both halves are offered: `panvk-linux-*` to the runtime list,
  `panvk-android-*` to the display list.
- The arch table (`archsOf`, pinned by `app/src/test/java/com/droiddeck/launcher/gpu/PanvkReleasesTest.kt`):
  every row says which generations its build carries, and the ones carrying this GPU's are marked.
- `PanvkDriver.chooseByDevice()` picks by `GpuInfo.arch`: an imported build that declares the
  generation, then a build that stays silent, then the bundled one - and logs
  `no installed PanVK display driver carries a Mali vN backend` when nothing does. Hiding the
  bundled driver now means "system Vulkan" on Mali, as it already did on Adreno.
- Both importers record `gpuArch` per imported driver (`LinuxPanvkDriverManager`, `PanvkDriver`), so
  a downloaded or hand-picked build keeps its declaration; `SessionService` logs when the chosen
  guest driver was built for other generations than the device's.
- Mali fixes found on the way: deleting or restoring a driver went to the *Turnip* managers and did
  nothing; `PanvkDriver` had no `hideBundled`/`restoreBundled`; the Android import picker did not
  accept a `.tzst`.

Still open: a CSF submission path (`app/src/main/rust/panvk-kbase-jm` is the Job Manager one) and a
bionic build from the same tree as p4. Until then a G615's compositor has no PanVK build, and its
guest runs the only published build that carries v11.

## 19. A CSF fault in the build we ship, and the guest's missing render node (2026-10-14)

Sources read for this section, all on 2026-10-14:

| Tree | Revision | Why it was read |
|---|---|---|
| `emir34-34/DroidDeck` (`mediatek-mali-support`) | fork of `Droid-Deck/DroidDeck`, tip `229fe074`, device POCO X8 Pro / MT6899 / Mali-G720 MC8 / Android 16 | the Mali work with on-device Steam measurements |
| `wonderkast02/panvk-g720-kbase-csf` | `g720-development` @ `ca163891` | the kbase/CSF tree emir's driver is built from, and the one our fork is a cousin of |
| `BkaNeko/panvk-mtk-driver` | `594efbf` | bionic system-Vulkan replacement for a Mali-G610 under HyperOS; read for CSF queue/gralloc fixes |

### 19.1 The CSF provoking-vertex address truncation is compiled into our pinned driver, for arch 11

emir's `8f18a5ad` ("panvk: fix the real cause of Steam's UI freezing on Mali"), patch 0002, is one
line: `get_fb_descs()` in `csf/panvk_vX_cmd_draw.c` held `fns_bo->addr.dev` in a `uint32_t`. That
field is `uint64_t` (`panvk_priv_bo.h:23`), and on Android's kbase the addresses are SAME_VA and sit
above 4 GB, so the command stream frontend prefetched from a truncated address and every group
stopped: `CSF group N fatal error ... exception 0xc1` in the client log, dmesg `Unhandled Page fault
... source Command Stream Frontend, pref0`. Queue timeouts, `DEVICE LOST` and a client whose memory
grows without bound (Zink unable to recycle) all follow from that one fault. emir measured the fix on
a G720: 120 scroll/tap/back gestures in Big Picture over ~5 minutes, no fault, CEF's GPU process
steady at 0.45-0.7 GB.

**It is in the source our pinned release was built from.** At tag `v1.2.0` (the tag
`panvk-linux-g57-1.2.0-p4` was built from) the line is
`src/panfrost/vulkan/csf/panvk_vX_cmd_draw.c:1852: uint32_t fn_addr =`. It is still unfixed in
wonderkast02's own tree (`g720-development` @ `ca16389`, line 2449), so there was nothing to pull
from there: their issue #9 is emir's report.

**And that line is arch 11's, not a dormant one.** The fork's `meson.build` puts arch 11 in
`csf_archs` (`:115` — `[10, 11, 12, 13, 14]`) while `jm_archs` stays `[6, 7, 9]` (`:95`), so the
`panvk_v11_` backend that 0003 ports in is the **CSF** backend. `panvk_cmd_draw.h:299` guards the
whole provoking-vertex machinery on `PAN_ARCH >= 10`, so arch 9 never has this code in any build and
a G77 session cannot be affected by it either way.

**This corrects §17.** The GT 30 Pro is not being handed a Job Manager driver: on a G615 the pinned
build has no JM backend for that arch at all, so `js_present=0x0` costs nothing by itself. The
concrete CSF defect is this truncation, and the trigger is exactly what a G615 runs: a render pass
that starts before the provoking-vertex mode is known, which Chromium on Zink does on every pass
(Steam's CEF) and `vkcube` does not.

Fixed as `tools/panvk-g57/patches/0004-csf-fix-provoking-vertex-fn-address-truncation.patch`.

### 19.2 kbase reported no DRM device at all to a glibc build

emir's `8f260546` + `3d6ede4b` (their patch 0001): `panvk_physical_device_init_kbase()` left
`drm.render_rdev` and `drm.primary_rdev` zeroed, so `VkPhysicalDeviceDrmPropertiesEXT` named no
device and everything that matches a Vulkan device to a DRM node by device number silently failed to
match — gamescope's WSI among them, which is what decides zero-copy present against a copy.

In the runtime that number is knowable: proot presents the kbase node as the session's render node
(`tools/linuxfs/preload/drm.c:383` picks `/dev/mali0`, `:332` synthesizes a DRM device for it), so the
node's own `st_rdev` is the render node's number. This is §7.2 of `ping-pong-frame-handoff.md` —
"give the guest a real render node" — reached from the driver side instead of the device side.

Fixed as `tools/panvk-g57/patches/0005-kbase-report-node-rdev-as-drm-device.patch`, guarded
`#ifndef __ANDROID__` because the bionic half presents to a real Android surface and must keep
whatever the platform reports. That guard is the same one 0001 already uses in this patch set.

### 19.3 The compositor no longer asks for extensions the driver may not have

Our `dev_init()` (`app/src/main/cpp/waylandcomp/src/vk_present.c`) enabled five device extensions
unconditionally. A driver without any one of them fails `vkCreateDevice` outright — no session at all
— where leaving it out costs only the zero-copy path (clients fall back to `wl_shm`). emir hit this
with a system Mali driver that lacks `VK_EXT_image_drm_format_modifier`; the same shape of failure is
available to us the moment the display half is not a PanVK/Turnip build.

Applied: only `VK_KHR_SWAPCHAIN_EXTENSION_NAME` is required, each dma-buf extension is enabled if the
driver advertises it and logged if not, and a new `g_has_drm_modifiers` gates
`vkp_dmabuf_modifiers()` and `vkp_image_import_dmabuf()` so nothing offers a modifier list the device
was never created for. On Turnip all four are present and the behaviour is unchanged.
`aarch64-linux-android35-clang -fsyntax-only -Wall -Wextra` over the file: clean.

### 19.4 Read and deliberately not taken

- **BkaNeko's CSF queue fixes** — drop the userspace doorbell so every publish kicks the scheduler,
  and widen `KBASE_WAIT_TIMEOUT_NS` from 10 s to 120 s. The fork already does the first: after
  ringing the doorbell it re-reads `CS_ACTIVE` and falls through to `kbase_kmod_csf_queue_kick()` if a
  suspend raced the write (`csf/panvk_vX_gpu_queue.c:694-709`), which is the same mitigation, and it
  re-kicks every 500 ms while waiting (`:770-777`). Not taken; worth revisiting only if a CSF device
  still reports `timeout on subqueue` after 0004.
- **BkaNeko's MTK gralloc/AFBC work** (report the MTK AFBC modifier where gralloc says
  `DRM_FORMAT_MOD_INVALID`, AFBC body 4096 alignment, take the largest of MTK's three dma-buf fds):
  it targets a bionic system Vulkan driver feeding SurfaceFlinger on a G610, which is not the path
  this app takes — our bionic half is a PanVK build the compositor loads itself.
- **emir's app-side Mali work** (detection, the "explain instead of offering Adreno pairs" panel, the
  `build-panvk-kbase.sh` cross-build): our fork already covers this ground and differently — our
  display half is PanVK rather than the system Mali driver, and our own `tools/panvk-g57/` +
  `tools/panvk/` build scripts are what publish our builds.

### 19.5 The guest had no render node at all on a Mali — the device half of the ping-pong

Found while reading emir's `LinuxRuntime.java` hunk, and it is the strongest lead yet for the X6739's
own ping-pong rather than for a CSF device.

`LinuxRuntime.bindGpuNode()` fabricates a render node in the guest: it makes
`<cache>/drm/dri/renderD<minor>` plus the sysfs entries libdrm reads
(`/sys/dev/char/<maj>:<min>/device/{uevent,subsystem,driver}`, and `/sys/class/drm/<node>` where the
app may not list the real one), then binds them over the guest's `/sys/dev/char`, `/dev/dri` and the
node itself. It did this **only for KGSL** — `Os.stat("/dev/kgsl-3d0")` throwing returned early, and
a Mali has no KGSL, so on this device **the guest got no `/dev/dri/renderD*` and no sysfs entry to
describe it.**

That has a direct consequence in the driver. With no node, `tools/linuxfs/preload/drm.c` falls to its
synthesized device (`alloc_fallback_drm_device()`, `:332`), which sets `dev->bustype = -1` (`:346`).
Every WSI that asks whether the device can present directly then answers no:
`panvk_can_present_on_device()` (`panvk_wsi.c:38-49` in the fork) returns true only for
`DRM_BUS_PLATFORM`, and the guest's X11/DRI3 path calls it (`wsi_common_x11.c:172`). So the guest is
refused the DRI3 present path and falls back to MIT-SHM — which is exactly the state
`steam-ui-logo-pingpong-bug.md:32` describes as the ping-pong's mechanism (a two-frame X11 SHM
alternation), and it also explains the other gate in the same note, `kbase_dmabuf=0 → sw_device=1`.

Fixed here:

- `LinuxRuntime.bindGpuNode()` now falls back to `/dev/mali0` (constant `MALI_DEVICE`) when KGSL is
  absent, writes `DRIVER=panfrost` into `uevent`, points the `driver` symlink at
  `/sys/bus/platform/drivers/panfrost`, replaces a stale link from the other family, and binds the
  GPU node it actually chose. With the node present, libdrm builds the device itself from sysfs and
  reports `DRM_BUS_PLATFORM`, which is what the check above wants.
- `GpuStatsComponent.clockSources()` could not see a Mali's devfreq node either: it matches
  `kgsl`/`gpu`, and MT6893's node is `13000000.mali`. It now matches `mali` too, the way `GpuInfo`
  already does, so the session can feed the overlay's clock on a Mali at all.

This is emir's `bindGpuNode` change (their `8f260546`), which extended the same function to Mali; the
`GpuInfo`-style devfreq match is the same fork's `gpuClockSource()`. Both are app-side and need only
an APK.

### 19.6 Effect, and what is still required to ship it

0004 reaches every CSF arch in the pinned build (10, 11, 12, 13, 14); 0005 is glibc-only and affects
how the guest's WSI matches the presented render node; 19.5 gives the guest that node to begin with;
19.3 is in the compositor. Only the app-side changes (19.3, 19.5) are in this build.

**None of the four has been run on a device, and the two driver patches are not in a published
driver yet.** `tools/panvk-g57/patches/` is consumed by `.github/workflows/build-panvk-g57.yml`, so
the patches only change the *next* build of that line; `tools/panvk/release.env` still pins
`panvk-linux-g57-1.2.0-p4`, which carries neither 0004 nor 0005. To ship them: dispatch that workflow
(`version=1.2.0`, `tag=panvk-linux-g57-1.2.0-p5`), then set `PANVK_TAG` and `PANVK_SHA256` in
`tools/panvk/release.env` to the new asset.

What to measure, in order: (1) whether the guest session log now shows a `/dev/dri/renderD*` and
`PANVKDBG WSI: uses_kbase=1 … kbase_dmabuf=1` rather than `sw_device=1` (19.5, the ping-pong), on the
X6739; (2) whether `PANVKDBG` now prints non-zero DRM device numbers for the physical device (0005);
(3) the G615 on the GT 30 Pro for 0004, since the X6739's G77 submits through JM and is untouched by
it.

## 20. The runtime importer only knew one soname (GT 20 Pro report, 2026-10-14)

The GT 20 Pro bundle `DroidDeck-2026-10-14-11-steam.zip` (Infinix X6871, MT6896, **Mali-G610 MC6**,
arch v10, kbase **CSF 1.18**) reports one failure plainly: the PanVK build the tester wanted for the
runtime could not be imported at all, so the session fell back to the pinned driver.

### 20.1 What the report proves

`device.txt`:

```
GPU                     Mali-G610 MC6 · Mali Valhall · Experimental
Driver mode             manual
Display driver (chosen) PanVK_G720   Mesa 26.3.0-devel / PanVK G720 0.1.0-beta.2   (imported)
Linux driver (PanVK)    Mesa PanVK 1.2.0 (Mali-G57 fork, v9 JM kbase) 1.2.0 (glibc 2.35+)
```

`session.log`:

```
== uid=10352 size=1624x720 fps=0 icd=/usr/share/vulkan/icd.d/panvk_icd.aarch64.json
== vulkan driver: imported .../linux_panvk_drivers/Mesa_PanVK_1.2.0_Mali-G57_fork_v9_JM_kbase_/libvulkan_panvk.so
PANVKDBG kbase uAPI: CSF 1.18
PANVKDBG kbase props: js_present=0x0 jsf=0,0,0 num_cg=1 cg0=0x130013 shader_present=0x130013
PANVKDBG WSI: uses_kbase=1 termux_raw=0 raw_dri3=0 kbase_dmabuf=0 sw_device=1 raw_fd_modifier=0
```

`js_present=0x0` with `kbase uAPI: CSF 1.18` confirms the G610 is **CSF**, not JM, and that the
pinned `panvk-linux-g57-1.2.0-p4` does create a device on it (`CREATE_DEVICE AFTER_INIT result=0`).
The *display* half is a third-party PanVK (wonderkast02's G720 CSF fork, bionic) and imported fine.
The failure is specific to the runtime half.

### 20.2 Root cause: `LinuxPanvkDriverManager` matched one library name, and said "freedreno"

Upstream Mesa's PanVK soname is **`libvulkan_panfrost.so`**; this fork renames it to
`libvulkan_panvk.so` only for the runtime. The bionic importer keys off `meta.json`'s `libraryName`
and so accepted the G720 package; the glibc importer matched a hardcoded prefix instead:

- `LinuxPanvkDriverManager.java` — `LIB_NAME = "libvulkan_panvk.so"`, the zip path required
  `base.startsWith("libvulkan_panvk")`, the tzst path `find(tree, LIB_NAME)`.
- A PanVK build keeping the upstream name matched neither, so the import failed with
  `R.string.lvd_no_library` — the **Turnip** string, which reads
  *"No libvulkan_freedreno*.so in this zip…"*. That wording is what the report called "our app …
  expect[s] a freedreno.so instead of being able to import a panvk driver as a runtime driver".

Measured on the actual G720 package (`PanVK-G720-0.1.0-beta.2.zip`, sha256 `fc1d6964…`, from
`wonderkast02/panvk-g720-kbase-csf`): it contains exactly `libvulkan_panfrost.so` (21497392 B) and
`meta.json`, nothing named `libvulkan_panvk*`.

**Fix:** `isPanvkLibrary()` accepts `libvulkan_panvk*` and `libvulkan_panfrost*` (with an optional
`.so.1` suffix); both import paths use it, the library is copied in as `LIB_NAME` whatever it was
called, and the refusals are PanVK-worded (`lvd_no_panvk_library`, `lvd_panvk_not_glibc`). The bionic
tzst path got the same predicate. Pinned by `LinuxPanvkDriverManagerTest`.

### 20.3 The built-in driver against the release-page one (byte comparison, 2026-10-14)

The staged runtime driver (`app/src/main/assets/linuxfs/usr/lib/libvulkan_panvk.so`), the build
cache's copy, and the release-page asset `panvk-linux-g57-1.2.0.tzst` from tag
`panvk-linux-g57-1.2.0-p4` are **the same bytes**:

```
sha256 .scratch/releasepage/panvk-linux-g57-1.2.0.tzst
  dc6503e3e9aa649387e01022c6337276c6e150ca4093b05c07f3226fef97fd88   == PANVK_SHA256 in release.env
sha256 <staged>/usr/lib/libvulkan_panvk.so == <release-page>/usr/lib/libvulkan_panvk.so
  21871824 bytes, identical; meta.json identical byte for byte
```

So the apk's "built-in" runtime PanVK *is* the release-page p4 asset by construction (fetched by tag
and checksum). They are not two different builds; the only way to tell which build a device runs was
the driver's own name, which is [20.4].

Against a third-party bionic PanVK (the G720 CSF fork) the differences are the ABI and the platform
layer, not the GPU table — both are Mesa 26.3.0-devel and both list the same models
(G57/-G310/-G510/-G610/-G615/-G710/-G715/-G720/-G725/-G1-*):

| | our glibc build (p4) | wonderkast02 G720 (bionic) |
|---|---|---|
| NEEDED | `libc.so.6`, `ld-linux-aarch64.so.1`, `libdrm.so.2`, `libwayland-client.so.0`, `libxcb*`, `libxshmfence.so.1`, `libexpat.so.1`, `libz/zstd`, `libstdc++.so.6` | `libc.so`, `liblog.so`, `libhardware.so`, `libnativewindow.so`, `libsync.so`, `libdl.so`, `libm.so`, `libz.so` |
| SONAME | `libvulkan_panfrost.so` (file renamed `libvulkan_panvk.so`) | `libvulkan_panfrost.so` |
| Mesa | `26.3.0-devel (git-66306ebde5)` | `26.3.0-devel` |
| fd interop | `PANVK_FD_INTEROP`, `/dev/dma_heap/system` | `/dev/dma_heap/system` only (no gate string) |

Neither can stand in for the other: the bionic one cannot be `dlopen`ed by a glibc Steam client at
all, which is the check `adopt()` still makes (`libc.so.6` present, or it is refused).

### 20.4 The built-in driver now names itself — `PanVK-rynx-<version>-<patch>`

A driver that only says "Built-in" cannot be compared against anything.
`tools/panvk-g57/build-panvk-g57.sh` now takes `PANVK_G57_PATCH` (the tag's own suffix,
`panvk-linux-g57-1.2.0-p5` → `p5`), names the driver **`PanVK-rynx-<version>-<patch>`**
(e.g. `PanVK-rynx-1.2.0-p5`), sets `driverVersion` to `<version>-<patch>` and declares
`gpuArch: [9, 10, 11]` so an imported copy does not need the tag to be recognised.
`LinuxPanvkDriverManager.getBuiltinName()/getBuiltinVersion()` read that meta from
`<filesDir>/linuxfs/meta.json` (where `tar -xf` of the tzst leaves it), and the runtime-default row
in the driver list and the device report's `Linux driver (PanVK)` line show it.

The workflow derives the patch in its own step and exports `PANVK_G57_PATCH` through `$GITHUB_ENV`;
the release note uses the same code name.

### 20.5 What this does not yet do

The p5 build carrying 0004/0005/0006 (19.1, 19.2) is still only *prepared*: `release.env` pins p4.
Until that workflow is dispatched, a CSF device runs a driver with the provoking-vertex truncation
and the old merged-CSG behaviour, and `kbase_dmabuf=0 sw_device=1` remains the reason nothing
presents.

## 21. "Merge the CSF driver into rynx" — surveyed 2026-10-14

The ask was to clone the PanVK **CSF** driver and merge it into ours "so our driver rynx can support
both v9 JM and CSF dynamically without requiring to compile separately". Two separate questions are
hiding in that sentence, and they have different answers.

### 21.1 One build already carries v9 JM *and* the CSF backends

Nothing has to be merged for this half, and it is not a claim from reading the patch list — it is in
the build log of the workflow run that produced the pinned asset. `.scratch/p3run.log`, the arm64
runner's own output for the p3 build, compiles every arch's objects into the one library:

```
[937/1082] Compiling C object src/panfrost/vulkan/libpanvk_v12.a.p/...libpan_shaders_v12.c.o
[954/1082] Compiling C object src/panfrost/vulkan/libpanvk_v10.a.p/csf_panvk_vX_exception_handler.c.o
[963/1082] Compiling C object src/panfrost/vulkan/libpanvk_v12.a.p/csf_panvk_vX_cmd_buffer.c.o
[971/1082] Compiling C object src/panfrost/vulkan/libpanvk_v12.a.p/csf_panvk_vX_instr.c.o
```

and the meson that generates those lists is
`src/panfrost/vulkan/meson.build:159` — `foreach arch : [6, 7, 9, 10, 11, 12, 13, 14]`, with
`jm_archs = [6, 7, 9]` at :95, `csf_archs = [10, 11, 12, 13, 14]` at :115 and
`valhall_archs = [9, 10, 11]` at :87 (arch 11 is what 0003 adds). Every arch is a static library
(`libpanvk_v@0@`, :183) linked into the single `libvulkan_panfrost.so`, and PanVK picks the backend
from the GPU id at runtime. There is no "JM build" and "CSF build" to reconcile.

The on-device receipt is the GT 20 Pro report (2026-10-14, §20.1): a CSF-only G610
(`js_present=0x0`, `kbase uAPI: CSF 1.18`) reached
`PANVKDBG CREATE_DEVICE AFTER_INIT result=0` — that print lives in `panvk_vX_device.c`
`panvk_per_arch(create_device)`, so the v10 CSF backend was selected dynamically out of the same
binary that also contains v9 JM.

### 21.2 What is in `wonderkast02/panvk-g720-kbase-csf`, and what is not

The repository is **not** a Mesa fork. `gh repo view` gives "Experimental Mesa/PanVK for Mali-G720 on
Android Kbase/CSF", and a `--depth 1` clone is 988 KB / 49 files: docs, kbase UAPI headers,
standalone test programs (`test_gpu_write.c`, `test_gpu_copy_v2.c`), and **two file snapshots** of
the Mesa tree they build:

```
mesa-snapshot/src/panfrost/lib/kmod/kbase_kmod.c          1966 lines
mesa-snapshot/src/panfrost/vulkan/csf/panvk_vX_gpu_queue.c 3231 lines
patches/0001-panvk-kbase-g720-register-count.patch
```

Our tree's siblings of those two files are 2516 and 3141 lines and share their lineage: the same
`kbase_query_csif_info()` comment, the same `kbase_subqueue_emit_job()` / `kbase_subqueue_wait_seqno()`
/ `kbase_try_destroy_retired_heap()` decomposition. So this is not two implementations to fuse; it
is two divergences of one. Diffing theirs against ours (`diff -u`, then dropping the
logging-only lines) leaves **three** substantive items in their column:

1. `.cs_reg_count = (stream_features & 0xff) + 1` in `kbase_query_csif_info()`, where ours has a
   hardcoded `96`.
2. `.nr_kernel_registers = MAX2(csif_info->unpreserved_cs_reg_count, 4)` in
   `kbase_subqueue_emit_job()`'s `cs_builder_conf`, which ours omits there.
3. A `to_kbase_mem_flags()` helper where ours inlines the same flag computation.

Everything else in their column is unconditional `mesa_logi()`/`pandecode_*` diagnostics that ours
either already has or reaches behind `PANVK_DEBUG(KBASE_DIAG)`; ours is the quieter tree. And the
reverse direction is much larger: ours carries things theirs does not have at all, in particular the
**single merged CSG** (`kbase_kmod_csf_group_create(dev->kmod.dev, PANVK_SUBQUEUE_COUNT, ...)`, one
CSG holding every subqueue's CSI, with the destroy path moved to the queue) and the fence-wait
generation reclaim in `kbase_wait_sync_targets()` — i.e. 0006. Their bionic drop binary does not
have 0004's provoking-vertex fix either.

### 21.3 Merged: 0007, the register file sized from the uAPI

Of the three, items 1 and 2 are real defects in ours, so they are now
`tools/panvk-g57/patches/0007-csf-reported-register-count.patch`.

The register count: a comment in our `kbase_query_csif_info()` argued the stream-feature encoding is
unreliable (a Pixel 7 kbase 1.38 reports a work-register value below the file the CS compiler was
sized against) and hardcoded `96`. That is right for the under-reporting case and wrong for the
other one: this tree's own drm-shim says the file is not always 96 —

```c
src/panfrost/drm-shim/panfrost_noop.c:203
      csif_info->cs_reg_count = arch >= 12 ? 128 : 96;
```

— and a 96-register allocation under-runs a 128-register 5th-gen part, which is precisely the defect
`0001-panvk-kbase-g720-register-count.patch` fixes on a G720. The merge takes both behaviours
instead of picking one:

```c
      .cs_reg_count = CLAMP((stream_features & 0xff) + 1, 96, 128),
```

A stale under-report still floors at the 96 panthor assumes below 5th-gen, a 5th-gen report now
reaches 128, and a bogus high report cannot push the register allocator past the file (the
`assert(reg + size <= nr_registers - nr_kernel_registers)` in `cs_builder.h:437` is what a too-large
value would otherwise walk into).

The second half of 0007 is `kbase_subqueue_emit_job()`'s `cs_builder_conf`. Every other CS builder
in the tree sets `.nr_kernel_registers = MAX2(csif_info->unpreserved_cs_reg_count, 4)`
(`csf/panvk_vX_cmd_draw.c:103`, `csf/panvk_vX_exception_handler.c:124`, `csf/panvk_vX_utrace.c:245`,
`csf/panvk_vX_cmd_buffer.c:793`, and the file's own two other `cs_builder_conf` sites); this one did
not, and `cs_builder_init()` floors the field at 3 (`cs_builder.h:251`), so the allocator was free
to hand the stream one register the kernel clobbers.

Item 3 is deliberately not taken: `to_kbase_mem_flags()` is a pure refactor of code ours already
executes inline, and churning a file for style would make the next diff against either fork harder
to read.

### 21.4 What 0007 does not do

It does not advertise arch 12. `gpuArch` stays `[9, 10, 11]` in the driver's `meta.json`, and that
field is load-bearing: `PanvkDriver.autoSelect()` (`gpu/PanvkDriver.java:459`) returns the first
driver whose `gpuArch` contains the device's arch (`canDrive(id, arch)`), so adding 12 would hand a
G720-family device a driver no one has run on that hardware. The fifth-gen CSF backend is compiled
into the same binary and is now sized correctly, but "compiled and sized correctly" is not
"measured", so the claim stops where the measurements do.

Nor does it change the CSF session's remaining dead end: `kbase_dmabuf=0 sw_device=1` (§19.5,
§20.5) is still why a CSF device presents nothing, and that is a WSI question, not a register-file
one.

The patch was verified the way the build applies it: a `git worktree` at the pinned tag with
`tools/panvk-g57/patches/*.patch` applied in glob order, then `diff` against the two files this
change touches — identical. It has not been compiled: the driver only builds on an arm64 runner.
