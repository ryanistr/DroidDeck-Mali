#!/usr/bin/bash
# Builds the Mali-G57 fork's PanVK (v9 JM + kbase, dma-buf fd interop) for aarch64 glibc.
#
# The runtime's shipped PanVK has no v9 backend (panvk_v9 = 1 symbol) and upstream Mesa's JM
# backend is Bifrost-only, so a Valhall device enumerates nothing and gamescope reports "failed to
# find physical device". This fork carries a real v9 JM backend with its own kbase driver, so it
# needs no libpanvk_kbase_jm.so beside it.
#
# Built natively on an arm64 runner. Outputs: panvk-linux-g57-<VERSION>.tzst + .sha256
set -euxo pipefail

VERSION=${PANVK_G57_VERSION:-1.2.0}
G57_REPO=${PANVK_G57_REPO:-https://github.com/apexspan-svg/mesa-panvk-mali-g57.git}
WORK="${GITHUB_WORKSPACE:-$PWD}"

git clone -q --depth 1 --branch "v$VERSION" "$G57_REPO" mesa-g57
cd mesa-g57

# Glibc has no AHardwareBuffer, so the AHB-only helpers the WSI path calls are not compiled in;
# without this the whole file fails to build outside __ANDROID__.
for p in "$WORK/tools/panvk-g57/patches"/*.patch; do
    [ -f "$p" ] && patch -p1 --no-backup-if-mismatch < "$p"
done

# -Dpanfrost-kmds=kbase is what defines HAVE_PAN_KMOD_KBASE; the fork's own bionic build script
# uses -Dpanvk-use-kbase=true alongside it, and both are required.
# wayland is the WSI the session's gamescope presents through. -Dgallium-drivers= is left empty on
# purpose: only the Vulkan driver is wanted here.
meson setup builddir \
    -Dbuildtype=release \
    -Dvulkan-drivers=panfrost \
    -Dgallium-drivers= \
    -Dplatforms=wayland,x11 \
    -Dpanvk-use-kbase=true \
    -Dpanfrost-kmds=kbase,panthor \
    -Ddebug=false \
    -Dbuild-tests=false \
    -Dprefix=/usr \
    -Dlibdir=lib

ninja -C builddir src/panfrost/vulkan/libvulkan_panfrost.so

test -f builddir/src/panfrost/vulkan/libvulkan_panfrost.so

mkdir -p "$WORK/out/usr/lib" "$WORK/out/usr/share/vulkan/icd.d"
cp builddir/src/panfrost/vulkan/libvulkan_panfrost.so "$WORK/out/usr/lib/libvulkan_panvk.so"

# v9 backend present? A driver without it is exactly what this component replaces, so fail
# here rather than ship one. Checked before stripping: these are local symbols, and
# --strip-unneeded drops them.
readelf -sW "$WORK/out/usr/lib/libvulkan_panvk.so" | grep -q 'panvk_v9_' \
    || { echo "built driver has no v9 backend" >&2; exit 1; }

strip --strip-unneeded "$WORK/out/usr/lib/libvulkan_panvk.so"

# The fork gates the external memory/semaphore fd group on this; gamescope requires
# VK_KHR_external_memory_fd, VK_EXT_external_memory_dma_buf and VK_KHR_external_semaphore_fd,
# and a build without the gate cannot advertise them.
strings -a "$WORK/out/usr/lib/libvulkan_panvk.so" | grep -q 'PANVK_FD_INTEROP' \
    || { echo "built driver predates the PANVK_FD_INTEROP gate" >&2; exit 1; }

# LLVM, libclc and the SPIR-V translator are only needed to build mesa_clc, which compiles
# libpoly's shaders during the build. Nothing of that may end up in the driver the runtime loads.
if readelf -dW "$WORK/out/usr/lib/libvulkan_panvk.so" | grep -qiE 'libLLVM|libclc|libLLVMSPIRV|libSPIRV'; then
    echo "driver links LLVM/SPIRV, which it must not" >&2
    readelf -dW "$WORK/out/usr/lib/libvulkan_panvk.so" | grep -iE 'NEEDED' >&2
    exit 1
fi

cat > "$WORK/out/usr/share/vulkan/icd.d/panvk_icd.aarch64.json" <<ICD
{
    "file_format_version": "1.0.1",
    "ICD": {
        "api_version": "1.3.354",
        "library_arch": "64",
        "library_path": "/usr/lib/libvulkan_panvk.so"
    }
}
ICD

cat > "$WORK/out/meta.json" <<META
{
    "schemaVersion": 1,
    "kind": "linux-vulkan-icd",
    "name": "Mesa PanVK $VERSION (Mali-G57 fork, v9 JM kbase)",
    "driverVersion": "$VERSION",
    "libc": "glibc",
    "minGlibc": "2.35",
    "sourceLibraryName": "libvulkan_panvk.so",
    "importedAt": 0
}
META

cd "$WORK/out"
tar --use-compress-program="zstd -19" -cf "$WORK/panvk-linux-g57-$VERSION.tzst" usr meta.json
sha256sum "$WORK/panvk-linux-g57-$VERSION.tzst" > "$WORK/panvk-linux-g57-$VERSION.tzst.sha256"
ls -l "$WORK/panvk-linux-g57-$VERSION.tzst"*
