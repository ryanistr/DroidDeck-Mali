# Handoff: Steam Big Picture on DroidDeck (Mali G77 / X6739) — boot stall + frame ping-pong

**Updated:** 2026-10-15 (§7.2f: a content-identity hold for the ring's rotation was implemented, measured and then **reverted** — it converts the ping-pong into a freeze; the section records why the rule is unsound. §7.2c/§7.2d: the p6 driver + `PANVK_KBASE_DRI3=1` leaves the SHM path; the ping-pong on that path is a frozen two-content ring. §7.2e: that same flag forces the DRI3 route, which the guest X cannot serve, so every GL client dies; with the flag off nothing presents)
**Device:** Infinix GT 10 Pro `102562537K104189` — MT6893, Mali-G77 MC9, Android 16
**Tool:** `python3 tools/droiddeckctl …` (run from `/mnt/project/DroidDeck`)
**Report:** `docs/development/panvk-mali-g77-bringup.md` (driver bring-up). This file is the
ping-pong/stall story and supersedes all earlier revisions of it.

**Bottom line:** both defects are reproduced with hard numbers. The ping-pong is real and
whole-screen (~99% of pixels per flip). A compositor-side content hold (§7.2f) was tried and
**reverted** — it stops the alternation only by freezing the screen (0% -> 90-97% of commits held,
but `gen` stops advancing, i.e. nothing new is ever drawn), and the guest still declares no damage and still re-presents
stale slots, so the underlying guest behaviour is unchanged and Defect A is untouched. Its one
unmeasurable case is a legitimate two-frame animation re-rendered byte-identically (§8.4).

Evidence tags used throughout: **[E]** = observed (command output, file:line, measured pixels),
**[I]** = inference. Keep the convention if you extend this file.

---

## 0. Current state in one screen

| Thing | State |
|---|---|
| Ping-pong reproduced? | **Yes** — whole-screen cycling measured live in three separate content regimes (§3.8a). Cleanest evidence: session `2026-10-14-04-steam`, where the guest presented a frozen ring (`mean=85/67/67`) for **292 s / 1,046 commits** with nothing rendering [E] |
| Root cause pinned to a layer? | **Yes: the guest.** All three gamescope output images are CPU-readable and were sampled directly; the compositor's output tracks its input. The compositor only draws what the guest hands it [E] |
| Any verified fix? | **Partly — a mitigation, measured, with a measured hole.** The compositor content gate (§7.5) holds blank commits while a content frame is recent: gate on ⇒ spread **0.00**, gate off ⇒ **68.59** — but only while the stale frames are *dark*. When every ring image is `>= BLANK_MEAN` (the `85/67/67` regime) the gate is inert and the flicker survives (§7.5a) [E] |
| Guest on the GPU present path? | **Yes, since p6 + `PANVK_KBASE_DRI3=1`** (`kbase_dmabuf=1 sw_device=0`, §7.2c): the WSI left MIT-SHM and the compositor draws ~57 frames/10 s where p5 drew 0. The ping-pong is unchanged by it, and Defect A still stalls until the §7.3 nudge [E] |
| Ping-pong, measured on the GPU path | **A frozen two-content ring.** Exactly two screen states, differing only by a ~180 px vertical shift of one bright element; the present mode (mailbox vs fifo) does not change it; the three ring fds are frozen (stable statistics over 40 commits, period 3). The compositor draws what it is given — it ignores Wayland damage entirely (§7.2d) [E] |
| Guest-side root cause | **Narrowed (§9.1a)**: *not* "one image never updates" — all three do. The guest presents its 3-image ring **continuously** while the images hold content from different moments, and it updates them only in **bursts of 1-2**, so the ring rarely converges [E] |
| Current session | `2026-10-14-04-steam` on the released test build `test-0.3.3-mali-frame-gate-20261014` (installed `base.apk` sha256 `10f842416f842acdba9069dbfcee893dd063f347a26dcea5c85bc5c6171f0702`); gate left at its shipped default (on), `frame-probe` marker still in place [E] |
| Device claim | `/mnt/project/X6739/Status.md` — claimed by Antigravity; handoff details recorded below. |

---

## 1. The two defects (as the user experiences them)

**Defect A — startup stall ("stuck on the DroidDeck logo").** Session starts, black screen with
the virtual pad visible, then the DroidDeck startup movie's last frame sits there indefinitely.
Nothing repaints because Steam has not asked for anything yet, so a viewer sees a "frozen logo".

**Defect B — the ping-pong.** Once Steam's Big Picture UI starts rendering (it happens when a pad
input arrives), the display alternates between full-screen states non-stop — the user's words:
*"doing this will cause a screen ping pong frame and going back and forth between frames
continuously non stop."*

The user's reproduction recipe (follow it — a passive capture will NOT show the bug):

1. Start a Steam session; wait for the UI to be up.
2. Confirm the display is *not* moving yet (that is Defect A).
3. Send non-selecting D-pad nudges (`input pad down/up`) until the Big Picture page navigates off
   `about:blank` to `https://steamloopback.host/index.html…` — verify with `cdp targets`.
4. Tap the virtual A and B buttons a few times (in Big Picture, A = SELECT, B = BACK).
5. Capture frames now — this is the window where the ping-pong runs.

---

## 2. How to test (exact protocol, and the traps that burned this investigation twice)

### 2.0 Device rules first

- Read `/mnt/project/X6739/Status.md` **before** any `adb`/`fastboot` call; write your name/task
  into it before touching the hardware; unclaim when done. Use the `x6739-device-rules` skill.
- One root command per invocation: `adb shell su -c 'cmd1'` then a **separate** `adb shell su -c
  'cmd2'`. A `&&`/`;` inside the quoted string silently drops root for everything after the first
  command.
- The device **drops off USB for 2–4 min at a time** (`adb: no devices/emulators found`). Poll, do
  not conclude it is dead. Every drop kills the running session — restart it when the device is
  back. Use single `adb` commands; a chained command dies mid-chain.
- Never work in `/tmp` (host tmpfs). Search with `rg`, find with `fd`.

### 2.1 Start a session

```bash
cd /mnt/project/DroidDeck
python3 tools/droiddeckctl --serial 102562537K104189 start steam --ui bigpicture --wait --timeout 180
```
Run it as a background process — it takes ~2–4 min and a foreground call dies at the tool timeout.
Then confirm:
```bash
python3 tools/droiddeckctl --serial 102562537K104189 state      # phase: READY, running: true
```

### 2.2 Steer and observe the guest

```bash
# Non-selecting navigation (use down/up, NOT A — A selects and types into "Account Name"):
python3 tools/droiddeckctl --serial 102562537K104189 input pad down --action tap

# The A/B taps the user describes:
python3 tools/droiddeckctl --serial 102562537K104189 input pad a --action tap
python3 tools/droiddeckctl --serial 102562537K104189 input pad b --action tap

# Steam's own view of itself — the ground truth for "has the UI actually loaded":
python3 tools/droiddeckctl --serial 102562537K104189 cdp targets
   # expect: SharedJSContext … https://steamloopback.host/routes/login  (sign-in)
   #         "Steam Big Picture Mode" … about:blank   == STILL STUCK (Defect A)
   #         "Steam Big Picture Mode" … https://steamloopback.host/index.html  == UI IS UP

# Anything else in the guest:
python3 tools/droiddeckctl --serial 102562537K104189 guest -- sh -c 'cmd'
python3 tools/droiddeckctl --serial 102562537K104189 focus        # Xwayland windows per display
python3 tools/droiddeckctl --serial 102562537K104189 ui dump      # the APP's own nodes (not Steam's)
python3 tools/droiddeckctl --serial 102562537K104189 logs latest /path/to/dest
python3 tools/droiddeckctl --serial 102562537K104189 stop
```

`input pad` takes `{tap,down,up}` + `--hold`. Buttons: `a b x y lb rb start select guide qam up
down left right`.

### 2.3 Capture frames correctly — this is where the previous attempts went wrong

```bash
cd /mnt/project/DroidDeck
mkdir -p pp2 && for i in $(seq -w 1 20); do
  adb -s 102562537K104189 exec-out screencap -p > pp2/f$i.png
done
```

Then hash **the crop that excludes the FPS HUD** and count distinct states:

