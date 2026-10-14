#!/usr/bin/bash
# Builds the Mali-G57 fork's PanVK (Valhall v9 JM + v10/v11 CSF + kbase, dma-buf fd interop) for
# aarch64 glibc: the runtime's PanVK on every Mali generation this fork drives. It is published as
# "PanVK-rynx-<version>-<patch>", the code name that tells this fork's own build apart from any
# other PanVK package when the driver names itself in the menus and the device report.
#
# The runtime's shipped PanVK has no v9 backend (panvk_v9 = 1 symbol) and upstream Mesa's JM
# backend is Bifrost-only, so a Valhall device enumerates nothing and gamescope reports "failed to
# find physical device". This fork carries a real v9 JM backend with its own kbase driver, so it
# needs no libpanvk_kbase_jm.so beside it. Its CSF backends (v10 for a G610, v11 for a G615/G715)
# come from the same build and the patches in tools/panvk-g57/patches/.
#
# Built natively on an arm64 runner. Outputs: panvk-linux-g57-<VERSION>.tzst + .sha256
set -euxo pipefail

VERSION=${PANVK_G57_VERSION:-1.2.0}
# The published patch level, so the driver names itself the way its tag does
# (panvk-linux-g57-1.2.0-p5 -> PanVK-rynx-1.2.0-p5). Empty for an unreleased build.
PATCH=${PANVK_G57_PATCH:-}
CODENAME="PanVK-rynx-$VERSION${PATCH:+-$PATCH}"
G57_REPO=${PANVK_G57_REPO:-https://github.com/apexspan-svg/mesa-panvk-mali-g57.git}
WORK="${GITHUB_WORKSPACE:-$PWD}"

git clone -q --depth 1 --branch "v$VERSION" "$G57_REPO" mesa-g57
cd mesa-g57

# Glibc has no AHardwareBuffer, so the AHB-only helpers the WSI path calls are not compiled in;
# without this the whole file fails to build outside __ANDROID__.
for p in "$WORK/tools/panvk-g57/patches"/*.patch; do
    [ -f "$p" ] && patch -p1 --no-backup-if-mismatch < "$p"
done

# The fork's v1.2.0 arch lists stop at Valhall arch 10 and its model table has no arch-11 entry,
# so a Mali-G615 (Valhall gen3) enumerates nothing; 0003 ports arch 11 back in (genxml schema,
# per-arch build lists, arch dispatch and the G615/G715 models).
#
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

# v9, v10 and v11 backends present? A driver without v9 is exactly what this component replaces;
# v10 is the CSF backend a G610 (the GT 20 Pro) needs and v11 the one a G615/G715 needs, and arch
# 11 only exists behind 0003. Fail here rather than ship a driver that enumerates nothing on the
# generation it was published for. Checked before stripping: these are local symbols, and
# --strip-unneeded drops them.
readelf -sW "$WORK/out/usr/lib/libvulkan_panvk.so" | grep -q 'panvk_v9_' \
    || { echo "built driver has no v9 backend" >&2; exit 1; }
readelf -sW "$WORK/out/usr/lib/libvulkan_panvk.so" | grep -q 'panvk_v10_' \
    || { echo "built driver has no v10 CSF backend" >&2; exit 1; }
readelf -sW "$WORK/out/usr/lib/libvulkan_panvk.so" | grep -q 'panvk_v11_' \
    || { echo "built driver has no v11 backend (0003 not applied?)" >&2; exit 1; }
# 0004/0006/0007 are the CSF fixes (provoking-vertex fn address, single merged CSG, register file
# sized from the uAPI). They land in the CSF command files and in kbase_kmod.c, so a build whose
# objects are missing them cannot be told apart by symbols; the patch loop above is what guarantees
# them, and release.env pins the tag they were built into.
#
# 0005 (report the kbase node's rdev as the DRM device) and 0008 (advertise
# VK_EXT_physical_device_drm when those numbers are known) are one change in two files: filling the
# numbers without offering the extension leaves them unreadable, which is what a p5 session
# measured (see docs/development/panvk-mali-g77-bringup.md §22). Keep them together.
#
# The same binary also carries the fifth-gen CSF backends (v12/v13/v14, the arch list at
# src/panfrost/vulkan/meson.build:159 covers 6, 7, 9 through 14 and csf_archs starts at 10), so
# "one build, every Mali generation this fork drives" is already true here. gpuArch stops at 11 on
# purpose: v12+ has never been run on device from this tree, and gpuArch is what PanvkDriver's
# Mali auto-selection keys on, so advertising it would hand a G720-family device a driver nobody
# has measured.

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
    "name": "$CODENAME",
    "description": "Mesa PanVK $VERSION from the Mali-G57 fork, glibc: Valhall v9 through Job Manager and v10/v11 through CSF, on this fork's own kbase driver. Built by tools/panvk-g57/build-panvk-g57.sh with its patches applied.",
    "driverVersion": "$VERSION${PATCH:+-$PATCH}",
    "libc": "glibc",
    "minGlibc": "2.35",
    "gpuArch": [9, 10, 11],
    "sourceLibraryName": "libvulkan_panvk.so",
    "importedAt": 0
}
META

cd "$WORK/out"
tar --use-compress-program="zstd -19" -cf "$WORK/panvk-linux-g57-$VERSION.tzst" usr meta.json
sha256sum "$WORK/panvk-linux-g57-$VERSION.tzst" > "$WORK/panvk-linux-g57-$VERSION.tzst.sha256"
ls -l "$WORK/panvk-linux-g57-$VERSION.tzst"*
