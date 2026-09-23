package com.sadik.novaplayer.core

import android.content.Context
import android.util.Log
import android.view.Surface

/**
 * Process-scoped libmpv engine.
 *
 * Deliberately a singleton held outside any Activity: rotation, backgrounding
 * and PiP must never re-create the engine mid-playback (the desktop's equivalent
 * is one long-lived mpv process per session). The desktop's 917-line JSON-IPC
 * bridge with its reconnect/watchdog machinery collapses to this class — we are
 * in-process now, so there is no pipe to lose.
 */
class MpvClient private constructor() : MpvObserver {

    private var surface: Surface? = null
    private val pendingOptions = mutableListOf<Pair<String, String>>()
    @Volatile var onEvent: ((name: String, value: String?) -> Unit)? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    @Volatile
    var ready = false
        private set

    companion object {
        private const val TAG = "MpvClient"

        // mpv_format
        private const val FMT_STRING = 1
        private const val FMT_FLAG = 3
        private const val FMT_INT64 = 4
        private const val FMT_DOUBLE = 5

        @Volatile
        private var instance: MpvClient? = null

        fun get(): MpvClient = instance ?: synchronized(this) {
            instance ?: MpvClient().also { instance = it }
        }
    }

    /**
     * Create + initialize the engine once per process. [options] are applied
     * before `mpv_initialize`, mirroring the desktop's `mpv.conf` + CLI args.
     */
    fun start(context: Context, options: Map<String, String>) {
        synchronized(this) {
            if (ready) return
            val appCtx = context.applicationContext
            MpvNative.create(appCtx)
            val all = options + synchronized(pendingOptions) {
                pendingOptions.toList().also { pendingOptions.clear() }
            }
            for ((k, v) in all) {
                val r = MpvNative.setOptionString(k, v)
                if (r < 0) Log.w(TAG, "set_option $k=$v failed (${r})")
            }
            MpvNative.addObserver(this)
            val result = MpvNative.initialize(MpvNative::class.java)
            if (result < 0) {
                MpvNative.removeObserver(this)
                MpvNative.destroy()
                error("Playback engine initialization failed ($result)")
            }
            ready = true
            surface?.let { attachSurface(it) }
            Log.i(TAG, "libmpv initialized with ${all.size} options")
        }
    }

    fun stop() {
        synchronized(this) {
            if (!ready) return
            MpvNative.removeObserver(this)
            MpvNative.destroy()
            ready = false
        }
    }

    /** Attach/detach the video surface. Safe to call from a SurfaceHolder.Callback. */
    fun attachSurface(s: Surface) {
        surface = s
        if (ready) {
            MpvNative.attachSurface(s)
            resizeSurface(surfaceWidth, surfaceHeight)
            setStr("vo", "gpu")
            setFlag("force-window", true)
        }
    }

    fun resizeSurface(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        if (ready && width > 0 && height > 0) setStr("android-surface-size", "${width}x$height")
    }

    fun hasSurface() = surface?.isValid == true
    fun detachSurface() {
        surface = null
        if (ready) {
            setStr("vo", "null")
            setFlag("force-window", false)
            MpvNative.detachSurface()
        }
    }

    fun command(vararg args: String) {
        check(ready) { "Playback engine is not ready" }
        val result = MpvNative.command(arrayOf(*args))
        check(result >= 0) { "Playback command ${args.firstOrNull()} failed ($result)" }
    }

    fun load(path: String, append: Boolean = false) =
        command("loadfile", path, if (append) "append" else "replace")

    fun setOption(name: String, value: String) {
        if (ready) MpvNative.setOptionString(name, value)
        else synchronized(pendingOptions) { pendingOptions.add(name to value) }
    }

    /* --------------------------------------------------------- properties */

    fun getStr(name: String): String? = if (ready) MpvNative.getPropertyString(name) else null
    fun getLong(name: String): Long = if (ready) MpvNative.getPropertyLong(name) else 0L
    fun getDouble(name: String): Double = if (ready) MpvNative.getPropertyDouble(name) else 0.0
    fun getFlag(name: String): Boolean = if (ready) MpvNative.getPropertyFlag(name) else false

    fun setStr(name: String, v: String) { if (ready) MpvNative.setPropertyString(name, v) }
    fun setLong(name: String, v: Long) { if (ready) MpvNative.setPropertyLong(name, v) }
    fun setDouble(name: String, v: Double) { if (ready) MpvNative.setPropertyDouble(name, v) }
    fun setFlag(name: String, v: Boolean) { if (ready) MpvNative.setPropertyFlag(name, v) }

    fun observe(name: String, format: Int) {
        if (ready) MpvNative.observeProperty(name, format)
    }

    fun observeAll(names: List<String>) {
        for (n in names) observe(n, FMT_STRING)
    }

    override fun onMpvEvent(name: String, value: String?) {
        onEvent?.invoke(name, value)
    }
}

/**
 * Startup options — the Android equivalent of the desktop's `mpv.conf` plus the
 * CLI overrides `MpvController.play` passes. Sourced from inventory B.
 *
 * Notes on what is deliberately different from the desktop:
 *  - `profile=high-quality` and `deband` are gated behind a setting (off by
 *    default): desktop GPU load cooks phones. See "Video quality" in Settings.
 *  - `sub-auto=fuzzy` is dropped: scoped storage hides foreign .srt files from
 *    directory listings, so fuzzy auto-load cannot work. We `sub-add` explicitly
 *    from the SubtitleAsset sidecar table instead.
 *  - `--no-resume-playback` / `save-position-on-quit=no`: Nova owns resume.
 *  - Windows-only options (osc/osd-bar/border/window-dragging/snap-window/
 *    autofit-larger) are gone. Screenshot goes to MediaStore, not ~~desktop/.
 */
fun buildMpvOptions(
    volumeMax: Int = 200,
    subBorderSize: Double = 2.0,
    hwdec: Boolean = true,
    audioLang: String? = null,
    subLang: String? = null,
    highQualityVideo: Boolean = false,
): Map<String, String> {
    val opts = mutableMapOf(
        "vo" to "gpu",
        "gpu-context" to "android",
        "opengl-es" to "yes",
        "ao" to "audiotrack,opensles",
        "idle" to "yes",
        "force-window" to "no",
        "interpolation" to "no",
        "video-sync" to "audio",
        "hwdec" to if (hwdec) "mediacodec,mediacodec-copy" else "no",
        "volume-max" to volumeMax.toString(),
        "sub-border-size" to subBorderSize.toString(),
        "audio-file-auto" to "fuzzy",
        "audio-pitch-correction" to "yes",
        "sub-font-size" to "44",
        "sub-color" to "#FFFFFF",
        "sub-border-color" to "#101010",
        "sub-shadow-offset" to "1",
        "sub-fix-timing" to "yes",
        "keep-open" to "yes",
        "prefetch-playlist" to "yes",
        "screenshot-format" to "png",
        "resume-playback" to "no",
        "save-position-on-quit" to "no",
    )
    if (highQualityVideo) {
        opts["profile"] = "high-quality"
        opts["deband"] = "yes"
    }
    if (!audioLang.isNullOrBlank()) opts["alang"] = audioLang
    if (!subLang.isNullOrBlank()) opts["slang"] = subLang
    return opts
}

