# GameHub's Mali graphics path vs DroidDeck-Mali

Source: reverse-engineering dump at `/mnt/project/Misc/ReverseEngineer/GameHub/` (GameHub 6.3.1,
`com.xiaoji.egggame`, plus a live `log_pcengine.txt` captured on an **MT6893 / Mali-G77 MC9** device —
the same SoC as ours). Every claim below cites a file, a symbol, or a log line; anything inferred is
marked *hypothesis*.

The question this answers: GameHub runs Wine + D3D games on Mali-G77 today. What is it actually doing,
and which part of it can this fork transplant?

## 1. The stack, bottom-up (verified)

```
Windows game (.exe, x86/x64)
  └─ Proton 11.0 arm64x  (wine_proton11.0-arm64x-9.16, aarch64-windows + i386-windows + arm64x)
       └─ d3d11.dll  = DXVK 1.10.3 (async)   "dxvk-v1.11.1-mali-fix" variant also present
            └─ Vulkan loader
                 └─ ICD = libGameScopeV2.so   ← their own bionic shim, NOT a GPU driver
                      └─ dlopen("vulkan." + ro.hardware.vulkan + ".so")
                           └─ /vendor/lib64/hw/vulkan.mali.so   ← ARM's stock vendor blob
```

Evidence for each hop:

- `VK_ICD_FILENAMES` is set to the shim manifest, in code:
  `jadx2/sources/p000/i320.java:312-314`
  ```java
  String path22 = new File(file2, "share/vulkan/GameScopeVK_icd.json").getPath();
  l8bVar.m9282a("VK_ICD_FILENAMES", path22);
  ```
- `libGameScopeV2.so` (device dump, 1,673,504 B) **is a Vulkan ICD**: it exports
  `vk_icdGetInstanceProcAddr`, `vk_icdGetPhysicalDeviceProcAddr`,
  `vk_icdNegotiateLoaderICDInterfaceVersion`, and contains the property lookup and the dlopen format
  string:
  ```
  ro.hardware.vulkan
  vulkan.{}.so
  ```
  It is also where the Mali SPIR-V fixup passes live:
  `N5spirv8rewriter28MaliCompositeConstantFixPassE`, `N5spirv8rewriter26MaliArithmeticShiftFixPassE`
  (plus `RemoveClipDistancePass` / `RemoveCullDistancePass` per the existing doc).
- The ICD is **bionic/NDK-built**: `readelf -d` NEEDED = `libandroid.so, liblog.so, libEGL.so,
  libGLESv2.so, libGLESv3.so, libX11.so, libxcb.so, libxcb-dri3.so, libxcb-present.so, libvulkan.so,
  libm.so`; `file` says `built by NDK r27`. So is the whole native set: `gamescope-wm` (interpreter
  `/system/bin/linker64`), `libwinemu.so`, `libvfs.so`, `libxserver.so`, `libgpuinfo.so` — all NDK r27/r28/r29.
- The GPU the stack gets is the vendor blob, reported to their backend as
  `gpu_device_name=Mali-G77 MC9&gpu_vendor=ARM&gpu_system_driver_version=134221824`
  (`log_pcengine.txt:295`). 134221824 = `0x8000400` = the vendor blob's own version encoding.
  On our device `ro.hardware.vulkan=mali` and `/vendor/lib64/hw/vulkan.mali.so` exists, so the same
  dlopen resolves here.

**There is no rebuilt driver anywhere in the handheld path.** The only bundled Mesa driver in the dump
is `libvulkan_freedreno.so` (SONAME `vulkan.adreno.so`, debug-symbol build) — that is the Adreno path,
irrelevant on Mali.

## 2. The environment their launcher sets (`i320.java`, two branches)

Always set (both branches): `DXVK_ASYNC=1`, `DXVK_LOG_LEVEL=warn`, `DXVK_STATE_CACHE_PATH=<prefix>/dxvk-cache`,
`WINEESYNC=1`, `WINEDEBUG=-all`, `WINELOADERNOEXEC=1`, `PROTON_DISABLE_LSTEAMCLIENT=1`,
`PROTON_DISABLE_LSTEAMCLIENT`, `DISPLAY=:0`, `MESA_VK_WSI_PRESENT_MODE=mailbox`,
`MESA_SHADER_CACHE_DISABLE=false`, `MESA_SHADER_CACHE_MAX_SIZE=512MB`, `mesa_glthread=true`,
`GALLIUM_DRIVER=zink`, `ZINK_DESCRIPTORS=lazy`, `ZINK_DEBUG=compact`,
`VKD3D_FEATURE_LEVEL=12_1`, `VKD3D_SHADER_MODEL=6_6`, `VKD3D_DEBUG=none`.

