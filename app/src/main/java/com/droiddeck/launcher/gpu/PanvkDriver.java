package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.droiddeck.launcher.R;
import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.TarZst;
import com.droiddeck.launcher.session.SessionPrefs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The Vulkan driver the in-app compositor runs on for Mali GPUs. On Mali, we use the system's
 * Mali Vulkan driver (vulkan.mali.so or vulkan.panfrost.so) or an imported PanVK build.
 * This is separate from the Linux runtime's PanVK driver ({@link LinuxPanvkDriverManager}).
 *
 * <p>Every driver, bundled or imported, lives under {@code files/graphics_driver/<id>/} with a
 * {@code meta.json} whose {@code libraryName} names the .so beside it - the AdrenoTools layout.
 */
public final class PanvkDriver {
    private static final String TAG = "PanvkDriver";

    /** Overrides the pick, for a device we cannot reach. */
    private static final String OVERRIDE_FILE = "Download/droiddeck-panvk-driver";

    /** Bundled drivers the user deleted: hidden from the list, their unpacked copy removed. */
    private static final String PREFS = "graphics_driver_panvk";
    private static final String KEY_HIDDEN = "hiddenBundled";
    /** Driver id -> the Mali architectures it was built with, for the drivers that said. */
    private static final String KEY_ARCHS = "driverArch";
    /** Bundled driver id (unpacked on demand from the matching tzst asset). */
    public static final String BUNDLED_PANVK = "panvk-android-24.3.0";
    /**
     * The Mali architectures each bundled build carries, read from the driver's own `panvk_vN_`
     * symbols (the one bundled build has v9 and no v11 at all). A GPU of a generation a build does
     * not carry enumerates nothing, so this is what decides whether the compositor has a driver
     * for this device at all - the vendor blob is what renders when it does not.
     */
    private static final int[] BUNDLED_ARCHS = {9};
    /** Version of the bundled asset; bumping it re-unpacks the file over a copy already on the
     * device, which matters because the id is stable across rebuilds. */
    public static final String BUNDLED_VERSION = "1.2.0";
    public static final List<String> BUNDLED = Collections.unmodifiableList(
            Collections.singletonList(BUNDLED_PANVK));
    /** Stored choice meaning "pick by GPU" - the default, and what a removed import falls back to. */
    public static final String AUTO = "";

    public static final String HELP_TEXT =
            "The driver the app's compositor puts frames on the screen with on Mali GPUs - the last step of every "
            + "session, Steam or desktop. PanVK zips (libvulkan_panvk.so): a \"-Linux\" PanVK "
            + "belongs in the Linux runtime list above and is refused here. The compositor loads its "
            + "driver once per app process, so a change takes effect after the app is fully closed and "
            + "started again.";

    private final Context context;
    private final File contentDir;

    public PanvkDriver(Context context) {
        this.context = context;
        this.contentDir = new File(context.getFilesDir(), "graphics_driver_panvk");
    }

    /** Absolute directory holding the driver, with a trailing slash. */
    public String driverPath(String driverId) {
        return new File(contentDir, driverId).getAbsolutePath() + "/";
    }

    public String libraryName(String driverId) {
        JSONObject meta = readMeta(driverId);
        return meta != null ? meta.optString("libraryName", null) : null;
    }

    /** meta.json's "name", or the id when it has none. */
    public String displayName(String driverId) {
        JSONObject meta = readMeta(driverId);
        String name = meta != null ? meta.optString("name", "") : "";
        return name.isEmpty() ? driverId : name;
    }

    public String driverVersion(String driverId) {
        JSONObject meta = readMeta(driverId);
        return meta != null ? meta.optString("driverVersion", "") : "";
    }

