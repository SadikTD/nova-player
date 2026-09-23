package com.sadik.novaplayer

import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream

 data class SubtitleResult(val id: String, val name: String, val language: String, val format: String, val url: String,
    val exact: Boolean, val downloads: Int, val encoding: String, val score: Double)
 data class ParsedTitle(val title: String, val season: Int?, val episode: Int?)
private val FORCED = Regex("""\b(forced|foreign[ ._-]?parts?|signs?[ ._-]?(and|&)[ ._-]?songs?)\b""", RegexOption.IGNORE_CASE)
object OnlineSubtitles {
    val batchProgress = MutableStateFlow("")
    private var batchJob: Job? = null
    fun parseTitle(name: String): ParsedTitle {
        val base = name.substringBeforeLast('.').replace(Regex("[._]+"), " ").replace(Regex("^\\[[^]]+]"), "").trim()
        val episode = Regex("(?i)\\b(?:s(\\d{1,2})[ .-]*e(\\d{1,3})|(\\d{1,2})x(\\d{1,3}))\\b").find(base)
        // "Sintel (2010)" / "Show - S01E02": drop the separators left dangling before the year or episode.
        fun String.tidy() = trimEnd(' ', '(', '[', '{', '-', ',', '–').trim()
        if (episode != null) return ParsedTitle(base.substring(0, episode.range.first).tidy(), (episode.groupValues[1].ifBlank { episode.groupValues[3] }).toInt(), (episode.groupValues[2].ifBlank { episode.groupValues[4] }).toInt())
        val anime = Regex("^(.+?) - (\\d{1,3})(?:v\\d)?(?: |$)").find(base)
        if (anime != null) return ParsedTitle(anime.groupValues[1], 1, anime.groupValues[2].toInt())
        return ParsedTitle(base.split(Regex("(?i)\\b(?:19\\d{2}|20\\d{2}|\\d{3,4}p|2160|uhd|bluray|web dl|webrip|hdtv|x264|x265|hevc)\\b"))[0].tidy().ifBlank { base }, null, null)
    }
    private fun request(address: String): ByteArray {
        var url = URL(address)
        repeat(5) {
            require(url.protocol == "https") { "The subtitle service returned an insecure download address" }
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000; connection.readTimeout = 20000; connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "NovaPlayer"); connection.setRequestProperty("X-User-Agent", "TemporaryUserAgent"); connection.setRequestProperty("Accept", "application/json")
            try {
                if (connection.responseCode in listOf(301,302,303,307,308)) { url = URL(url, connection.getHeaderField("Location") ?: error("Invalid subtitle redirect")); require(url.host.contains('.')); return@repeat }
                require(connection.responseCode == 200) { "Subtitle service returned ${connection.responseCode}. Try again later." }
                return connection.inputStream.use { it.readBytesLimited(8 * 1024 * 1024) }
            } finally { connection.disconnect() }
        }
        error("Too many subtitle redirects")
    }
    private fun hash(video: Video): Pair<String, Long>? = runCatching {
        val uri = Uri.parse(video.uri)
        if (uri.scheme != "content" && uri.scheme != "file") return null
        val descriptor = NovaRuntime.app.contentResolver.openFileDescriptor(uri, "r") ?: return null
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val channel = input.channel; val size = channel.size(); if (size < 131072) return null
            var sum = size
            for (position in listOf(0L, size - 65536)) {
                channel.position(position); val bytes = ByteBuffer.allocate(65536).order(ByteOrder.LITTLE_ENDIAN)
                while (bytes.hasRemaining()) { if (channel.read(bytes) < 0) error("Incomplete video hash") }
                bytes.flip(); while (bytes.hasRemaining()) sum += bytes.long
            }
            java.lang.Long.toUnsignedString(sum,16).padStart(16,'0') to size
        }
    }.getOrNull()
    suspend fun search(video: Video, query: String = "", languages: List<String> = listOf("eng")): List<SubtitleResult> {
        val title = parseTitle(video.title)
        val clean = (query.ifBlank { title.title }).lowercase().replace(Regex("['’`]"), "").replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        val querySegment = "query-" + URLEncoder.encode(clean, "UTF-8")
        val groups = mutableListOf<List<String>>()
        hash(video)?.let { groups += listOf("moviehash-${it.first}", "moviebytesize-${it.second}") }
        groups.add(buildList<String> { add(querySegment); if (query.isBlank()) { title.season?.let { add("season-$it") }; title.episode?.let { add("episode-$it") } } })
        val base = video.title.substringBeforeLast('.')
        val group = Regex("""-([A-Za-z0-9]{2,})(?:\[[^\]]*\])?$""").find(base)?.groupValues?.get(1) ?: Regex("""^\[([^\]]+)\]""").find(base)?.groupValues?.get(1).orEmpty()
        val source = Regex("""(?i)\b(web[ ._-]?dl|webrip|bluray|blu[ ._-]?ray|brrip|bdrip|hdtv|dvdrip|remux|hdrip)\b""").find(base)?.groupValues?.get(1).orEmpty()
        val all = mutableMapOf<String, SubtitleResult>(); var lastError: Exception? = null
        for ((rank, language) in languages.map { it.trim().lowercase() }.filter { it.matches(Regex("[a-z]{3}")) }.ifEmpty { listOf("eng") }.take(5).withIndex()) {
            for (parts in groups) {
                currentCoroutineContext().ensureActive(); delay(400)
                try {
                    val rows = JSONArray(String(request("https://rest.opensubtitles.org/search/" + (parts + "sublanguageid-$language").sorted().joinToString("/")), Charsets.UTF_8))
                    for (i in 0 until rows.length()) {
                        val row = rows.getJSONObject(i); val id = row.optString("IDSubtitleFile"); val url = row.optString("SubDownloadLink"); if (id.isBlank() || !url.startsWith("https://")) continue
                        val exact = row.optString("MatchedBy") == "moviehash"
                        val downloads = row.optInt("SubDownloadsCnt"); val name = row.optString("SubFileName"); val format = row.optString("SubFormat", "srt").lowercase()
                        if (format !in setOf("srt","ass","ssa","vtt")) continue
                        // Desktop score(): hash ≫ language ≫ same release group/source ≫ popularity ≫ rating; forced/tiny files sink.
                        val hay = (name + " " + row.optString("MovieReleaseName")).lowercase()
                        val size = row.optDouble("SubSize", 0.0); val votes = row.optInt("SubSumVotes"); val rating = row.optDouble("SubRating", 0.0)
                        val forced = FORCED.containsMatchIn(hay)
                        var score = (if (exact) 1000.0 else 0.0) + maxOf(0, 60 - rank * 60)
                        if (group.isNotBlank() && group.lowercase() in hay) score += 120
                        if (source.isNotBlank() && source.lowercase().replace(Regex("[ ._-]"), "") in hay) score += 40
                        if (format == "srt") score += 25
                        score += kotlin.math.min(90.0, kotlin.math.log10(downloads + 1.0) * 18)
                        if (votes >= 2) score += kotlin.math.min(30.0, (rating - 5) * 6)
                        if (row.optInt("SubBad") > 0) score -= 200
                        if (row.optString("SubHearingImpaired") == "1") score -= 8
                        if (forced) score -= 400 else if (size > 0 && size < 12000) score -= kotlin.math.min(180.0, (12000 - size) / 50)
                        val result = SubtitleResult(id, name, row.optString("LanguageName", language), format, url, exact, downloads, row.optString("SubEncoding"), score)
                        if (all[id] == null || exact) all[id] = result
                    }
                } catch (e: CancellationException) { throw e } catch (e: Exception) { lastError = e }
            }
        }
        if (all.isEmpty() && lastError != null) throw lastError
        return all.values.sortedByDescending { it.score }.take(60)
    }
    fun download(video: Video, result: SubtitleResult): File {
        var bytes = request(result.url)
        if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()) bytes = GZIPInputStream(bytes.inputStream()).use { it.readBytesLimited(8 * 1024 * 1024) }
        var text = when {
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, Charsets.UTF_16LE)
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, Charsets.UTF_16BE)
            else -> runCatching { String(bytes, Charset.forName(result.encoding.ifBlank { "UTF-8" })) }.getOrElse { String(bytes, Charsets.UTF_8) }
        }.removePrefix("\uFEFF")
        if ('\uFFFD' in text) text = String(bytes, Charset.forName("windows-1252"))
        require(!text.trimStart().startsWith("<html", true)) { "The service returned a webpage instead of subtitles" }
        text = if (result.format == "srt") cleanSrt(text)
            else text.lines().filterNot { it.startsWith("Dialogue:", true) && looksLikeAd(it.substringAfterLast(",,")) }.joinToString("\n")
        require(text.isNotBlank()) { "Downloaded subtitle is empty" }
        val base = result.name.removeSuffix(".${result.format}").ifBlank { video.title.substringBeforeLast('.') }
        val file = NovaRuntime.subtitleFile("${stableId(video.uri)}-${result.id}", "$base.${result.language}.${result.format}")
        file.writeText(text); return file
    }
    fun batch(videos: List<Video>) {
        batchJob?.cancel()
        batchJob = NovaRuntime.scope.launch {
            var count = 0; var failed = 0
            try {
                for ((index, video) in videos.withIndex()) {
                    ensureActive(); batchProgress.value = "${index + 1}/${videos.size} · ${video.title}"
                    try {
                        val file = withContext(Dispatchers.IO) { search(video, languages = NovaRuntime.text("subLangs","eng").split(',')).firstOrNull()?.let { download(video,it) } }
                        if (file != null) { NovaRuntime.store.update(video.copy(externalSub = file.path, originalSub = file.path)); count++ } else failed++
                    } catch (e: CancellationException) { throw e } catch (_: Exception) { failed++ }
                }
                batchProgress.value = "Downloaded $count subtitles · $failed unavailable"
            } catch (_: CancellationException) { batchProgress.value = "Cancelled · $count subtitles saved" }
        }
    }
    fun cancelBatch() { batchJob?.cancel() }
}