`GALLIUM_DRIVER=zink` is the important one: **GL is not used directly — it is funnelled into Vulkan**
(`i320.java:357-366` and again `:548-557`). Nothing in either branch references Panfrost, panvk, or
Mesa's Mali GL driver.

The branch is selected by `config.m` = a `GPUDriver` object (`i320.java:320`, `:395-458`):

| GPUDriver value | Env produced | Mobile relevance |
|---|---|---|
| none | nothing | the default, and what the Mali log shows |
| a path to a **file** | `GAMESCOPE_DRIVER_PATH`, `GAMESCOPE_VULKAN_DRIVER_PATH`, **`TU_DEBUG=sysmem,noconform`** | Turnip/freedreno tuning — Adreno only |
| a path to a **directory** | `WINEMU_REPLACED_DRIVER=<dir>` (replaces the whole in-container driver directory) | generic |

So on Mali the driver knobs are simply not used — the ICD shim plus the vendor blob is the whole plan.
`GAMESCOPEVK_PROFILE=1` / `_THRESHOLD_MS=1` appear in both custom-driver paths only.

## 3. Component versions they pin (`log_pcengine.txt`)

`Firmware-1.5.1 (imagefs.zst)`, `proton11.0-arm64x` = `wine_proton11.0-arm64x-9.16.tar.zst`,
`dxvk-1.10.3` (`9924b076c98284cc8847cbf299fbeb5b`, the `-async` build, their `pc_d_dxvk`), `vkd3d-2.12`
(`4f3e99438281f7749cb2b8b2412c9d10`), `Fex_20260913`, `SteamAgent2-1.0.7`, `goldberg-1.2`.
The server catalog (`sp_winemu_unified_resources.xml`) also carries `Fex_20260813`, `Fex_20260921`,
`Box64-0.31-b1` — so FEX/box64 are swappable components, and 16.9 MB each, i.e. real emulators.

## 4. The "mali-fix" DXVK build — what it is and is not

Four d3d11.dll builds were A/B'd in `dxvk_ab/`:

| build | size | md5 |
|---|---|---|
| stock DXVK | 3,198,990 | `0ea4a57eef9cae03e69a6a3dbe87f84c` |
| GameHub v1.11.0 async | 3,387,406 | `9cd43edc6ec00282092f7a6a7ea8bc53` |
| **dxvk-v1.11.1-mali-fix** | **3,383,310** | **`72a541d706e9cbaa6bdceaea1f65bacf`** |
| DXVK-Sarek v1.11.0 (x32) | 3,633,166 | `aad48f952c9907caafae0d8cea01859c` |

The Mali build is a genuinely distinct binary. But its **string** surface shows no Mali-specific config
keys: its option set is the general 1.11 set plus `dxgi.hideAmdGpu/hideIntelGpu/hideNvidiaGpu`,
`dxgi.customDeviceId`, `dxgi.maxDeviceMemory` — identical additions to the GameHub async build. The only
per-executable special case in any of them is `rs3.exe`. The one occurrence of the literal `mali` in the
binary is not a workaround string.

Conclusion: **the Mali-specific behaviour is code-level** (draw-time shader/format workarounds applied at
the device level, plus per-game handling), not a config profile we could copy. It has to be found by
binary diffing/RE if we ever want that half; the ICD-level fixups (§1) are the part that is nameable and
reproducible today.

## 5. Presentation path (unchanged from the earlier doc, for completeness)

"GPU passthrough" is not a driver switch: the guest frame becomes an `AHardwareBuffer` and is imported
into Vulkan for a direct present, vs a POSIX-shm memcpy when off; toggled by
`XServer.setGpuPassthroughEnabled(boolean)`, observable as
`x11vk heartbeat: passthrough=1 kind=user … directPresents=2387 (+63)`.