    private JSONObject readMeta(String driverId) {
        if (driverId == null || driverId.isEmpty() || driverId.contains("/") || driverId.contains("..")) return null;
        File meta = new File(new File(contentDir, driverId), "meta.json");
        String content = FileUtils.readString(meta);
        if (content == null) return null;
        try {
            return new JSONObject(content);
        } catch (Exception e) {
            Log.w(TAG, "meta.json for " + driverId, e);
            return null;
        }
    }

    /** An imported driver is installed when its meta.json names a library that is there. */
    public boolean isInstalled(String driverId) {
        String library = libraryName(driverId);
        return library != null && !library.isEmpty() && new File(new File(contentDir, driverId), library).isFile();
    }

    /**
     * The Mali architectures this driver was built with, empty when nobody said. A driver that is
     * quiet is not thereby wrong: only one that names other generations is refused the device.
     */
    public int[] gpuArch(String driverId) {
        if (BUNDLED.contains(driverId)) return BUNDLED_ARCHS;
        String raw = prefs().getString(KEY_ARCHS, "{}");
        try {
            JSONArray archs = new JSONObject(raw).optJSONArray(driverId);
            if (archs == null) return new int[0];
            int[] out = new int[archs.length()];
            for (int i = 0; i < out.length; i++) out[i] = archs.optInt(i);
            return out;
        } catch (Exception e) {
            return new int[0];
        }
    }

    /** Remember what an imported driver was built with, so the device picks by it later. */
    public void setGpuArch(String driverId, int[] archs) {
        if (driverId == null || archs == null || archs.length == 0) return;
        try {
            JSONObject all = new JSONObject(prefs().getString(KEY_ARCHS, "{}"));
            all.put(driverId, new JSONArray(archs));
            prefs().edit().putString(KEY_ARCHS, all.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "could not record the architectures of " + driverId, e);
        }
    }

    /** True when this driver can drive a Mali of architecture [arch]; a silent driver never refuses. */
    public boolean canDrive(String driverId, int arch) {
        int[] archs = gpuArch(driverId);
        if (archs.length == 0 || arch == 0) return true;
        for (int a : archs) if (a == arch) return true;
        return false;
    }

    private android.content.SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Imported drivers only - the bundled two are listed by {@link #BUNDLED}. */
    public List<String> enumerateImported() {
        ArrayList<String> ids = new ArrayList<>();
        File[] dirs = contentDir.listFiles();
        if (dirs == null) return ids;
        for (File d : dirs) {
            String id = d.getName();
            if (d.isDirectory() && !BUNDLED.contains(id) && !id.startsWith(".") && isInstalled(id)) ids.add(id);
        }
        Collections.sort(ids);
        return ids;
    }

    /** The bundled drivers the list shows: {@link #BUNDLED} minus the ones the user deleted. */
    public List<String> visibleBundled() {
        java.util.Set<String> hidden = hiddenBundled();
        ArrayList<String> out = new ArrayList<>();
        for (String id : BUNDLED) if (!hidden.contains(id)) out.add(id);
        return out;
    }

    public java.util.Set<String> hiddenBundled() {
        return new java.util.HashSet<>(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_HIDDEN, java.util.Collections.<String>emptySet()));
    }

    /** "Delete" for the bundled driver: remove its unpacked copy and hide it from the list. */
    public void hideBundled(String driverId) {
        if (!BUNDLED.contains(driverId)) return;
        FileUtils.delete(new File(contentDir, driverId));
        java.util.Set<String> hidden = hiddenBundled();
        hidden.add(driverId);
        prefs().edit().putStringSet(KEY_HIDDEN, hidden).apply();
        Log.i(TAG, "bundled driver " + driverId + " hidden by the user");
    }

    public void restoreBundled() {
        prefs().edit().remove(KEY_HIDDEN).apply();
    }

    /** The system driver the GPU would pick on its own, for the "Auto" row's label. */
    public String autoId() {
        String id = chooseByDevice();
        return id == null ? "system" : id;
    }

