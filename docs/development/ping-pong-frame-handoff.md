# Handoff: Steam UI Startup Latency & Frame Ping-Pong Investigation (Mali GT 10 Pro)

**Date:** 2026-10-13  
**Target Device:** Infinix GT 10 Pro (`102562537K104189`), MediaTek Dimensity 8050 / MT6893, Mali-G77 MC9 (Valhall v9), Android 16  
**Repository Branch:** `main` at commit `b5c77f0d` (local APK built with `DROIDDECK_SKIP_RUST=1 ./gradlew :app:assembleRelease`)  
**Status:** In Progress — Defect A diagnosed; Defect B (2-frame ping-pong) definitively reproduced and isolated to display swapchain pipeline.

---

## 1. Executive Summary & Problem Statement

Two distinct defects affect Steam Big Picture startup on the MediaTek MT6893 Mali-G77 MC9 platform:

1. **Defect A: Stuck at DroidDeck Boot Logo Until Input (Logo Stall)**
   - **Symptom:** After session start, the display shows a static DroidDeck logo (`bigpicture_startup.webm`). It appears frozen until a controller input (D-pad or Button A/B) is dispatched, whereupon the Steam Big Picture UI suddenly becomes visible.
   - **Root Cause Discovered:**
     - The DroidDeck logo is Steam's configured startup movie (`root/.local/share/Steam/config/uioverrides/movies/bigpicture_startup.webm`, staged from `app/src/main/assets/steam-startup/droiddeck-startup.webm` by `SessionFiles.kt:224`).
     - The video is 6.35 seconds long. While it plays, Steam initializes CEF (`steamwebhelper` with `-cef-force-gpu -cef-use-gl=angle -cef-use-angle=vulkan`).
     - On Mali-G77, CEF takes 40–70s to compile Angle/Vulkan shaders and load `routes/login`.
     - When the 6.35s video reaches the end, CEF is not ready; Steam pauses on the video's last frame.
     - When CEF finally completes navigating `Steam Big Picture Mode` to `https://steamloopback.host/index.html...`, Steam does **not** automatically drop the movie layer. It waits for an input interrupt (D-pad or Button event) to clear the finished boot movie and transition to the active UI.

2. **Defect B: 2-Frame Ping-Pong (Strict Alternation Between Two Frames)**
   - **Symptom:** Once the Steam UI is displayed, the rendered output alternates strictly between two consecutive frames (e.g. Frame A: Sign-in dialog with NO keyboard $\longleftrightarrow$ Frame B: Sign-in dialog WITH keyboard open).
   - **Empirical Evidence (Session 28):**
     Four consecutive screenshots taken at 200ms intervals without user input:
     - `frame1.png`: md5 `29eda6680d8bf14845454d3e8713b940`
     - `frame2.png`: md5 `f22fb099b9fdc7e6bf01a52d003dd033` (Sign In UI, NO keyboard)
     - `frame3.png`: md5 `6eca3627476509e8f71288ac122a55ac` (Sign In UI, WITH KEYBOARD OPEN)
     - `frame4.png`: md5 `f22fb099b9fdc7e6bf01a52d003dd033` (Sign In UI, NO keyboard — **identical byte-for-byte to frame2!**)
     This proves the compositor / display swapchain is actively oscillating between two frame buffers at ~7–8 FPS.

---

## 2. Progress Count & What We Implemented / Verified

### A. CEF GPU Acceleration Baseline (Verified & Durable)
- CEF hardware acceleration script edit in `tools/linuxfs/overlay/usr/local/bin/droiddeck-session` is verified active in the APK assets.
- Steam starts with:
  ```sh
  steam -gamepadui -steamdeck -steamos3 -cef-enable-debugging -devtools-port 35892 \
    -clientbeta steamdeck_publicbeta -no-cef-sandbox -cef-force-gpu \
    -cef-ozone-platform=x11 -cef-use-gl=angle -cef-use-angle=vulkan
  ```
