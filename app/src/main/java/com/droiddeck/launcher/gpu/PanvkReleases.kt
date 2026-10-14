package com.droiddeck.launcher.gpu

import android.content.Context
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.core.Hashes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The PanVK builds this fork publishes, offered in the driver menus on a Mali GPU the way
 * [TurnipReleases] offers the Adreno ones: checked only when the user taps refresh, remembered
 * afterwards, and downloaded through the same importer a picked file uses.
 *
 * The point of the list is that one build cannot serve every Mali generation. A build either
 * carries a generation's backend or the GPU enumerates nothing, and which generations a build
 * carries is not in its file name - it is in how it was built, so [archsOf] keeps what each
 * published line carries and every row says it.
 *
 * Every asset is a `.tzst` (zstd + tar). The runtime's half (`panvk-linux-*`, glibc) is what the
 * Steam client, its games and the desktop draw with; the display half (`panvk-android-*`, bionic)
 * is what the app's compositor puts the screen frame together with.
 */
object PanvkReleases {
    /** The fork's own releases: tools/panvk/release.env pins what the apk ships from here. */
    const val REPO = "ryanistr/DroidDeck-Mali"

    private const val PREFS = "panvk_releases"
    private const val KEY_RELEASE = "latest"
    private const val KEY_DOWNLOADS = "downloads"

    /**
     * [linux] is the runtime's glibc half (false = the compositor's bionic half).
     * [archs] are the Mali architectures the build was built with, empty when the tag says nothing.
     */
    class Asset(
        val tag: String,
        val name: String,
        val url: String,
        val size: Long,
        val linux: Boolean,
        val sha256: String,
        val archs: IntArray,
    )

    class Check(val assets: List<Asset>, val checkedAt: Long)

    /**
     * What a published build carries, by the line it came from. The evidence is the build itself:
     * `tools/panvk-g57/build-panvk-g57.sh` fails unless the driver has `panvk_v9_` and `panvk_v11_`
     * local symbols, and only its patch 0003 (landed just before the p4 tag) adds the arch-11 ones,
     * so p1-p3 carry Valhall v9 and p4 onward carry v9 through Job Manager and v10/v11 through CSF
     * (the fork's `meson.build` puts arch 11 in `csf_archs` and keeps `jm_archs` at 6, 7, 9).
     * The 24.3.0 line is our own mesa-patches build (`0003-panvk-v9-jm`), v9 through Job Manager
     * only, and the committed bionic asset is the same fork tree with no `panvk_v11` symbol at all.
     */
    internal fun archsOf(tag: String): IntArray {
        val t = tag.lowercase()
        return when {
            t.startsWith("panvk-linux-g57-1.2.0-p") -> {
                val patch = t.substringAfterLast("-p").toIntOrNull() ?: 0
                if (patch >= 4) intArrayOf(9, 10, 11) else intArrayOf(9)
            }
            t.startsWith("panvk-linux-24.3.0-p") -> intArrayOf(9)
            t.startsWith("panvk-android-") -> intArrayOf(9)
            else -> IntArray(0)
        }
    }

    /**
     * How a Mali generation is submitted in these builds: v9 runs the Job Manager path, v10/v11
     * the Command Stream Frontend path (`csf_archs = [10, 11, 12, 13, 14]` in the fork). This is
     * what decides whether a GPU gets the fixes it needs - a CSF fault fix means nothing to a
     * G77, and a JM fix means nothing to a G610/G615.
     */
    @JvmStatic
    fun backendOf(arch: Int): String = when (arch) {
        9 -> "JM"
        10, 11 -> "CSF"
        else -> ""
    }

    /** "Valhall v9 (JM) + v10 (CSF) + v11 (CSF)", or "" for a build whose tag did not say. */
    @JvmStatic
    fun archLabel(context: Context, archs: IntArray): String {
        if (archs.isEmpty()) return ""
        val parts = archs.map { a ->
            val backend = backendOf(a)
            if (backend.isEmpty()) "v$a" else "v$a ($backend)"
        }
        return context.getString(R.string.drivers_valhall, parts.joinToString(" + "))
    }

    /** [arch] in [archs]; an empty set (unknown) never rules a build out. */
    @JvmStatic
    fun drives(archs: IntArray, arch: Int): Boolean = archs.isEmpty() || arch == 0 || archs.contains(arch)

    /** The result of the last check, or null when the user has never checked. */
    fun cached(context: Context): Check? {
        val raw = prefs(context).getString(KEY_RELEASE, null) ?: return null
        return runCatching { parse(JSONObject(raw)) }.getOrNull()
    }

    /**
     * The line a build belongs to. Patches of one line supersede each other (each is the previous
     * plus a fix), so only the newest of a line is offered - the way [TurnipReleases] offers the
     * newest release of each variant: the G57 fork's 1.2.0 line, our own 24.3.0 line, and whatever
     * bionic builds are published.
     */
    internal fun lineOf(tag: String): String {
        val t = tag.lowercase()
        return when {
            t.startsWith("panvk-linux-g57-1.2.0") -> "panvk-linux-g57-1.2.0"
            t.startsWith("panvk-linux-24.3.0") -> "panvk-linux-24.3.0"
            t.startsWith("panvk-android-") -> "panvk-android"
            else -> t
        }
    }