    public void remove(String driverId) {
        if (driverId == null || driverId.isEmpty() || driverId.contains("/") || driverId.contains("..")) return;
        Log.d(TAG, "removing imported PanVK driver " + driverId);
        FileUtils.delete(new File(contentDir, driverId));
        try {
            JSONObject all = new JSONObject(prefs().getString(KEY_ARCHS, "{}"));
            all.remove(driverId);
            prefs().edit().putString(KEY_ARCHS, all.toString()).apply();
        } catch (Exception e) {
            Log.w(TAG, "architectures of " + driverId + " left behind", e);
        }
        if (driverId.equals(SessionPrefs.androidDriver(context))) SessionPrefs.setAndroidDriver(context, AUTO);
    }

    /**
     * Import a PanVK zip. Returns the new driver id.
     * @throws IllegalArgumentException with a user-facing reason when the zip is not a PanVK
     *         driver; {@link IOException} on read/extract failures.
     */
    public String installFromZip(Uri zipUri, String displayName) throws IOException {
        File tmpDir = newStagingDir();
        boolean keep = false;
        try {
            try (InputStream is = context.getContentResolver().openInputStream(zipUri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                if (is == null) throw new IOException("cannot open " + zipUri);
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) continue;
                    // Flatten: only the base name matters, and it also defeats zip-slip paths.
                    String base = new File(entry.getName()).getName();
                    if (base.isEmpty()) continue;
                    Files.copy(zis, new File(tmpDir, base).toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            String id = adopt(tmpDir, displayName);
            keep = true;
            return id;
        } finally {
            if (!keep) FileUtils.delete(tmpDir);
        }
    }

    /**
     * Install a published bionic build straight from its release {@code .tzst}: the same archive
     * shape as the runtime's half ({@code tools/panvk/build-panvk-android.sh} writes
     * {@code usr/lib/libvulkan_panvk.so} beside a {@code meta.json}). The files are flattened and
     * then go through {@link #adopt} like a picked zip, so a downloaded build lands as an ordinary
     * imported driver. [gpuArch] are the Mali architectures it was built with, when the caller knows.
     */
    public String installFromTzst(Uri tzst, String displayName, int[] gpuArch) throws IOException {
        File tree = newStagingDir();
        File tmpDir = newStagingDir();
        boolean keep = false;
        try {
            try (InputStream in = context.getContentResolver().openInputStream(tzst)) {
                if (in == null) throw new IOException("cannot open " + tzst);
                if (!TarZst.extract(in, tree)) throw new IOException("cannot unpack the driver");
            }
            File so = find(tree, "libvulkan_panvk.so");
            File metaFile = find(tree, "meta.json");
            if (so == null || metaFile == null) throw new IllegalArgumentException(context.getString(R.string.turnip_reject_no_meta));
            JSONObject meta = readMetaFile(metaFile);
            String library = meta != null ? meta.optString("libraryName", "") : "";
            Files.copy(so.toPath(), new File(tmpDir, library.isEmpty() ? so.getName() : library).toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.copy(metaFile.toPath(), new File(tmpDir, "meta.json").toPath(), StandardCopyOption.REPLACE_EXISTING);
            String id = adopt(tmpDir, displayName);
            setGpuArch(id, gpuArch);
            keep = true;
            return id;
        } finally {
            FileUtils.delete(tree);
            if (!keep) FileUtils.delete(tmpDir);
        }
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
        if (!contentDir.exists()) contentDir.mkdirs();
        File tmpDir = new File(contentDir, ".tmp-" + System.nanoTime());
        FileUtils.delete(tmpDir);
        if (!tmpDir.mkdirs()) throw new IOException("cannot create " + tmpDir);
        return tmpDir;
    }

    /**
     * Check a staged driver (meta.json and its library, flat) and move it in as a new driver.
     * Returns the id; the caller deletes {@code tmpDir} when this throws.
     */
    String adopt(File tmpDir, String displayName) throws IOException {
        String rejected = rejectionReason(tmpDir);
        if (rejected != null) throw new IllegalArgumentException(context.getString(R.string.turnip_not_adrenotools, rejected));

        JSONObject meta = readMetaFile(new File(tmpDir, "meta.json"));
        String name = meta != null ? meta.optString("name", "") : "";
        if (name.isEmpty()) {
            name = displayName != null ? displayName : "panvk-driver";
            if (name.toLowerCase().endsWith(".zip")) name = name.substring(0, name.length() - 4);
        }
        String id = uniqueId(LinuxVulkanDriverManager.sanitizeId(name));
        File dir = new File(contentDir, id);
        if (!tmpDir.renameTo(dir)) throw new IOException("cannot move into " + dir);
        Log.i(TAG, "imported PanVK driver " + id + " (" + (meta != null ? meta.optString("libraryName", "") : "?") + ") -> " + dir);
        return id;
    }

    private static JSONObject readMetaFile(File metaFile) {
        try {
            return new JSONObject(FileUtils.readString(metaFile));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Why a just-extracted zip is not something that can be used, or null when it is.
     */
    private String rejectionReason(File dir) {
        File metaFile = new File(dir, "meta.json");
        if (!metaFile.isFile()) return context.getString(R.string.turnip_reject_no_meta);
        String libraryName, kind;
        try {
            JSONObject meta = new JSONObject(FileUtils.readString(metaFile));
            libraryName = meta.optString("libraryName", "");
            kind = meta.optString("kind", "");
        } catch (Exception e) {
            return context.getString(R.string.turnip_reject_meta_unreadable, e.getMessage());
        }
        if ("linux-vulkan-icd".equals(kind))
            return context.getString(R.string.turnip_reject_linux_driver);
        if (libraryName.isEmpty())
            return context.getString(R.string.turnip_reject_no_library_name);
        File library = new File(dir, libraryName);
        if (!library.isFile())
            return context.getString(R.string.turnip_reject_library_missing, libraryName);
        if (!LinuxVulkanDriverManager.isAarch64Elf(library))
            return context.getString(R.string.turnip_reject_not_aarch64, libraryName);
        // A glibc build that happens to carry a libraryName would load nothing in this process.
        if (LinuxVulkanDriverManager.containsAscii(library, "libc.so.6"))
            return context.getString(R.string.turnip_reject_glibc, libraryName);
        return null;
    }

    private String uniqueId(String base) {
        String id = base;
        int n = 2;
        while (new File(contentDir, id).exists()) id = base + "-" + (n++);
        return id;
    }

    /**
     * Pick the driver for this device and return its id, or null to fall back to the system
     * Vulkan loader.
     */
    public String install() {
        String id = choose();
        if (id == null) return null;
        File dir = new File(contentDir, id);
        boolean bundled = BUNDLED.contains(id);
        // A bundled driver whose version on disk is not the one this apk ships must be unpacked
        // again: the id is stable across rebuilds, so an older copy would otherwise live on.
        boolean stale = bundled && !BUNDLED_VERSION.equals(driverVersion(id));
        if (stale || !new File(dir, "meta.json").isFile()) {
            FileUtils.delete(dir);
            if (!bundled || !TarZst.extractAsset(context, "graphics_driver/" + id + ".tzst", dir)) {
                Log.e(TAG, "could not unpack " + id);
                FileUtils.delete(dir);
                return null;
            }
        }
        String library = libraryName(id);
        if (library == null || !new File(dir, library).isFile()) {
            Log.e(TAG, id + " unpacked without " + library);
            return null;
        }
        Log.i(TAG, "graphics driver " + id + " (" + library + ")");
        return id;
    }

    private String choose() {
        String chosen = SessionPrefs.androidDriver(context);
        if (chosen != null && !chosen.isEmpty()) {
            if (isInstalled(chosen)) {
                Log.i(TAG, "imported driver chosen by the user: " + chosen);
                return chosen;
            }
            Log.w(TAG, "chosen driver \"" + chosen + "\" is gone; picking by GPU");
        }
        return chooseByDevice();
    }

    private String chooseByDevice() {
        File override = new File(Environment.getExternalStorageDirectory(), OVERRIDE_FILE);
        String forced = override.isFile() ? FileUtils.readString(override) : null;
        if (forced != null) {
            forced = forced.trim().toLowerCase(java.util.Locale.US);
            Log.i(TAG, "driver forced by " + override + ": " + forced);
            if (forced.startsWith("system")) return null;
        }
        // A Mali device wants PanVK: the system driver is the vendor blob, which the compositor
        // process cannot load (it is not an ICD we ship, and on this SoC it is not reachable), so
        // returning null here left the compositor with no driver at all.
        String model = gpuModel();
        Log.i(TAG, "gpu model: " + (model == null ? "unknown" : model));
        if (model == null || !model.toLowerCase().contains("mali")) {
            // Nothing to go on
            return null;
        }
        // Which build is not a matter of taste on Mali: a build carries the backend of the
        // generations it was built with, and a GPU of another generation enumerates nothing through
        // it. The user's own imports are looked at first, then the bundled build.
        int arch = GpuInfo.detect().getArch();
        ArrayList<String> candidates = new ArrayList<>(enumerateImported());
        candidates.addAll(visibleBundled());
        String silent = null;
        for (String id : candidates) {
            int[] archs = gpuArch(id);
            if (archs.length == 0) {
                if (silent == null) silent = id;
                continue;
            }
            if (arch == 0 || canDrive(id, arch)) {
                Log.i(TAG, "mali v" + arch + ": " + id + " carries v" + join(archs));
                return id;
            }
            Log.i(TAG, "mali v" + arch + ": " + id + " carries v" + join(archs) + " only");
        }
        if (silent != null) {
            Log.i(TAG, "mali v" + arch + ": " + silent + " does not say which generations it carries; using it");
            return silent;
        }
        // Nothing carries this generation - say so rather than let a session render through a
        // driver that cannot drive the GPU and look like a frame-handoff bug.
        int[] bundled = gpuArch(BUNDLED_PANVK);
        if (arch > 0 && !canDrive(BUNDLED_PANVK, arch)) {
            Log.w(TAG, "no installed PanVK display driver carries a Mali v" + arch + " backend (the bundled "
                    + BUNDLED_PANVK + " carries v" + join(bundled) + "): the compositor has no driver for this GPU");
        }
        // A hidden bundled driver means "system Vulkan" here, as it does on Adreno.
        if (hiddenBundled().contains(BUNDLED_PANVK)) {
            Log.i(TAG, "the bundled PanVK driver is hidden: using the system Vulkan driver");
            return null;
        }
        Log.i(TAG, "mali device: using bundled " + BUNDLED_PANVK);
        return BUNDLED_PANVK;
    }

    private static String join(int[] archs) {
        StringBuilder sb = new StringBuilder();
        for (int a : archs) sb.append(sb.length() == 0 ? "" : ", ").append(a);
        return sb.toString();
    }

    /** Check for Mali Vulkan driver on the system. */
    private static String gpuModel() {
        // Check vendor Vulkan driver
        File[] vulkanDrivers = new File("/vendor/lib64/hw").listFiles((dir, name) -> name.startsWith("vulkan.") && (name.contains("mali") || name.contains("panfrost")));
        if (vulkanDrivers != null && vulkanDrivers.length > 0) {
            return vulkanDrivers[0].getName();
        }
        // Check devfreq
        File devfreq = new File("/sys/class/devfreq");
        if (devfreq.exists()) {
            File[] drivers = devfreq.listFiles(f -> {
                String n = f.getName().toLowerCase();
                return n.contains("mali") || (n.contains("gpu") && !n.contains("kgsl"));
            });
            if (drivers != null && drivers.length > 0) {
                return drivers[0].getName();
            }
        }
        return null;
    }
}