- GPU utilization measured at `/sys/kernel/ged/hal/gpu_utilization`: reports ~45–55% active during UI rendering (was strictly `0 0 100` software rasterization previously).

### B. Logo Dismissal Nudge Logic (Partially Working, Needs Refinement)
- Modified files (uncommitted in working tree):
  1. [`app/src/main/java/com/droiddeck/launcher/input/PadBridge.java`](file:///mnt/project/DroidDeck/app/src/main/java/com/droiddeck/launcher/input/PadBridge.java):
     Added `nudge()` method that pulses `PadState.A` (Button 0) down and up with a 120ms hold:
     ```java
     public synchronized void nudge() {
         if (!open && !start()) return;
         if (state.isDown(PadState.A)) return;
         state.press(PadState.A, true);
         publish();
         mainHandler.postDelayed(() -> {
             synchronized (PadBridge.this) {
                 if (state.isDown(PadState.A)) {
                     state.press(PadState.A, false);
                     publish();
                 }
             }
         }, 120);
     }
     ```
  2. [`app/src/main/java/com/droiddeck/launcher/SessionActivity.kt`](file:///mnt/project/DroidDeck/app/src/main/java/com/droiddeck/launcher/SessionActivity.kt):
     Scheduled `steamWakeNudges` to fire 8.0s after `firstFrameListener`, repeating every 2500ms up to 50 pulses (~125s).
- **Flaws Discovered with this Approach:**
  - **Issue 1:** `bridge.setOnPlayerInput { stopSteamWakeNudges("player pad input") }` at `SessionActivity.kt:388`: If the user touches the on-screen controls, or an agent sends input while waiting, nudges are cancelled permanently before CEF has finished compiling (~60s).
  - **Issue 2:** Pulsing Button A (`PadState.A`) selects whatever UI element happens to have focus when Steam finishes loading. On the initial sign-in screen, the focused element is "Account Name", which immediately triggers the on-screen keyboard. Pulsing D-pad Down / Up instead navigates focus without activating input fields.
  - **Alternative Solution:** In `SessionFiles.kt:ensureStartupMovieDefault`, consider whether the startup movie override can be omitted or replaced with a 0.1s blank animation on Mali, or if CDP can monitor target navigation and send a single safe D-pad nudge once `Steam Big Picture Mode` reaches `https://steamloopback.host/index.html`.

---

## 3. What Was Tried for Defect B (Ping-Pong) & Dead Ends

| Attempt / Knob | Configuration | Outcome / Why It Failed |
|---|---|---|
| `PANVK_AHB_WSI=1` | Guest session environment | Gate remained `sw_device=1`; zink failed to create swapchain (`zink: could not create swapchain`). Strictly worse. |
| `PANVK_KBASE_DRI3=raw` + `mailbox` | `PANVK_KBASE_DRI3=raw MESA_VK_WSI_PRESENT_MODE=mailbox` | Session crashed to `IDLE` with `GLXBadCurrentWindow`; zink clients died. |
| `droiddeckctl env` knobs | Compositor present modes | Did not resolve the 2-slot oscillation. |

---

## 4. Architecture & Root Cause Analysis of the Ping-Pong Effect

The display pipeline from guest to screen comprises three stages:

```
[Steam Client (mesa zink)]
       │
       │ (X11 MIT-SHM copy via PanVK WSI sw_device=1)
       ▼
[gamescope (Xwayland server :0 -> headless Wayland compositor)]
       │
       │ (wayland-0 dma-buf export, format XR24 linear)
       ▼
[DroidDeckWayland Compositor (libwaylandcomp.so)]
       │
       │ (sc_layer.c: take_free_slot from POOL_MAX 5 AHB buffers)
       ▼
[Android SurfaceFlinger (SurfaceControl layer)]
```

### Key Clues from Logs:
1. **Gamescope side:**
   - `[gamescope] [Error] wlserver: [types/wlr_drm.c:211] Failed to get DRM FD from renderer`
   - Gamescope uses headless backend and falls back to software / SHM WSI presentation for Xwayland clients:
     `PANVKDBG PRESENT CREATE_ENTER surface=0x3000f31cd0 format=44 extent=1600x720 minImages=3 mode=1 sw=1`
   - PanVK registers 3 `USERBUF` slots for SHM blits:
     - `sw_host_ptr=0x709157b000` (count=1)
     - `sw_host_ptr=0x709059b000` (count=2)
     - `sw_host_ptr=0x7090136000` (count=3)
   - When gamescope presents via X11 MIT-SHM, it flips between buffers.
2. **DroidDeckWayland side (`app/src/main/cpp/waylandcomp/src/sc_layer.c`):**
   - Gamescope exports dma-buf buffers to Wayland.
   - DroidDeckWayland receives the buffers:
     `DroidDeckWayland: [vulkan] "Steam Big Picture Mode" (gamescope) is presenting GPU frames through Wayland: 1600x720, format XR24, linear (dma-buf: copied into the screen swapchain)`
   - `sc_layer.c:take_free_slot` allocates from `POOL_MAX 5` slots using release fences from SurfaceFlinger.
   - If gamescope alternates between two buffers (Buffer 1 and Buffer 2), and DroidDeckWayland copies each incoming dma-buf into the layer pool, why does the displayed content show Frame A (stale) and Frame B (new)?
   - **Hypothesis A:** Gamescope itself is alternating between two front/back buffers where only one was updated by the X11 SHM client, or gamescope's damage tracking is not updating the second buffer.
   - **Hypothesis B:** PanVK WSI blit synchronization (`PANVKDBG WSI_BLIT_SHM`) in `mesa-panvk-mali-g57`: the SHM userbuf memory is read by gamescope before the previous copy completes, or zink presents to alternating SHM pixmaps where the damage region is partial.
   - **Hypothesis C:** `sc_layer.c` in `waylandcomp`: check whether `take_free_slot` or `vkp_pass_copy_to` / `vkp_blit_image` is retaining stale AHBs without clearing.

---

## 5. Next Steps for the Next Agent

1. **Verify if Ping-Pong is Inside Gamescope or Compositor:**
   - Run in guest: capture screenshot directly from gamescope or inspect gamescope's Wayland output:
     ```sh
     tools/droiddeckctl --serial 102562537K104189 guest -- grim /tmp/test.png
     ```
     (Or use an X11 screenshot tool on `:0`: `xwd -root -display :0` or `import -window root /tmp/x11.png`).
   - If the X11 / gamescope frame captured in the guest ALSO alternates, the ping-pong originates inside gamescope / zink / X11 SHM.
   - If the X11 / gamescope frame in the guest is STATIC (shows only the active screen), the ping-pong is inside `DroidDeckWayland` (`sc_layer.c` / `compositor.c`).

2. **Investigate Gamescope Present & Vsync Flags:**
   - In `tools/linuxfs/overlay/usr/local/bin/droiddeck-session`:
     Gamescope is launched with:
     ```sh
     gamescope --backend wayland --expose-wayland -c -f -W 1600 -H 720 -w 1600 -h 720 -r 60 -e --xwayland-count 2 --default-touch-mode 4 --mangoapp -- ...
     ```
   - Check gamescope flags: `--force-grab-cursor`, `--immediate-flips`, `--nested-unfocused-refresh`, `--vsync`. Test if `--immediate-flips` or disabling/enabling specific present modes affects the oscillation.

3. **Refine Logo Dismissal (Defect A):**
   - Replace Button A pulse with D-pad Down / Up in `PadBridge.java`:
     ```java
     // Pulse D-pad Down briefly instead of Button A to navigate without selecting
     state.down = true;
     publish();
     // ... release
     ```
   - In `SessionActivity.kt`, do NOT cancel `steamWakeNudges` on player input unless the Steam UI has already presented its first interactive frame (or verify via CDP target `Steam Big Picture Mode` url `!= about:blank`).
   - Alternatively, test removing `StartupMovie` from `config.vdf` in `SessionFiles.kt` to observe if Steam boots cleanly without the freezing boot video.
