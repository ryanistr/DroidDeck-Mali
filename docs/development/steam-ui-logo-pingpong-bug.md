# Bug Report: Steam UI Startup Latency + Frame Ping-Pong on Mali GT 10 Pro

**Filed against:** DroidDeck-Mali (fork of DroidDeck), targeting MediaTek MT6893 Mali-G77 MC9, Android 16, Infinix GT 10 Pro (X6739)  
**Reporter:** Hermes Agent (auto-generated from session)  
**Date:** 2026-10-13  
**Status:** Open (Quality of Service / Latency + Present Path)

---

## 1. Session Context

- **Device serial:** `102562537K104189` (X6739, MediaTek Dimensity 8050 / MT6893, Mali-G77 MC9, Android 16, slot `_a`)
- **APK baseline:** Rebuilt with CEF GPU fix (committed `b5c77f0d` + script edit removing `XWAYLAND_NO_GLAMOR=1` fallback), APK assembled via `./gradlew :app:assembleRelease` with `DROIDDECK_SKIP_RUST=1`.
- **Session:** `2026-10-13-20-steam`: `READY`, `firstFrame: True`.
- **Live command line:** `steam ... -cef-force-gpu -cef-use-gl=angle -cef-use-angle=vulkan` (from `droiddeck-session`'s auto case; no `BL_CEF_GPU` env override needed).
- **GPU utilization samples:** `45 0 55` → `0 0 100` → `10 0 90` (non-zero when UI active; was strictly `0 0 100` software rasterization before the CEF fix).

---

## 2. Defects (Remaining After CEF GPU Fix Deployment)

### Defect A: "Stuck at DroidDeck logo until dpad interaction"

- **Symptom:** After `firstFrameSeen` becomes true, the loading overlay (`SessionActivity.kt:407-415`) does not hide until the user presses the virtual dpad. Spamming dpad triggers UI redraw and Steam Big Picture appears.
- **Root cause:** CEF ANGLE-on-Vulkan initialization on Mali takes additional time beyond the `firstFrameSeen` threshold. Without dpad input, the UI never completes painting → overlay stays visible → user stays stuck at the DroidDeck logo.
- **Evidence:** Session state confirms `firstFrame: True` but overlay persists until dpad interaction. GPU util hovers at ~0% during this wait (software rasterization path still engaged during CEF init).
- **Impact:** Latency/initialization issue rather than a rendering failure. The CEF GPU fix is necessary but not sufficient to eliminate the init overhead on this hardware.

### Defect B: 2-Frame Ping-Pong (Frame 1 ↔ Frame 2 Alternating) Even at ~10 FPS

- **Symptom:** Once Steam UI is visible, frames alternate strictly between two states (e.g., sidebar-open ↔ sidebar-closed). The FPS counter may read ~10 FPS but the display actually shows a strict 2-frame alternation regardless of scene content or input.
- **Root cause:** The compositor's screen swapchain (`sc_layer.c:POOL_MAX 5`, `take_free_slot`) uses a round-robin pool that assigns consecutive frames to different slots. With the guest's WSI present path set to `X11_MITSHM` (`sw_device=1`, gamescope log: `wlserver: Failed to get DRM FD from renderer`), gamescope presents via X11 SHM copy — and the present logic alternates between the two most-recently-copied guest frames. This is not a CEF issue; it persists regardless of CEF GPU/software mode.
- **Evidence:** Earlier experiments confirmed `PANVK_AHB_WSI=1` left the gate unchanged (`sw_device=1`) and broke zink swapchain creation; `PANVK_KBASE_DRI3=raw` killed zink entirely (`zink: could not create swapchain`). Both were strictly worse than baseline. The ping-pong is intrinsic to the guest/compositor present path on this Mali + gamescope combination.

---

## 3. What Is Working (Durable Baseline)

- **Blank screen is fixed:** The root cause (`-cef-disable-gpu` forced on Mali) is resolved. The script edit (removed `XWAYLAND_NO_GLAMOR` fallback that forced software CEF) is in the APK asset and persists across sessions/installs. The source is committed in `b5c77f0d` and updated in `tools/linuxfs/overlay/usr/local/bin/droiddeck-session`; the APK carries the fixed script; a clean install (uninstall → install) preserves the fix because the session scripts are re-staged from APK assets on each session.
- **GPU utilized during UI:** `cat /sys/kernel/ged/hal/gpu_utilization` reports ~45–55% while Steam UI is active (was `0 0 100` with software CEF). This confirms the GPU path is active.
- **Session durability:** After `./gradlew :app:assembleRelease` → `adb install -r`, a fresh session starts `READY`/`firstFrame True` with `-cef-force-gpu` in the cmdline. No env override needed.

---

## 4. Tried and Rejected (Dead Ends)

| Experiment | Configuration / Command | Result |
|---|---|---|
| `PANVK_AHB_WSI=1` | Set in guest session env | Gate stayed `sw_device=1`; zink could not create swapchain → strictly worse. Glibc guest lacks `libandroid.so` / `AHardwareBuffer_*`. |
| `PANVK_KBASE_DRI3=raw` + mailbox | `PANVK_KBASE_DRI3=raw MESA_VK_WSI_PRESENT_MODE=mailbox` | Session ended `IDLE`, `firstFrame False`; killed zink clients (`zink: could not create swapchain`, `GLXBadCurrentWindow`). |
| Manual `XWAYLAND_NO_GLAMOR=0` | Session env | Not viable on Mali without working Xwayland glamor acceleration; removing the fallback check in `droiddeck-session` was the correct fix because ANGLE-on-Vulkan bypasses Xwayland GL directly. |
| droiddeckctl env knobs | Various compositor present modes | No measurable effect on ping-pong; out of scope for current build. |

---

## 5. Next Steps / Open Investigations

1. **Logo-stall optimization (Defect A):**
   - Accelerate CEF/ANGLE init on Mali so `firstFrameSeen` fires sooner.
   - Investigate pre-warming the Vulkan ICD, adjusting gamescope startup arguments, or adding a DroidDeck-side "wait-for-cef-ready" check before dropping the loading screen.
2. **Ping-pong mitigation (Defect B):**
   - The 2-frame alternation is in the guest WSI / X11 MIT-SHM present path.
   - Investigate gamescope `--vsync` handling.
   - Investigate compositor swapchain image count/config (`sc_layer.c:POOL_MAX`).
   - Investigate forcing a single-backbuffer present mode (may introduce tearing, but breaks the 2-slot oscillation).
3. **Defect interaction:**
   - Defect A and Defect B originate in different subsystems (CEF ANGLE/Vulkan initialization timing vs. gamescope + compositor MIT-SHM swapchain present slot cycling). Mitigating A does not solve B, and vice-versa.
