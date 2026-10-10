# Mali GPU Support Handoff Document

## Overview
This document describes the minimal Mali GPU support implementation for DroidDeck (fork: DroidDeck-Mali). The goal is to enable Steam/gamescope sessions on Mali Valhall/Bifrost GPUs by switching the Mesa driver stack from Zink/Turnip (Adreno) to PanVK/Panfrost (Mali).

**Status**: Core Android-side GPU detection and driver selection implemented. Runtime PanVK, gamescope rebuild, and CI pipeline still needed.

---

## What Was Implemented

### 1. GPU Detection (`app/src/main/java/com/droiddeck/launcher/gpu/GpuInfo.kt`)

**Changes**:
- Added Mali families to `Family` enum:
  - `MALI_MIDGARD` (T8xx) — OpenGL ES only, **unsupported**
  - `MALI_BIFROST` (G5x, G71-G76) — Vulkan 1.0, experimental
  - `MALI_VALHALL` (G610, G715, G720) — Vulkan 1.1+, experimental
  - `MALI_UNKNOWN` — fallback
- Detection via:
  - `/sys/class/devfreq/*mali*` or `*gpu*` (non-KGSL)
  - `/vendor/lib64/hw/vulkan.panfrost.so` or `vulkan.mali.so`
- Model parsing from kernel names (`mali-g78`, `mali-t860`) and Vulkan device names (`Mali-G78`)

**Support classification**:
- `MALI_VALHALL` / `MALI_BIFROST` → `UNTESTED` (experimental)
- `MALI_MIDGARD` → `UNSUPPORTED` (no Vulkan)

### 2. Device Support Gate (`app/src/main/java/com/droiddeck/launcher/core/DeviceSupport.kt`)

**Added**:
```kotlin
fun mali(): Boolean = /* devfreq + vendor driver check */
fun supportedGpu(): Boolean = adreno() || mali()
```

Replaces hardcoded Adreno-only check in `MainActivity.kt`.

### 3. Session Environment (`app/src/main/java/com/droiddeck/launcher/session/SessionService.kt`)

**GPU-specific Mesa env vars**:
```kotlin
val isMali = gpuInfo.family in MaliFamilies
if (isMali) {
    MESA_LOADER_DRIVER_OVERRIDE=panvk
    GALLIUM_DRIVER=panfrost
} else {
    MESA_LOADER_DRIVER_OVERRIDE=zink
    GALLIUM_DRIVER=zink
}
LIBGL_KOPPER_DRI2=true  // both paths
```

**Linux driver resolution**: Uses `LinuxPanvkDriver.resolveIcdPath()` for Mali, `LinuxVulkanDriver` for Adreno.

### 4. Compositor Driver Selection (`app/src/main/java/com/droiddeck/launcher/SessionActivity.kt`)

```kotlin
val isMali = gpuInfo.family in MaliFamilies
val driverId = if (isMali) PanvkDriver(this).install() else TurnipDriver(this).install()
```

### 5. PanVK Driver Stack (New Files)

| File | Purpose |
|------|---------|
| `LinuxPanvkDriverManager.java` | Imports glibc PanVK zips → `files/linux_panvk_drivers/<id>/` with ICD manifest |
| `LinuxPanvkDriver.java` | Runtime driver selector (parallel to `LinuxVulkanDriver`) |
| `PanvkDriver.java` | Android compositor driver — uses system Mali Vulkan or imported PanVK |

### 6. Device Report & UI Strings
- `DeviceReport.kt`: Reports Mali driver info
- `strings.xml`: Added `gpuinfo_mali_experimental`, `gpuinfo_mali_midgard_unsupported`, updated `gpuinfo_unsupported`

---

## Files Changed / Created