## 6. How a glibc Wine talks to a bionic ICD — the part worth stealing

They do **not** recompile Wine against bionic, and they do not use proot. They bridge with the same
trick this fork already uses elsewhere — an `LD_PRELOAD` shim that translates paths and identity:

```
LD_PRELOAD      = <plugin>/lib/arm64-v8a/libvfs.so : <container>/usr/lib/libsandboxfs.so
WINE_LD_PRELOAD = same
WINE_VFS        = <plugin>/lib/arm64-v8a/libvfs.so
```

`libvfs.so` maps the container's expected `/data/data/com.winemu/…` paths and is preloaded into every
container process. The ICD manifest points *into that virtual namespace*:
`"library_path": "/data/data/com.winemu/files/usr/lib/libGameScopeV2.so"`. So the bionic ICD is loaded
into a Wine process (whose Windows side is an ABI-translated PE) because a shim makes the path and the
allocator handoff work. Their own natives all being bionic is what lets the AHardwareBuffer import and
the vendor Vulkan blob (which is also bionic and talks to gralloc) succeed.

Mapping to this fork: `tools/linuxfs/preload/*.c` → `libblsession.so` **is** our version of `libvfs.so`,
`libsandboxfs.so` (bwrap) is already in our tree, and our compositor (`libdroiddeckwayland.so`) is
already built bionic via CMake. The missing piece is only that nothing on our side *is* an ICD, and
`/vendor` is not reachable from the guest.

## 7. What this fork is missing to do the same (concrete)

1. **No `/vendor` bind.** `LinuxRuntime`'s bind list is `/dev, /proc, /sys, /dev/urandom, /proc/self/fd…,
   /sys/fs/selinux, filesDir, cacheDir, runtimeDir, sessionRoot, externalStorage, /dev/shm, fonts`.
   `/vendor/lib64/{hw,egl}` — where `vulkan.mali.so`, `libGLES_mali.so` and the gralloc/ion libs live —
   is never bound, so the vendor blob is unreachable even though it exists on device.
2. **No ICD of ours.** We ship `panvk_icd.aarch64.json` only. A `GameScopeVK` equivalent — a bionic
   `libvulkan_*`-style shim whose ICD entry points dlopen `vulkan.$ro.hardware.vulkan.so` — does not
   exist in this tree.
3. **`bindGpuNode()` is Adreno/KGSL-only** (`Os.stat(KGSL_DEVICE)` → early return on Mali), and the
   `drm.c` preload synthesises a DRM node for `/dev/mali0`. Under the vendor-blob plan the shape changes:
   the blob wants `/dev/mali0` (kbase), which we already have — it does not want a DRM render node.
4. **PanVK remains v9-only and JM-only** (`PANVK_ARCHS = {9}`; `jm_archs=[6,7,9]`, `csf_archs=[10]`; the
   Rust shim exposes BO/get_props/submit and no CSF). The vendor blob has none of those limits: it drives
   v9 through v12 (Valhall → 5th-gen) on any MediaTek Mali part, **including the tester's G615 (v11)**,
   which is exactly the device our PanVK path cannot serve.

## 8. Recommendation

Treat the two as parallel paths rather than competitors:

- **Product path (fastest to a rendering Mali session, all Mali generations):** vendor blob + a bionic
  shim ICD + `/vendor` + `/dev/mali0` binds, DXVK (their Mali build or stock) + zink for GL, BCN
  emulation. This is what makes the GT 30 Pro (G615/v11) work, and it is the mechanism a shipping app
  uses. It does *not* use this fork's Rust kbase backend at all.
- **Fork path (the mission):** keep PanVK + the Rust kbase JM backend as the from-source driver, and
  finish it: publish a driver that actually contains v9 code, implement `get_props` (still a
  `// TODO: Fill props from kbase` stub — the likely reason our G77 reports GPU ID `0x9091` = G57 when
  the hardware is `0x9080`), then add CSF for v10+.

The honest read of the evidence: GameHub "renders 3D beautifully on MT6893" because it never left ARM's
driver. Our fork's premise — replacing that driver — is a much longer road, and the vendor-blob path is
what will make the tester's device useful in the meantime.

## Not verified

