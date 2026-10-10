package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import com.droiddeck.launcher.R;
import com.droiddeck.launcher.core.FileUtils;
import com.droiddeck.launcher.core.TarZst;
import com.droiddeck.launcher.session.SessionPrefs;

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
    /** No bundled PanVK drivers in the APK yet. */
    public static final List<String> BUNDLED = Collections.emptyList();
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

    /** The system driver the GPU would pick on its own, for the "Auto" row's label. */
    public String autoId() {
        String id = chooseByDevice();
        return id == null ? "system" : id;
    }

    public void remove(String driverId) {
        if (driverId == null || driverId.isEmpty() || driverId.contains("/") || driverId.contains("..")) return;
        Log.d(TAG, "removing imported PanVK driver " + driverId);
        FileUtils.delete(new File(contentDir, driverId));
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
        if (!new File(dir, "meta.json").isFile()) {
            FileUtils.delete(dir);
            // No bundled PanVK drivers yet - would need to extract from assets
            Log.w(TAG, "No bundled PanVK driver available for " + id);
            return null;
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
        // Check for Mali Vulkan driver on the system
        String model = gpuModel();
        Log.i(TAG, "gpu model: " + (model == null ? "unknown" : model));
        if (model != null && model.toLowerCase().contains("mali")) {
            // System Mali driver available
            return "system-mali";
        }
        // Nothing to go on
        return null;
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