package com.droiddeck.launcher.runtime

import android.content.Context
import com.droiddeck.launcher.gpu.GpuInfo
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class LinuxRuntimeIcdTest {
    private lateinit var context: Context
    private lateinit var root: File
    private lateinit var icdDir: File
    private lateinit var usrLib: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        root = LinuxRuntime.rootDir(context)
        root.deleteRecursively()
        icdDir = File(root, "usr/share/vulkan/icd.d").apply { mkdirs() }
        usrLib = File(root, "usr/lib").apply { mkdirs() }
    }

    @Test
    fun generatesLavapipeManifestWhenLibraryExists() {
        File(usrLib, "libvulkan_lvp.so").writeBytes(ByteArray(10))
        File(icdDir, "panvk_icd.aarch64.json").writeText("{\"ICD\":{\"library_path\":\"/usr/lib/libvulkan_panvk.so\"}}")

        val icd = LinuxRuntime.vulkanIcd(context)
        val lvpManifest = File(icdDir, "lvp_icd.aarch64.json")

        assertTrue("lvp_icd.aarch64.json should be created", lvpManifest.isFile)
        val text = lvpManifest.readText()
        assertTrue(text.contains("/usr/lib/libvulkan_lvp.so"))
    }

    @Test
    fun selectsLavapipeManifestWhenPresent() {
        File(usrLib, "libvulkan_lvp.so").writeBytes(ByteArray(10))
        val lvpManifest = File(icdDir, "lvp_icd.aarch64.json").apply {
            writeText("{\"ICD\":{\"library_path\":\"/usr/lib/libvulkan_lvp.so\"}}")
        }
        val icd = LinuxRuntime.vulkanIcd(context)
        assertNotNull(icd)
        assertTrue(icd!!.name.contains("lvp") || icd.name.contains("panvk"))
    }

    /** A manifest pointing at a driver the runtime does not carry must not be handed to the loader. */
    @Test
    fun ignoresAManifestWhoseLibraryIsMissing() {
        File(icdDir, "lvp_icd.aarch64.json").apply {
            writeText("{\"ICD\":{\"library_path\":\"/usr/lib/libvulkan_lvp.so\"}}")
        }

        val icd = LinuxRuntime.vulkanIcd(context)
        assertNull(icd)
    }
}