```python
from PIL import Image, ImageChops
import hashlib, glob
def load(f): return Image.open(f).convert('RGB').crop((0,0,2200,1080))   # HUD lives at x2249..2340, y45..117
h = {f: hashlib.md5(load(f).tobytes()).hexdigest()[:8] for f in sorted(glob.glob('pp2/f*.png'))}
print(" ".join(h.values()))
A, B = load('pp2/f01.png'), load('pp2/f02.png')
d = ImageChops.difference(A, B); tot = A.size[0]*A.size[1]
nz = sum(1 for p in d.getdata() if p != (0,0,0))
print("%d/%d = %.2f%% differ, bbox=%s" % (nz, tot, 100.0*nz/tot, d.getbbox()))
```

**The three traps — each of these has already produced a wrong "fixed!" conclusion in this file:**

1. **A frozen screen looks exactly like a fixed screen.** Dozens of byte-identical captures prove
   Defect A, not success. Always confirm with `cdp targets` that the UI is actually up before
   measuring anything.
2. **`screencap` aliases.** It takes ~150–200 ms per shot; a ≥5 Hz alternation can lock to one
   phase and return identical images. Capture ≥20 frames, and never conclude "static" from a handful.
3. **`screenrecord` hides it.** The H.264 encode erases ±1-per-channel changes, and the FPS HUD
   ticking in the corner fabricates differences where there are none.

**A positive result looks like this** (measured, session 33 — 2400x1080 screen, hash crop 2200x1080
= 2,376,000 px, HUD excluded):

```
g35->g36  differ 2,367,551/2,376,000 = 99.64%   (>24 levels on 43.3%)   bbox = whole crop
g38->g39  99.71%      g40->g41  99.71%      g41->g42  99.71%      g42->g43  99.06%
idle, minutes later: a7dea031 fa996611 a7dea031 fa996611 fa996611×4 a7dea031 … (fa996611 ×13)
```

### 2.4 Read the compositor's own accounting

`wayland.log` in the session log dir prints a 10-second summary:

```
601 ticks, 77 scenes, 77 on screen (copy 77, zero-copy 0, layer copy 0) ... 0 pool drops
stats: 77 frames on screen (7.7 fps) | 78 GPU frames from games
```

`copy N` vs `GPU frames from games M`: if they track 1:1 (as here) the compositor is faithfully
mirroring what the guest delivers, and the alternation is upstream. If they diverge, look in the
compositor.

---

## 3. What is PROVEN

### 3.1 Defect A is the startup movie never being dismissed

- The movie is Steam's own startup video, staged by DroidDeck: `SessionFiles.kt:224-232` copies
  `assets/steam-startup/droiddeck-startup.webm` → `root/.local/share/Steam/config/uioverrides/movies/`
  as `bigpicture_startup.webm` + `steam_os_startup.webm`, and `ensureStartupMovieDefault`
  (`SessionFiles.kt:259`) writes `StartupMovie` into `config.vdf`. [E]
- It is 6.35 s long; Steam pauses on its last frame and only clears it when an input arrives. [E/I]
- While it is up: `cdp targets` → `SharedJSContext` = `…/routes/login`,
  `"Steam Big Picture Mode"` = `about:blank`, and 12 consecutive captures are byte-identical outside
  the HUD. Non-selecting D-pad nudges moved the BPM page to `…/index.html…` and the UI took over. [E]

### 3.2 Defect B is real, whole-screen, and sustained

Numbers in §2.3. It is **not** two subtly different frames: `ImageChops.difference().getbbox()`
covers the entire hashed area, so every pixel moves.

### 3.3 What the alternating states are

All three states are **Steam UI** — the sign-in page (QR panel, "SIGN IN WITH ACCOUNT NAME" with
`gggggg` typed into it by the pad taps, "PASSWORD", "Sign in"/"Cancel", "Create account") under
DroidDeck's virtual gamepad overlay (LT/LB/RB/RT, D-pad, ABXY, STEAM MENU, A SELECT / B BACK, FPS
counter). None of them is a DroidDeck logo or a startup-movie frame. [E — read from the actual
pixels of g30/g36/g39]

What differs is the **background**, and not by a scale factor:

```
dark pixel    (3,4,5) -> (7,10,14) -> (255,255,255)
bright pixel  (159,188,209) -> (162,189,209)          (unchanged)
mean RGB      (15.5,17.1,19.6) / (44.4,46.3,52.5) / (88.0,90.4,96.7)
```

The dark background turns **white** while UI content is preserved → the same UI composited over a
**black** background vs a **white/undefined** background. [E for the pixels, I for the mechanism]

### 3.4 The guest's present path

```
PANVKDBG WSI: uses_kbase=1 termux_raw=0 raw_dri3=0 kbase_dmabuf=0 sw_device=1 raw_fd_modifier=0
PANVKDBG PRESENT CREATE_ENTER surface=… format=44 extent=1600x720 minImages=3 mode=1 sw=1
PANVKDBG WSI_BLIT_SHM sw_host_ptr=0x… (3 slots, size 4,608,000)
PANVKDBG XSHM_GATE dri3=0 present=1 wants_shm=1
```
The guest has **no DRM render node** — `/dev/dri` holds `card0` only (a display node
SurfaceFlinger owns), no `renderD128`, plus `/dev/mali0`. PanVK-on-kbase has no dma-buf WSI
(`KBASE_IOCTL_MEM_IMPORT` is "left for future work" in the fork's `kbase_kmod.c`), so
`kbase_dmabuf=0` is structural → `sw_device=1` → present = GPU blit into an X11 MIT-SHM pixmap
(`present=1`, `dri3=0`) through a 3-slot ring. `PANVK_KBASE_DRI3=raw` cannot help (it crashed with
`GLXBadCurrentWindow`). [E, restated from the earlier sessions — still true in session 33]

Also in the log: 12 of 27 `WSI_BLIT_SHM` allocations returned `alloc_shm=(nil) sw_host_ptr=(nil)`.
**Never explained.** Could matter. [E]

### 3.5 The frame path the compositor actually takes here

```
vulkan: "Steam Big Picture Mode" (gamescope) is presenting GPU frames through Wayland:
        1600x720, format XR24, linear (dma-buf: copied into the screen swapchain)
```
`copy 77, zero-copy 0, layer copy 0` — all frames went through the plain copy path. The dma-buf is
imported as a VkImage (`b->img` non-NULL, compositor.c:820-832) and composited with `vkp_render`
into the screen swapchain. It is **not** the mmap-fallback path and **not** layer/zero-copy mode. [E]

### 3.6 Guest window facts

`droiddeckctl focus` → exactly **one** viewable X11 window on `:0`: `class=steam`, `[0,0,1600,720]`
(two `Steamwebhelper` windows are `unmapped`). So there is one guest window and whatever overlays
Steam draws live inside it. [E]

### 3.7 The app's UI tree has no A/B buttons

`droiddeckctl ui dump` returns exactly one node: `"5 fps"` at `[2249,45]-[2340,117]` (the FPS HUD).
`uiautomator dump` returns `ERROR: null root node returned by UiTestAutomationBridge`. The pad
overlay is native-rendered, not in the accessibility tree — do not expect to find A/B coordinates
there. [E]

### 3.8 The alternation is in the GUEST's ring: one bright image, two blank (MEASURED)

> **Partly superseded by §3.8a.** "One bright + two blank" is only one of three regimes, and its
> consequence ("only one of three images gets the scene") is wrong — all three update over time.
> §3.8a has the corrected measurement and the frozen-rotation evidence; read it first.

A CPU content probe was added to `take_dmabuf`'s `if (b->img)` branch (`compositor.c`): it mmaps the
committed buffer (`b->fd[0]`, which is linear here so the bytes are directly readable), samples a
64x36 grid, and logs `mean/max/hi` next to the existing `framedbg … DMABUF` line. Gated on a marker
file `<session log>.frame-probe` so it is inert without one. Rebuilt with the 7 s ninja target,
hot-swapped into the installed 0.3.3, session `2026-10-14-01-steam`. With the alternation plainly on
screen (A/B spam, OSK open/close), 2,196 commits, **all three** buffers sampled:

```
last 60 commits, as seq:fd:mean
2137:331:12 2138:332:12 2139:311:92 2140:331:12 2141:332:12 2142:311:92 …  (period 3, forever)

fd 311   mean=92  max=255  hi=1136/2170   <- the composited UI (Big Picture page + OSK)
fd 331   mean=12  max=255  hi=122/2170    <- blank (one small panel only)
fd 332   mean=12  max=255  hi=122/2170    <- blank (same)
```

The screen measured in the same window alternates `R=88.8` / `R=13.2`, i.e. it tracks the buffer it
was handed (92 -> 88.8, 12 -> 13.2). **The compositor is faithful; the guest hands it a blank frame
two presents out of three.** [E]

When the guest's content stops changing, the three images **converge** (all `mean=6`, or all
`mean=92`) and the screen goes static — that is the "frozen black / frozen white" the user
describes. Same defect, seen at two rates. [E]

