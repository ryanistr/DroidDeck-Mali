#!/usr/bin/bash
# Builds Mesa with panvk for arm64 bionic (Android NDK), for the compositor driver.
# Outputs: panvk-android-<VERSION>.tzst + .sha256
set -euxo pipefail

VERSION=${MESA_VERSION:-24.3.0}
NDK_VERSION=${DROIDDECK_NDK_VERSION:-r27c}
WORK=/work
cd "$WORK"

# Use the NDK from the SDK
NDK_PATH="${ANDROID_HOME}/ndk/${NDK_VERSION}"
if [[ ! -d "${NDK_PATH}" ]]; then
    echo "NDK not found at ${NDK_PATH}"
    exit 1
fi

docker run --rm --platform linux/amd64 \
    --user "$(id -u):$(id -g)" \
    -v "$PWD:/work" -w /work \
    -v "${NDK_PATH}:${NDK_PATH}" \
    -e NDK="${NDK_PATH}" \
    droiddeck-local-cross:24.04-v3 bash -c '
set -euxo pipefail
apt-get update -qq
apt-get install -y -qq --no-install-recommends \
    git meson ninja-build pkg-config \
    libwayland-dev libdrm-dev libx11-dev libxext-dev \
    libxcb1-dev libxcb-dri3-dev libxcb-present-dev \
    libxcb-sync-dev libxshmfence-dev libxxf86vm-dev \
    python3-mako python3-ply python3-yaml \
    zstd >/dev/null

# Android NDK cross-compile for aarch64
export ANDROID_NDK_ROOT='"${NDK_PATH}"'
export CC=aarch64-linux-android35-clang
export CXX=aarch64-linux-android35-clang++
export AR=llvm-ar
export STRIP=llvm-strip
export PKG_CONFIG_PATH=/usr/lib/aarch64-linux-android/pkgconfig

# Clone Mesa
git clone -q --depth 1 --branch mesa-'"$VERSION"' https://gitlab.freedesktop.org/mesa/mesa.git mesa
cd mesa

# Apply any patches
if [ -d /work/tools/panvk/mesa-patches ]; then
    for p in /work/tools/panvk/mesa-patches/*.patch; do
        [ -f "$p" ] && patch -p1 --no-backup-if-mismatch < "$p"
    done
fi

# Build panvk only (no gallium drivers needed for Android)
meson setup builddir \
    -Dvulkan-drivers=panfrost \
    -Dgallium-drivers= \
    -Dplatforms=android \
    -Dbuildtype=release \
    -Dprefix=/usr \
    -Dlibdir=lib \
    -Degl=enabled \
    -Dgles1=enabled \
    -Dgles2=enabled \
    -Dopengl=true \
    -Dgbm=enabled \
    -Ddri3=disabled \
    -Dllvm=disabled \
    -Dshared-llvm=disabled \
    -Dvalgrind=disabled \
    -Dlibunwind=disabled \
    -Dselinux=disabled \
    -Dglx=disabled \
    -Dvulkan-beta=true \
    -Dandroid-stub=true

ninja -C builddir

# Verify the driver was built
test -f builddir/src/panvk/libvulkan_panvk.so

# Create output structure
mkdir -p /work/out/usr/lib
mkdir -p /work/out/usr/share/vulkan/icd.d

# Copy the driver
cp builddir/src/panvk/libvulkan_panvk.so /work/out/usr/lib/
llvm-strip --strip-unneeded /work/out/usr/lib/libvulkan_panvk.so

# Generate ICD manifest
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
    "kind": "android-vulkan-icd",
    "name": "Mesa PanVK '"$VERSION"' (Android)",
    "driverVersion": "'"$VERSION"'",
    "libc": "bionic",
    "minApi": 28,
    "sourceLibraryName": "libvulkan_panvk.so",
    "importedAt": 0
}
META

# Package as .tzst
cd /work/out
tar --use-compress-program="zstd -19" -cf ../panvk-android-'"$VERSION"'.tzst usr meta.json
sha256sum ../panvk-android-'"$VERSION"'.tzst > ../panvk-android-'"$VERSION"'.tzst.sha256
ls -l ../panvk-android-'"$VERSION"'.tzst*
'

# Copy results out
cp /work/panvk-android-"$VERSION".tzst .
cp /work/panvk-android-"$VERSION".tzst.sha256 .