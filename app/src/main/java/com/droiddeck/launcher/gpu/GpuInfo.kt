package com.droiddeck.launcher.gpu

import android.content.Context
import android.os.Build
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import java.io.File

/**
 * What GPU this is, in the terms the driver lists are sorted by. KGSL names the model
 * ("Adreno740v2", "Adreno825"); the family decides which Turnip builds run on it at all, and the
 * support level is what the app has actually been tested on (Adreno 650, 725 and newer) - an
 * Adreno 610 may start, but it is not "supported" just because it is a Qualcomm chip.
 */
data class GpuInfo(
    /** "Adreno 740", "Mali-G78", or the vendor's own name when this is not an Adreno. */
    val name: String,
    /** The three-digit Adreno model (740, 825), or Mali midgard/bifrost/valhall gen, or 0 when unknown. */
    val model: Int,
    val family: Family,
    /** The SoC as a person would say it ("Snapdragon 8 Gen 2 (QCS8550)"), or "" when the phone does not say. */
    val soc: String,
    /** Samsung's One UI on an 8 Gen 2: its Turnip needs the OneUI build, or frames tear and flicker. */
    val oneUi8Gen2: Boolean,
    /** KGSL's own name for the GPU, as the kernel spells it ("Adreno740v2", "Adreno33v2"). */
    val kgslName: String = "",
    /** Where [model] came from: "kernel", "vulkan" or "platform", "" when unknown. */
    val modelSource: String = "",
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        /** Adreno 710/720/722: gen 7 cores cut down enough to need their own patches. */
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        /** An Adreno whose model KGSL does not give: treated as the newest family it could be. */
        ADRENO_UNKNOWN("Adreno"),
        /** Mali Midgard (G7x, T8xx) - OpenGL ES only, no Vulkan. */
        MALI_MIDGARD("Mali Midgard"),
        /** Mali Bifrost (G5x, G7x) - Vulkan 1.0, Panfrost/PanVK support. */
        MALI_BIFROST("Mali Bifrost"),
        /** Mali Valhall (G610, G715, G720, G615) - Vulkan 1.1+, PanVK support. */
        MALI_VALHALL("Mali Valhall"),
        /** A Mali whose generation is not known. */
        MALI_UNKNOWN("Mali"),
        NOT_ADRENO("Not an Adreno GPU");

        /** [label] in the app's language: the Adreno families are names, the rest is words. */
        fun label(context: Context): String = if (this == NOT_ADRENO) context.getString(R.string.gpuinfo_not_adreno) else label
    }

    enum class Support { TESTED, UNTESTED, UNSUPPORTED }

    val support: Support
        get() = when {
            family == Family.NOT_ADRENO -> Support.UNSUPPORTED
            family == Family.A6XX && model == 650 -> Support.TESTED
            family == Family.A8XX -> Support.TESTED
            family == Family.A7XX && model >= 725 -> Support.TESTED
            family == Family.MALI_VALHALL -> Support.UNTESTED  // PanVK support experimental
            family == Family.MALI_BIFROST -> Support.UNTESTED  // PanVK support experimental
            family == Family.MALI_MIDGARD -> Support.UNSUPPORTED  // No Vulkan
            else -> Support.UNTESTED
        }

    /** One line for the device card and the system check, in English for the device report. */
    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> when {
                family == Family.A7XX_LOW -> "Experimental: its drivers are test builds"
                family in listOf(Family.MALI_VALHALL, Family.MALI_BIFROST) -> "Experimental: Mali/PanVK support is early"
                else -> "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            }
            Support.UNSUPPORTED -> when {
                family == Family.MALI_MIDGARD -> "Not supported: Mali Midgard lacks Vulkan"
                else -> "Not supported: DroidDeck needs an Adreno (Snapdragon) GPU or Mali Valhall/Bifrost"
            }
        }

    /** [supportText] in the app's language. */
    fun supportText(context: Context): String = context.getString(when (support) {
        Support.TESTED -> R.string.gpuinfo_supported
        Support.UNTESTED -> when {
            family == Family.A7XX_LOW -> R.string.gpuinfo_experimental
            family in listOf(Family.MALI_VALHALL, Family.MALI_BIFROST) -> R.string.gpuinfo_mali_experimental
            else -> R.string.gpuinfo_below_tested
        }
        Support.UNSUPPORTED -> when {
            family == Family.MALI_MIDGARD -> R.string.gpuinfo_mali_midgard_unsupported
            else -> R.string.gpuinfo_unsupported
        }
    })

    /** [name] for the screen: the stand-in for a GPU the device does not name is in the app's language. */
    fun displayName(context: Context): String = if (name == UNNAMED) context.getString(R.string.gpu_this_gpu) else name

    companion object {
        /** [name] when the device names no GPU; English, as the device report shows it. */
        private const val UNNAMED = "this GPU"

        fun detect(): GpuInfo {
            // Check for Adreno (KGSL or vendor Vulkan driver)
            val adreno = File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
            // Check for Mali (devfreq nodes or vendor Vulkan driver)
            val mali = File("/sys/class/devfreq").listFiles()?.any { f ->
                val n = f.name.lowercase()
                n.contains("mali") || n.contains("gpu") && !n.contains("kgsl")
            } == true || File("/vendor/lib64/hw/vulkan.panfrost.so").exists() || File("/vendor/lib64/hw/vulkan.mali.so").exists()

            val raw = if (adreno) {
                listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_chipid")
                    .firstNotNullOfOrNull { FileUtils.readString(File(it))?.trim()?.takeIf(String::isNotEmpty) }
            } else if (mali) {
                // Try to read Mali model from devfreq or sysfs
                val devfreq = File("/sys/class/devfreq").listFiles()?.firstOrNull { f ->
                    val n = f.name.lowercase()
                    n.contains("mali") || (n.contains("gpu") && !n.contains("kgsl"))
                }
                devfreq?.let { FileUtils.readString(File(it.path, "device/of_node/compatible"))?.trim()?.takeIf(String::isNotEmpty) }
                    ?: FileUtils.readString(File("/sys/class/mali0/device/of_node/compatible"))?.trim()?.takeIf(String::isNotEmpty)
                    ?: FileUtils.readString(File("/sys/bus/platform/drivers/mali"))?.trim()?.takeIf(String::isNotEmpty)
            } else null

            // Where vendors put the chip's model, named when it is known: "Snapdragon 8 Gen 2 (QCS8550)".
            val soc = if (adreno) SocNames.label() else if (mali) SocNames.label() else ""

            // Parse model from kernel
            val fromKernel = raw?.let { parseModelFromKernel(it, adreno, mali) } ?: 0

            // If kernel didn't give a model, try Vulkan driver's name
            val fromVulkan = if (fromKernel > 0) 0 else VulkanInfo.cachedOrNull()?.get("device")?.let { parseModelFromVulkan(it, adreno, mali) } ?: 0

            // If still nothing, try platform for Adreno
            val fromPlatform = if (fromKernel > 0 || fromVulkan > 0 || (!adreno && !mali)) 0 else platformModel()

            val model = maxOf(fromKernel, fromVulkan, fromPlatform)
            val source = when {
                fromKernel > 0 -> "kernel"
                fromVulkan > 0 -> "vulkan"
                fromPlatform > 0 -> "platform"
                else -> ""
            }

            val isAdreno = adreno && !mali
            val isMali = mali && !adreno
            val family = familyOf(isAdreno, isMali, model)
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

            val name = when {
                isAdreno && model > 0 -> "Adreno $model"
                isAdreno -> "Adreno"
                isMali && model > 0 -> "Mali-$model"
                isMali -> "Mali"
                else -> Build.HARDWARE.ifBlank { UNNAMED }
            }

            return GpuInfo(
                name = name,
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && model == 740,
                kgslName = raw.orEmpty(), modelSource = source,
            )
        }

        private fun parseModelFromKernel(text: String, isAdreno: Boolean, isMali: Boolean): Int {
            if (isAdreno) {
                return Regex("""(\d{3})""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            if (isMali) {
                // Mali kernel names: "mali-t860", "mali-g76", "mali-g78", "mali-g715", "arm,mali-g78"
                val m = Regex("""(?i)mali[_-]?(t|g)(\d{2,3})""").find(text)
                if (m != null) {
                    val prefix = m.groupValues[1] ?: ""
                    val num = m.groupValues[2]?.toIntOrNull() ?: 0
                    return if (prefix == "t") num else num // T8xx = Midgard, Gxx = Bifrost/Valhall
                }
                return 0
            }
            return 0
        }

        private fun parseModelFromVulkan(text: String, isAdreno: Boolean, isMali: Boolean): Int {
            if (isAdreno) {
                return Regex("""(\d{3})""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            if (isMali) {
                // Vulkan device names: "Mali-G78", "Mali-G715", "Mali-G57", "PanVK (Mali-G78)"
                val m = Regex("""(?i)mali[_-]?g(\d{2,3})""").find(text)
                return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            return 0
        }

        private fun threeDigits(text: String): Int = Regex("""(\d{3})""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        /** Qualcomm platform code names whose GPU is known for certain. */
        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
        )

        internal fun platformModel(platform: String = systemProperty("ro.board.platform")): Int =
            PLATFORMS[platform.lowercase()] ?: PLATFORMS[systemProperty("ro.vendor.qti.soc_name").lowercase()] ?: 0

        /** android.os.SystemProperties.get, which apps may read but not call directly. */
        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        internal fun familyOf(isAdreno: Boolean, isMali: Boolean, model: Int): Family = when {
            isMali -> when {
                model == 0 -> Family.MALI_UNKNOWN
                model in 820..899 -> Family.MALI_MIDGARD  // T820-T880
                model in 500..599 -> Family.MALI_BIFROST  // G51, G52, G57
                model in 700..799 -> Family.MALI_BIFROST  // G71, G72, G76
                model in 600..699 -> Family.MALI_VALHALL  // G610, G615
                model >= 700 -> Family.MALI_VALHALL       // G715, G720, G710
                else -> Family.MALI_UNKNOWN
            }
            isAdreno -> when {
                model == 0 -> Family.ADRENO_UNKNOWN
                model >= 800 -> Family.A8XX
                model in listOf(710, 720, 722) -> Family.A7XX_LOW
                model >= 700 -> Family.A7XX
                model >= 600 -> Family.A6XX
                else -> Family.ADRENO_UNKNOWN
            }
            else -> Family.NOT_ADRENO
        }
    }
}
