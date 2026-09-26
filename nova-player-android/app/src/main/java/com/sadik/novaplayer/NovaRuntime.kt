package com.sadik.novaplayer

import android.content.*
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import com.sadik.novaplayer.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

data class Track(val id: String, val type: String, val title: String, val detail: String, val selected: Boolean, val external: Boolean, val file: String = "")
data class Chapter(val title: String, val time: Double)
data class Playing(
    val video: Video? = null, val position: Double = 0.0, val duration: Double = 0.0,
    val paused: Boolean = false, val buffering: Boolean = false, val loading: Boolean = false,
    val tracks: List<Track> = emptyList(), val chapters: List<Chapter> = emptyList(),
    val speed: Double = 1.0, val volume: Double = 100.0, val muted: Boolean = false, val error: String = "", val decoder: String = "",
    val queue: List<String> = emptyList(), val queueIndex: Int = 0, val sleepAt: Long = 0, val sleepEnd: Boolean = false,
    val abStart: Double = -1.0, val abEnd: Double = -1.0, val ended: Boolean = false, val loop: Boolean = false,
    val videoW: Int = 0, val videoH: Int = 0, val cached: Double = 0.0, val subVisible: Boolean = true,
)
/** Transient on-screen feedback (volume keys, brightness, seek) rendered by the player. */
data class Hud(val kind: String, val value: Double, val max: Double, val text: String, val at: Long = System.nanoTime())
/** Snack with an optional action ("Resumed from 12:04 · Start over"). */
data class Snack(val text: String, val action: String? = null, val run: (() -> Unit)? = null, val at: Long = System.nanoTime())

private val CODECS = mapOf("subrip" to "SRT", "ass" to "ASS", "ssa" to "SSA", "webvtt" to "WebVTT", "hdmv_pgs_subtitle" to "PGS (image)", "dvd_subtitle" to "VobSub (image)",
    "dvb_subtitle" to "DVB (image)", "mov_text" to "Text", "aac" to "AAC", "ac3" to "Dolby Digital", "eac3" to "Dolby Digital Plus", "truehd" to "Dolby TrueHD", "dts" to "DTS",
    "opus" to "Opus", "vorbis" to "Vorbis", "flac" to "FLAC", "mp3" to "MP3", "pcm_s16le" to "PCM", "h264" to "H.264", "hevc" to "HEVC", "av1" to "AV1", "vp9" to "VP9")

private val SCRIPT_BY_LANG = mapOf("ben" to "Noto Sans Bengali", "bn" to "Noto Sans Bengali", "hin" to "Noto Sans Devanagari", "hi" to "Noto Sans Devanagari",
    "mar" to "Noto Sans Devanagari", "nep" to "Noto Sans Devanagari", "ara" to "Noto Naskh Arabic", "ar" to "Noto Naskh Arabic", "per" to "Noto Naskh Arabic", "fas" to "Noto Naskh Arabic",
    "urd" to "Noto Naskh Arabic", "ur" to "Noto Naskh Arabic", "tha" to "Noto Sans Thai", "th" to "Noto Sans Thai", "heb" to "Noto Sans Hebrew", "he" to "Noto Sans Hebrew",
    "tam" to "Noto Sans Tamil", "ta" to "Noto Sans Tamil", "tel" to "Noto Sans Telugu", "te" to "Noto Sans Telugu",
    "chi" to "Noto Sans CJK JP", "zho" to "Noto Sans CJK JP", "zh" to "Noto Sans CJK JP", "jpn" to "Noto Sans CJK JP", "ja" to "Noto Sans CJK JP", "kor" to "Noto Sans CJK JP", "ko" to "Noto Sans CJK JP")
/** Most frequent non-Latin script in a subtitle sample, as a font family we ship. */
fun dominantScript(text: String): String? {
    val counts = HashMap<String, Int>()
    for (ch in text) { val f = when (ch.code) { in 0x0980..0x09FF -> "Noto Sans Bengali"; in 0x0900..0x097F -> "Noto Sans Devanagari"; in 0x0600..0x06FF -> "Noto Naskh Arabic"
        in 0x0E00..0x0E7F -> "Noto Sans Thai"; in 0x0590..0x05FF -> "Noto Sans Hebrew"; in 0x0B80..0x0BFF -> "Noto Sans Tamil"; in 0x0C00..0x0C7F -> "Noto Sans Telugu"
        in 0x3040..0x30FF, in 0x4E00..0x9FFF, in 0xAC00..0xD7AF -> "Noto Sans CJK JP"; else -> null } ?: continue; counts[f] = (counts[f] ?: 0) + 1 }
    return counts.maxByOrNull { it.value }?.takeIf { it.value >= 20 }?.key
}

