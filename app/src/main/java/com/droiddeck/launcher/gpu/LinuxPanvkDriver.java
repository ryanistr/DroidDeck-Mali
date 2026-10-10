package com.droiddeck.launcher.gpu;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * The Vulkan driver a session draws with inside the runtime on Mali GPUs: in Steam mode the native
 * client's own UI (OpenGL through the runtime's Panfrost), every game it launches (D3D through
 * Proton's DXVK/VKD3D) and gamescope's compositing; in desktop mode every program on the desktop.
 * One choice per mode covers all of it.
 *
 * <p>Distinct from the Android driver the app's compositor loads to put the finished frame on the
 * screen ({@link PanvkDriver}), which every session ends in - and NOT interchangeable with it:
 * these are glibc processes, that one is bionic.
 *
 * <p>"" (the default) means the driver the runtime was built with, at
 * {@code usr/lib/libvulkan_panvk.so} inside it - nothing is set and nothing is touched.
 * Otherwise the value is an imported driver's id ({@link LinuxPanvkDriverManager}), and the
 * session is handed that driver's ICD manifest in {@code BL_VK_DRIVER}: the session script points
 * the Vulkan loader at it with {@code VK_DRIVER_FILES} after checking both the manifest and the
 * library are readable from inside, so the runtime itself is never modified and the choice is
 * reversible.
 */
public final class LinuxPanvkDriver {
    private static final String TAG = "LinuxPanvkDriver";
    /** Env var the session reads; its value is an absolute ICD manifest path. */
    public static final String ENV = "BL_VK_DRIVER";

    public static final String HELP_TEXT =
            "The driver the Steam client, the games it launches and the desktop's programs render on, "
            + "inside the Linux runtime (for Mali GPUs). Imported \"-Linux\" PanVK zips only: these "
            + "are Linux processes and cannot load an Android or Wayland driver. Frames still reach "
            + "the screen through the display driver below. Takes effect at the next session start.";

    private LinuxPanvkDriver() {}

    /** Stored values in editor order: the runtime's own driver, then each imported one. */
    public static List<String> optionValues(Context context) {
        ArrayList<String> values = new ArrayList<>();
        values.add("");
        values.addAll(new LinuxPanvkDriverManager(context).enumerateInstalledDrivers());
        return values;
    }

    /**
     * Label for a stored value. An id that is no longer installed says so - the launch path falls
     * back to the runtime's own driver for it.
     */
    public static String optionLabel(Context context, String value) {
        if (value == null || value.isEmpty()) return "Runtime default (built into the runtime)";
        LinuxPanvkDriverManager m = new LinuxPanvkDriverManager(context);
        if (!m.isInstalled(value)) return value + " (imported, missing - uses the runtime default)";
        String ver = m.getDriverVersion(value);
        return m.getDriverName(value) + (ver.isEmpty() ? "" : " " + ver) + " (imported)";
    }

    /**
     * The ICD manifest path to hand the session, or null to leave the runtime's own driver alone.
     * An id whose import is gone resolves to null rather than failing the launch.
     */
    public static String resolveIcdPath(Context context, String value) {
        if (value == null || value.isEmpty()) return null;
        LinuxPanvkDriverManager m = new LinuxPanvkDriverManager(context);
        String icd = m.getIcdPath(value);
        if (icd == null) {
            Log.w(TAG, "imported Linux PanVK driver \"" + value + "\" is gone; using the runtime's own driver");
            return null;
        }
        Log.i(TAG, "Linux session draws with imported PanVK driver " + value + " (" + icd + ")");
        return icd;
    }
}