### Modified
```
app/src/main/java/com/droiddeck/launcher/gpu/GpuInfo.kt
app/src/main/java/com/droiddeck/launcher/core/DeviceSupport.kt
app/src/main/java/com/droiddeck/launcher/session/SessionService.kt
app/src/main/java/com/droiddeck/launcher/SessionActivity.kt
app/src/main/java/com/droiddeck/launcher/core/DeviceReport.kt
app/src/main/java/com/droiddeck/launcher/MainActivity.kt
app/src/main/java/com/droiddeck/launcher/DriverMenus.kt
app/src/main/res/values/strings.xml
```

### Created
```
app/src/main/java/com/droiddeck/launcher/gpu/LinuxPanvkDriverManager.java
app/src/main/java/com/droiddeck/launcher/gpu/LinuxPanvkDriver.java
app/src/main/java/com/droiddeck/launcher/gpu/PanvkDriver.java
```

---

## What Still Needs Work

| Component | Status | Effort |
|-----------|--------|--------|
| **Runtime PanVK (glibc)** | ❌ Not built | Build Mesa with `panvk` for arm64 glibc |
| **gamescope with PanVK** | ❌ Not built | Rebuild gamescope against PanVK |
| **Android PanVK (bionic)** | ❌ Not built | Cross-compile Mesa for bionic |
| **Driver release CI** | ❌ Missing | `.github/workflows/build-panvk.yml` |
| **Driver bundle format** | ❌ Missing | DD-Turnip-style bundles with both halves |

---

## Build & Test Instructions (Run on Build Machine)

### Prerequisites
- Android SDK/NDK (API 28+)
- `rustup` + `cargo-ndk` (for Rust stores, or `DROIDDECK_SKIP_RUST=1`)
- Docker (for `tools/build_local.sh`)
- adb device with Mali GPU (Valhall/Bifrost), root access

### 1. Build APK
```bash
cd /path/to/DroidDeck

# Full build (matches CI, uses Docker)
DROIDDECK_SKIP_RUST=1 tools/build_local.sh

# OR Gradle only (faster if deps cached)
./gradlew :app:assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

### 2. Install on Device
```bash
tools/deploy_local.sh app/build/outputs/apk/debug/app-debug.apk
# OR
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3. Test on Device
```bash
# GPU detection
adb shell droiddeckctl gpu

# Start Steam session, follow logs
adb shell droiddeckctl start steam
adb shell droiddeckctl logs -f

# Check session state
adb shell droiddeckctl state
```

### 4. Expected Success Logs
```
GPU: Mali detected (Mali Valhall), using PanVK/Panfrost
MESA_LOADER_DRIVER_OVERRIDE=panvk
GALLIUM_DRIVER=panfrost
vulkan driver: imported <path> (or) using the runtime's own
```

### 5. If Black Screen — Debug Inside Guest
```bash
# When session is running
adb shell droiddeckctl guest 'vulkaninfo | grep -i "device\|driver\|API"'
adb shell droiddeckctl guest 'glxinfo | grep -i "opengl\|renderer"'
adb shell droiddeckctl guest 'ls -la /usr/lib/libvulkan_panvk.so'
```

---

## Device Inspection (Run Now on Your Device)

```bash
# Mali kernel model
adb shell cat /sys/class/devfreq/*/device/of_node/compatible 2>/dev/null | head -5

# Vendor Vulkan drivers
adb shell ls -la /vendor/lib64/hw/vulkan.*mali* /vendor/lib64/hw/vulkan.*panfrost* 2>/dev/null

# Vulkan driver info
adb shell vulkaninfo 2>/dev/null | grep -A5 "deviceName\|driverName\|driverVersion"

# Kernel Mali logs
adb shell dmesg | grep -i mali | head -10

# GPU freq nodes
adb shell ls -la /sys/class/devfreq/ | grep -i mali
```

---

## Reference Projects for Runtime Builds