Consequences:

- `bf1f65b8`'s compositor guard cannot fix this **by construction**: it keys on buffer identity, and
the guest rotates three *distinct* buffers, so it never fires here (it also sits in the layer-mode
branch this configuration never takes). Any compositor-side dedupe has the same problem — the
incoming content genuinely differs.
- A compositor-side fix can only be a *content* gate (§7.5), i.e. a mitigation that hides the blank
frames. The real defect is guest-side: gamescope's three output images are not all receiving the
composited scene.
- The blank frames are not all-black: `mean=12, max=255, hi=5.6%` — a near-empty frame with one
  small centred panel. That is a real rendered frame, not an uninitialised buffer.
- A later session (`2026-10-14-02-steam`) showed the ring is not always 1 bright + 2 identical
  blanks: with the QAM overlay open it was **one bright (`mean=78/91`) and two *different* partial
  frames (`mean=10` and `mean=25`)**. The `mean=25` frame is the UI panel drawn except a right-hand
  sliver (`hi=282/2170`, 13%). So the blanks are not a single fixed image either — a content gate
  must treat both as blank (see §7.5), which is why `BLANK_MEAN` is 40, not 16.

### 3.8a The three regimes, and the frozen-rotation measurement (session `2026-10-14-04-steam`)

§3.8's headline ("one bright image, two blank") describes only **one** of three regimes the ring
actually passes through, and its consequence ("only one of three images gets the scene") is wrong.
The probe was re-run on the **released** test build (`test-0.3.3-mali-frame-gate-20261014`, installed
`base.apk` sha256 `10f84241…`, verified) for 2,413 commits, and every per-fd content transition was
extracted. The three fds (`325`/`326`/`327`) are the ring; the probe line is
`#seq res=<wl_buffer> fd=<n> b=<buf> 1600x720 stride=6400 mean=<luma> max=<max> hi=<n>/2170`
(`mean` is luma sampled on a 64x36 grid, `n=2170`).

Complete transition history — a line is emitted only when one of the three means changes:

```
05:00:42 seq=3    | 325=7   326=6   327=6     all identical  -> invisible
05:01:32 seq=273  | 325=85  326=6   327=6     burst: 325 updated
05:01:32 seq=274  | 325=85  326=85  327=6     burst: 326 updated  (327 never did)
05:03:46 seq=713  | 325=85  326=85  327=13
05:03:46 seq=714  | 325=13  326=85  327=13
05:05:28 seq=1225 | 325=13  326=90  327=13
05:05:28 seq=1226 | 325=13  326=90  327=90
05:05:32 seq=1242 | 325=12  326=90  327=90
05:05:32 seq=1243 | 325=12  326=11  327=90
05:05:33 seq=1248 | 325=90  326=11  327=90
05:05:33 seq=1249 | 325=90  326=90  327=90    the only full convergence (invisible)
05:05:33 seq=1253 | 325=90  326=90  327=37
05:05:33 seq=1254 | 325=85  326=90  327=37
05:05:34 seq=1255 | 325=85  326=67  327=37
05:05:34 seq=1256 | 325=85  326=67  327=67    settles: 85 / 67 / 67
   ... 292 s, 1,046 presents, NOT ONE CHANGE ...
05:10:26 seq=2302 | 325=85  326=11  327=67    the guide tap
05:10:26 seq=2303 | 325=85  326=11  327=11    325 stays at its 05:05:34 value
```

Three things this establishes that §3.8 did not:

