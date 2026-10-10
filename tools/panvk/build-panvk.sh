#!/usr/bin/bash
# Builds Mesa with panvk + panfrost for arm64 glibc, natively on arm64 runner.
# Dependencies are installed in the "Install dependencies" workflow step.
# Outputs: panvk-linux-<VERSION>.tzst + .sha256
set -euxo pipefail

VERSION=${MESA_VERSION:-24.3.0}

# Use the repository directory as working directory
WORK="${GITHUB_WORKSPACE:-$PWD}"
cd "$WORK"

# Clone Mesa
git clone -q --depth 1 --branch mesa-"$VERSION" https://gitlab.freedesktop.org/mesa/mesa.git mesa
cd mesa

# Apply any patches
if [ -d "$WORK/tools/panvk/mesa-patches" ]; then
    for p in "$WORK/tools/panvk/mesa-patches"/*.patch; do
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
    -Dllvm=enabled \
    -Dshared-llvm=enabled \
    -Dvalgrind=disabled \
    -Dlibunwind=disabled \
    -Dglx=dri \
    -Dvulkan-beta=true

ninja -C builddir

# Verify the driver was built
test -f builddir/src/panvk/libvulkan_panvk.so

# Create output structure
mkdir -p "$WORK/out/usr/lib"
mkdir -p "$WORK/out/usr/share/vulkan/icd.d"

# Copy the driver
cp builddir/src/panvk/libvulkan_panvk.so "$WORK/out/usr/lib/"
strip --strip-unneeded "$WORK/out/usr/lib/libvulkan_panvk.so"

# Generate ICD manifest (absolute path for the session)
cat > "$WORK/out/usr/share/vulkan/icd.d/panvk_icd.aarch64.json" <<ICD
{
    "file_format_version": "1.0.0",
    "ICD": {
        "library_path": "/usr/lib/libvulkan_panvk.so",
        "api_version": "1.1.274"
    }
}
ICD

# Create meta.json for the zip
cat > "$WORK/out/meta.json" <<META
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
cd "$WORK/out"
tar --use-compress-program="zstd -19" -cf ../panvk-linux-"$VERSION".tzst usr meta.json
sha256sum ../panvk-linux-"$VERSION".tzst > ../panvk-linux-"$VERSION".tzst.sha256
ls -l ../panvk-linux-"$VERSION".tzst*

# Copy results out
cp ../panvk-linux-"$VERSION".tzst "$WORK/"
cp ../panvk-linux-"$VERSION".tzst.sha256 "$WORK/"