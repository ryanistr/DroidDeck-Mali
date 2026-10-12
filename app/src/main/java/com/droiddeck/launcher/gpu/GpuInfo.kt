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
 *
 * Mali is classified by its Mesa architecture ([arch]), never by its marketing number: a G615 is a
 * newer GPU than a G77 and a smaller number, so no ordering of the model decides anything here.
 */
data class GpuInfo(
    /** "Adreno 740", "Mali-G615 MC6", or the vendor's own name when this is not an Adreno. */
    val name: String,
    /** The Adreno model (740, 825) or the Mali model (77, 615) - never a "generation". */
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
    /** Mesa's architecture number (PAN_ARCH) for a Mali GPU, 0 for an Adreno or an unnamed Mali. */
    val arch: Int = 0,
) {
    enum class Family(val label: String) {
        A8XX("Adreno 8xx"),
        /** Adreno 710/720/722: gen 7 cores cut down enough to need their own patches. */
        A7XX_LOW("Adreno 710/720/722"),
        A7XX("Adreno 7xx"),
        A6XX("Adreno 6xx"),
        /** An Adreno whose model KGSL does not give: treated as the newest family it could be. */
        ADRENO_UNKNOWN("Adreno"),
        /** Mali Midgard (T6xx, T7xx, T8xx) - OpenGL ES only, no Vulkan. */
        MALI_MIDGARD("Mali Midgard"),
        /** Mali Bifrost (G31, G51, G52, G71, G72, G76) - Vulkan through PanVK. */
        MALI_BIFROST("Mali Bifrost"),
        /** Mali Valhall (G57, G77, G78, G310, G510, G610, G615, G710, G715) - Vulkan through PanVK. */
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
            // A generation with no backend in this build cannot render at all, which is not the
            // same thing as an experimental one that might.
            family in MALI_PANVK_FAMILIES -> if (panvkBackend) Support.UNTESTED else Support.UNSUPPORTED
            family == Family.MALI_MIDGARD -> Support.UNSUPPORTED  // No Vulkan
            else -> Support.UNTESTED
        }

    /** Whether this build carries the PanVK backend this Mali GPU's architecture needs. */
    val panvkBackend: Boolean get() = arch in PANVK_ARCHS

    /** One line for the device card and the system check, in English for the device report. */
    val supportText: String
        get() = when (support) {
            Support.TESTED -> "Supported"
            Support.UNTESTED -> when {
                family == Family.A7XX_LOW -> "Experimental: its drivers are test builds"
                family in MALI_PANVK_FAMILIES -> "Experimental: Mali/PanVK support is early"
                else -> "Outside tested hardware (Adreno 650, 725 and newer): it may not run"
            }
            Support.UNSUPPORTED -> when {
                family == Family.MALI_MIDGARD -> "Not supported: Mali Midgard lacks Vulkan"
                family in MALI_PANVK_FAMILIES -> if (arch > 0) {
                    "No PanVK backend for this Mali generation (v$arch) yet: the session runs on software rendering"
                } else {
                    "No PanVK backend for this Mali generation yet: the session runs on software rendering"
                }
                else -> "Not supported: DroidDeck needs an Adreno (Snapdragon) GPU or Mali Valhall/Bifrost"
            }
        }

    /** [supportText] in the app's language. */
    fun supportText(context: Context): String = when (support) {
        Support.TESTED -> context.getString(R.string.gpuinfo_supported)
        Support.UNTESTED -> context.getString(when {
            family == Family.A7XX_LOW -> R.string.gpuinfo_experimental
            family in MALI_PANVK_FAMILIES -> R.string.gpuinfo_mali_experimental
            else -> R.string.gpuinfo_below_tested
        })
        Support.UNSUPPORTED -> when {
            family == Family.MALI_MIDGARD -> context.getString(R.string.gpuinfo_mali_midgard_unsupported)
            family in MALI_PANVK_FAMILIES ->
                if (arch > 0) context.getString(R.string.gpuinfo_mali_no_backend_arch, arch)
                else context.getString(R.string.gpuinfo_mali_no_backend)
            else -> context.getString(R.string.gpuinfo_unsupported)
        }
    }

    /** [name] for the screen: the stand-in for a GPU the device does not name is in the app's language. */
    fun displayName(context: Context): String = if (name == UNNAMED) context.getString(R.string.gpu_this_gpu) else name

    companion object {
        /** [name] when the device names no GPU; English, as the device report shows it. */
        private const val UNNAMED = "this GPU"

        /**
         * The Mali architectures this build carries a PanVK backend for. The kbase layer under this
         * fork is a Job Manager implementation, which is what v6, v7 and v9 submit through; v10 and
         * newer hand work to the firmware through CSF instead, and Mesa hides v6/v7 behind
         * PAN_I_WANT_A_BROKEN_VULKAN_DRIVER, which a session does not set.
         */
        internal val PANVK_ARCHS = intArrayOf(9)

        /** The Mali families PanVK drives, when its backend for the architecture is built. */
        internal val MALI_PANVK_FAMILIES = listOf(Family.MALI_VALHALL, Family.MALI_BIFROST)

        @JvmStatic
        fun detect(): GpuInfo {
            android.util.Log.i("GpuInfo", "=== GpuInfo.detect() START ===")
            // Check for Adreno (KGSL or vendor Vulkan driver)
            val adreno = File("/sys/class/kgsl/kgsl-3d0").exists() || File("/vendor/lib64/hw/vulkan.adreno.so").exists()
            // Check for Mali (devfreq nodes or vendor Vulkan driver)
            val mali = File("/sys/class/devfreq").listFiles()?.any { f ->
                val n = f.name.lowercase()
                n.contains("mali") || n.contains("gpu") && !n.contains("kgsl")
            } == true || File("/vendor/lib64/hw/vulkan.panfrost.so").exists() || File("/vendor/lib64/hw/vulkan.mali.so").exists()
            android.util.Log.i("GpuInfo", "adreno=$adreno, mali=$mali")

            val raw = if (adreno) {
                listOf("/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/class/kgsl/kgsl-3d0/gpu_chipid")
                    .firstNotNullOfOrNull { FileUtils.readString(File(it))?.trim()?.takeIf(String::isNotEmpty) }
            } else if (mali) {
                // Try to read Mali model from devfreq or sysfs
                val devfreq = File("/sys/class/devfreq").listFiles()?.firstOrNull { f ->
                    val n = f.name.lowercase()
                    n.contains("mali") || (n.contains("gpu") && !n.contains("kgsl"))
                }
                val result = devfreq?.let { FileUtils.readString(File(it.path, "device/of_node/compatible"))?.trim()?.takeIf(String::isNotEmpty) }
                    ?: FileUtils.readString(File("/sys/class/mali0/device/of_node/compatible"))?.trim()?.takeIf(String::isNotEmpty)
                    ?: FileUtils.readString(File("/sys/bus/platform/drivers/mali"))?.trim()?.takeIf(String::isNotEmpty)
                android.util.Log.d("GpuInfo", "Mali raw compatible: $result")
                result
            } else null

            // Where vendors put the chip's model, named when it is known: "Snapdragon 8 Gen 2 (QCS8550)".
            val soc = if (adreno) SocNames.label() else if (mali) SocNames.label() else ""

            val isAdreno = adreno && !mali
            val isMali = mali && !adreno

            // Parse model from kernel
            val fromKernel = raw?.let { parseModelFromKernel(it, adreno, mali) } ?: 0
            android.util.Log.i("GpuInfo", "fromKernel model: $fromKernel, raw: $raw")

            // If kernel didn't give a model, try Vulkan driver's name
            val fromVulkan = if (fromKernel > 0) 0 else VulkanInfo.cachedOrNull()?.get("device")?.let { parseModelFromVulkan(it, adreno, mali) } ?: 0
            android.util.Log.i("GpuInfo", "fromVulkan model: $fromVulkan")

            // If still nothing, fall back to the platform code name.
            val fromPlatform = if (fromKernel > 0 || fromVulkan > 0 || (!adreno && !mali)) 0 else platformModel()
            android.util.Log.i("GpuInfo", "fromPlatform model: $fromPlatform")

            // Mali model numbers are not ordered: a G615 is newer than a G77 and a smaller number,
            // so taking the largest would report the wrong GPU. The first source that names one
            // wins. Adreno models are ordered, and there the largest is the safest guess.
            val model = if (isMali) {
                listOf(fromKernel, fromVulkan, fromPlatform).firstOrNull { it > 0 } ?: 0
            } else {
                maxOf(fromKernel, fromVulkan, fromPlatform)
            }
            val source = when {
                fromKernel > 0 -> "kernel"
                fromVulkan > 0 -> "vulkan"
                fromPlatform > 0 -> "platform"
                else -> ""
            }

            // Mali: the generation decides which backend can drive the GPU at all. The kernel's own
            // spelling tells a T720 (Midgard) from a G720 (5th gen), which the number alone cannot.
            val arch = if (isMali) maliArch(model, raw.orEmpty()) else 0
            val family = familyOf(isAdreno, isMali, model, arch)
            val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

            val name = when {
                isAdreno && model > 0 -> "Adreno $model"
                isAdreno -> "Adreno"
                isMali && model > 0 -> {
                    val n = maliName(systemProperty("ro.board.platform"), model, arch)
                    android.util.Log.i("GpuInfo", "Mali display name: $n (model $model, arch v$arch)")
                    n
                }
                isMali -> "Mali"
                else -> Build.HARDWARE.ifBlank { UNNAMED }
            }

            return GpuInfo(
                name = name,
                model = model, family = family, soc = soc,
                oneUi8Gen2 = samsung && model == 740,
                kgslName = raw.orEmpty(), modelSource = source, arch = arch,
            )
        }

        private fun parseModelFromKernel(text: String, isAdreno: Boolean, isMali: Boolean): Int {
            if (isAdreno) {
                return threeDigits(text)
            }
            if (isMali) {
                // Mali kernel names: "mali-t860", "mali-g76", "mali-g78", "mali-g715", "arm,mali-g78"
                // Also "mali-g77-mc9" or similar for core count
                val m = Regex("""(?i)mali[_-]?(t|g)(\d{2,3})""").find(text)
                return m?.groupValues?.get(2)?.toIntOrNull() ?: 0
            }
            return 0
        }

        private fun parseModelFromVulkan(text: String, isAdreno: Boolean, isMali: Boolean): Int {
            if (isAdreno) {
                return threeDigits(text)
            }
            if (isMali) {
                // Vulkan device names: "Mali-G78", "Mali-G715", "Mali-G57", "Mali-G615 MC6"
                val m = Regex("""(?i)mali[_-]?g(\d{2,3})""").find(text)
                return m?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            return 0
        }

        private fun threeDigits(text: String): Int = Regex("""(\d{3})""").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        /** A MediaTek platform's GPU: the model the app reports and the name Arm gives it. */
        private data class MtkGpu(val model: Int, val name: String)

        /**
         * MediaTek platform codes whose GPU is known for certain. On these phones the platform is
         * the only source: mali_kbase's gpuinfo node is root-only, so the kernel names nothing.
         * One wrong entry sends a whole family of phones to a driver that cannot drive their GPU.
         */
        private val MTK_PLATFORMS = mapOf(
            "mt6893" to MtkGpu(77, "Mali-G77 MC9"),          // Dimensity 1200/8050
            "mt6895" to MtkGpu(610, "Mali-G610 MC6"),        // Dimensity 8100
            "mt6897" to MtkGpu(615, "Mali-G615 MC6"),        // Dimensity 8300/8350
            "mt6983" to MtkGpu(710, "Mali-G710 MC10"),       // Dimensity 9000
            "mt6985" to MtkGpu(715, "Immortalis-G715 MC11"), // Dimensity 9200
            "mt6989" to MtkGpu(720, "Immortalis-G720 MC12"), // Dimensity 9300
        )

        /** Qualcomm platform code names whose GPU is known for certain. */
        private val PLATFORMS = mapOf(
            "msmnile" to 640, "kona" to 650, "lahaina" to 660, "taro" to 730, "cape" to 730,
            "kalama" to 740, "pineapple" to 750, "sun" to 830,
        )

        internal fun platformModel(platform: String = systemProperty("ro.board.platform")): Int =
            MTK_PLATFORMS[platform.lowercase()]?.model
                ?: PLATFORMS[platform.lowercase()]
                ?: PLATFORMS[systemProperty("ro.vendor.qti.soc_name").lowercase()] ?: 0

        /** android.os.SystemProperties.get, which apps may read but not call directly. */
        fun systemProperty(name: String): String = try {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as? String ?: ""
        } catch (_: Exception) {
            ""
        }

        /**
         * The Mesa architecture (PAN_ARCH) a Mali model number belongs to: 4/5 Midgard, 6/7
         * Bifrost, 9/10/11 Valhall, 12 5th gen. 0 when the model is not one we can name.
         *
         * A T720 (Midgard) and a G720 (5th gen) are both "720", so the kernel's own spelling
         * decides when it carries a 't'.
         */
        internal fun maliArch(model: Int, kernelName: String = ""): Int {
            if (Regex("""(?i)mali[_-]?t\d{3}""").containsMatchIn(kernelName)) {
                return if (model in intArrayOf(600, 620, 720)) 4 else 5
            }
            return when (model) {
                71, 72 -> 6            // Bifrost v6
                31, 51, 52, 76 -> 7    // Bifrost v7
                57, 77, 78 -> 9        // Valhall v9
                310, 510, 610 -> 10    // Valhall v10
                615, 715 -> 11         // Valhall v11
                720, 725 -> 12         // 5th gen v12
                else -> 0
            }
        }

        /** The family a Mesa Mali architecture belongs to. */
        internal fun maliFamily(arch: Int): Family = when (arch) {
            4, 5 -> Family.MALI_MIDGARD
            6, 7 -> Family.MALI_BIFROST
            9, 10, 11, 12, 13, 14 -> Family.MALI_VALHALL
            else -> Family.MALI_UNKNOWN
        }

        /** "Mali-G615 MC6" when the SoC's part is known, else "Mali-G615" from the model alone. */
        private fun maliName(platform: String, model: Int, arch: Int): String {
            MTK_PLATFORMS[platform.lowercase()]?.let { if (it.model == model) return it.name }
            val prefix = if (arch == 4 || arch == 5) "T" else "G"
            return "Mali-$prefix$model"
        }

        internal fun familyOf(isAdreno: Boolean, model: Int): Family = familyOf(isAdreno, false, model)

        internal fun familyOf(isAdreno: Boolean, isMali: Boolean, model: Int, arch: Int = 0): Family = when {
            isMali -> if (model == 0) Family.MALI_UNKNOWN else maliFamily(if (arch > 0) arch else maliArch(model))
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
