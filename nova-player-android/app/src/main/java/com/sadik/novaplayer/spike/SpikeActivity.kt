package com.sadik.novaplayer.spike

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.sadik.novaplayer.core.MpvClient
import com.sadik.novaplayer.core.buildMpvOptions

/** Device-test harness, not the production library/player interface. */
class SpikeActivity : ComponentActivity(), SurfaceHolder.Callback {
    private val engine get() = MpvClient.get()
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var surfaceReady = false
    private var pendingUri: Uri? = null
    private var activeUri: Uri? = null
    private var opening = false
    private var resumeOnReturn = false
    private val diagnostics = ArrayDeque<String>()
    // fd:// borrows these descriptors; close only after mpv has stopped using them.
    private val descriptors = mutableListOf<ParcelFileDescriptor>()
    private val loadTimeout = Runnable {
        if (opening && !isDestroyed) {
            opening = false
            status.text = "Video did not finish opening within 20 seconds.\n" +
                "Tap Copy diagnostics so the playback error can be checked."
            record("Opening timed out")
            runCatching { engine.command("stop") }
            activeUri = null
        }
    }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && engine.ready) {
            try {
                engine.command("sub-add", openDescriptor(uri), "select")
            } catch (e: Exception) { status.text = "Could not load subtitle: ${e.message}" }
        }
    }
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { /* Session-only provider. */ }
            open(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = "Nova Player • playback test 2\nChoose a video to test the Android engine."
            setPadding(24, 24, 24, 24)
            maxLines = 6
            setTextIsSelectable(true)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = "Open video"
            setOnClickListener { picker.launch(arrayOf("video/*", "application/octet-stream")) }
        })
        val surface = SurfaceView(this)
        root.addView(surface, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(Button(this).apply {
            text = "Play / pause"
            setOnClickListener {
                if (engine.ready) runCatching { engine.command("cycle", "pause") }
                    .onFailure { status.text = it.message }
            }
        })
        root.addView(Button(this).apply {
            text = "Load subtitle"
            setOnClickListener { if (engine.ready) subtitlePicker.launch(arrayOf("*/*")) }
        })
        root.addView(Button(this).apply {
            text = "Copy diagnostics"
            setOnClickListener {
                val report = "Nova Player playback test 2\n${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}\n" +
                    diagnostics.joinToString("\n")
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Nova playback diagnostics", report))
                Toast.makeText(this@SpikeActivity, "Diagnostics copied", Toast.LENGTH_SHORT).show()
            }
        })
        setContentView(root)
        engine.onEvent = { name, value ->
            runOnUiThread {
                if (!isDestroyed) handleEngineEvent(name, value)
            }
        }
        pendingUri = savedInstanceState?.getString("video")?.let(Uri::parse) ?: intent?.data
        surface.holder.addCallback(this)
    }

    private fun record(message: String) {
        if (diagnostics.size >= 60) diagnostics.removeFirst()
        diagnostics.addLast(message.trim().take(2000))
    }

    private fun handleEngineEvent(name: String, value: String?) {
        if (name.startsWith("@")) record("$name: $value")
        when {
            name == "@load-error" -> {
                opening = false
                activeUri = null
                handler.removeCallbacks(loadTimeout)
                status.text = "Could not play this video: $value\nTap Copy diagnostics for the detailed error."
            }
            name == "@event" && value == "file-loaded" -> {
                opening = false
                handler.removeCallbacks(loadTimeout)
                engine.setFlag("pause", false)
                showPlaybackStatus()
            }
            name == "@event" && value == "playback-restart" -> showPlaybackStatus()
            name == "@event" && value == "shutdown" -> {
                opening = false
                handler.removeCallbacks(loadTimeout)
                status.text = "The playback engine stopped. Tap Copy diagnostics."
            }
        }
    }

    private fun showPlaybackStatus() {
        val decoder = engine.getStr("hwdec-current") ?: "not reported"
        status.text = "${engine.getStr("media-title") ?: "Playing video"}\nDecoder: $decoder"
        record("Playback ready; decoder=$decoder; video=${engine.getStr("video-codec")}; audio=${engine.getStr("audio-codec")}")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let(::open)
    }

    private fun openDescriptor(uri: Uri): String {
        val descriptor = contentResolver.openFileDescriptor(uri, "r")
            ?: error("The file provider could not open this file")
        descriptors.add(descriptor)
        record("Provider descriptor opened; size=${descriptor.statSize}")
        return "fd://${descriptor.fd}"
    }

    private fun open(uri: Uri) {
        pendingUri = uri
        if (!surfaceReady) return
        handler.removeCallbacks(loadTimeout)
        try {
            if (!engine.ready) engine.start(applicationContext, buildMpvOptions())
            record("Engine ${engine.getStr("mpv-version")}; renderer=gpu/android; input=${uri.scheme}")
            val target = when (uri.scheme) {
                "content" -> openDescriptor(uri)
                "file", null -> uri.path ?: error("Missing video path")
                "https", "http" -> uri.toString()
                else -> error("This address type is not supported")
            }
            status.text = "Opening video…"
            opening = true
            activeUri = uri
            engine.setFlag("pause", false)
            engine.load(target)
            handler.postDelayed(loadTimeout, 20_000)
        } catch (e: Exception) {
            opening = false
            activeUri = null
            record("Open failed: ${e.message}")
            status.text = "Could not open video: ${e.message}"
        } catch (e: LinkageError) {
            opening = false
            activeUri = null
            record("Native library failure: ${e.message}")
            status.text = "The Android playback libraries are missing or incompatible."
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        engine.resizeSurface(holder.surfaceFrame.width(), holder.surfaceFrame.height())
        engine.attachSurface(holder.surface)
        // Reattaching after the file picker must not reload/reset an active video.
        pendingUri?.let { if (it != activeUri) open(it) }
        if (resumeOnReturn && engine.ready) {
            engine.setFlag("pause", false)
            resumeOnReturn = false
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine.resizeSurface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        engine.detachSurface()
    }

    override fun onResume() {
        super.onResume()
        if (surfaceReady && resumeOnReturn && engine.ready) {
            engine.setFlag("pause", false)
            resumeOnReturn = false
        }
    }

    override fun onStop() {
        if (engine.ready) {
            resumeOnReturn = !engine.getFlag("pause")
            engine.setFlag("pause", true)
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("video", pendingUri?.toString())
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        handler.removeCallbacks(loadTimeout)
        engine.onEvent = null
        engine.stop()
        descriptors.forEach { runCatching { it.close() } }
        descriptors.clear()
        super.onDestroy()
    }
}
