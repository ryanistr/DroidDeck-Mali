package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.droiddeck.launcher.R;
import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.TarZst;
import com.droiddeck.launcher.runtime.LinuxRuntime;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Imported LINUX PanVK Vulkan drivers: glibc PanVK ICDs for the runtime - the gamescope session
 * that runs Valve's native ARM64 Steam client, and the KDE Plasma desktop beside it. This is the
 * driver that DRAWS there on Mali GPUs: the client's own UI (OpenGL through the runtime's Zink/Panfrost),
 * every game the client launches (D3D through Proton's DXVK/VKD3D) and everything on the desktop.
 * Putting the frame on the screen stays the Android driver's job, in the app's compositor.
 *
 * <p>Not interchangeable with the Android driver: the client and its games are glibc processes and
 * cannot load a bionic object at all, and this one cannot be loaded by the app. The check that
 * separates them is the libc the driver links against - {@code libc.so.6} here, {@code libc.so} there.
 *
 * <p>Layout (under the app's files dir, which the session sees by its full host path):
 * <pre>
 *   files/linux_panvk_drivers/<id>/
 *       libvulkan_panvk.so      the driver, renamed to this from whatever the build called it
 *       icd.json                generated Vulkan ICD manifest; library_path = the .so's ABSOLUTE path
 *       meta.json               name / driverVersion / minGlibc, our own schema
 * </pre>
 * The session gets {@code icd.json}'s path in {@code BL_VK_DRIVER} and exports it as
 * {@code VK_DRIVER_FILES}, so nothing inside the runtime is modified and the choice is reversible.
 */
public class LinuxPanvkDriverManager {
    private static final String TAG = "LinuxPanvkDriver";
    public static final String DIR_NAME = "linux_panvk_drivers";
    public static final String LIB_NAME = "libvulkan_panvk.so";
    public static final String ICD_NAME = "icd.json";
    public static final String META_NAME = "meta.json";

    /**
     * Whether [base] is a library a PanVK build carries. Upstream's soname is
     * {@code libvulkan_panfrost.so}; this fork renames it to {@code libvulkan_panvk.so} for the
     * runtime. Both are accepted - a build is identified by being PanVK, not by a name we chose -
     * and whatever it was called is copied in as {@link #LIB_NAME}, which the ICD manifest points at.
     * A versioned suffix ({@code .so.1}) is the same library.
     */
    static boolean isPanvkLibrary(String base) {
        if (!(base.startsWith("libvulkan_panvk") || base.startsWith("libvulkan_panfrost"))) return false;
        int so = base.indexOf(".so");
        if (so < 0) return false;
        String trailing = base.substring(so + 3);
        return trailing.isEmpty() || trailing.matches("\\.\\d+");
    }

    private final Context context;
    private final File rootDir;

    public LinuxPanvkDriverManager(Context context) {
        this.context = context;
        this.rootDir = new File(context.getFilesDir(), DIR_NAME);
        if (!rootDir.exists()) rootDir.mkdirs();
    }

    public File getDriverDir(String id) {
        return new File(rootDir, id);
    }

    /** Installed = has the .so AND the generated manifest (a half-extracted dir is never offered). */
    public boolean isInstalled(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return false;
        File dir = getDriverDir(id);
        return new File(dir, LIB_NAME).isFile() && new File(dir, ICD_NAME).isFile();
    }

    /** Absolute path of the driver's ICD manifest, or null when the id isn't installed. */
    public String getIcdPath(String id) {
        return isInstalled(id) ? new File(getDriverDir(id), ICD_NAME).getAbsolutePath() : null;
    }

    public List<String> enumerateInstalledDrivers() {
        ArrayList<String> ids = new ArrayList<>();
        File[] dirs = rootDir.listFiles();
        if (dirs == null) return ids;
        for (File d : dirs) if (d.isDirectory() && isInstalled(d.getName())) ids.add(d.getName());
        Collections.sort(ids);
        return ids;
    }

    private JSONObject readMeta(String id) {
        try {
            File meta = new File(getDriverDir(id), META_NAME);
            if (!meta.isFile()) return null;
            return new JSONObject(FileUtils.readString(meta));
        } catch (Exception e) {
            return null;
        }
    }

    public String getDriverName(String id) {
        JSONObject m = readMeta(id);
        String name = m != null ? m.optString("name", "") : "";
        return name.isEmpty() ? id : name;
    }

    public String getDriverVersion(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("driverVersion", "") : "";
    }

    /** The glibc the driver asks for, as its zip recorded it ("" when the zip did not say). */
    public String getMinGlibc(String id) {
        JSONObject m = readMeta(id);
        return m != null ? m.optString("minGlibc", "") : "";
    }

    /**
     * The code name the PanVK built into the runtime carries, from the meta.json the staged driver
     * leaves at the rootfs root ("PanVK-rynx-<version>-<patch>"), or "" when the runtime is absent
     * or its root carries no PanVK meta. This is the driver the session falls back to when no
     * imported one is chosen, so the menus and the report can name it instead of saying "Built-in".
     */
    public String getBuiltinName() {
        JSONObject m = builtinMeta();
        return m != null ? m.optString("name", "") : "";
    }

    /** The version of the built-in PanVK, as {@link #getBuiltinName} reads it ("" when unknown). */
    public String getBuiltinVersion() {
        JSONObject m = builtinMeta();
        return m != null ? m.optString("driverVersion", "") : "";
    }

    private JSONObject builtinMeta() {
        try {
            File meta = new File(LinuxRuntime.rootDir(context), META_NAME);
            if (!meta.isFile()) return null;
            JSONObject m = new JSONObject(FileUtils.readString(meta));
            return "linux-vulkan-icd".equals(m.optString("kind", "")) ? m : null;
        } catch (Exception e) {
            return null;
        }
    }

    public void removeDriver(String id) {
        if (id == null || id.isEmpty() || id.contains("/") || id.contains("..")) return;
        Log.d(TAG, "removing imported Linux PanVK driver " + id);
        FileUtils.delete(getDriverDir(id));
        // A mode still set to this id falls back to the runtime's own driver at launch (logged there).
    }

    /**
     * Import a {@code -Linux.zip} containing PanVK. Returns the new driver id.
     * @throws IllegalArgumentException with a user-facing reason when the zip is not an importable
     *         Linux driver; {@link IOException} on read/extract failures.
     */
    public String installDriver(Uri zipUri, String displayName) throws IOException {
        return installDriver(zipUri, displayName, null);
    }

    /**
     * [installDriver] with the Mali architectures the driver was built with, when the caller knows
     * them (a release build's line does; a zip picked by hand usually does not).
     */
    public String installDriver(Uri zipUri, String displayName, int[] gpuArch) throws IOException {
        File tmpDir = newStagingDir();
        boolean keep = false;
        try {
            String soName = null;
            JSONObject zipMeta = null;
            try (InputStream is = context.getContentResolver().openInputStream(zipUri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                if (is == null) throw new IOException("cannot open " + zipUri);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    // Flatten: only the base name matters, and it also defeats zip-slip paths.
                    String base = new File(entry.getName()).getName();
                    if (base.isEmpty()) continue;
                    if (isPanvkLibrary(base)) {
                        if (soName != null) Log.w(TAG, "zip has several PanVK libraries; using the first (" + soName + ")");
                        else {
                            Files.copy(zis, new File(tmpDir, LIB_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING);
                            soName = base;
                        }
                    } else if (base.equals(META_NAME)) {
                        try {
                            zipMeta = new JSONObject(new String(readAll(zis), StandardCharsets.UTF_8));
                        } catch (Exception e) {
                            Log.w(TAG, "meta.json unreadable, ignoring: " + e.getMessage());
                        }
                    }
                    // The zip's own panvk_icd.aarch64.json is dropped on purpose: its
                    // library_path is relative to itself, and adopt() writes an absolute one.
                }
            }
            String id = adopt(tmpDir, soName, zipMeta, displayName, gpuArch);
            keep = true;
            return id;
        } finally {
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    /**
     * Install a published build straight from its release {@code .tzst} - the shape
     * {@code tools/panvk/build-panvk*.sh} produce and {@code tools/build_local.sh} unpacks into the
     * runtime ({@code usr/lib/libvulkan_panvk.so} beside a {@code meta.json} the importer already
     * writes). Same checks, layout and manifest as {@link #installDriver}, so a build downloaded
     * from a release - or picked by hand - lands as an ordinary imported driver.
     */
    public String installFromTzst(Uri tzst, String displayName) throws IOException {
        return installFromTzst(tzst, displayName, null);
    }

    /** [installFromTzst] with the Mali architectures the published build carries. */
    public String installFromTzst(Uri tzst, String displayName, int[] gpuArch) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(tzst)) {
            if (in == null) throw new IOException("cannot open " + tzst);
            return installFromTzst(in, displayName, gpuArch);
        }
    }

    /** [installFromTzst] for a driver already unpacking from a stream, such as a release download. */
    public String installFromTzst(InputStream source, String displayName) throws IOException {
        return installFromTzst(source, displayName, null);
    }

    /** [installFromTzst] with the Mali architectures the published build carries. */
    public String installFromTzst(InputStream source, String displayName, int[] gpuArch) throws IOException {
        File tree = newStagingDir();
        File tmpDir = newStagingDir();
        boolean keep = false;
        try {
            if (!TarZst.extract(source, tree)) throw new IOException("cannot unpack the driver");
            File so = findPanvk(tree);
            if (so == null) throw new IllegalArgumentException(context.getString(R.string.lvd_no_panvk_library));
            Files.copy(so.toPath(), new File(tmpDir, LIB_NAME).toPath(), StandardCopyOption.REPLACE_EXISTING);
            JSONObject meta = null;
            File metaFile = find(tree, META_NAME);
            if (metaFile != null) {
                try {
                    meta = new JSONObject(FileUtils.readString(metaFile));
                } catch (Exception e) {
                    Log.w(TAG, "meta.json in the unpacked driver is unreadable, ignoring: " + e.getMessage());
                }
            }
            String id = adopt(tmpDir, so.getName(), meta, displayName, gpuArch);
            keep = true;
            return id;
        } finally {
            FileUtils.delete(tree);
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    /** The first library a PanVK build can carry anywhere under [dir], or null. */
    private static File findPanvk(File dir) {
        File[] entries = dir.listFiles();
        if (entries == null) return null;
        for (File f : entries) {
            if (f.isDirectory()) {
                File hit = findPanvk(f);
                if (hit != null) return hit;
            } else if (isPanvkLibrary(f.getName())) {
                return f;
            }
        }
        return null;
    }

    /** First file named [name] anywhere under [dir], or null: a release build nests it under usr/lib. */
    private static File find(File dir, String name) {
        File[] entries = dir.listFiles();
        if (entries == null) return null;
        for (File f : entries) {
            if (f.isDirectory()) {
                File hit = find(f, name);
                if (hit != null) return hit;
            } else if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** An empty directory beside the installed drivers, for a driver's files before {@link #adopt}. */
    File newStagingDir() throws IOException {
        File tmpDir = new File(rootDir, ".tmp-" + System.nanoTime());
        FileUtils.delete(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("cannot create " + tmpDir);
        return tmpDir;
    }

    /**
     * Check a staged driver ({@link #LIB_NAME} in {@code tmpDir}, copied from {@code soName} in
     * the zip), write its manifest and meta.json, and move it in. Returns the id; the caller
     * deletes {@code tmpDir} when this throws.
     */
    String adopt(File tmpDir, String soName, JSONObject zipMeta, String displayName) throws IOException {
        return adopt(tmpDir, soName, zipMeta, displayName, null);
    }

    /** [adopt] with the Mali architectures the driver was built with, empty for an unnamed build. */
    String adopt(File tmpDir, String soName, JSONObject zipMeta, String displayName, int[] gpuArch) throws IOException {
        try {
            if (soName == null) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_no_panvk_library));
            }
            File so = new File(tmpDir, LIB_NAME);
            if (!isAarch64Elf(so)) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_not_aarch64, soName));
            }
            // The one check that actually separates this from the bionic builds. Both sonames
            // are in the driver's .dynstr, so a byte scan is enough and needs no ELF parsing:
            // glibc's is versioned ("libc.so.6"), bionic's is not ("libc.so").
            if (!containsAscii(so, "libc.so.6")) {
                throw new IllegalArgumentException(context.getString(R.string.lvd_panvk_not_glibc, soName));
            }
            String kind = zipMeta != null ? zipMeta.optString("kind", "") : "";
            if (!kind.isEmpty() && !"linux-vulkan-icd".equals(kind)) {
                Log.w(TAG, "meta.json says kind=" + kind + ", importing anyway (the binary is glibc)");
            }

            String name = zipMeta != null ? zipMeta.optString("name", "") : "";
            String version = zipMeta != null ? zipMeta.optString("driverVersion", "") : "";
            String minGlibc = zipMeta != null ? zipMeta.optString("minGlibc", "") : "";
            if (name.isEmpty()) {
                name = displayName != null ? displayName : "linux-panvk-driver";
                if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
            }
            String id = uniqueId(sanitizeId(name));
            File dir = getDriverDir(id);

            // ICD manifest: absolute library_path, so the loader never depends on cwd and the guest
            // reads it at the same path the app wrote (the files dir is bound into the session).
            JSONObject icd = new JSONObject();
            icd.put("file_format_version", "1.0.0");
            JSONObject icdBody = new JSONObject();
            icdBody.put("library_path", new File(dir, LIB_NAME).getAbsolutePath());
            icdBody.put("api_version", "1.1.274");
            icd.put("ICD", icdBody);
            if (!FileUtils.writeString(new File(tmpDir, ICD_NAME), icd.toString(2))) throw new IOException("cannot write icd.json");

            JSONObject meta = new JSONObject();
            meta.put("schemaVersion", 1);
            meta.put("kind", "linux-vulkan-icd");
            meta.put("name", name);
            meta.put("driverVersion", version);
            meta.put("libc", "glibc");
            meta.put("minGlibc", minGlibc);
            meta.put("sourceLibraryName", soName);
            meta.put("importedAt", System.currentTimeMillis());
            int[] archs = declaredArchs(gpuArch, zipMeta);
            if (archs.length > 0) meta.put("gpuArch", new org.json.JSONArray(archs));
            if (!FileUtils.writeString(new File(tmpDir, META_NAME), meta.toString(2))) throw new IOException("cannot write meta.json");

            if (!tmpDir.renameTo(dir)) throw new IOException("cannot move into " + dir);
            Log.i(TAG, "imported Linux PanVK driver " + id + " (" + soName + ", minGlibc=" + minGlibc + ") -> " + dir);
            return id;
        } catch (org.json.JSONException e) {
            throw new IOException("manifest write failed: " + e.getMessage());
        }
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (getDriverDir(id).exists()) id = base + "-" + (n++);
        return id;
    }

    /**
     * The Mali architectures an imported driver was built with, which the caller passed or its own
     * meta.json named. Empty means "not said": the build is offered and picked anyway, because a
     * driver that is quiet about it is not thereby wrong - only one that names other generations is.
     */
    public int[] getGpuArch(String id) {
        JSONObject m = readMeta(id);
        if (m == null) return new int[0];
        JSONArray archs = m.optJSONArray("gpuArch");
        if (archs == null) return new int[0];
        int[] out = new int[archs.length()];
        for (int i = 0; i < out.length; i++) out[i] = archs.optInt(i);
        return out;
    }

    /** True when [id] can drive a Mali of architecture [arch]; a silent driver never refuses. */
    public boolean canDrive(String id, int arch) {
        int[] archs = getGpuArch(id);
        if (archs.length == 0 || arch == 0) return true;
        for (int a : archs) if (a == arch) return true;
        return false;
    }

    private static int[] declaredArchs(int[] given, JSONObject zipMeta) {
        if (given != null && given.length > 0) return given;
        if (zipMeta == null) return new int[0];
        JSONArray archs = zipMeta.optJSONArray("gpuArch");
        if (archs == null || archs.length() == 0) return new int[0];
        int[] out = new int[archs.length()];
        for (int i = 0; i < out.length; i++) out[i] = archs.optInt(i);
        return out;
    }

    static String sanitizeId(String name) {
        String s = name.trim().replaceAll("[^A-Za-z0-9._-]+", "_").replaceAll("^[._]+", "");
        if (s.length() > 64) s = s.substring(0, 64);
        return s.isEmpty() ? "linux-panvk-driver" : s;
    }

    /** ELF magic, EI_CLASS = 64-bit, EI_DATA = little-endian, e_machine = 0xB7 (AArch64). */
    static boolean isAarch64Elf(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] h = new byte[20];
            int n = 0;
            while (n < h.length) {
                int r = in.read(h, n, h.length - n);
                if (r < 0) break;
                n += r;
            }
            if (n < 20) return false;
            if (h[0] != 0x7f || h[1] != 'E' || h[2] != 'L' || h[3] != 'F') return false;
            if (h[4] != 2 || h[5] != 1) return false;
            int machine = (h[18] & 0xff) | ((h[19] & 0xff) << 8);
            return machine == 0xB7;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    static boolean containsAscii(File f, String needle) {
        byte[] nb = needle.getBytes(StandardCharsets.US_ASCII);
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[1 << 16];
            int carry = 0;
            int r;
            while ((r = in.read(buf, carry, buf.length - carry)) > 0) {
                int len = carry + r;
                for (int i = 0; i + nb.length <= len; i++) {
                    int j = 0;
                    while (j < nb.length && buf[i + j] == nb[j]) j++;
                    if (j == nb.length) return true;
                }
                // Keep the last needle-1 bytes so a match across a buffer boundary is still found.
                carry = Math.min(nb.length - 1, len);
                System.arraycopy(buf, len - carry, buf, 0, carry);
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}