- Our runtime's Mesa build actually providing zink (GL→Vulkan) — not checked in this pass.
- Whether our gamescope/compositor can consume the shim ICD's present path, or whether we would be
  duplicating an `x11vk`-style direct-present we already have.
- The code-level deltas inside `dxvk-v1.11.1-mali-fix` (needs a binary diff, not done).


---

## Update 2026-10-12 — the v9 build has never compiled, and upstream has no v9 JM backend

Measured this session, locally reproduced rather than inferred.

### 1. `0003-panvk-v9-jm.patch` does not build — CI has been red since it landed

Applying `0002` + `0003` to a pristine Mesa `mesa-24.3.0` (`f1f246c`, extracted with
`git -C mesa archive f1f246c`) and building the `panvk_v9` static library:

```
ninja -C builddir -k 0 src/panfrost/vulkan/libpanvk_v9.a
FAILED OBJECTS (19)   UNIQUE ERRORS (115)
```

Every published PanVK release is therefore **p13 (2026-10-11T12:14Z)**, which predates both patches
(`0002` mtime 14:43Z, `0003` 15:21Z). `gh run list -R ryanistr/DroidDeck-Mali --workflow=build-panvk.yml`
shows `success` only at 12:14/12:18Z that day and `failure` for every run since — including the two on
`1e51bd13` and the one on `2d2cd010`.

The two errors CI reports are the tip only:

- `panvk_vX_cmd_desc_state.c` — the patch guards `CmdPushDescriptorSet2KHR` (L404-405, L412-413) but
  **not** the identical memsets in `CmdBindDescriptorSets2KHR` (L354-355, L362-363) or
  `CmdPushDescriptorSetWithTemplate2KHR` (L447-448, L450-451).
- `panvk_vX_cmd_fb_preload.c` — the TSD block is inserted into `get_preload_shader()`, which has no
  `cmdbuf`; the `cfg.shader.thread_storage` read at L650 is in a different function.

With `-k 0` the real scope appears: 19 objects / 115 errors. `panvk_vX_cmd_draw.c` and
`panvk_vX_cmd_dispatch.c` alone fail on ~90 symbols (`MALI_DRAW`, `MALI_RENDERER_STATE`,
`MALI_VIEWPORT`, `MALI_ATTRIBUTE_BUFFER`, `MALI_INVOCATION`, `MALLOC_VERTEX_JOB`,
`INDEXED_VERTEX_JOB`, `MALI_PRIMITIVE`, `PANVK_BIFROST_DESC_TABLE_*`, `pan_shader_prepare_rsd`,
`panfrost_pack_work_groups_compute`, `job_task_split`, `first_provoking_vertex`).

### 2. Those symbols do not exist for v9 in this Mesa

`src/panfrost/lib/genxml/v9.xml` has **50 structs and no** `Draw`, `Renderer Properties`,
`Viewport`, `Attribute Buffer`, `Invocation`, `Malloc Vertex Job` or `Indexed Vertex Job`. It models
the Valhall **CSF** (compute-stream) command stream: `Shader Program`, `Scissor`, `Local Storage`,
`RT Buffer`, `Framebuffer Parameters`, `Render Target`, `Tiler Heap`. `v10.xml` (89 structs) adds the
`CS *` instruction set. The JM/Bifrost packers the sources want (`Draw`, `Renderer Properties`,
`Primitive.first_provoking_vertex`, `Invocation.job_task_split`, …) exist only in `v7.xml` (55
structs).

### 3. Upstream Mesa has no v9 JM backend at all

`src/panfrost/vulkan/meson.build` on **mesa main** still reads:

```
bifrost_archs = [6, 7]
valhall_archs = [9, 10, 11]
jm_archs      = [6, 7]
csf_archs     = [10, 11, 12, 13, 14]
foreach arch : [6, 7, 10, 11, 12, 13, 14]
```

So `jm_archs = [6, 7]` in every released Mesa: **v9 is CSF-only upstream and PanVK does not implement
v9 CSF in any release.** Adding `9` to `jm_archs` drags the Bifrost JM draw/dispatch/queue layer into a
v9 build whose packers cannot describe v9 jobs — that is exactly what `0003` attempts, and why it
fails.

