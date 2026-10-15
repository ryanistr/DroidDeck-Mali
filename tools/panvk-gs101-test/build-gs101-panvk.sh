#!/usr/bin/bash
# Experiment: build PanVK from m8l8th814n-eng/mesa-gs101's driver-side patches ONLY (no kernel
# patches), which is NOT our Mali-G57 fork. Two arms, both published as artifacts, never as a
# release and never into the apk's pins:
#
#   arm=gsl   "pure": upstream Mesa at the gs101 tree's own base commit + its patches 0001-0005.
#             Nothing of ours is in the source, so a failure here is a statement about THEIR tree.
#   arm=ours  our Mali-G57 fork (v1.2.0) + our patches, then their patches on top. This is the
#             arm that would actually be shipped if the experiment succeeds.
#
# Purpose: the gs101 tree claims a fix for "transaction elimination produces stale tiles", which
# sounds like this fork's frame ping-pong. Analysis says it cannot reach our path (our guest renders
# through PanVK, where no CRC/TE code exists; the change lives in gallium panfrost's pan_resource.c,
# and our build has -Dgallium-drivers= empty). This build settles it by measurement instead.
set -euxo pipefail

ARM=${GS101_ARM:-gsl}
WORK=${GITHUB_WORKSPACE:-$PWD}
BASE_COMMIT=dc330577adb9e98363ef6061e09062f171d0d053
PATCHDIR="$WORK/tools/panvk-gs101-test/patches"
OUTDIR="$WORK/out-gs101-$ARM"

rm -rf "$OUTDIR" mesa-gs101
mkdir -p "$OUTDIR/usr/lib"

# --- 0003 is the only patch in the set that touches a content/CRC path. It reads
# dev->model->quirks.no_crc, but no model in the set ever SETS that flag, so it is inert as written.
# arm=gsl+fix patches the intent in so the arm actually tests the mechanism rather than the typo.
apply_set() {
    for p in 0001-panfrost-add-Mali-G78 \
             0002-panfrost-advertise-Mali-G77 \
             0003-panfrost-disable-transaction-elimination-on-G78 \
             0004-panvk-valhall-v9 \
             0005-panvk-indirect-draws; do
        echo "== applying $p"
        patch -p1 --no-backup-if-mismatch < "$PATCHDIR/$p.patch" || true
    done
}

case "$ARM" in
gsl)
    git clone -q https://gitlab.freedesktop.org/mesa/mesa.git mesa-gs101
    cd mesa-gs101
    git checkout -q "$BASE_COMMIT"
    apply_set
    ;;
gsl+fix)
    git clone -q https://gitlab.freedesktop.org/mesa/mesa.git mesa-gs101
    cd mesa-gs101
    git checkout -q "$BASE_COMMIT"
    apply_set
    # set the quirk the patch reads on the Valhall arch-9 rows (G77 0x9000, G78 0x9222).
    # MODEL_QUIRKS(.no_crc = true) is the tree's own macro (pan_model.c:58) and goes in as the
    # trailing vararg of VALHALL_MODEL; there is no single-flag MODEL_QUIRK() macro.
    python3 "$WORK/tools/panvk-gs101-test/set-no-crc-quirk.py" src/panfrost/model/pan_model.c
    grep -n "no_crc" src/panfrost/model/pan_model.c || true
    ;;
