package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Mali model number is a name, not a rank: a G615 is a newer GPU than a G77 and a smaller number,
 * so nothing may be derived by comparing the numbers. What the driver needs is the generation.
 */
class GpuMaliFamilyTest {
    private fun mali(model: Int, arch: Int, kernel: String = "") =
        GpuInfo(
            name = "Mali-G$model",
            model = model,
            family = GpuInfo.familyOf(false, true, model, arch),
            soc = "", oneUi8Gen2 = false,
            arch = arch,
        )

    @Test
    fun generationsComeFromTheModelNotItsSize() {
        assertEquals(9, GpuInfo.maliArch(57))
        assertEquals(9, GpuInfo.maliArch(77))
        assertEquals(9, GpuInfo.maliArch(78))
        assertEquals(10, GpuInfo.maliArch(610))
        assertEquals(11, GpuInfo.maliArch(615))
        assertEquals(11, GpuInfo.maliArch(715))
        assertEquals(12, GpuInfo.maliArch(720))
        assertEquals(0, GpuInfo.maliArch(0))
    }

    @Test
    fun aMidgardTIsNotASameNumberedG() {
        // A T720 is Midgard and a G720 is 5th gen: the number alone cannot tell them apart.
        assertEquals(4, GpuInfo.maliArch(720, "mediatek,mali\u0000arm,mali-t720"))
        assertEquals(12, GpuInfo.maliArch(720, "mediatek,mali\u0000arm,mali-valhall"))
        assertEquals(12, GpuInfo.maliArch(720, ""))
    }

    @Test
    fun familiesFollowTheArchitecture() {
        assertEquals(GpuInfo.Family.MALI_VALHALL, GpuInfo.familyOf(false, true, 615, 11))
        assertEquals(GpuInfo.Family.MALI_VALHALL, GpuInfo.familyOf(false, true, 77, 9))
        assertEquals(GpuInfo.Family.MALI_BIFROST, GpuInfo.familyOf(false, true, 52, 7))
        assertEquals(GpuInfo.Family.MALI_MIDGARD, GpuInfo.familyOf(false, true, 860, 5))
        // Without an architecture, the model alone still classifies a known one.
        assertEquals(GpuInfo.Family.MALI_VALHALL, GpuInfo.familyOf(false, true, 77))
    }

    @Test
    fun aMediaTekPlatformGivesItsOwnGpu() {
        assertEquals(77, GpuInfo.platformModel("mt6893"))    // Dimensity 1200/8050: Mali-G77
        assertEquals(615, GpuInfo.platformModel("mt6897"))   // Dimensity 8300/8350: Mali-G615
        assertEquals(610, GpuInfo.platformModel("mt6895"))   // Dimensity 8100: Mali-G610
        assertEquals(GpuInfo.Family.MALI_VALHALL, GpuInfo.familyOf(false, true, GpuInfo.platformModel("mt6897")))
        assertTrue(GpuInfo.maliArch(GpuInfo.platformModel("mt6897")) > GpuInfo.maliArch(GpuInfo.platformModel("mt6893")))
    }

    @Test
    fun onlyAGenerationWithABackendHereRunsOnPanVK() {
        val g77 = mali(77, 9)
        assertEquals(GpuInfo.Support.UNTESTED, g77.support)
        assertTrue(g77.panvkBackend)

        // The tester's GT 30 Pro: PanVK has no v11 backend, so the session must not be pointed at it.
        val g615 = mali(615, 11)
        assertEquals(GpuInfo.Support.UNSUPPORTED, g615.support)
        assertFalse(g615.panvkBackend)
    }
}
