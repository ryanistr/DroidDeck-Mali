package com.droiddeck.launcher.gpu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The generations a driver carries decide which GPU it may be used on. */
class MaliArchTest {

    @Test
    fun aDriverThatSaysNothingIsOfferedToEveryGpu() {
        assertTrue(MaliArch.carries(intArrayOf(), 9))
        assertTrue(MaliArch.carries(intArrayOf(), 12))
    }

    @Test
    fun anUnknownGenerationIsGivenTheBenefitOfTheDoubt() {
        assertTrue(MaliArch.carries(intArrayOf(9, 10, 11), 0))
    }

    @Test
    fun aDriverIsRefusedTheGenerationsItDoesNotCarry() {
        assertTrue(MaliArch.carries(intArrayOf(9, 10, 11), 10))
        assertFalse(MaliArch.carries(intArrayOf(9, 10, 11), 12))
        assertFalse(MaliArch.carries(intArrayOf(9), 11))
    }

    @Test
    fun theImportedDriverIsTheOneThatDecidesWhenItSpoke() {
        assertArrayEquals(intArrayOf(12), MaliArch.effective(intArrayOf(12), intArrayOf(9, 10, 11)))
        assertArrayEquals(intArrayOf(9, 10, 11), MaliArch.effective(intArrayOf(), intArrayOf(9, 10, 11)))
        assertArrayEquals(intArrayOf(9, 10, 11), MaliArch.effective(null, intArrayOf(9, 10, 11)))
    }

    @Test
    fun aDriverThatCarriesTheGenerationIsPicked() {
        val picked = MaliArch.pick(listOf("other" to intArrayOf(12), "ours" to intArrayOf(9, 10, 11)), 11)
        assertEquals("ours", picked)
    }

    @Test
    fun aSilentDriverIsOnlyUsedWhenNoNamedOneMatches() {
        val named = MaliArch.pick(listOf("silent" to intArrayOf(), "ours" to intArrayOf(9)), 9)
        assertEquals("ours", named)
        val onlySilent = MaliArch.pick(listOf("silent" to intArrayOf()), 9)
        assertEquals("silent", onlySilent)
    }

    @Test
    fun noDriverForTheGenerationMeansTheGpusOwnDriverIsUsed() {
        assertNull(MaliArch.pick(listOf("ours" to intArrayOf(9, 10, 11)), 12))
    }

    /** The app must not claim generations the runtime's own driver does not carry. */
    @Test
    fun theAppAndTheShippedRuntimeDriverAgreeOnTheGenerations() {
        val declared = Regex("\"gpuArch\"\\s*:\\s*\\[([^]]*)\\]")
            .find(shippedRuntimeMeta())
            ?.groupValues?.get(1)
            ?.split(",")
            ?.map { it.trim().toInt() }
            ?: error("the runtime's meta.json names no gpuArch")
        assertEquals(
            "GpuInfo.PANVK_ARCHS and the runtime driver's own gpuArch disagree",
            declared,
            GpuInfo.PANVK_ARCHS.toList())
    }

    /** The metadata the runtime ships, read as text: org.json is stubbed in plain JVM tests. */
    private fun shippedRuntimeMeta(): String {
        val candidates = listOf(
            File("src/main/assets/linuxfs/meta.json"),
            File("app/src/main/assets/linuxfs/meta.json"),
            File("../app/src/main/assets/linuxfs/meta.json"))
        val file = candidates.firstOrNull { it.isFile }
            ?: error("the runtime's meta.json is not where it should be: $candidates")
        return file.readText()
    }
}
