package com.droiddeck.launcher.gpu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One PanVK build does not drive every Mali: a build carries the backends it was built with, and a
 * GPU of another generation enumerates nothing through it. The published lines differ exactly there,
 * so the release list has to say which generations each one carries.
 */
class PanvkReleasesTest {
    @Test
    fun theG57LineCarriesArch11OnlyFromP4() {
        // The fork's build script fails unless the driver has v9 and v11 local symbols, and patch
        // 0003 (which adds the v11 ones) landed just before the p4 tag. Arch 11 comes from the
        // fork's CSF backend (csf_archs = [10, 11, 12, 13, 14] in its meson.build), so v10 is
        // carried too, and a G610 (the GT 20 Pro) needs exactly that.
        assertArrayEquals(intArrayOf(9), PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p1"))
        assertArrayEquals(intArrayOf(9), PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p3"))
        assertArrayEquals(intArrayOf(9, 10, 11), PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p4"))
        assertArrayEquals(intArrayOf(9, 10, 11), PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p12"))
    }

    @Test
    fun theOlderLinesAndTheBionicBuildCarryValhallV9() {
        assertArrayEquals(intArrayOf(9), PanvkReleases.archsOf("panvk-linux-24.3.0-p13"))
        assertArrayEquals(intArrayOf(9), PanvkReleases.archsOf("panvk-android-24.3.0"))
    }

    @Test
    fun aTagNobodyKnowsMakesNoClaim() {
        assertArrayEquals(intArrayOf(), PanvkReleases.archsOf("panvk-linux-something-new-9.9.9"))
    }

    @Test
    fun aBuildThatNamesNoGenerationIsNotRefused() {
        // A build whose tag says nothing is offered and picked; only one that names other
        // generations is refused the device.
        assertTrue(PanvkReleases.drives(intArrayOf(), 11))
        assertTrue(PanvkReleases.drives(intArrayOf(9, 11), 0))
    }

    @Test
    fun aG615IsRefusedTheBuildsThatHaveNoArch11Backend() {
        val g615 = GpuInfo.maliArch(615)
        assertFalse(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-24.3.0-p13"), g615))
        assertFalse(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p3"), g615))
        assertTrue(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p4"), g615))

        // The GT 20 Pro's G610 is arch 10, which rides the same CSF backend as 11, so the same
        // builds that give a G615 nothing or something give it nothing or something.
        val g610 = GpuInfo.maliArch(610)
        assertFalse(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-24.3.0-p13"), g610))
        assertFalse(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p3"), g610))
        assertTrue(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p4"), g610))

        // A G77 is the one the older lines were built for.
        val g77 = GpuInfo.maliArch(77)
        assertTrue(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-24.3.0-p13"), g77))
        assertTrue(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-g57-1.2.0-p3"), g77))
    }

    @Test
    fun backendsAreWiredThroughTheTable() {
        // v9 submits through Job Manager, v10/v11 through the Command Stream Frontend; anything
        // else has no per-arch submission path here.
        assertEquals("JM", PanvkReleases.backendOf(9))
        assertEquals("CSF", PanvkReleases.backendOf(10))
        assertEquals("CSF", PanvkReleases.backendOf(11))
        assertEquals("", PanvkReleases.backendOf(0))
        assertEquals("", PanvkReleases.backendOf(12))
    }

    @Test
    fun anUnknownGpuIsLeftToTheDriver() {
        assertTrue(PanvkReleases.drives(PanvkReleases.archsOf("panvk-linux-24.3.0-p13"), 0))
    }
}
