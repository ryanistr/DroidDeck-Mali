package com.droiddeck.launcher.core

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import java.io.File

/**
 * Whether this device can draw a Linux session at all.
 *
 * The runtime draws with Turnip, an Adreno driver. On Mali, Xclipse and PowerVR the compositor
 * gets no usable Vulkan device and a session comes up as sound over a black screen - a failure
 * with nothing in it to read. The app cannot fix that, so it says so before the download rather
 * than after it. Adreno is recognised by what only Qualcomm's stack has: the KGSL node, or the
 * vendor's own Vulkan driver at its usual path. Mali is recognised by devfreq nodes or Panfrost/PanVK driver.
 */
object DeviceSupport {
    fun adreno(): Boolean =
        File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()

    fun mali(): Boolean =
        File("/sys/class/devfreq").listFiles()?.any { f ->
            val n = f.name.lowercase()
            n.contains("mali") || (n.contains("gpu") && !n.contains("kgsl"))
        } == true || File("/vendor/lib64/hw/vulkan.panfrost.so").exists() || File("/vendor/lib64/hw/vulkan.mali.so").exists()

    fun supportedGpu(): Boolean = adreno() || mali()

    /** The chip as the device names it, for the card that explains the refusal. */
    fun gpuName(context: Context): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } else null
        return soc?.let { "$it (${Build.HARDWARE})" } ?: Build.HARDWARE.ifBlank { context.getString(R.string.gpu_this_gpu) }
    }
}
