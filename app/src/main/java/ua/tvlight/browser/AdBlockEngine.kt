package ua.tvlight.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Offline-first DNS-host filter with a periodically refreshed community hosts list. */
class AdBlockEngine(private val context: Context) {
    private val cache = File(context.filesDir, "adblock-hosts.txt")
    private val hosts = AtomicReference<Set<String>>(builtIn)
    private val filterHosts = AtomicReference<Set<String>>(emptySet())
    private val filterCache = File(context.filesDir, "adblock-easylist-hosts.txt")
    private val prefs = context.getSharedPreferences("adblock_engine", Context.MODE_PRIVATE)

    init {
        Thread {
            try {
                if (cache.exists()) {
                    val parsed = parseHosts(cache.readText())
                    if (parsed.size > 1000) hosts.set(builtIn + parsed)
                }
                if (filterCache.exists()) filterHosts.set(filterCache.readLines().toSet())
                val last = prefs.getLong("updated", 0L)
                if (System.currentTimeMillis() - last > 24L * 60 * 60 * 1000) refresh()
            } catch (_: Exception) { /* keep offline rules */ }
        }.start()
    }

    fun shouldBlock(request: WebResourceRequest?, pageHost: String?, strong: Boolean): Boolean {
        val uri = request?.url ?: return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (host == pageHost) return false
        val domainBlock = matches(host, hosts.get()) || matches(host, filterHosts.get())
        if (domainBlock) return true
        if (!strong) return false
        // Restrict heuristic rules to third-party resources; do not block media URLs.
        val path = uri.path?.lowercase(Locale.ROOT) ?: ""
        val thirdParty = pageHost != null && host != pageHost &&
            !host.endsWith(".$pageHost") && !pageHost.endsWith(".$host")
        if (!thirdParty) return false
        val media = path.endsWith(".m3u8") || path.endsWith(".mp4") ||
            path.endsWith(".mpd") || path.endsWith(".ts")
        if (media) return false
        return path.contains("/ads/") || path.contains("/advert/") ||
            path.contains("/popunder/") || path.contains("/banner/")
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun matches(host: String, set: Set<String>): Boolean {
        var candidate = host
        while (true) {
            if (candidate in set) return true
            val dot = candidate.indexOf('.')
            if (dot < 0) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    private fun parseHosts(text: String): Set<String> {
        val result = HashSet<String>()
        for (line in text.lineSequence()) {
            val trimmed = line.substringBefore('#').trim()
            if (trimmed.isEmpty()) continue
            val pieces = trimmed.split(Regex("\\s+"))
            if (pieces.size < 2 || pieces[0] !in listOf("0.0.0.0", "127.0.0.1")) continue
            for (host in pieces.drop(1)) {
                val h = host.lowercase(Locale.ROOT)
                if (h != "localhost" && h.contains('.') &&
                    h.all { it.isLetterOrDigit() || it == '.' || it == '-' }) result.add(h)
            }
        }
        return result
    }

    private fun refreshFilterHosts() {
        val sources = listOf(
            "https://easylist.to/easylist/easylist.txt",
            "https://easylist.to/easylist/easyprivacy.txt",
            "https://filters.adtidy.org/extension/ublock/filters/2.txt"
        )
        val domains = HashSet<String>()
        for (source in sources) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(source).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 12000
                }
                if (conn.responseCode != 200) continue
                conn.inputStream.bufferedReader().use { reader ->
                    var count = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        count += line.length
                        if (count > 6_000_000) break
                        if (!line.startsWith("||") || line.startsWith("||~")) continue
                        val domain = line.drop(2).substringBefore('^')
                        if (domain.length < 5 || !domain.contains('.') ||
                            !domain.all { it.isLetterOrDigit() || it == '.' || it == '-' }) continue
                        domains.add(domain.lowercase(Locale.ROOT))
                    }
                }
            } catch (_: Exception) {} finally { conn?.disconnect() }
        }
        if (domains.size > 1000) {
            filterCache.writeText(domains.joinToString("\n"))
            filterHosts.set(domains)
        }
    }

    private fun refresh() {
        refreshFilterHosts()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts")
                .openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 12000
                instanceFollowRedirects = true
            }
            if (connection.responseCode != 200) return
            val data = connection.inputStream.bufferedReader().use { reader ->
                val builder = StringBuilder()
                val buffer = CharArray(8192)
                while (true) {
                    val n = reader.read(buffer)
                    if (n < 0) break
                    builder.append(buffer, 0, n)
                    if (builder.length > 12_000_000) return
                }
                builder.toString()
            }
            val parsed = parseHosts(data)
            if (parsed.size < 1000) return
            val tmp = File(context.filesDir, "adblock-hosts.tmp")
            tmp.writeText(data)
            if (!tmp.renameTo(cache)) {
                cache.writeText(data)
                tmp.delete()
            }
            hosts.set(builtIn + parsed)
            prefs.edit().putLong("updated", System.currentTimeMillis()).apply()
        } catch (_: Exception) { /* preserve offline rules */ }
        finally { connection?.disconnect() }
    }

    companion object {
        private val builtIn = setOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adnxs.com", "taboola.com", "outbrain.com", "popads.net",
            "adsrvr.org", "criteo.com", "scorecardresearch.com", "pubmatic.com",
            "rubiconproject.com", "openx.net", "smartadserver.com",
            "exoclick.com", "exosrv.com", "trafficjunky.net",
            "propellerads.com", "onclickads.net", "adsterra.com",
            "hilltopads.net", "bidgear.com", "mgid.com", "revcontent.com",
            "popcash.net", "juicyads.com", "clickadu.com",
            "ad-maven.com", "adcash.com"
        )
    }
}
