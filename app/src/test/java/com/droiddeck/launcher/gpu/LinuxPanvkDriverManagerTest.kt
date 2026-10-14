package com.droiddeck.launcher.gpu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A PanVK build does not always call its library libvulkan_panvk.so: upstream Mesa's soname is
 * libvulkan_panfrost.so, and this fork renames it only for the runtime. A build that keeps the
 * upstream name must still import - the AOSP/Qualcomm importer looked only for the fork's name and
 * refused the upstream one, which is what the GT 20 Pro hit when a PanVK build was picked as the
 * runtime driver.
 */
class LinuxPanvkDriverManagerTest {
    @Test
    fun bothPanvkSonamesAreAccepted() {
        assertTrue(LinuxPanvkDriverManager.isPanvkLibrary("libvulkan_panvk.so"))
        assertTrue(LinuxPanvkDriverManager.isPanvkLibrary("libvulkan_panfrost.so"))
        assertTrue(LinuxPanvkDriverManager.isPanvkLibrary("libvulkan_panvk_kbase.so"))
        assertTrue(LinuxPanvkDriverManager.isPanvkLibrary("libvulkan_panfrost.so.1"))
    }

    @Test
    fun otherVulkanDriversAreNot() {
        assertFalse(LinuxPanvkDriverManager.isPanvkLibrary("libvulkan_freedreno.so"))
        assertFalse(LinuxPanvkDriverManager.isPanvkLibrary("libvulkan_lvp.so"))
        assertFalse(LinuxPanvkDriverManager.isPanvkLibrary("vulkan.mali.so"))
        assertFalse(LinuxPanvkDriverManager.isPanvkLibrary("libvulkan_panvk.a"))
        assertFalse(LinuxPanvkDriverManager.isPanvkLibrary("meta.json"))
    }
}
