#!/usr/bin/bash
# Builds Mesa with panvk + panfrost for arm64 glibc, natively on arm64 runner.
# Outputs: panvk-linux-<VERSION>.tzst + .sha256
set -euxo pipefail

VERSION=${MESA_VERSION:-24.3.0}
WORK=/work
cd "$WORK"

# Native arm64 build (runner is already arm64)
# Install build dependencies
apt-get update -qq
apt-get install -y -qq --no-install-recommends \
    git meson ninja-build pkg-config \
    libwayland-dev libdrm-dev libx11-dev libxext-dev \
    libxcb1-dev libxcb-dri3-dev libxcb-present-dev \
    libxcb-sync-dev libxshmfence-dev libxxf86vm-dev \
    libglvnd-dev libegl-dev libgles-dev \
    python3-mako python3-ply python3-yaml \
    llvm-17-dev clang-17 lld-17 \
    zstd >/dev/null

# Clone Mesa
git clone -q --depth 1 --branch mesa-"$VERSION" https://gitlab.freedesktop.org/mesa/mesa.git mesa
cd mesa

# Apply any patches
if [ -d /work/tools/panvk/mesa-patches ]; then
    for p in /work/tools/panvk/mesa-patches/*.patch; do
        [ -f "$p" ] && patch -p1 --no-backup-if-mismatch < "$p"
    done
fi

# Build panvk + panfrost
meson setup builddir \
    -Dvulkan-drivers=panfrost \
    -Dgallium-drivers=panfrost \
    -Dplatforms=wayland,x11 \
    -Dbuildtype=release \
    -Dprefix=/usr \
    -Dlibdir=lib \
    -Dshared-glapi=enabled \
    -Degl=enabled \
    -Dgles1=enabled \
    -Dgles2=enabled \
    -Dopengl=true \
    -Dgbm=enabled \
    -Ddri3=enabled \
    -Dllvm=enabled \
    -Dshared-llvm=enabled \
    -Dvalgrind=disabled \
    -Dlibunwind=disabled \
    -Dselinux=disabled \
    -Dglx=dri \
    -Dvulkan-beta=true

ninja -C builddir

# Verify the driver was built
test -f builddir/src/panvk/libvulkan_panvk.so

# Create output structure
mkdir -p /work/out/usr/lib
mkdir -p /work/out/usr/share/vulkan/icd.d

# Copy the driver
cp builddir/src/panvk/libvulkan_panvk.so /work/out/usr/lib/
strip --strip-unneeded /work/out/usr/lib/libvulkan_panvk.so

# Generate ICD manifest (absolute path for the session)
cat > /work/out/usr/share/vulkan/icd.d/panvk_icd.aarch64.json <<ICD
{
    "file_format_version": "1.0.0",
    "ICD": {
        "library_path": "/usr/lib/libvulkan_panvk.so",
        "api_version": "1.1.274"
    }
}
ICD

# Create meta.json for the zip
cat > /work/out/meta.json <<META
{
    "schemaVersion": 1,
    "kind": "linux-vulkan-icd",
    "name": "Mesa PanVK $VERSION",
    "driverVersion": "$VERSION",
    "libc": "glibc",
    "minGlibc": "2.35",
    "sourceLibraryName": "libvulkan_panvk.so",
    "importedAt": 0
}
META

# Package as .tzst
cd /work/out
tar --use-compress-program="zstd -19" -cf ../panvk-linux-"$VERSION".tzst usr meta.json
sha256sum ../panvk-linux-"$VERSION".tzst > ../panvk-linux-"$VERSION".tzst.sha256
ls -l ../panvk-linux-"$VERSION".tzst*

# Copy results out
cp /work/panvk-linux-"$VERSION".tzst .
cp /work/panvk-linux-"$VERSION".tzst.sha256 .