1. **The ring is presented continuously while nothing is rendered.** Between `seq=1256` and
   `seq=2302` — 292 seconds, 1,046 commits — not a single buffer changes, yet the guest keeps
   committing at ~4 fps (the compositor's `rdbg FRAME` line reads `dt≈250 ms`). The display therefore
   alternates for five minutes over a static scene. An earlier window shows the same thing: `seq=274`
   to `seq=713` was frozen at `85/85/6` for 134 s.
2. **All three images are updated over time — there is no permanently-stale slot.** Across the
   session `fd325` was seen at `{7,12,13,85,90}`, `fd326` at `{6,11,67,85,90}`, `fd327` at
   `{6,11,13,37,67,90}`. §9.1a's premise is therefore wrong as stated. What *is* true: updates arrive
   in **bursts of 1-2 images**, so a burst that ends before the third image gets its turn leaves the
   ring holding two different scenes indefinitely.
3. **The odd-one-out is not a fixed slot.** It was `327` in the `85/85/6` window, `326` in the
   `13/90/13` window, and `325` from 05:05:34 onward. A gate keyed on buffer *identity* is hopeless;
   only content can work — and even content only works while the stale frames are dark (§7.5a).

The three regimes, and what each does on screen:

| Ring state | Screen | Spread | User sees |
|---|---|---|---|
| all three equal (`6/6/7`, or `90/90/90`) | flat | ~0 | nothing (no defect) |
| 1 bright + 2 dark (`85/85/6`, `85/13/13`) | bright <=> dark | ~70 | hard flicker |
| 1 bright + 2 mid (`85/67/67`) | 83 <=> 65 | 17.9 | soft flicker / brightness pumping |

Measured on this session (20 captures each, ImageMagick mean-R over the whole 2400x1080 screen; the
compositor tracks its input with a fixed ~0.97 factor from the Android chrome, so guest `85` reads
`83.05` and guest `67` reads `65.11`):

| Ring (guest) | Gate | Screen R | Spread |
|---|---|---|---|
| `85/67/67` | on | 65.11 <=> 83.05 | 17.93 |
| `85/67/67` | off | 65.11 <=> 83.05 | 17.93 |
| `85/11/11` | off | 12.82 <=> 83.05 | 70.23 |

The first two rows are identical: in the `85/67/67` regime the gate does nothing at all. See §7.5a.

---

## 4. WITHDRAWN claims — do not re-serve these

1. **The compositor copy-path guard was "verified fixed" (earlier §4b).** Withdrawn. The
   verification was taken while the screen was frozen on the movie frame (trap 1). The guard exists
   at `compositor.c:2284-2293` but sits in the **layer-mode** branch; this configuration runs the
   fallback copy path, so it never executed. `copy == GPU frames from games` means the compositor is
   mirroring, not inventing, the alternation.
2. **`MESA_VK_WSI_PRESENT_MODE=fifo` was "the fix" (earlier §4c).** Withdrawn. Same trap: the
   10-capture "0 px difference" was measured on the frozen screen. Re-measured live with no FIFO
   staged, the alternation is plainly there. Treat FIFO as untested. Also `ENABLE_GAMESCOPE_WSI=1`
   is set in the session, so the gamescope WSI layer is in the path anyway and the Mesa X11 WSI
   present-mode knob may not even control it.
3. **"The movie frame keeps returning, so the display alternates movie ↔ UI."** Withdrawn — the
   states are all Steam UI (§3.3).
4. **The old §5 instruction to use `grim` / `xwd` / `import` in the guest.** These do not exist
   (§5.1). Following that instruction wastes a session.

---

## 5. Instrument inventory — what works, what is deadly

### 5.1 The guest has NO screenshot tool

Probed and **all missing**: `grim`, `slurp`, `wf-recorder`, `xwd`, `import`, `convert`, `ffmpeg`,
`scrot`, `gnome-screenshot`. Present: `gamescopectl` (`/usr/bin/gamescopectl`, gamescope 3.16.29).
There is no in-guest compiler either. So the only guest-side capture route is §5.2 — which crashes.

### 5.2 `gamescopectl` — reads are fine, `screenshot` KILLS the session

`gamescopectl help` lists 109 control keys; bare `gamescopectl <key>` reads one. Useful keys:

```
backend_info                  (dump backend state — safe, used already)
paint_steam_overlay_plane     paint_external_overlay_plane     overlay_unmultiplied_alpha
composite_debug               composite_force  (force composition always, never scanout)
paint_debug_pause_base_plane  (pause updates to the base plane)
screenshot                    (take a screenshot to a given path)   <-- DO NOT USE
```

**`gamescopectl screenshot <path>` segfaults gamescope** — it produced no file and ended the session:
`16339 Segmentation fault (core dumped) stdbuf -oL -eL gamescope …` /
`failed to read Wayland events: Connection reset by peer` / `== gamescope exited with code: 139` →
`guest.exited status 139` → `session.failed GUEST_EXIT`. Do not call it again on this build. [E]

The boolean plane keys read back **empty** (unset). Writing them is untested — that is the cheapest
next experiment (§7.2). [E]

### 5.3 The `framedbg` instrumentation — corrected: the import log DOES fire

Stale as first written. Session 33's zero `framedbg` lines held for the three sites in the table
below, but the **fourth** site — the dma-buf import branch, `compositor.c:823-838` (`if (b->img)`) —
fires on every commit: session `2026-10-14-01-steam` has 4,594 `framedbg … DMABUF res=… gen=…` lines
and 4,608 `RENDER-COPY`. That log is what proved the 3-slot rotation and the unconditional
`commit_gen++` (§3.8). [E]

The three sites that really never fire here:

| site | branch | live here? |
|---|---|---|
| `compositor.c:844` | mmap-fallback dedupe | no |
| `compositor.c:881` | mmap-fallback `TAKE` | no |
| `compositor.c:2286` | layer-mode present guard | no |

**Where the live path is:** `compositor.c:823-838` (`if (b->img != NULL)`) — the dma-buf import
succeeded, and it does `s->commit_gen++` **unconditionally, with no content check**. Rebuild with
`./gradlew :app:externalNativeBuildDebug` and hot-swap per §6.3.

**The cheap option was enough — but only the mmap form of it.** Logging the `wl_buffer` resource
pointer per commit (option (a) below) already existed as `framedbg … DMABUF res=…` and answered
ring-reuse vs alternation: the guest rotates **three** distinct buffers. It could not say whether a
given buffer is *blank*, which is the question that mattered. On this device the buffers are linear
(`b->modifier == LINEAR`, advertised by the compositor), so **no GPU readback is needed at all**: a
plain `mmap(b->fd[0])` + a 64x36 sample grid gives the mean in microseconds, with no GPU submit and
no timing perturbation of the very oscillation being measured. That is what §3.8 uses, and it is
now in the tree (`compositor.c`, gated on the marker file). A `vkCmdCopyImageToBuffer` readback
would have been the wrong tool: it cannot read a `VK_QUEUE_FAMILY_FOREIGN_EXT` image without a
layout dance, it costs a full submit+wait per frame, and it would have perturbed the timing.

---

## 6. Environment, files, and how to change code

### 6.1 Repo state

`/mnt/project/DroidDeck` — **nothing committed**. Modified: `compositor.c`, `sc_layer.c`,
`sc_layer.h`, `vk_present.c`, `vk_present.h`, `docs/development/ping-pong-frame-handoff.md`. Many
untracked artifact dirs (`.logs33/`, `.logs34/`, `.handoff-pull/`, `.sessdump/`, `.tester-logs/`,
`pingpong_test/`, per-session dirs). Branch `main`.

### 6.2 Session log layout (on device)

`/data/user/0/com.droiddeck.launcher/files/logs/<session-id>/` → `wayland.log` (compositor),
`session.log` (gamescope/session stdout — the SIGSEGV lands here), `app.log`, `pad.log`,
`events.jsonl` (machine-readable transitions), `crash.log`, `device.txt`, plus a `.complete` marker
when it ended. Pull with `droiddeckctl logs latest <dest>`; read individual files with
`adb shell su -c 'cat <path>'`.

### 6.3 Changing the compositor without rebuilding the APK

Per the `x6739-device-rules` skill §4h: app native libs are extracted to
`/data/app/*/com.droiddeck.launcher*/lib/arm64/`, so a `.so` can be rebuilt, hot-swapped as root,
and the app restarted. **Kotlin/Java and assets require the full APK** — and note that
`SessionFiles.stage()` re-stages everything under `files/linuxfs/` from the APK at **every** session
start, so a device-side edit to `droiddeck-session` or a preload vanishes. If a session misbehaves,
first check `adb logcat -d -b all -v time | grep 'NOT staged'`.

### 6.4 Caution from this session

`crash.log` for session 33 holds a DroidDeck **app** SIGSEGV at 15:17:53
(`Fatal signal 11 … fault addr 0x980, tid 4570`) — immediately after the 15:16 `.so` hot-swap, so
most likely swap-under-loaded-library; the session then ran 20 min with the new lib. The hot-swap
recipe itself is fine, but restart the app cleanly after swapping and check for that crash. [E]

---

## 7. Candidate fixes, cheapest first

### 7.1 gamescope plane/alpha keys (TESTED on session 34 — did NOT resolve)

Evaluated on live session `2026-10-13-34-steam`:
- `gamescopectl composite_force 1`: Evaluated. Alternation continued unabated across all 3 states [E]. Reverted to 0.
- `gamescopectl overlay_unmultiplied_alpha 1`: Evaluated. Alternation continued unabated [E]. Reverted to 0.
- `gamescopectl paint_steam_overlay_plane 1`: Window `0x2000045` (`class=steam`) is the base focus window (`overlay == 0`), not an overlay plane. No effect [E].
- `gamescopectl paint_debug_pause_base_plane 1`: Paused base plane commits in gamescope, producing a static black screen. When toggled back to 0, tapping D-pad down woke the display immediately back into the 3-frame oscillation [E].
- `document.activeElement.blur()` via CDP: Evaluated. Active element blurred from `<input ...>` to `BODY` (stopping cursor blink animation), but the 3-buffer swapchain ring continued rotating [E].

### 7.2 Give the guest a real render node (root cause)

`/dev/dri` has no `renderD*`; `tools/linuxfs/preload/drm.c:384-388` already tries renderD128 →
mali0 → card0. If a kbase DRM render node can be exposed/bound, PanVK gets `sw=0` hardware presents
and the whole SHM ring disappears. This is the actual fix if the guest is the alternator. Highest
value, highest effort.

### 7.2a Both halves of 7.2 now exist (2026-10-14, untested)

Reading another Mali fork (`emir34-34/DroidDeck`) turned up two things 7.2 was missing, and the
second one is the strongest lead in this file for *this* device.

1. **The guest never had a render node here.** `LinuxRuntime.bindGpuNode()` fabricates the guest's
   `/dev/dri/renderD<minor>` and the sysfs entries libdrm reads — and returned early when
   `/dev/kgsl-3d0` was absent, which on a Mali it always is. With no node, the guest's `drm.c`
   preload falls to its synthesized device (`alloc_fallback_drm_device()`, `drm.c:332`), which sets
   `bustype = -1` (`:346`), while `panvk_can_present_on_device()` accepts only `DRM_BUS_PLATFORM`
   — so the guest's X11/DRI3 present path is refused and it copies through MIT-SHM. That is the
   mechanism `steam-ui-logo-pingpong-bug.md:32` already names, and it explains
   `kbase_dmabuf=0 → sw_device=1` at §3.4 as a *consequence* rather than a knob we failed to set.
   `bindGpuNode()` now falls back to `/dev/mali0` (uevent `DRIVER=panfrost`, `driver` link at
   panfrost, stale links replaced), so libdrm builds the device from sysfs and reports PLATFORM
   itself. **[E]** the code path, by `file:line`; **[I]** that it is what produces the SHM ring —
   measurable: the session log should show a `/dev/dri/renderD*` and a `kbase_dmabuf=1` WSI line.
2. **The driver answered no device at all.** `panvk_physical_device_init_kbase()` left
   `drm.render_rdev`/`drm.primary_rdev` at zero, so `VkPhysicalDeviceDrmPropertiesEXT` named nothing
   and an rdev matcher could not match even with a node present. The node's own `st_rdev` is knowable
   (proot presents `/dev/mali0` as the session's render node) and is now reported by
   `tools/panvk-g57/patches/0005`. The same fork also fixes a separate CSF fault (0004) that had
   frozen its Steam client on a G720; that one cannot affect a G77 at all (`panvk_cmd_draw.h:299`
   gates that code on `PAN_ARCH >= 10`, and a G77 submits through JM). Full write-up and the source
   reads: bringup §19. **[I]** until measured.

### 7.2b Measured: the node is there, and the extension is not (2026-10-14)

Both halves of 7.2a were put on the device and the prediction in 7.2a was **half wrong**. On
session `2026-10-14-34-steam` (APK at `9a37329a`, so the runtime's `libvulkan_panvk.so` is p5 — the
build carrying 0005):

- The guest has the render node: `crw-rw-rw- 1 root root 10, 110 /dev/dri/renderD110`, the same
  `st_rdev` as `/dev/mali0` (`droiddeckctl guest`). Half 1 of 7.2a is confirmed working. [E]
- 0005 alone changes nothing observable. The log still reads
  `PANVKDBG WSI: uses_kbase=1 … kbase_dmabuf=0 sw_device=1` and gamescope still warns
  `physical device doesn't support VK_EXT_physical_device_drm`. [E]
- Cause: `.EXT_physical_device_drm = false` is hard-coded at
  `src/panfrost/vulkan/panvk_vX_physical_device.c:198` in the fork, so the `drm*Major/Minor`
  properties 0005 fills are unreachable. 0005 is half of one change; 0008
  (`tools/panvk-g57/patches/0008-advertise-physical-device-drm.patch`) is the other. [E: source]
- `kbase_dmabuf` is a **third**, independent gate: it needs `PANVK_KBASE_DRI3` non-empty and not
  `0` (`panvk_wsi.c:112-118`), and the session sets neither it nor `WSI_X11_TERMUX`. So even a
  driver that reports its DRM device will keep `sw_device=1` until that knob is set. See bringup
  §22.3.

The 7.2a prediction should be read as two predictions, not one: the DRM device numbers need 0005 +
0008; leaving the SHM ring needs `PANVK_KBASE_DRI3` as well.

### 7.2c Measured: the p6 driver + `PANVK_KBASE_DRI3=1` does leave the SHM path

Both halves of the §7.2b reading were closed on a driver carrying 0005 **and** 0008 (the p6 build:
`release.env` `PANVK-rynx-1.2.0-p6`, staged `assets/linuxfs/usr/lib/libvulkan_panvk.so` sha256
`2aa4f1749ef98ebf542ee46c727d7b305113d4d05bd8340ca631106c7071df7c`), with the third gate set for one
session by `droiddeckctl env set PANVK_KBASE_DRI3=1`. Session `2026-10-14-35-steam`:

```
PANVKDBG WSI: uses_kbase=1 termux_raw=0 raw_dri3=0 kbase_dmabuf=1 sw_device=0 raw_fd_modifier=0
[gamescope] [Info] vulkan: physical device supports DRM format modifiers
[gamescope] [Info] vulkan: selecting physical device 'Mali unknown 0x90800011 MC9': queue family 0
[Gamescope WSI] Created swapchain for xid: 0xc00002 swapchain: 0x3000f573e0 - imageCount: 3
PANVKDBG CREATE_DEVICE AFTER_INIT result=0 KHR_swapchain=1
```

- `kbase_dmabuf=1 sw_device=0` — the WSI left the MIT-SHM blit path. `WSI_BLIT_SHM`, `XSHM_GATE`
  and `WSI_CPU_CONFIG` are each at **count 0** in the entire log, where p5 logged them per frame. [E]
- The compositor's perf line moved from `0 ticks, 71 scenes, 0 on screen … sw` (p5, session -34) to
  `1201 ticks, 57 scenes, 57 on screen (copy 57, zero-copy 0, layer copy 0)` at ~5.5 fps,
  sustained. [E]
- The screen left black (session -34 `std=0`): a capture of session `-35` read
  `2400x1080 mean 71 std 32 colors 4354`. [E]

So §7.2b's prediction is answered: the DRM extension needs 0005 + 0008, and leaving SHM needs
`PANVK_KBASE_DRI3` as well. `tools/linuxfs/overlay/usr/local/bin/droiddeck-session` now exports
`PANVK_KBASE_DRI3=${PANVK_KBASE_DRI3:-1}` next to `PANVK_FD_INTEROP`/`PANVK_KBASE_DMA_HEAP`, so a
caller-set value still wins for an A/B against `0`.

**This is not a ping-pong fix and it does not fix Defect A.** In the same session, 20 screencaps
(raw pixels, 2400x600 crop starting at y=240) taken while the Steam UI was still on the startup
movie's last frame returned **1 distinct state** — frozen, the §1 step-2 stall. The session then
ended (`session stopped at 16:50:12`) with CDP still showing `SharedJSContext` on
`https://steamloopback.host/routes/login` and the BPM page on `about:blank?createflags=6292738&…`;
`~/.local/share/Steam/config/loginusers.vdf` has `RememberPassword 0`, so there is no account for the
client to load. [E]

The BPM page did leave `about:blank` (for `https://steamloopback.host/index.html?…`) after five
non-selecting D-pad nudges — the §7.3 nudge — and the same crop then read 11 distinct pixel states
across 20 captures with `mean` spanning `71…151` (including a `mean=151 std=0 colors=146` frame and a
`mean=150 std=68 colors=93061` frame). That is the §1 step-5 ping-pong window, now reachable with the
GPU present path live. [E]

### 7.2d Measured on the GPU path: the ping-pong is a frozen two-content ring

Sessions `2026-10-14-40-steam` (installed build `e25c2cef`, whose `droiddeck-session` exports
`PANVK_KBASE_DRI3=${PANVK_KBASE_DRI3:-1}`, so no override was involved) and `-41-steam` (same build,
with `MESA_VK_WSI_PRESENT_MODE=fifo` in the session env) were measured while Steam sat on the
startup movie's last frames — the state the user reported as *"the boot animation played up to the
end, then it gets stuck with the DroidSpace icon going up and down"*.

- Both sessions are off the SHM path: `kbase_dmabuf=1 sw_device=0`, `WSI_BLIT_SHM` and `XSHM_GATE`
  at count 0, driver `…/linux_panvk_drivers/PanVK-rynx-1.2.0-p6/libvulkan_panvk.so`
  (`2aa4f174…`), and no env override present. `PRESENT CREATE_ENTER … mode=1` (mailbox) on `-40`
  becomes `mode=2` (fifo) on `-41`, so the env knob reached the guest. [E]
- The compositor draws ~5.6 scenes/s (`1202 ticks, 56 scenes, 56 on screen` over 10 s): one draw per
  guest commit. Zero `HOLD-BLANK` lines — the mean of every guest frame is 6-7, far below
  `BLANK_MEAN` 40, so the §7.5 gate is **inert** in this regime. [E]
- The screen alternates between **exactly two** states, and the present mode makes no difference:
  8 s of screen recording gives mailbox `32 : 14` and fifo `18 : 10` (plus two near-duplicates
  differing by one unit in a single grid band, i.e. the same two states). Frames were compared as a
  12x12 luma grid, not by file hash: a lossy mp4 makes every frame's hash unique. [E]
- The two states differ in **one** thing. Against a background of 0-18, a bright element (grid
  values 45-191) sits two grid rows higher in one state than the other — **~180 px** — and every
  other cell of the 12x12 grid is unchanged. That is the icon the user sees going up and down. [E]
- The three ring images are **frozen**. Over 40 consecutive commits the same three fds recur on a
  strict period of 3 with stable statistics each time — `fd=322 mean=7 hi=71/2170`,
  `fd=323 mean=6 hi=68/2170`, `fd=324 mean=7 hi=71/2170`. So this is not a scene animating slowly:
  the client is not rendering at all and keeps committing a ring whose slots hold two different
  contents, two of the three carrying the same one (the 2:1 draw ratio above). [E]
- The compositor is faithful: it draws whatever it is handed. `surface_damage` and
  `surface_damage_buffer` are **empty stubs** (`compositor.c:1114`, `:1372`), so it cannot tell a
  re-attached stale buffer from freshly rendered content even in principle today. [E: source]

**What this means for a fix.** §7.5's mean-based gate cannot help here (both contents are mean 71 on
screen, 6-7 in the guest's own frame), and a fingerprint-only dedupe is not sound either: "A,B,A,B"
is exactly what a legitimate two-frame animation looks like, so freezing on a repeat would break real
animations. Every signal that distinguishes them lives on the **client** side — whether the commit
re-rendered anything — and the one channel that carries that, the Wayland damage region, is the one
this compositor throws away. Capturing it (and logging it in the `framedbg` line) is the next
measurement: if gamescope reports empty damage for the stale commits, "commit with no damage and a
fingerprint already on screen" becomes a rule that is correct rather than a heuristic, and the
10 s window with 56 commits and 0 new contents is a test case for it.

### 7.2e The knob's other half: `kbase_dmabuf=1` needs DRI3, and the guest X has none (2026-10-15)

§7.2c showed `PANVK_KBASE_DRI3=1` is what puts the guest on the GPU present path. Measured on
sessions `-01`, `-02` and `-03` under one installed build (`58a827ad`), that same flag is also what
kills every GL/X11 client - so each arm of the knob is unusable in a different way:

| Session | `PANVK_KBASE_DRI3` | WSI | zink lines | "No DRI3" | on screen | `session.ready` |
|---|---|---|---|---|---|---|
| `-40`, `-41` | 1 (default) | `kbase_dmabuf=1 sw_device=0` | 1 | 0 | ~56 per 10 s | yes |
| `-01` | 1 (default) | `kbase_dmabuf=1 sw_device=0` | 8 | 4 | - | no |
| `-02` | 0 (override) | `kbase_dmabuf=0 sw_device=1` | 0 | 0 | **0** | no |

- The mechanism is a dichotomy inside the driver, not a heuristic. `wsi_common_x11.c:246` derives
  `wants_shm = wsi_dev->sw && !(WSI_DEBUG & WSI_DEBUG_NOSHM) && has_import_memory_host`, and
  `panvk_wsi.c:142` sets `sw = !ahb_wsi && uses_kbase && !kbase_dmabuf`. So `kbase_dmabuf=1`
  disables MIT-SHM presentation and forces the DRI3 route, and `wsi_x11_check_for_dri3()`
  (`wsi_common_x11.c:423`) then fails because the guest X has no DRI3 (it has no glamor). [E]
- Verbatim from `-01-session.log`, twice each: `PANVKDBG XSHM_GATE dri3=0 present=1 wants_shm=0`,
  `MESA: info: vulkan: No DRI3 support detected - required for presentation`,
  `MESA: error: zink: could not create swapchain`, `X Error of failed request:  GLXBadCurrentWindow`,
  with four `pEngineName: mesa zink` instances. The session never emitted `session.ready`. [E]
- `-02` (knob off) has none of that and does open the Big Picture window, but the compositor then
  reports `0 on screen (copy 0, zero-copy 0, layer copy 0)` against ~70 `GPU frames from games` -
  the same 0-on-screen p5 gave in `-34` (§7.2c). It also never emitted `session.ready`. [E]
- **`-03` proves the export is not uniform.** Its `session.log` reads `kbase_dmabuf=1 sw_device=0`
  while its `steam/steamwebhelper.log` reads `kbase_dmabuf=0 sw_device=1`, followed by
  `MESA-EGL: warning: DRI3 error: Could not get DRI3 device` and
  `ANGLE Display::initialize error 12289: Could not create GL context`. CEF is launched
  ANGLE-on-Vulkan precisely so it needs no Xwayland GL (`droiddeck-session:1024`, `b8c26d7f`,
  "allow CEF GPU acceleration without Xwayland glamor"), so this is a new failure. [E]
- **And the client UI is on SwiftShader, not the GPU.** The guest's
  `/root/.steam/steam/logs/webhelper_gpu.txt` (timestamps `04:55:53`) reports
  `DRIVER_VENDOR=SwANGLE`, `[ GL implementation parts ]: (gl=egl-angle,angle=swiftshader)`,
  `[ Display type ]: ANGLE_SWIFTSHADER`,
  `Cannot create an OpenGL ES platform on GLX without the GLX_ARB_create_context extension` and
  `gpu_memory_buffer_support_x11.cc(49)] : dri3 extension not supported`. So `-cef-force-gpu
  -cef-use-angle=vulkan` is not buying hardware Big Picture: ANGLE lands on SwiftShader and CEF
  rasterizes on the CPU. That is the likeliest cause both of the lag the user reports and of the UI
  sitting at ~5 fps, and it names the same root cause as §7.2 - the guest X has no glamor, so it
  offers neither GLX nor DRI3. [E]
- **And that is a switch this tree throws, not a device limitation.** Xwayland names it: session
  `-05-steam` logs, twice (one per Xwayland instance), `Disabling glamor and dri3 support,
  XWAYLAND_NO_GLAMOR is set`, then `Failed to initialize glamor, falling back to sw`.
  `XWAYLAND_NO_GLAMOR=1` comes from `droiddeck-session:311`, unconditionally. So the "GPU present
  path" the knob selects is being pointed at a guest X that has been told to disable DRI3 - §7.2c and
  §7.2d should be read with that in mind. **Dropping the export is the highest-value next
  experiment.** It needs an APK build. [E]
- **The live client is also stuck before its webhelper exists.** In `-04` (repaired client, knob at
  its default) the guest shows `steam` at 95.6% CPU with a `steam <defunct>` child and no
  `steamwebhelper` at all, while the compositor logs `DMABUF res=` zero times and
  `RENDER-COPY n=0`, i.e. no guest frame ever arrives to probe. [E]
- `XSHM_GATE` is a per-process marker of the path a process took (twice in `-01`, absent in `-02`),
  so its presence or absence says nothing about frames. [E]
- Testing is also now gated by the app's self-repair: "repairing the Steam client (the client was
  started 2 times without showing anything)" re-downloads every component
  (`droiddeck-steam-install:23-25`) before Steam starts at all, and it only records `repaired` when
  it finishes, so a session killed mid-download repeats it next time. Budget for a full download
  (~170 KB/s measured) before expecting the ping-pong state to be reachable. [E]
- **A session-script change cannot be tested from the device.** The app re-stages
  `files/linuxfs/usr/local/bin/droiddeck-session` from the APK at every session start, so editing it
  in place (md5 `99849e1f…`) reverts to the repo's `16ee9334…` within seconds of `start`, and the
  edited line returns. Only `droiddeckctl env set` works without a rebuild, and only where the script
  itself reads `${VAR:-default}`. Any experiment that changes `XWAYLAND_NO_GLAMOR` or the CEF
  arguments therefore needs an APK build. [E]

**This is why §7.2d's damage capture has not been taken yet.** The session must reach the ping-pong
state first, and every route to it is blocked by one of the two arms above. The instrumented build
(`58a827ad`; its `lib/arm64-v8a/libdroiddeckwayland.so` contains `fp=%08x dmg=%d`) is installed and
the marker was placed, but no session since has drawn a frame with `frame_probe_on()` true.

### 7.2f The ring's rotation is held by content identity — REVERTED 2026-10-15, it freezes the screen

**Status: reverted, not shipped.** The hold below was implemented, measured, then taken back out
after a live test showed it converts the ping-pong into a *freeze* — the screen stops updating at all.
Keep this section as the record of why that rule fails; do not re-land it as written.

§7.2d ended by asking for the damage region, on the theory that "commit with no damage and a
fingerprint already on screen" would be the sound rule. §8.3 of the handoff then measured the damage
region, and it **cannot carry that rule**: `s->damage_n / s->damage_area` is a *constant*
`1 / 0x3FFFFFFF00000001` (4611686014132420609) on all 724 commits of session
`2026-10-15-08-steam` — gamescope declares the whole surface damaged every time, so damage is
identical for a re-attached stale image and a freshly rendered one. The rule had to come from the
buffer contents instead.

**What was implemented** (`compositor.c`, `content_hold()` + `content_hold_off()`;
`compositor_internal.h`, `struct dmabuf_buffer.drawn_fp/drawn_fp_valid`): each dma-buf buffer
remembered the FNV-1a fingerprint it held when the compositor last drew from it. A commit whose
buffer still held exactly that fingerprint was held. Disabled by a `<session log>.no-content-hold`
marker.

**Why it failed.** `drawn_fp` answers "has this buffer's content changed since I last drew it",
which is the wrong question — during the ring's rotation the answer is *no* for every slot, and the
compositor drives its own render loop from one of them. Session `2026-10-15-16-steam` on that build
measured it directly:

```
perf last 10 s: 601 ticks, 41 scenes, 41 on screen (copy) | commits 41 held-same 14 held-blank 27
perf last 10 s: 601 ticks, 46 scenes, 46 on screen (copy) | commits 46 held-same 15 held-blank 31
```

Every guest commit held (14+27 of 41, 15+31 of 46) while the compositor kept rendering 41-46 scenes/s
from the buffer it already had — a live render loop over a frozen picture. That is the freeze the
user reports, and `gen` (the drawn-commit generation) stopped advancing entirely: `184 -> 184` across
20 s of real input.

**The measurement that had said "97% held" was measuring the wrong thing.** Holding 97% of commits is
not success; it is the bug. The A/B tables in the earlier revision of this section (marker flips on
`-15-steam`) are accurate as *counts* and were misread as *progress*. With the rule reverted
(compositor byte-identical to HEAD, hot-swapped, session `2026-10-15-17-steam`) the same counters show
`held` gone and `gen` advancing normally — `171 -> 314 -> 627` under input — with the ring's two
contents alternating on screen again (`8c43d86c` / `0bf7c651`). That alternation is the ping-pong,
and it is the thing that still has to be fixed; a freeze is not an improvement on it.

**What the failure teaches.** Any rule built on *buffer content identity* is unsound here, because
the guest re-presents its ring with unchanged contents as a matter of course, and the compositor must
still draw one of them — the one the client means as current. The compositor has no channel that says
which slot that is: damage is constant (§8.3), and content identity is identical by construction. The
signal has to come from the guest, or from not treating the ring as three interchangeable frames.

**Log volume** (kept as a lesson even though the rule is out): holding is the normal state in the
regime this acted on, so the per-hold `framedbg` line was rate-limited to one in 200 with the real
count in `perf`; the unbounded version wrote 3.8 MB of `HOLD-SAME` per session, and `wayland.log` is
one file the app shares.

### 7.3 Defect A: don't rely on the movie being dismissed

- Cheapest test of the hypothesis: blank/remove `StartupMovie` (`SessionFiles.kt:224-232`,
  `ensureStartupMovieDefault:259`) and see whether the stall and the ping-pong both change. The user
  may not want to lose the branded boot movie — ask before shipping this.
- Or keep the movie and dismiss it deliberately: watch CDP for the BPM page reaching
  `index.html`, then send one **non-selecting** D-pad nudge (A selects "Account Name" and opens the
  keyboard — that is what typed `gggggg` into the field during this session).

### 7.4 The existing nudge logic needs two fixes

`PadBridge.nudge()` (working tree) pulses `PadState.A`:
- **Should pulse D-pad down/up**, not A — A selects whatever has focus. [E: the pad taps typed
  `gggggg` into the account field]
- `SessionActivity.kt:388` cancels the nudges on *any* player input
  (`bridge.setOnPlayerInput { stopSteamWakeNudges(…) }`), which kills them ~60 s before CEF is
  ready. Gate that on the Steam UI actually having presented (CDP: BPM url `!= about:blank`).

### 7.5 Compositor-side content gating / ring protection (mitigation & fix candidate)

The live dma-buf import branch is at `compositor.c:823-838` (`if (b->img)`).
When gamescope rotates through its 3 dma-buf pool (`res_A` -> `res_B` -> `res_C`), `s->dmabuf != buffer`
is true on every commit, unconditionally executing `s->commit_gen++`.
Because `surface_translucent()` returns 1 for Plane 1 subsurface (`sc_layer_candidate()` returns -1),
the compositor drops to the `vkp_render()` copy path (`compositor.c:2328`), which has NO same-frame
guard or deduplication check.
Options:
1. Detect recurring ring commits on `s->dmabuf` and check whether the underlying buffer content or fence has changed.
2. Fix gamescope subsurface opacity so Plane 1 qualifies for direct layer presentation (`dl.d[1].blend = 0`).

**Measured since (§3.8): both are mitigations only.** Option 1 in its identity form cannot fire — the
three ring buffers are three distinct `wl_buffer`s, so `s->dmabuf != buffer` is true 100% of the time
and `commit_gen++` runs unconditionally. The only version that can work is **content-based**: hash or
mean the incoming buffer (the probe in §3.8 already does the cheap version) and hold the last
non-blank frame when the guest hands over a blank one. That is a real tradeoff, not a free win: a
genuinely dark scene (a fade, a black loading screen, a dark game) would be held too, so it needs a
bound (only while the previous frame was bright, or for N frames), and it hides a guest bug rather
than fixing it. It is worth having anyway — the flicker is the user-visible defect and this is the
only lever this repo owns.

**IMPLEMENTED AND VERIFIED (session `2026-10-14-03-steam`).** The content gate is in
`compositor.c`: `dmabuf_sample()` mmaps the incoming linear buffer and returns `{mean, max, hi>32,
samples}`; `blank_frame_hold()` holds a commit whose mean is below `BLANK_MEAN` while a frame with
content arrived within `BLANK_HOLD_NS`, releasing the held buffer to the client as a replaced buffer
would be. It is wired into `take_dmabuf`'s `if (b->img)` branch as `const int hold =
blank_frame_hold(...); if (!hold && (s->dmabuf != buffer || s->dmabuf_buf != b)) { … }` — it gates
only the buffer *swap*, so `g_stat_dmabuf`, the size-change log and the HUD tick at the tail of
`take_dmabuf` all still run.

Measured with the guest's real ring live (guide button opened the QAM), the same probe as §3.8:

```
res 0x74e086b3d0  mean=91  hi=1118/2170   <- the composited UI
res 0x74e086a9b0  mean=12  hi=130/2170    <- blank
res 0x74e0824420  mean=12  hi=130/2170    <- blank
strict period 3, guest alternation unchanged
```

`BLANK_MEAN 16` was **not enough**: an earlier run measured a second partial frame at `mean=25`
(`hi=282/2170`, the UI panel drawn except a right sliver), which passed as "content" and still
flickered (`R=75.98` <=> `R=24.27`, spread 51.7). `BLANK_MEAN` is therefore **40** (between the
measured 25 and 78/91) and `BLANK_HOLD_NS` is **1200000000** (1.2 s; the measured content-frame
interval is up to ~950 ms, so 600 ms released mid-cycle).

Screen capture, 20 frames each:

| Build | Screen R | Spread |
|---|---|---|
| gate on | 82.88 only | **0.00** |
| gate off (`<log>.no-blank-hold`) | 14.29 <=> 82.88 | **68.59** |
| gate on again | 82.88 only | **0.00** |

The gate fired 122 times in the session; every hold reports a content age <= 946 ms, under the 1.2 s
bound. The A/B (marker added then removed on the same running session) is what proves the gate, not
the guest, is doing the stabilising — the guest keeps alternating throughout.

Caveats, unchanged: this **hides** the guest bug; the bound must exceed the client's content-frame
interval (raise `BLANK_HOLD_NS` if the guest runs slower); and a genuinely dark scene can be held
for up to the bound (1.2 s) before it reaches the screen. The diagnostic probe (`frame_probe`,
`<log>.frame-probe`) and the user's `rdbg` lines in `vk_present.c` are bring-up instrumentation, not
part of the fix. The gate only reaches users through a rebuilt
`libdroiddeckwayland.so` (i.e. a new APK); hot-swapping it into the installed 0.3.3 was how it was
tested here.

### 7.5a The gate's two measured holes (session `2026-10-14-04-steam`, released build)

The gate shipped in the released test build (`test-0.3.3-mali-frame-gate-20261014`) and was exercised
on-device with the marker A/B, not by hot-swapping:

| Window | Marker | Ring | Screen spread |
|---|---|---|---|
| gate on (baseline) | none | `85/67/67` | 17.93 |
| gate off | `<log>.no-blank-hold` | `85/67/67` | 17.93 |

Adding `.no-blank-hold` at 05:07 did stop `HOLD-BLANK` dead — the last such line is `05:05:33`, and
none follow — so the marker works. The screen did not change, because **the gate is inert whenever
every ring image is at or above `BLANK_MEAN`.** In the `85/67/67` regime all three are `>= 40`, so
`blank_frame_hold()` returns 0 for every commit. The gate only acts when the stale frames are
*dark*, i.e. only in the `85/85/6` / `85/13/13` regime.

The second hole is the dangerous one. In the `85/13/13` regime the gate holds the two dark commits
and draws the bright one. Whether that is correct depends entirely on **which** image is current —
which the compositor cannot know. The transition data shows the stale image can be the *bright* one:
at the 05:10:26 guide tap, `325` stayed at its 05:05:34 value (`85`) while `326`/`327` took the new
scene (`11`). With the gate on, that state is held on the stale bright frame — exactly the
`R=82.55, spread 0.00` "stuck bright" reading taken at the top of this session while the UI had gone
dark. Confirmed by A/B: gate off in that state gives `12.82 <=> 83.05` (spread 70.23), so the dark
frames are real and reachable and the gate is what suppresses them.

Neither hole is fixable by tuning `BLANK_MEAN`: the compositor has no way to distinguish a current
dark frame from a stale dark frame, or a current bright frame from a stale bright frame. The gate is
a cosmetic damper for the loudest regime, nothing more. A gate that *could* work would have to key on
the cycle (e.g. remember the last three content fingerprints and hold the one that repeats as
"stale"), which is guesswork layered on a guest bug.

**Superseded in part (2026-10-15):** the gate itself turned out to be a *symptom source* on the kbase
path, not just a damper. Its `mean < 40` test held real UI frames there (UI measures `mean` 12-42 with
`max=255`), freezing the display for up to `BLANK_HOLD_NS` per frame. The test is now `max == 0`, so
it can only suppress a fully black commit — see `panvk-mali-g77-bringup.md` §28 for the before/after
counts. The conclusion above is unchanged: the alternation is the guest's, and no threshold fixes it.

---

## 8. Dead ends (do not repeat)

| Knob | Result |
|---|---|
| `PANVK_AHB_WSI=1` | Gate stayed `sw_device=1`; zink could not create a swapchain. Worse. |
| `PANVK_KBASE_DRI3=raw` (+ `mailbox`) | Session died with `GLXBadCurrentWindow`; zink clients died. |
| `MESA_VK_WSI_PRESENT_MODE=fifo` | Unverified (claim withdrawn, §4.2). |
| `MESA_VK_WSI_PRESENT_MODE=mailbox` | No effect on the oscillation. |
| `gamescopectl screenshot` | **Segfaults gamescope** (§5.2). |
| `gamescopectl composite_force 1` | Tested live on session 34; oscillation persists unchanged (§7.1). |
| `gamescopectl overlay_unmultiplied_alpha 1` | Tested live on session 34; oscillation persists unchanged (§7.1). |
| `grim` / `xwd` / `import` / `ffmpeg` in the guest | Not installed (§5.1). |
| Compositor copy-path guard as "the fix" | Wrong layer for this configuration (§4.1). |
| `uiautomator dump` for the A/B pad | Returns a null root; `ui dump` shows only the FPS HUD (§3.7). |

---

## 9. Open questions (ranked)

1. ~~Is the alternating content produced by gamescope/Xwayland/PanVK, or by the compositor?~~
   **ANSWERED (§3.8): the guest.** All three of gamescope's output images are CPU-readable and were
   sampled directly: one carries the UI, two carry a blank frame, on a strict period of 3. The
   compositor's output mean tracks its input mean exactly. No compositor-side dedupe can fix it.
   Still open: *why* only one of three images gets the scene — see 1a below.
1a. **Why do the guest's three swapchain images not hold the same content?** Restated after §3.8a —
   the premise "only one image receives the scene" is **wrong**: over session `2026-10-14-04-steam`
   all three fds took many distinct values (`fd327` alone: `6,11,13,37,67,90`), so all three are
   updated *at some point*. The real questions are why the ring is (a) **presented continuously while
   nothing changes** (292 s / 1,046 commits of a frozen `85/67/67`) and (b) only ever updated in
   **bursts of 1-2 images**, so 3-image convergence is rare (one instance in 2,413 commits,
   `seq=1249`).
   Remaining candidates, ranked: (i) the PanVK WSI's acquire/present slot handling re-presenting
   images it did not re-blit; (ii) gamescope re-presenting its previous output image when the scene is
   unchanged instead of re-rendering; (iii) the client's own present not being rate-limited. (iii) is
   the cheapest to rule out and should be first. The `WSI_BLIT_SHM` slot theory is now ruled out: all
   12 allocations across 4 sessions carry a valid `alloc_shm` and three distinct `sw_host_ptr`s, and
   no `alloc_shm=(nil)` line exists anywhere (§9.3).
   **This is the last real defect: the compositor-side gate (§7.5) hides its symptom but does not
   answer it.** A content-identity hold (§7.2f) was tried as a second answer and **reverted** — it
   stops the rotation only by freezing the screen (nothing new is ever drawn), so it does not answer
   it either; the guest still presents stale slots.
2. **What is the white state?** A never-drawn/cleared buffer, a format/alpha misinterpretation, or a
   gamescope clear colour? The white background with preserved UI content is the strongest clue.
3. ~~**Why do 12 of 27 `WSI_BLIT_SHM` allocations return NULL?**~~ **WITHDRAWN (2026-10-14): not
   reproducible.** There are exactly 12 `WSI_BLIT_SHM` allocation lines across every log on disk
   (3 per session x 4 sessions), each with a valid `alloc_shm` and one of three distinct
   `sw_host_ptr`s (`size=4608000` = 1600x720x4). No `alloc_shm=(nil)` or `sw_host_ptr=(nil)` line
   exists in any log. `alloc_shm=(nil)` in `WSI_CPU_CONFIG`/`GDPA` means the *callback pointer has not
   been filled in yet* (the pre-init pass, logged alongside `swapchain_ext=0`), not a failed
   allocation.
4. **Which of the three states is the correct one?** Nobody has compared against a known-good
   reference (e.g. Steam on a working Mali device or the same app with `sw=0`).
5. Session 33's `backend_info` reported `Total Presents Queued/Completed/In Flight: 0/0/0` and
   `Uses Vulkan Swapchain: false` while the screen was clearly presenting — unexplained.

---

## 10. Artifacts on disk

- `pingpong_test/s33/` — the reproduction: `f01..f24.png`, `g30..g45.png`, `h50..h67.png` (the
  idle alternation series). 58 files.
- `pingpong_test/s33_wayland.log`, `session30.log`, `s31_wayland.log` — pulled logs.
- `pingpong_test/t2_f1..f8.png` — the pre-FIFO session-29 series (whole-screen alternation).
- `pingpong_test/frame1..6.png` — the original session-28 evidence
  (md5 `29eda668…` / `f22fb099…` / `6eca3627…` / `f22fb099…` → keyboard open/closed, ~23% of the
  full 2400x1080 screen flipping).
- `pingpong_test/ui_dump.xml`, `ui.xml`, `fifo*.png`, `s31/` (video + extracted frames).
- `.ppv/s01` (frozen dark + OSK), `.ppv/s03` (bright UI, `R=88.8`), `.ppv/s04` (the live
  alternation: `k01 R=13.2` dark / `k02 R=88.8` light), `.ppv/s38` (the previous session's
  `f01..f16`). Session `2026-10-14-01-steam` logs at `/home/ryanistr/droiddeck-ci/art/dd01-*.log`;
  `dd01-w6.log` is the ping-pong window (2,196 probe lines).
- `/home/ryanistr/droiddeck-ci/art/dd02-wayland.log` (the gate with `BLANK_MEAN 16`: the `mean=25`
  partial frame passes as content and the screen still flickers) and `dd02b-wayland.log` (the live
  ping-pong measured with the guide button: `mean=78/10/25`, period 3; 83 holds on the `mean=10`
  buffer only). `dd03-wayland.log` / `dd03b-wayland.log` are the finished gate
  (`BLANK_MEAN 40`, `BLANK_HOLD_NS 1.2 s`) holding a still-alternating guest: 122 `HOLD-BLANK`, max
  content age 946 ms.
- `/home/ryanistr/droiddeck-ci/gateshots/` — the raw captures behind the §7.5 table (`g*`/`h*`
  static dark, `p*` the threshold-16 ping-pong `R=75.98 <=> 24.27`, `q*` gate-on spread 0.00,
  `r*` gate-off spread 68.59, `s*` gate-on again, `t*`/`u*`/`v*` bright).
- `/home/ryanistr/droiddeck-ci/art/dd05…dd07-wayland.log` — session `2026-10-14-04-steam` on the
  released build. **`dd07-wayland.log` is the one behind §3.8a**: 2,413 probe rows, the full
  transition history, 429 `HOLD-BLANK` before the `.no-blank-hold` marker at 05:07 and none after.
  `dd06-session.log`/`dd06-app.log` hold the guest's PanVK WSI setup (3 `WSI_BLIT_SHM` at
  `size=4608000` with distinct `sw_host_ptr`s, 3 `IMAGE_BIND`, `sw=1` present, `minImageCount: 3`).
- `/home/ryanistr/droiddeck-ci/gateshots2/` and `/home/ryanistr/droiddeck-ci/shots/` — the §3.8a and
  §7.5a captures (`gon_before/` = `85/67/67` gate on, `goff/` = gate off and identical, `tap_after/`
  = the `85/11/11` state at `R=12.82 <=> 83.05`).
- `/home/ryanistr/droiddeck-ci/scripts/probe_timeline.py`, `scripts/measure.py` — the probe
  transition extractor and the ImageMagick mean-R capturer behind the tables above.
- `docs/development/panvk-mali-g77-bringup.md` — driver bring-up background (DXVK-zink on PanVK,
  CEF GPU flags, `/sys/kernel/ged/hal/gpu_utilization` for real GPU load).

### Appendix: the launch line (unchanged, for reference)

```sh
steam -gamepadui -steamdeck -steamos3 -cef-enable-debugging -devtools-port 35892 \
  -clientbeta steamdeck_publicbeta -no-cef-sandbox -cef-force-gpu \
  -cef-ozone-platform=x11 -cef-use-gl=angle -cef-use-angle=vulkan
gamescope --backend wayland --expose-wayland -c -f -W 1600 -H 720 -w 1600 -h 720 -r 60 -e \
  --xwayland-count 2 --default-touch-mode 4 --mangoapp -- …
```
CEF GPU acceleration is verified durable (was 0% GPU / software raster before; now ~45–55% at
`/sys/kernel/ged/hal/gpu_utilization`).