### 1. Mesa PanVK (glibc) — Primary Reference
- **Repo**: https://gitlab.freedesktop.org/mesa/mesa
- **CI**: `.gitlab-ci.yml` → `arm64` jobs build `panvk` + `panfrost`
- **Build**:
  ```bash
  meson setup builddir -Dvulkan-drivers=panvk -Dgallium-drivers=panfrost,kmsro \
    -Dplatforms=wayland,x11 -Dbuildtype=release -Dprefix=/usr
  ninja -C builddir
  # Driver: builddir/src/panvk/libvulkan_panvk.so
  ```

### 2. gamescope — Primary Reference
- **Repo**: https://github.com/ValveSoftware/gamescope
- **Tag**: `3.16.29` (matches DroidDeck runtime)
- **Patches**: Apply from `tools/gamescope/patches/` (already in repo)
- **Build**:
  ```bash
  meson setup builddir -Dpipewire=disabled -Dudev=enabled
  ninja -C builddir
  # Binary: builddir/gamescope
  ```

### 3. PanVK Android (bionic) — Reference
- **Mesa Android build**: https://gitlab.freedesktop.org/mesa/mesa/-/blob/main/.gitlab-ci.yml (search `android`)
- **Alternative**: https://github.com/panfork/mesa (fork with better Android support)
- **Key**: Cross-compile with NDK targeting bionic, output `libvulkan_panvk.so` + ICD

### 4. Driver Bundle Format (DD-Turnip style)
- **Reference**: `tools/release/update_catalog.py` + `DriverBundle.kt`
- **Structure**: One zip with `manifest.json` + `android/` + `linux/` directories
- **Android half**: `meta.json` + `libvulkan_panvk.so` (bionic)
- **Linux half**: `meta.json` + `libvulkan_panvk.so` (glibc) + `icd.json`

---

## Next Agent Tasks (Priority Order)

1. **Build Runtime PanVK** — Create `tools/panvk/build-panvk.sh` using Mesa CI as reference
2. **Build gamescope with PanVK** — Update `tools/gamescope/build-in-arch.sh` to link PanVK
3. **Create CI workflow** — `.github/workflows/build-panvk.yml` modeled on `build-gamescope.yml`
4. **Test on device** — Iterate until Steam/desktop launches without black screen
5. **Add driver bundle support** — Extend `DriverBundle.kt` for PanVK paired bundles

---

## Key Code Paths to Understand

| Path | What It Does |
|------|--------------|
| `SessionService.addClientEnvironment()` | Sets Mesa env vars per GPU |
| `SessionActivity.onSurfaceCreated()` | Picks PanvkDriver vs TurnipDriver |
| `LinuxPanvkDriverManager.installDriver()` | Imports glibc PanVK zip |
| `PanvkDriver.install()` | Picks system Mali or imported PanVK for compositor |
| `GpuInfo.detect()` | Detects Mali via devfreq + vendor driver |

---

## Device Info (for reference)

**Your device** (connected via adb 24/7, root):
- GPU: Mali (Valhall or Bifrost — check `adb shell droiddeckctl gpu`)
- Android: Custom ROM (check `adb shell getprop ro.build.version.release`)
- Kernel: GKI 5.10+ (check `adb shell uname -r`)

---

## Contact / Context

- **Repo**: `/mnt/project/DroidDeck` (this workdir)
- **Branch**: `main` (fork will be `mali-support`)
- **Original project**: DroidDeck (Adreno-only)
- **Fork goal**: DroidDeck-Mali with full PanVK stack

---

## Quick Commands Cheatsheet

```bash
# Build
DROIDDECK_SKIP_RUST=1 tools/build_local.sh

# Install
tools/deploy_local.sh app/build/outputs/apk/debug/app-debug.apk

# Test
adb shell droiddeckctl gpu
adb shell droiddeckctl start steam
adb shell droiddeckctl logs -f

# Debug guest
adb shell droiddeckctl guest 'vulkaninfo'
adb shell droiddeckctl guest 'ls -la /usr/lib/libvulkan_panvk.so'

# Device inspect
adb shell cat /sys/class/devfreq/*/device/of_node/compatible
adb shell vulkaninfo | grep -i mali
```