    /** Read the repo's releases now and remember the result; throws when GitHub cannot be reached. */
    fun refresh(context: Context): Check {
        val assets = JSONArray()
        val seen = HashSet<String>()
        val releases = JSONArray(get(context, "https://api.github.com/repos/$REPO/releases?per_page=30"))
        for (i in 0 until releases.length()) {
            val r = releases.getJSONObject(i)
            if (r.optBoolean("draft")) continue
            val tag = r.getString("tag_name")
            val list = r.optJSONArray("assets") ?: continue
            for (j in 0 until list.length()) {
                val a = list.getJSONObject(j)
                val name = a.getString("name")
                if (!name.startsWith("panvk-") || !name.endsWith(".tzst")) continue
                // Only assets GitHub has a sha256 for are offered: the download is checked against it.
                val sha = Hashes.githubSha256(a.optString("digest")) ?: continue
                val linux = name.startsWith("panvk-linux-")
                // Newest first, so the first release carrying a line is the one offered.
                if (!seen.add("${lineOf(tag)}|$linux")) continue
                val archs = archsOf(tag)
                assets.put(
                    JSONObject().put("tag", tag).put("name", name)
                        .put("url", a.getString("browser_download_url")).put("size", a.optLong("size"))
                        .put("linux", linux).put("sha256", sha)
                        .put("archs", JSONArray(archs.toList())),
                )
            }
        }
        val stored = JSONObject().put("assets", assets).put("checkedAt", System.currentTimeMillis())
        prefs(context).edit().putString(KEY_RELEASE, stored.toString()).apply()
        return parse(stored)
    }

    private fun get(context: Context, url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "DroidDeck-app")
        try {
            val code = c.responseCode
            if (code == 403 || code == 429) throw IOException(context.getString(R.string.turnip_rate_limited))
            // A private repo answers 404 here; the app never sees one, but say so if it happens.
            if (code != 200) throw IOException(context.getString(R.string.turnip_github_http, code))
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(json: JSONObject): Check {
        val out = ArrayList<Asset>()
        val list = json.optJSONArray("assets") ?: JSONArray()
        for (i in 0 until list.length()) {
            val a = list.getJSONObject(i)
            val archs = a.optJSONArray("archs") ?: JSONArray()
            out.add(
                Asset(
                    a.getString("tag"), a.getString("name"), a.getString("url"), a.optLong("size"),
                    a.getBoolean("linux"), a.optString("sha256", ""),
                    IntArray(archs.length()) { archs.optInt(it) },
                ),
            )
        }
        return Check(out, json.optLong("checkedAt"))
    }

    /** The id an asset was installed as, when it was downloaded before and is still installed. */
    fun installedId(context: Context, asset: Asset, isInstalled: (String) -> Boolean): String? =
        downloads(context).optString(asset.name, "").takeIf { it.isNotEmpty() && isInstalled(it) }

    fun recordDownload(context: Context, asset: Asset, id: String) {
        prefs(context).edit().putString(KEY_DOWNLOADS, downloads(context).put(asset.name, id).toString()).apply()
    }

    /** Forget a deleted driver, so its release offers the download again. */
    fun forget(context: Context, id: String) {
        val d = downloads(context)
        val keys = d.keys().asSequence().filter { d.optString(it) == id }.toList()
        if (keys.isEmpty()) return
        keys.forEach { d.remove(it) }
        prefs(context).edit().putString(KEY_DOWNLOADS, d.toString()).apply()
    }

    /** Download an asset into the cache; the caller imports it and deletes the file. */
    fun download(context: Context, asset: Asset, progress: (Int) -> Unit): File {
        if (asset.sha256.isEmpty()) throw IOException(context.getString(R.string.turnip_list_predates_checksums))
        val target = File(context.cacheDir, asset.name)
        val c = URL(asset.url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.setRequestProperty("User-Agent", "DroidDeck-app")
        c.instanceFollowRedirects = true
        try {
            if (c.responseCode != 200) throw IOException(context.getString(R.string.turnip_download_http, c.responseCode))
            val total = c.contentLengthLong.takeIf { it > 0 } ?: asset.size
            c.inputStream.use { input ->
                FileOutputStream(target).use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val r = input.read(buf)
                        if (r <= 0) break
                        out.write(buf, 0, r)
                        done += r
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != last) { last = pct; progress(pct) }
                    }
                }
            }
            if (!asset.sha256.equals(Hashes.sha256(target), ignoreCase = true)) {
                throw IOException(context.getString(R.string.turnip_checksum_mismatch))
            }
            return target
        } catch (e: Exception) {
            FileUtils.delete(target)
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun downloads(context: Context): JSONObject =
        runCatching { JSONObject(prefs(context).getString(KEY_DOWNLOADS, "{}")!!) }.getOrDefault(JSONObject())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