`0002` changes the local tree to `jm_archs = [6, 7, 9]` / `foreach [6, 7, 9, 10]` (patch L578-588) —
that hunk is the source of the red CI, not `0003` as previously believed.

### 4. What the shipped driver actually contains

Per-arch symbols in `libvulkan_panvk.so` (identical in the bionic asset
`assets/graphics_driver/panvk-android-24.3.0.tzst` and the glibc half staged at
`assets/linuxfs/usr/lib/`, i.e. p13):

```
v6: 28   v7: 28   v9: 1   v10: 27   v11: 0
```

`v9: 1` is the entry-point table only — no real v9 code. Consistent with that, the guest dies at
`[gamescope] [Error] vulkan: failed to find physical device` before any draw (`vulkaninfo` therefore
never reached the job-submit path, which is why bring-up showed no job errors).

### 5. Consequence for the Rust kbase fix

`app/src/main/rust/panvk-kbase-jm/src/lib.rs` hardcoded `0x9091` (G57) while the hardware is `0x9080`
(G77) — now fixed to parse the kernel's `GET_GPUPROPS` buffer (`id = key >> 2`, value width = `key & 3`,
`RAW_GPU_ID = 55`). The device's `gpuinfo` reports `0x9080`, and the running kernel populates both
`prop_buffer` and `prop_buffer_size` (`kbase_api_get_gpuprops`, `mali_kbase_core_linux.c:857-882`), so
the ioctl is the right source.

But this fix cannot make v9 work on its own: it corrects *which* GPU Mesa believes it has. With
`PANVK_KBASE_GPU_ID=0x9080` forced on device the session still failed (same `failed to find physical
device`, then SIGSEGV), because v9 has no backend either way.

### 6. The fork's real fork in the road

Making PanVK drive a v9 JM GPU from scratch means porting, for `PAN_ARCH=9`: the renderer-state,
draw, viewport, attribute-buffer, invocation, malloc/indexed-vertex-job and primitive packers (genxml
v9 JM descriptions that do not exist), plus `pan_shader_prepare_rsd`,
`panfrost_pack_work_groups_compute` and the JM TLS/TSD path. That is a driver bring-up project, not a
patch.

It is also what the prior art reports. `zenithblue-oss/panvk-kbase-android` (`docs/universal/v9-jm/`,
a project doing the same thing on a G57 tablet and a G77 phone, `0x90800011`): the v9 JM backend has
been experimental since beta.14, scores **1/17** on PanProbe, runs no D3D game, and the header table
still lists the G77 as untested/blocked. Their G77 `gpu_id` was `0x90800011`; ours is `0x9080`.

GameHub, meanwhile, never left ARM's driver: `libGameScopeV2.so` is **bionic** (NDK r27, `.note.android.ident`),
and its `NEEDED` list is `libandroid, liblog, libEGL, libGLESv2, libGLESv3, libX11-xcb, libX11, libxcb,
libxcb-dri3, libxcb-present, libvulkan, libm, libdl, libc` — an Android-native process loading the
vendor `vulkan.mali.so` through its own ICD, with `GALLIUM_DRIVER=zink` on top. That path cannot be
dropped into our glibc PRoot guest as-is.

### 7. Next steps, in order of evidence

1. **Unbreak the build gate.** Remove `9` from `jm_archs`/`foreach` in `0002` (or drop the v9 wiring
   entirely) so `build-panvk.yml` is green again and a release can be cut at all. Today the fork cannot
   publish a driver, whatever else changes.
2. **Vendor-blob route (works today, GameHub's recipe).** Bind `/vendor/lib64/{hw,egl}` + `/dev/mali0`
   into the guest and build a shim ICD that `dlopen`s `vulkan.$ro.hardware.vulkan.so` — with a glibc
   shim for our guest, since GameHub's is bionic. This is the shortest path to a non-blank screen.
3. **DXVK mali-fix as the Mali default.** Independent of the driver question and still worth doing:
   `dxvk-v1.11.1-mali-fix` (3,383,310 B, own md5) is a distinct build; its deltas are code-level and
   need a binary diff, since its string surface has no Mali keys and its option set equals GameHub's
   async build.
4. **v9 JM PanVK** — a real bring-up project (see 6); not the path to a working screen this week.