/* Uploaders' advertising, injected as real subtitle cues (desktop subtitles.js AD_PATTERNS). */
private val AD_PATTERNS = listOf("opensubtitles", "addic7ed", "subscene", "yifysubtitles", """https?://""",
    // A bare domain is the giveaway on the newer injected ads (osdb.link, getray.app).
    """\b[a-z0-9][a-z0-9-]{2,}\.(com|net|org|app|link|tv|io|me|co|info|xyz)\b""",
    "watch online movies", "become vip member", "advertise your product", "support us and become", "remove all ads").map { Regex(it, RegexOption.IGNORE_CASE) }
fun looksLikeAd(text: String): Boolean { val t = text.replace(Regex("<[^>]+>"), " ").trim(); return t.isNotEmpty() && AD_PATTERNS.any { it.containsMatchIn(t) } }
private val TIME_LINE = Regex("""^\s*-?\d{1,3}:\d{2}:\d{2}[.,]\d{1,3}\s*-->\s*-?\d{1,3}:\d{2}:\d{2}[.,]\d{1,3}""")

/**
 * Port of desktop cleanSrt. Cues are found by their timing lines, not by blank lines: the ad
 * injector overwrites a cue's index line, leaving two timing lines stacked with no separator, and
 * splitting on blank lines glues the advert onto real dialogue. Anything without recognisable
 * timing lines passes through untouched — better an advert than a mangled subtitle.
 */
