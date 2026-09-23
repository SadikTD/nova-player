package com.sadik.novaplayer.core

import android.content.Context
import android.view.Surface

/**
 * Thin JNI binding to libmpv.
 *
 * Structural debt to mpv-android's MPVLib.kt (MIT) — see THIRD-PARTY-NOTICES.md.
 * `onEvent` is the single entry point the native event thread calls; it fans out
 * to registered [MpvObserver]s on the JVM side.
 */
object MpvNative {
    init {
        System.loadLibrary("novamplayer")
        System.loadLibrary("mpv")
    }

    external fun extractAudio(path: String, aid: String, output: String): Int
    external fun detectSpeech(path: String): DoubleArray
    external fun cancelAnalysis()
    external fun create(appctx: Context)
    external fun initialize(callback: Class<*>): Int
    external fun destroy()

    external fun attachSurface(surface: Surface)
    external fun detachSurface()

    external fun setOptionString(name: String, value: String): Int
    external fun command(args: Array<String>): Int

    external fun getPropertyLong(name: String): Long
    external fun getPropertyDouble(name: String): Double
    external fun getPropertyFlag(name: String): Boolean
    external fun getPropertyString(name: String): String?

    external fun setPropertyLong(name: String, value: Long)
    external fun setPropertyDouble(name: String, value: Double)
    external fun setPropertyFlag(name: String, value: Boolean)
    external fun setPropertyString(name: String, value: String)

    external fun observeProperty(name: String, format: Int)

    private val observers = mutableListOf<MpvObserver>()

    @JvmStatic
    fun addObserver(o: MpvObserver) {
        synchronized(observers) { if (o !in observers) observers.add(o) }
    }

    @JvmStatic
    fun removeObserver(o: MpvObserver) {
        synchronized(observers) { observers.remove(o) }
    }

    /**
     * Called from the native event thread with **stringified** values (flags as
     * "0"/"1", numbers as decimal). [name] is a property name, or the literal
     * `"@event"` with [value] holding `mpv_event_name(...)`. A null [value]
     * means the property is unavailable.
     */
    @JvmStatic
    fun onEvent(name: String, value: String?) {
        val list = synchronized(observers) { observers.toList() }
        for (o in list) o.onMpvEvent(name, value)
    }
}

/** mpv_format values used with [MpvNative.observeProperty]. */
object MpvFormat {
    const val NONE = 0
    const val STRING = 1
    const val OSD_STRING = 2
    const val FLAG = 3
    const val INT64 = 4
    const val DOUBLE = 5
    const val NODE = 6
    const val NODE_ARRAY = 7
    const val NODE_MAP = 8
    const val BYTE_ARRAY = 9
}

interface MpvObserver {
    fun onMpvEvent(name: String, value: String?)
}