object NovaRuntime {
    lateinit var app: Context; private set
    lateinit var store: NovaStore; private set
    val engine get() = MpvClient.get()
    val state = MutableStateFlow(Playing())
    val notice = MutableStateFlow("")
    val snack = MutableStateFlow<Snack?>(null)
    val hud = MutableStateFlow<Hud?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var initialized = false
    private var loaded = false
    private var activePath = ""
    private var generation = 0
    private var requestedStart = 0.0
    private var loadTimer: Job? = null
    private var currentDescriptor: ParcelFileDescriptor? = null
    private val retired = mutableListOf<ParcelFileDescriptor>()
    private var lastPersisted = 0L
    var onChanged: (() -> Unit)? = null
    val diagnostics = ArrayDeque<String>()
    private val audio by lazy { app.getSystemService(AudioManager::class.java) }

    /** Sticky player preferences (desktop `prefs`): restored on every open while "Remember adjustments" is on. */
    val STICKY = listOf("speed", "volume", "mute", "sub-scale", "sub-pos", "sub-visibility")

    fun init(context: Context) {
        if (initialized) return
        app = context.applicationContext; store = NovaStore(app); initialized = true
        scope.launch { while (isActive) { delay(250); if (engine.ready && state.value.video != null) tick() } }
        engine.onEvent = { name, value -> scope.launch { event(name, value) } }
        // Desktop rescans 600 ms after start so new downloads just appear.
        scope.launch { delay(600); refreshLibrary() }
        // MX-style: a movie that finishes downloading while Nova is open shows up by itself.
        app.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, object : android.database.ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { mediaChanged?.cancel(); mediaChanged = scope.launch { delay(2000); refreshLibrary() } }
        })
    }
    private var mediaChanged: Job? = null
    private var lastRefresh = 0L
    fun hasVideoPermission(): Boolean {
        val p = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        return app.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && app.checkSelfPermission(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    /** Rescan everything Nova can see. Background calls are throttled and silent. */
    fun refreshLibrary(announce: Boolean = false) {
        if (!announce && System.currentTimeMillis() - lastRefresh < 4000) return
        lastRefresh = System.currentTimeMillis()
        store.scan(device = hasVideoPermission(), announce = announce)
    }
    fun pref(key: String, fallback: Boolean) = store.prefs.getBoolean(key, fallback)
    fun number(key: String, fallback: Double) = store.prefs.getString(key, fallback.toString())?.toDoubleOrNull() ?: fallback
    fun text(key: String, fallback: String = "") = store.prefs.getString(key, fallback) ?: fallback
    fun setNumber(key: String, value: Double) { store.prefs.edit().putString(key, value.toString()).apply() }
    fun setPref(key: String, value: Any) { store.prefs.edit().apply { when (value) { is Boolean -> putBoolean(key, value); is Int -> putInt(key, value); else -> putString(key, value.toString()) } }.apply() }
    private fun sticky(key: String, value: Double) { if (pref("rememberPlayerState", true)) setNumber("p:$key", value) }
    private fun stickyValue(key: String, fallback: Double) = if (pref("rememberPlayerState", true)) number("p:$key", fallback) else fallback
    fun clearSticky() { store.prefs.edit().apply { STICKY.forEach { remove("p:$it") } }.apply() }
    fun seekStep() = number("seekStep", 5.0)
    fun volumeMax() = number("volumeMax", 200.0)

    fun command(vararg args: String) {
        try { engine.command(*args) } catch (e: Exception) { notice.value = e.message ?: "Playback command failed" }
    }
    private fun ensureEngine() {
        if (engine.ready) return
        val certificates = File(app.filesDir, "cacert.pem")
        if (!certificates.exists()) app.assets.open("cacert.pem").use { input -> certificates.outputStream().use(input::copyTo) }
        val fonts = prepareFonts()
        engine.start(app, buildMpvOptions(volumeMax = volumeMax().toInt(), subBorderSize = number("subBorder", 2.0), hwdec = pref("hwdec", true),
            audioLang = text("audioLang"), subLang = text("subLang"), highQualityVideo = pref("highQuality", false)) +
            mapOf("tls-verify" to "yes", "tls-ca-file" to certificates.path, "force-seekable" to "yes",
                "sub-fonts-dir" to fonts.path, "osd-fonts-dir" to fonts.path, "sub-font" to "Roboto", "sub-font-provider" to "none"))
    }

    /**
     * libmpv on Android has no fontconfig: without a font directory libass falls back to a
     * glyph-poor face and even drops spaces. Copy a small curated set of the phone's own
     * fonts once (Latin + the scripts our subtitle languages need). CJK is 30 MB, so it is
     * only included when the user actually searches Chinese/Japanese/Korean subtitles.
     */
    private fun prepareFonts(): File {
        val dir = File(app.filesDir, "fonts").apply { mkdirs() }
        val cjk = text("subLangs", "eng").split(',').any { it in setOf("chi", "zht", "jpn", "kor") } || text("subLang").take(3) in setOf("chi", "jpn", "kor")
        val wanted = listOf("Roboto-Regular.ttf", "NotoSansBengali-VF.ttf", "NotoSansDevanagari-VF.ttf", "NotoNaskhArabic-Regular.ttf", "NotoSansThai-Regular.ttf",
            "NotoSansHebrew-Regular.ttf", "NotoSansTamil-VF.ttf", "NotoSansTelugu-VF.ttf") + (if (cjk) listOf("NotoSansCJK-Regular.ttc") else emptyList())
        for (name in wanted) runCatching {
            val out = File(dir, name); val src = File("/system/fonts", name)
            if (src.canRead() && (!out.exists() || out.length() != src.length())) src.inputStream().use { i -> out.outputStream().use(i::copyTo) }
        }
        if (!cjk) File(dir, "NotoSansCJK-Regular.ttc").delete()
        return dir
    }

    fun play(video: Video, queue: List<String> = listOf(video.uri), startOver: Boolean = false) {
        saveProgress()
        generation++
        SubtitleJobs.cancel()
        loadTimer?.cancel()
        val token = generation
        loaded = false
        val fresh = store.videos.value.find { it.uri == video.uri } ?: video
        // Desktop rule: resume only when ≥10 s in and not inside the final 15 s.
        requestedStart = if (!startOver && pref("rememberPosition", true) && fresh.position >= 10 && (fresh.duration <= 0 || fresh.position <= fresh.duration - 15)) fresh.position else 0.0
        if (startOver) store.resetProgress(video.uri)
        val old = state.value
        state.value = Playing(video = fresh, loading = true, queue = queue, queueIndex = queue.indexOf(video.uri).coerceAtLeast(0),
            speed = stickyValue("speed", number("defaultSpeed", 1.0)), volume = stickyValue("volume", 100.0).coerceAtMost(volumeMax()),
            muted = stickyValue("mute", 0.0) > 0, sleepAt = old.sleepAt, sleepEnd = old.sleepEnd, subVisible = stickyValue("sub-visibility", 1.0) > 0)
        val intent = Intent(app, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        scope.launch {
            var descriptor: ParcelFileDescriptor? = null
            try {
                if (!engine.ready) withTimeout(8000) { while (!engine.hasSurface()) delay(40) }
                ensureEngine()
                val uri = Uri.parse(video.uri)
                val path = if (uri.scheme == "content") {
                    withContext(Dispatchers.IO) { descriptor = app.contentResolver.openFileDescriptor(uri, "r") }
                    "fd://${descriptor?.fd ?: error("Access to this video was revoked. Open it again from Files.")}"
                } else if (uri.scheme == "file") uri.path!! else video.uri
                if (token != generation) { descriptor?.close(); return@launch }
                currentDescriptor?.let(retired::add); currentDescriptor = descriptor
                activePath = path
                val s = state.value
                engine.setFlag("pause", false)
                engine.setDouble("speed", s.speed); engine.setDouble("volume", s.volume); engine.setFlag("mute", s.muted)
                engine.load(path)
                loadTimer = scope.launch { delay(25_000); if (token == generation && !loaded) fail("Video did not finish opening. Check file access or try software decoding in Settings.") }
            } catch (e: Exception) { descriptor?.let { if (it !== currentDescriptor) runCatching { it.close() } }; if (token == generation) fail(e.message ?: "Cannot open video") }
            catch (e: LinkageError) { fail("The playback engine could not load: ${e.message}") }
        }
    }

    private fun event(name: String, value: String?) {
        if (name.startsWith("@")) { if (diagnostics.size > 100) diagnostics.removeFirst(); diagnostics.addLast("$name: $value") }
        if (name == "@load-error") { if (!loaded) fail(value ?: "Playback failed"); return }
        if (name != "@event") return
        when (value) {
            "file-loaded" -> {
                if (engine.getStr("path") != activePath) return
                loaded = true; loadTimer?.cancel()
                retired.forEach { runCatching { it.close() } }; retired.clear()
                val video = state.value.video ?: return
                // Timing corrections and framing belong to this video, never the next queue item.
                for ((k, v) in mapOf("sub-delay" to 0.0, "audio-delay" to 0.0, "sub-speed" to 1.0, "video-zoom" to 0.0, "video-rotate" to 0.0, "video-pan-x" to 0.0, "video-pan-y" to 0.0)) engine.setDouble(k, v)
                engine.setStr("video-aspect-override", "-1"); engine.setStr("loop-file", "no"); engine.setStr("ab-loop-a", "no"); engine.setStr("ab-loop-b", "no")
                engine.setStr("panscan", "0")
                engine.setDouble("sub-scale", stickyValue("sub-scale", number("subScale", 1.0)))
                engine.setDouble("sub-pos", stickyValue("sub-pos", 90.0))
                engine.setFlag("sub-visibility", state.value.subVisible)
                engine.setDouble("sub-border-size", number("subBorder", 2.0))
                engine.setStr("hwdec", if (pref("hwdec", true)) "mediacodec,mediacodec-copy" else "no")
                // A fix made by an older, less accurate sync is not shown again: load the subtitle it
                // was made from and sync that afresh (the badge shows "Syncing subtitles…").
                var resync = false
                if (SubtitleJobs.outdated(video.externalSub) && video.originalSub.isNotBlank() && File(video.originalSub).isFile) {
                    forgetFix(video.externalSub)
                    val updated = video.copy(externalSub = video.originalSub); store.update(updated); state.value = state.value.copy(video = updated); resync = true
                }
                val current = state.value.video ?: video
                if (current.externalSub.isNotBlank() && File(current.externalSub).isFile) { heal(File(current.externalSub)); runCatching { engine.command("sub-add", current.externalSub, "select") } }
                fitSubFont()
                if (requestedStart > 0) {
                    command("seek", requestedStart.toString(), "absolute+exact")
                    val at = requestedStart
                    snack.value = Snack("Resumed from ${com.sadik.novaplayer.ui.timeLabel(at)}", "Start over", run = { seek(0.0) })
                }
                state.value = state.value.copy(loading = false, ended = false, tracks = tracks(), chapters = chapters(), duration = engine.getDouble("duration"),
                    videoW = engine.getLong("video-params/w").toInt(), videoH = engine.getLong("video-params/h").toInt(), abStart = -1.0, abEnd = -1.0, loop = false)
                store.update(video.copy(played = System.currentTimeMillis()))
                val token = generation
                scope.launch {
                    delay(1800) // desktop: give embedded tracks time to appear before deciding a video "has no subtitles"
                    if (token != generation) return@launch
                    state.value = state.value.copy(tracks = tracks()); fitSubFont()
                    if (resync) SubtitleJobs.start(text("subSyncMode", "smart"))
                    // Videos whose subtitles were removed stay without until the user picks one.
                    else if (pref("autoSubs", true) && pref("onlineSubs", true) && state.value.tracks.none { it.type == "sub" } && !video.uri.startsWith("http") && !optedOut(video.uri)) {
                        notice.value = "Looking for subtitles…"
                        runCatching {
                            val results = withContext(Dispatchers.IO) { OnlineSubtitles.search(video, "", text("subLangs", "eng").split(',')) }
                            // Try the best few: one broken upload must not leave the video without subtitles.
                            var added: SubtitleResult? = null
                            // The user may load their own subtitle while this runs — theirs always wins.
                            fun userLoaded() = tracks().any { it.type == "sub" }
                            for (result in results.take(4)) {
                                if (token != generation || userLoaded()) return@runCatching
                                val file = runCatching { withContext(Dispatchers.IO) { OnlineSubtitles.download(video, result) } }.getOrNull() ?: continue
                                if (token != generation || userLoaded()) return@runCatching
                                if (attachSubtitle(file, original = true)) { added = result; break }
                            }
                            if (token != generation) return@runCatching
                            notice.value = when {
                                added != null -> "Subtitles added · ${added.language}${if (added.exact) " · exact match" else ""}"
                                results.isEmpty() -> "No subtitles found online for this video"
                                else -> "Found subtitles, but none could be read. Try Subtitles › Find online."
                            }
                            if (added != null && pref("subSyncDownloads", false) && !added.exact) SubtitleJobs.start(text("subSyncMode", "smart"))
                        }.onFailure { if (token == generation) notice.value = "Couldn't reach the subtitle service: ${it.message}" }
                    } else if (video.externalSub.isNotBlank() && pref("subSyncLocal", false)) SubtitleJobs.start(text("subSyncMode", "smart"))
                }
                engine.setFlag("pause", false)
                onChanged?.invoke()
            }
            "playback-restart", "tracks-changed", "video-reconfig" -> if (loaded) { if (value == "tracks-changed") fitSubFont(); state.value = state.value.copy(tracks = tracks(), decoder = decoder(),
                videoW = engine.getLong("video-params/w").toInt().takeIf { it > 0 } ?: state.value.videoW, videoH = engine.getLong("video-params/h").toInt().takeIf { it > 0 } ?: state.value.videoH) }
        }
    }
    private fun decoder() = engine.getStr("hwdec-current")?.takeIf { it.isNotBlank() && it != "no" }?.let { "Hardware · $it" } ?: "Software"
    private fun friendly(message: String): String = when {
        Regex("(?i)FileNotFound|No item at|ENOENT|No such file").containsMatchIn(message) -> "This video was moved or deleted, or Nova no longer has permission to open it."
        Regex("(?i)SecurityException|Permission Denial|EACCES").containsMatchIn(message) -> "Nova lost permission to this file. Open it again from Files or re-add its folder."
        Regex("(?i)UnknownHost|timed out|ECONNREFUSED|Network").containsMatchIn(message) -> "Couldn't reach the stream. Check your connection and the address."
        else -> message.replace(Regex("""^([a-z]+\.)+[A-Za-z]*(Exception|Error):\s*"""), "")
    }
    private fun fail(raw: String) {
        val message = friendly(raw)
        // A file opened from outside that turns out not to exist should not linger in the library.
        state.value.video?.let { v -> if (message.startsWith("This video was moved") && v.played == 0L && v.position == 0.0 && v.folder == "Opened videos") store.remove(v.uri) }
        if (engine.ready) engine.setFlag("pause", true); loaded = false; loadTimer?.cancel(); state.value = state.value.copy(loading = false, paused = true, error = message); onChanged?.invoke() }
    private fun tick() {
        if (!loaded) return
        val s = state.value
        val position = engine.getDouble("time-pos")
        val paused = engine.getFlag("pause")
        state.value = s.copy(position = position, duration = engine.getDouble("duration").takeIf { it > 0 } ?: s.duration, paused = paused,
            buffering = engine.getFlag("paused-for-cache") && !paused, decoder = decoder(), cached = engine.getDouble("demuxer-cache-time"))
        if (System.currentTimeMillis() - lastPersisted > 3000) { saveProgress(); lastPersisted = System.currentTimeMillis(); onChanged?.invoke() }
        if (s.sleepAt > 0 && System.currentTimeMillis() >= s.sleepAt) { pause(true); state.value = state.value.copy(sleepAt = 0); notice.value = "Sleep timer finished · sweet dreams" }
        if (engine.getFlag("eof-reached") && !s.ended && !s.loop) {
            if (BuildConfig.DEBUG) android.util.Log.d("NovaEof", "eof at pos=$position dur=${engine.getDouble("duration")} statePos=${s.position}")
            if (s.sleepEnd) { pause(true); state.value = state.value.copy(sleepEnd = false); notice.value = "Stopped at the end of the video" }
            else if (s.queueIndex + 1 < s.queue.size) { markFinished(); next(1) }
            else { markFinished(); state.value = state.value.copy(ended = true, paused = true); onChanged?.invoke() }
        }
    }
    private fun markFinished() { state.value.video?.let { v -> val d = engine.getDouble("duration"); if (d > 0) store.progress(v.uri, d, d, "auto", "auto") } }
    fun saveProgress() {
        if (!loaded) return
        val v = state.value.video ?: return
        val pos = engine.getDouble("time-pos"); val d = engine.getDouble("duration")
        if (pos < 1 && requestedStart > 0 && state.value.position < 1) return // resume seek still pending: don't clobber it with 0
        store.progress(v.uri, pos.coerceAtMost(if (d > 0) d else pos), d, "auto", "auto")
    }
    fun pause(value: Boolean = !state.value.paused) {
        if (!value && state.value.ended) { replay(); return }
        engine.setFlag("pause", value); state.value = state.value.copy(paused = value); saveProgress(); onChanged?.invoke()
    }
    fun replay() { state.value = state.value.copy(ended = false); seek(0.0); engine.setFlag("pause", false); state.value = state.value.copy(paused = false); onChanged?.invoke() }
    fun seek(value: Double, relative: Boolean = false, fast: Boolean = false) {
        if (BuildConfig.DEBUG) android.util.Log.d("NovaSeek", "seek $value rel=$relative fast=$fast")
        command("seek", value.toString(), if (relative) "relative+exact" else if (fast) "absolute+keyframes" else "absolute+exact")
        if (!relative && value == 0.0) state.value.video?.let { store.resetProgress(it.uri) }
        if (state.value.ended && (relative && value < 0 || !relative && value < state.value.duration - 1)) state.value = state.value.copy(ended = false)
    }
    fun next(delta: Int) { val s = state.value; val index = s.queueIndex + delta; if (index in s.queue.indices) store.videos.value.find { it.uri == s.queue[index] }?.let { play(it, s.queue, startOver = true) } }
    fun playIndex(index: Int) { val s = state.value; if (index in s.queue.indices) store.videos.value.find { it.uri == s.queue[index] }?.let { play(it, s.queue) } }
    fun speed(value: Double) { val v = (Math.round(value.coerceIn(0.25, 4.0) * 100) / 100.0); engine.setDouble("speed", v); state.value = state.value.copy(speed = v); sticky("speed", v) }
    fun mute(value: Boolean = !state.value.muted) { engine.setFlag("mute", value); state.value = state.value.copy(muted = value); sticky("mute", if (value) 1.0 else 0.0) }
    fun setVolume(value: Double) { val v = value.coerceIn(0.0, volumeMax()); engine.setDouble("volume", v); state.value = state.value.copy(volume = v, muted = false); engine.setFlag("mute", false); sticky("volume", v) }

    /**
     * MX-style unified volume: 0–100 walks the phone's media volume (so the hardware
     * level is never stranded low), anything above 100 is mpv's software boost.
     */
    fun level(): Double {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val sys = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max
        val v = state.value.volume
        return if (sys >= 99.9 && v > 100) v else sys * (v.coerceAtMost(100.0) / 100.0)
    }
    fun setLevel(target: Double, show: Boolean = true): Double {
        val t = target.coerceIn(0.0, volumeMax())
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        try {
            if (t <= 100) {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(t / 100 * max).toInt(), 0)
                if (state.value.volume != 100.0) setVolume(100.0)
            } else {
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0)
                setVolume(t)
            }
        } catch (_: Exception) { setVolume(t) } // e.g. Do Not Disturb blocks stream changes: fall back to mpv alone
        if (state.value.muted) mute(false)
        if (show) hud.value = Hud("volume", t, volumeMax(), if (t > 100) "Boost ${t.toInt()}%" else "Volume ${t.toInt()}%")
        return t
    }
    fun property(key: String, value: Double, remember: Boolean = false) { engine.setDouble(key, value); if (remember) sticky(key, value) }
    fun flag(key: String, value: Boolean) { engine.setFlag(key, value); if (key == "sub-visibility") { state.value = state.value.copy(subVisible = value); sticky(key, if (value) 1.0 else 0.0) } }
    fun toggleLoop() { val v = !state.value.loop; engine.setStr("loop-file", if (v) "inf" else "no"); state.value = state.value.copy(loop = v, ended = false) }
    fun tracks(): List<Track> = (0 until engine.getLong("track-list/count").toInt()).map { i ->
        val base = "track-list/$i"
        val type = engine.getStr("$base/type") ?: ""
        val lang = engine.getStr("$base/lang")?.uppercase()
        val title = engine.getStr("$base/title")
        val codec = engine.getStr("$base/codec")?.let { CODECS[it] ?: it.uppercase() }
        val detail = listOfNotNull(codec,
            engine.getLong("$base/demux-channel-count").takeIf { type == "audio" && it > 0 }?.let { when (it) { 1L -> "Mono"; 2L -> "Stereo"; 6L -> "5.1"; 8L -> "7.1"; else -> "$it ch" } },
            engine.getLong("$base/demux-h").takeIf { type == "video" && it > 0 }?.let { "${it}p" },
            engine.getStr("$base/external-filename")?.let { if (SubtitleJobs.isFix(it)) "Synced to the dialogue" else "Added" }, "Default".takeIf { engine.getFlag("$base/default") }, "Forced".takeIf { engine.getFlag("$base/forced") }).joinToString(" · ")
        Track(engine.getStr("$base/id") ?: "$i", type, listOfNotNull(title, lang).joinToString(" · ").ifBlank { "Track ${engine.getStr("$base/id") ?: i}" }, detail,
            engine.getFlag("$base/selected"), engine.getFlag("$base/external"), engine.getStr("$base/external-filename").orEmpty())
    }
    fun chapters() = (0 until engine.getLong("chapter-list/count").toInt()).map { Chapter(engine.getStr("chapter-list/$it/title") ?: "Chapter ${it + 1}", engine.getDouble("chapter-list/$it/time")) }
    fun selectTrack(type: String, id: String) { engine.setStr(if (type == "audio") "aid" else "sid", id); state.value = state.value.copy(tracks = tracks()); if (type == "sub") fitSubFont() }

    /**
     * libass here has no font fallback: a Bengali line drawn with Roboto is all boxes. Choose the
     * subtitle font per track, from its language tag or by sniffing the script of an external file.
     */
    fun fitSubFont() {
        val n = engine.getLong("track-list/count").toInt()
        val i = (0 until n).firstOrNull { engine.getStr("track-list/$it/type") == "sub" && engine.getFlag("track-list/$it/selected") } ?: return
        val lang = engine.getStr("track-list/$i/lang")?.lowercase().orEmpty()
        val file = engine.getStr("track-list/$i/external-filename")
        val script = SCRIPT_BY_LANG[lang.take(3)] ?: SCRIPT_BY_LANG[lang.take(2)] ?: file?.let { f ->
            runCatching { File(f).inputStream().use { String(it.readNBytes(65536), Charsets.UTF_8) } }.getOrNull()?.let(::dominantScript) }
        engine.setStr("sub-font", script ?: "Roboto")
    }
    /** Stored subtitles keep a readable name (mpv shows the file name as the track title);
     *  the folder carries the unique key so different sources never collide. */
    fun subtitleFile(key: String, name: String): File {
        val safe = name.replace(Regex("""[\\/:*?"<>|\x00-\x1f]"""), " ").trim().take(120).ifBlank { "subtitle.srt" }
        return File(app.filesDir, "subtitles/$key/$safe").apply { parentFile?.mkdirs() }
    }
    suspend fun importSubtitle(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = app.contentResolver.query(uri, arrayOf("_display_name"), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "subtitle.srt"
        val ext = name.substringAfterLast('.').lowercase(); require(ext in setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup")) { "Choose a subtitle file (SRT, ASS, SSA, VTT, SUB, SUP)" }
        val file = subtitleFile(stableId(uri.toString()), name)
        val bytes = app.contentResolver.openInputStream(uri)?.use { it.readBytesLimited(8 * 1024 * 1024) } ?: error("Cannot read subtitle")
        file.writeBytes(if (ext == "srt") healSrtBytes(bytes) ?: bytes else bytes); file
    }
    /** Repairs a stored SRT in place (see [healSrtBytes]); harmless for every other format. */
    private fun heal(file: File) { if (file.extension.equals("srt", true)) runCatching { healSrtBytes(file.readBytes())?.let(file::writeBytes) } }

    /**
     * Loads [file] into the playing video and returns whether the engine really took it.
     * mpv can refuse a file (bad timing lines, wrong format) while the command itself looks
     * fine, so success is judged by the track list — never announce a subtitle we can't show.
     */
    fun attachSubtitle(file: File, original: Boolean): Boolean {
        val video = state.value.video ?: return false
        if (!engine.ready || !file.isFile) return false
        heal(file)
        runCatching { engine.command("sub-add", file.path, "select") }
        val n = engine.getLong("track-list/count").toInt()
        val ok = (0 until n).any { engine.getStr("track-list/$it/type") == "sub" && engine.getStr("track-list/$it/external-filename") == file.path }
        if (!ok) { state.value = state.value.copy(tracks = tracks()); return false }
        engine.setFlag("sub-visibility", true); state.value = state.value.copy(subVisible = true)
        if (original) setOptOut(video.uri, false)
        val updated = (store.videos.value.find { it.uri == video.uri } ?: video).copy(externalSub = file.path, originalSub = if (original) file.path else video.originalSub)
        store.update(updated); state.value = state.value.copy(video = updated, tracks = tracks()); fitSubFont()
        return true
    }
    private fun optedOut(uri: String) = uri in store.prefs.getStringSet("subOptOut", emptySet())!!
    private fun setOptOut(uri: String, on: Boolean) {
        val set = store.prefs.getStringSet("subOptOut", emptySet())!!.toMutableSet()
        if (if (on) set.add(uri) else set.remove(uri)) store.prefs.edit().putStringSet("subOptOut", set).apply()
    }
    /** Only Nova's own copies (downloads, opened files, fixes) are ever deleted — never files elsewhere. */
    private fun ownSubtitle(path: String) = path.isNotBlank() && runCatching { File(path).canonicalPath.startsWith(File(app.filesDir, "subtitles").canonicalPath + File.separator) }.getOrDefault(false)
    private fun deleteOwn(path: String) {
        if (!ownSubtitle(path)) return
        val file = File(path)
        for (f in listOf(file, File("$path.approved"), File("$path.version"))) f.delete()
        val dir = file.parentFile ?: return
        if (dir.name.startsWith("sync-")) File(dir, "source.txt").delete()
        if (dir.list()?.isEmpty() == true) dir.delete()
    }
    private fun forgetFix(path: String) { if (SubtitleJobs.isFix(path)) deleteOwn(path) }
    /** Fixes made from [source] (or, with [video], every fix made for that video). */
    private fun fixesFor(source: String? = null, video: String? = null): List<String> =
        File(app.filesDir, "subtitles").listFiles { f -> f.isDirectory && f.name.startsWith("sync-") }.orEmpty().flatMap { dir ->
            val (from, forVideo) = runCatching { File(dir, "source.txt").readLines() }.getOrNull()?.let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() } ?: return@flatMap emptyList()
            if ((source != null && from == source) || (video != null && forVideo == video)) dir.listFiles { f -> f.extension.lowercase() in setOf("srt", "ass", "ssa") }.orEmpty().map { it.path } else emptyList()
        }
    private fun externalTracks() = (0 until engine.getLong("track-list/count").toInt()).mapNotNull { i ->
        if (engine.getStr("track-list/$i/type") != "sub") null else engine.getStr("track-list/$i/external-filename")?.let { engine.getStr("track-list/$i/id").orEmpty() to it }
    }

    /**
     * Desktop "remove this subtitle". A sync fix just goes away and the original timing comes
     * back. A subtitle Nova downloaded or copied is deleted with every fix made from it; when
     * nothing is left, Nova stops fetching subtitles for this video until you pick one.
     */
    fun removeSubtitle(path: String) {
        val video = state.value.video ?: return
        if (SubtitleJobs.state.value.running) SubtitleJobs.cancel()
        if (SubtitleJobs.isFix(path)) {
            externalTracks().filter { it.second == path }.forEach { runCatching { engine.command("sub-remove", it.first) } }
            forgetFix(path)
            engine.setDouble("sub-delay", 0.0); engine.setDouble("sub-speed", 1.0)
            val original = video.originalSub
            val back = original.isNotBlank() && File(original).isFile && showOriginal(original)
            if (!back) { val updated = video.copy(externalSub = ""); store.update(updated); state.value = state.value.copy(video = updated) }
            SubtitleJobs.reset()
            state.value = state.value.copy(tracks = tracks())
            notice.value = if (back) "Sync fix removed. Showing the original timing." else "Sync fix removed."
            return
        }
        val gone = fixesFor(source = path) + path
        externalTracks().filter { it.second in gone }.forEach { runCatching { engine.command("sub-remove", it.first) } }
        gone.forEach(::deleteOwn)
        val left = externalTracks()
        val fresh = (store.videos.value.find { it.uri == video.uri } ?: video).let { v ->
            v.copy(externalSub = if (v.externalSub in gone) left.lastOrNull()?.second.orEmpty() else v.externalSub, originalSub = if (v.originalSub in gone) "" else v.originalSub)
        }
        store.update(fresh); state.value = state.value.copy(video = fresh, tracks = tracks())
        if (left.isEmpty()) { setOptOut(video.uri, true); SubtitleJobs.reset() }
        notice.value = if (ownSubtitle(path)) "Subtitle removed" else "Subtitle unloaded"
    }

    /** Desktop "Remove all added subtitles…": the video goes back to exactly how it was. */
    fun removeAllSubtitles() {
        val video = state.value.video ?: return
        SubtitleJobs.reset()
        val stored = store.videos.value.find { it.uri == video.uri } ?: video
        val files = (externalTracks().map { it.second } + listOf(stored.externalSub, stored.originalSub) + fixesFor(video = video.uri))
            .filter { it.isNotBlank() }.toSet()
        externalTracks().forEach { runCatching { engine.command("sub-remove", it.first) } }
        files.forEach(::deleteOwn)
        // Downloads for this video that were never loaded (e.g. an earlier pick).
        File(app.filesDir, "subtitles").listFiles { f -> f.isDirectory && f.name.startsWith(stableId(video.uri) + "-") }.orEmpty().forEach { it.deleteRecursively() }
        engine.setDouble("sub-delay", 0.0); engine.setDouble("sub-speed", 1.0)
        setOptOut(video.uri, true)
        val fresh = stored.copy(externalSub = "", originalSub = "")
        store.update(fresh); state.value = state.value.copy(video = fresh, tracks = tracks())
        notice.value = "Subtitles removed. This video is back to how it was."
    }
    /** Select [original] again (it is usually still loaded; adding it twice lists it twice). */
    private fun showOriginal(original: String): Boolean {
        val video = state.value.video ?: return false
        val loaded = externalTracks().firstOrNull { it.second == original } ?: return attachSubtitle(File(original), false)
        selectTrack("sub", loaded.first)
        val updated = (store.videos.value.find { it.uri == video.uri } ?: video).copy(externalSub = original)
        store.update(updated); state.value = state.value.copy(video = updated)
        return true
    }
    fun restoreSubtitle() {
        val path = state.value.video?.originalSub ?: return
        if (path.isBlank()) return
        engine.setDouble("sub-delay", 0.0); engine.setDouble("sub-speed", 1.0)
        notice.value = if (File(path).isFile && showOriginal(path)) "Original timing restored" else "The original subtitle file is missing"
    }
    fun sleep(minutes: Int) {
        state.value = state.value.copy(sleepAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L, sleepEnd = minutes == -1)
        notice.value = when (minutes) { 0 -> "Sleep timer off"; -1 -> "Stopping at the end of this video"; else -> "Sleeping in $minutes minutes" }
    }
    fun ab() {
        val s = state.value
        when {
            s.abStart < 0 -> { engine.setDouble("ab-loop-a", s.position); state.value = s.copy(abStart = s.position); notice.value = "Loop start A · ${com.sadik.novaplayer.ui.timeLabel(s.position)}" }
            s.abEnd < 0 -> { engine.setDouble("ab-loop-b", s.position); state.value = s.copy(abEnd = s.position); notice.value = "Looping A → B" }
            else -> { engine.setStr("ab-loop-a", "no"); engine.setStr("ab-loop-b", "no"); state.value = s.copy(abStart = -1.0, abEnd = -1.0); notice.value = "A–B loop cleared" }
        }
    }
    /** Desktop "Reset to default": clears transient + sticky adjustments, keeps A–B points. */
    fun reset() {
        speed(number("defaultSpeed", 1.0)); setVolume(100.0); mute(false)
        for ((key, value) in mapOf("sub-delay" to 0.0, "audio-delay" to 0.0, "sub-speed" to 1.0, "video-zoom" to 0.0, "video-rotate" to 0.0, "video-pan-x" to 0.0, "video-pan-y" to 0.0)) property(key, value)
        property("sub-scale", number("subScale", 1.0)); property("sub-pos", 90.0); flag("sub-visibility", true)
        engine.setStr("video-aspect-override", "-1"); engine.setStr("panscan", "0"); engine.setStr("vf", ""); engine.setStr("loop-file", "no")
        state.value = state.value.copy(loop = false); clearSticky(); notice.value = "Everything back to default"
    }
    fun screenshot() { scope.launch {
        try {
            val file = File(app.cacheDir, "Nova-${System.currentTimeMillis()}.png")
            engine.command("screenshot-to-file", file.path, "subtitles")
            withContext(Dispatchers.IO) {
                repeat(30) { if (!file.exists() || file.length() == 0L) Thread.sleep(50) }
                require(file.exists()) { "Frame is not available yet" }
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, file.name); put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Nova Player"); put(MediaStore.Images.Media.IS_PENDING, 1) }
                    val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Cannot save screenshot")
                    app.contentResolver.openOutputStream(uri)!!.use { output -> file.inputStream().use { it.copyTo(output) } }
                    app.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                } else { File(app.getExternalFilesDir("Pictures"), file.name).writeBytes(file.readBytes()) }
                file.delete()
            }; notice.value = "Screenshot saved to Pictures › Nova Player"
        } catch (e: Exception) { notice.value = "Screenshot failed: ${e.message}" }
    } }
    fun close() { saveProgress(); generation++; loaded = false; loadTimer?.cancel(); SubtitleJobs.cancel(); if (engine.ready) command("stop"); state.value = Playing(); app.stopService(Intent(app, PlaybackService::class.java)) }
    fun destroyEngine() { engine.stop(); currentDescriptor?.close(); currentDescriptor = null; retired.forEach { runCatching { it.close() } }; retired.clear() }
}
fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray { val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192); while (true) { val n = read(buffer); if (n < 0) break; require(out.size() + n <= limit) { "File is too large" }; out.write(buffer, 0, n) }; return out.toByteArray() }