fun cleanSrt(input: String, dropAds: Boolean = true): String {
    val normalised = input.replace(Regex("\r\n?"), "\n").removePrefix("\uFEFF")
    val lines = normalised.split('\n')
    val anchors = lines.indices.filter { TIME_LINE.containsMatchIn(lines[it]) }
    if (anchors.isEmpty()) return normalised
    val cues = mutableListOf<String>()
    for ((k, at) in anchors.withIndex()) {
        val stop = if (k + 1 < anchors.size) anchors[k + 1] else lines.size
        val body = lines.subList(at + 1, stop).joinToString("\n").replace(Regex("""\n\s*\d{1,6}\s*$"""), "").trimEnd()
        if (body.isBlank() || (dropAds && looksLikeAd(body))) continue
        cues += canonicalTiming(lines[at]) + "\n" + body
    }
    if (cues.isEmpty()) return normalised
    return cues.mapIndexed { i, c -> "${i + 1}\n$c" }.joinToString("\n\n") + "\n"
}

private val STAMP = Regex("""(\d{1,3}):(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")
/**
 * "00:00:01,436-->00:00:02,9" → "00:00:01,436 --> 00:00:02,900". Uploads on OpenSubtitles
 * regularly drop the spaces around the arrow; mpv's SRT probe then refuses the whole file
 * ("Can not open external file") even though every cue is fine.
 */
fun canonicalTiming(line: String): String {
    val t = STAMP.findAll(line).take(2).toList()
    if (t.size < 2) return line.trim()
    fun f(m: MatchResult) = "%02d:%02d:%02d,%s".format(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].padEnd(3, '0'))
    return "${f(t[0])} --> ${f(t[1])}"
}

/**
 * Repairs timing lines of a local SRT without touching anything else. Works on ISO-8859-1
 * (one byte = one char) so any original encoding — cp1252, UTF-8, Bengali — survives byte-for-byte.
 * Returns null when nothing needed fixing (or the file is UTF-16, which mpv reads fine).
 */
fun healSrtBytes(bytes: ByteArray): ByteArray? {
    if (bytes.size >= 2 && ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) || (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))) return null
    val text = String(bytes, Charsets.ISO_8859_1)
    var changed = false
    val out = text.split('\n').joinToString("\n") { raw ->
        val cr = raw.endsWith('\r'); val line = raw.removeSuffix("\r")
        if (TIME_LINE.containsMatchIn(line)) { val c = canonicalTiming(line); if (c != line) changed = true; c + (if (cr) "\r" else "") } else raw
    }
    return if (changed) out.toByteArray(Charsets.ISO_8859_1) else null
}