ours)
    git clone -q --depth 1 --branch v1.2.0 https://github.com/apexspan-svg/mesa-panvk-mali-g57.git mesa-gs101
    cd mesa-gs101
    for p in "$WORK/tools/panvk-g57/patches"/*.patch; do
        patch -p1 --no-backup-if-mismatch < "$p" || true
    done
    # Only 0003 goes on top, and that is the whole point of this arm: 0004/0005 are a v9 JM backend
    # and its draws, which this fork already has (applied blindly they do NOT merge - they hit
    # sibling code that moved, giving `heap_templ has no member`, `too many arguments to
    # panvk_cmd_draw` and `#endif without #if`). 0001/0002 are model-table rows this fork already
    # carries for G77. The claim under test is only about transaction elimination, which is 0003.
    echo "== applying 0003 only (the transaction-elimination change) on top of the fork"
    patch -p1 --no-backup-if-mismatch < "$PATCHDIR/0003-panfrost-disable-transaction-elimination-on-G78.patch" || true
    python3 "$WORK/tools/panvk-gs101-test/set-no-crc-quirk.py" src/panfrost/model/pan_model.c
    grep -n "no_crc" src/panfrost/model/pan_model.c || true
    ;;
*)
    echo "unknown GS101_ARM=$ARM" >&2; exit 2;;
esac

# Build what our fork's script builds: the Vulkan driver only, on the fork's kbase KMD. For a pure
# upstream tree there is no -Dpanvk-use-kbase / -Dpanfrost-kmds=kbase, so gsl uses upstream's
# panfrost KMD (it would then need the upstream panfrost DRM driver, which this device does not have
# - that difference is itself part of the result).
if [ "$ARM" = "gsl" ] || [ "$ARM" = "gsl+fix" ]; then
    # gs101's own patches are written against gallium panfrost being built (0003 patches
    # gallium/drivers/panfrost/pan_resource.c), so build both drivers here, unlike our fork's script.
    # llvm must stay ENABLED: gallium panfrost needs CLC, and meson refuses -Dllvm=disabled with
    # "Feature llvm cannot be enabled: CLC requires LLVM" (first run of this arm failed on exactly
    # that, not on the patches).
    meson setup builddir \
        -Dbuildtype=release \
        -Dvulkan-drivers=panfrost \
        -Dgallium-drivers=panfrost \
        -Dplatforms=wayland,x11 \
        -Ddebug=false -Dbuild-tests=false \
        -Dprefix=/usr -Dlibdir=lib
else
    meson setup builddir \
        -Dbuildtype=release \
        -Dvulkan-drivers=panfrost \
        -Dgallium-drivers= \
        -Dplatforms=wayland,x11 \
        -Dpanvk-use-kbase=true \
        -Dpanfrost-kmds=kbase,panthor \
        -Ddebug=false -Dbuild-tests=false \
        -Dprefix=/usr -Dlibdir=lib
fi

ninja -C builddir src/panfrost/vulkan/libvulkan_panfrost.so

cp builddir/src/panfrost/vulkan/libvulkan_panfrost.so "$OUTDIR/usr/lib/libvulkan_panvk.so"
strip --strip-unneeded "$OUTDIR/usr/lib/libvulkan_panvk.so"

# Which arch backends made it in? This is the first thing to check: a v9 JM backend is what this
# device needs, and the gs101 patch adds one while our fork already has one.
echo "== backend symbols in the built driver:"
for a in v9 v10 v11 v12; do
    printf '  panvk_%s: %s\n' "$a" "$(nm -D "$OUTDIR/usr/lib/libvulkan_panvk.so" | grep -c "_${a}_" || true)"
done
strings "$OUTDIR/usr/lib/libvulkan_panvk.so" | grep -cE 'panvk_kbase' | sed 's/^/  kbase strings: /' || true

# A kbase-based build needs no libpanvk_kbase_jm beside it (our fork carries the kbase driver
# itself); a pure upstream build's panvk has no kbase backend at all.
if [ "$ARM" = "ours" ]; then
    cargo build --manifest-path "$WORK/app/src/main/rust/panvk-kbase-jm/Cargo.toml" \
        --release --locked --target aarch64-unknown-linux-gnu || true
    cp "$WORK/app/src/main/rust/panvk-kbase-jm/target/aarch64-unknown-linux-gnu/release/libpanvk_kbase_jm.so" \
       "$OUTDIR/usr/lib/libpanvk_kbase_jm.so" 2>/dev/null || true
fi

mkdir -p "$OUTDIR/usr/share/vulkan/icd.d"
cat > "$OUTDIR/usr/share/vulkan/icd.d/panvk_icd.aarch64.json" <<ICD
{
    "file_format_version": "1.0.0",
    "ICD": {
        "library_path": "/usr/lib/libvulkan_panvk.so",
        "api_version": "1.1.274"
    }
}
ICD

cat > "$OUTDIR/meta.json" <<META
{
    "name": "PanVK-gs101-$ARM-experiment",
    "arm": "$ARM",
    "base": "$BASE_COMMIT",
    "note": "experiment only - never a release, never in tools/panvk/release.env"
}
META

tar -c -C "$OUTDIR" . | zstd -19 -T0 -o "$WORK/panvk-gs101-$ARM.tzst"
sha256sum "$WORK/panvk-gs101-$ARM.tzst" > "$WORK/panvk-gs101-$ARM.tzst.sha256"
echo "== built $WORK/panvk-gs101-$ARM.tzst"
