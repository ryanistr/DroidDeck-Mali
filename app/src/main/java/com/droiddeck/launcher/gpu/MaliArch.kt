package com.droiddeck.launcher.gpu

/**
 * The Mali generations a driver carries, and the choice made from that.
 *
 * A PanVK build only drives the generations it was compiled for: a device of another generation
 * enumerates no physical device through it. The generations come from the driver itself - the
 * `gpuArch` in its own metadata - never from a constant in the app, so a device is never handed a
 * build that cannot drive it.
 */
object MaliArch {

    /** True when [archs] says the build carries [arch]. A build that says nothing never refuses. */
    fun carries(archs: IntArray, arch: Int): Boolean =
        arch == 0 || archs.isEmpty() || archs.contains(arch)

    /**
     * The generations of the driver a session draws with: the imported one when it named some,
     * the runtime's own build when it did, and [shipped] - what the app knows about the driver the
     * runtime ships - when neither said anything.
     */
    fun effective(imported: IntArray?, builtin: IntArray, shipped: IntArray = intArrayOf()): IntArray =
        when {
            imported != null && imported.isNotEmpty() -> imported
            builtin.isNotEmpty() -> builtin
            else -> shipped
        }

    /**
     * The first of [candidates] that carries [arch], or null when none does. A candidate that
     * names no generation is used only when no named one matches: it is offered, not preferred.
     */
    fun pick(candidates: List<Pair<String, IntArray>>, arch: Int): String? {
        var silent: String? = null
        for ((id, archs) in candidates) {
            if (archs.isEmpty()) {
                if (silent == null) silent = id
                continue
            }
            if (carries(archs, arch)) return id
        }
        return silent
    }
}
