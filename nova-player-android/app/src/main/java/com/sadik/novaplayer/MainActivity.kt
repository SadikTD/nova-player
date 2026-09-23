package com.sadik.novaplayer

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.sadik.novaplayer.ui.*
import kotlinx.coroutines.*
import java.io.File

class MainActivity : ComponentActivity() {
    var playerVisible by mutableStateOf(false)
    var inPip by mutableStateOf(false)
    var revision by mutableIntStateOf(0)
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.keys.all { it == Manifest.permission.POST_NOTIFICATIONS }) return@registerForActivityResult
        if (granted.values.any { it }) NovaRuntime.refreshLibrary(announce = true) else NovaRuntime.notice.value = "No problem — pick folders or single videos instead."
    }
    private val videosPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { val items = uris.map { uri -> persist(uri); NovaRuntime.store.import(uri) }; play(items.first(), items.map { it.uri }) }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) { persist(uri); NovaRuntime.store.addRoot(uri) } }
    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) NovaRuntime.scope.launch {
        runCatching { NovaRuntime.importSubtitle(uri) }.onSuccess { NovaRuntime.notice.value = if (NovaRuntime.attachSubtitle(it, true)) "Subtitle loaded" else "That subtitle file couldn't be read" }.onFailure { NovaRuntime.notice.value = it.message ?: "Cannot read subtitle" }
    } }
    private val referencePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) NovaRuntime.scope.launch {
        runCatching { NovaRuntime.importSubtitle(uri) }.onSuccess { SubtitleJobs.start(NovaRuntime.text("subSyncMode", "smart"), it, manual = true) }.onFailure { NovaRuntime.notice.value = it.message ?: "Cannot read reference" }
    } }
    private var exportFile: File? = null
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = exportFile
        if (uri != null && file != null) NovaRuntime.scope.launch(Dispatchers.IO) { runCatching { contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } } }.onSuccess { NovaRuntime.notice.value = "Subtitle exported" }.onFailure { NovaRuntime.notice.value = "Export failed: ${it.message}" } }
    }
    /* ------------------------------------------------------------------ deleting files */

    private var pendingDelete: List<Video> = emptyList()
    private var resumeAfterDelete = false
    private val deleteConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val items = pendingDelete; pendingDelete = emptyList()
        // Android 11+ deletes as part of the consent; Android 10 only grants access, so finish the job here.
        if (r.resultCode == RESULT_OK && Build.VERSION.SDK_INT < 30) NovaRuntime.scope.launch { withContext(Dispatchers.IO) { items.forEach { runCatching { contentResolver.delete(Uri.parse(it.uri), null, null) } } }; deleted(items) }
        else if (r.resultCode == RESULT_OK) deleted(items) else { NovaRuntime.notice.value = "Nothing was deleted"; if (resumeAfterDelete) NovaRuntime.pause(false) }
    }
    private fun isMedia(v: Video) = v.uri.startsWith("content://media/")
    /** Android shows its own "Allow Nova to delete…?" sheet for media files, so Nova only asks when Android won't. */
    fun systemConfirmsDelete(items: List<Video>) = Build.VERSION.SDK_INT >= 30 && items.all(::isMedia) && !(Build.VERSION.SDK_INT >= 31 && android.provider.MediaStore.canManageMedia(this))
    /** Entry point for every delete button: Nova's own confirmation only when Android won't show one. */
    var deleteAsk by mutableStateOf<List<Video>>(emptyList())
    fun askDelete(items: List<Video>) { if (items.isEmpty()) return; if (systemConfirmsDelete(items)) delete(items) else deleteAsk = items }
    fun canDelete(v: Video) = !v.uri.startsWith("http") && !v.uri.startsWith("rtsp") && v.uri.contains("://")
    /** Permanently deletes the files (after the caller has confirmed, or Android will). */
    fun delete(items: List<Video>) {
        val targets = items.filter(::canDelete).ifEmpty { NovaRuntime.notice.value = "Streams can't be deleted"; return }
        val s = NovaRuntime.state.value
        resumeAfterDelete = s.video?.uri in targets.map { it.uri } && !s.paused
        if (resumeAfterDelete) NovaRuntime.pause(true)
        pendingDelete = targets
        if (Build.VERSION.SDK_INT >= 30 && targets.all(::isMedia)) {
            val request = android.provider.MediaStore.createDeleteRequest(contentResolver, targets.map { Uri.parse(it.uri) })
            deleteConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(request.intentSender).build()); return
        }
        NovaRuntime.scope.launch {
            val failed = mutableListOf<Video>()
            val done = withContext(Dispatchers.IO) { targets.filter { v ->
                val uri = Uri.parse(v.uri)
                runCatching {
                    when {
                        uri.scheme == "file" -> File(uri.path!!).delete()
                        isMedia(v) -> contentResolver.delete(uri, null, null) > 0
                        else -> android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
                    }
                }.recoverCatching { e ->
                    // Android 10: the system can still ask the user, one file at a time.
                    if (Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException && targets.size == 1) {
                        withContext(Dispatchers.Main) { deleteConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build()) }
                        return@withContext null
                    }
                    false
                }.getOrDefault(false).also { if (!it) failed += v }
            } } ?: return@launch
            pendingDelete = emptyList()
            if (done.isNotEmpty()) deleted(done)
            if (failed.isNotEmpty()) NovaRuntime.snack.value = Snack(if (failed.size == 1) "Couldn't delete “${prettyTitle(failed[0].title)}” — Android didn't allow it" else "${failed.size} files couldn't be deleted",
                "Remove from library", run = { failed.forEach { NovaRuntime.store.remove(it.uri) } })
            else if (done.isEmpty() && resumeAfterDelete) NovaRuntime.pause(false)
        }
    }
    private fun deleted(items: List<Video>) {
        val gone = items.mapTo(HashSet()) { it.uri }
        val s = NovaRuntime.state.value
        if (s.video?.uri in gone) {
            // Deleting what's on screen: carry on with the next episode, or step back to the library.
            val next = s.queue.drop(s.queueIndex + 1).firstOrNull { it !in gone }?.let { u -> NovaRuntime.store.videos.value.find { it.uri == u } }
            if (next != null && playerVisible) NovaRuntime.play(next, s.queue.filterNot { it in gone }, startOver = false)
            else { NovaRuntime.close(); if (playerVisible) library(pause = false) }
        }
        NovaRuntime.store.forgetDeleted(items)
        val freed = sizeLabel(items.sumOf { it.size })
        NovaRuntime.notice.value = (if (items.size == 1) "Deleted “${prettyTitle(items[0].title)}”" else "Deleted ${items.size} videos") + if (freed.isNotBlank()) " · $freed freed" else ""
    }

    /* ------------------------------------------------------------------ share + rename */

    fun share(items: List<Video>) {
        val files = items.filter(::canDelete).map { Uri.parse(it.uri) }
        val links = items.filterNot(::canDelete).map { it.uri }
        val send = when {
            files.size == 1 -> Intent(Intent.ACTION_SEND).setType("video/*").putExtra(Intent.EXTRA_STREAM, files[0])
            files.size > 1 -> Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files))
            links.isNotEmpty() -> Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, links.joinToString("\n"))
            else -> return
        }
        if (files.isNotEmpty()) {
            // Grant the receiving app read access to every file, not just the first.
            send.clipData = android.content.ClipData.newUri(contentResolver, "Videos", files[0]).apply { files.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, if (items.size == 1) "Share “${prettyTitle(items[0].title)}”" else "Share ${items.size} videos")) }
            .onFailure { NovaRuntime.notice.value = "No app can receive this" }
    }

    private var pendingRename: Pair<Video, String>? = null
    private val writeConsent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val p = pendingRename; pendingRename = null
        if (r.resultCode == RESULT_OK && p != null) rename(p.first, p.second, asked = true) else NovaRuntime.notice.value = "Name unchanged"
    }
    /** Renames the file on disk, keeping its extension; history, resume point and playlists follow it. */
    fun rename(v: Video, requested: String, asked: Boolean = false) {
        val ext = v.title.substringAfterLast('.', "").takeIf { it.isNotBlank() && it.length <= 5 }
        val base = requested.trim().replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), " ").trim().removeSuffix(".$ext").trim()
        if (base.isBlank()) { NovaRuntime.notice.value = "Enter a name"; return }
        val name = if (ext != null) "$base.$ext" else base
        if (name == v.title) return
        val uri = Uri.parse(v.uri)
        NovaRuntime.scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching {
                when {
                    uri.scheme == "file" -> File(uri.path!!).let { f -> File(f.parentFile, name).let { to -> if (to.exists()) error("exists"); if (!f.renameTo(to)) error("failed"); Uri.fromFile(to).toString() } }
                    isMedia(v) -> { if (contentResolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name) }, null, null) <= 0) error("failed"); v.uri }
                    else -> android.provider.DocumentsContract.renameDocument(contentResolver, uri, name)?.toString() ?: error("failed")
                }
            } }
            result.onSuccess { newUri ->
                NovaRuntime.store.renamed(v.uri, newUri, name)
                NovaRuntime.state.value.video?.takeIf { it.uri == v.uri }?.let { NovaRuntime.state.value = NovaRuntime.state.value.copy(video = it.copy(uri = newUri, title = name)) }
                NovaRuntime.notice.value = "Renamed to “${prettyTitle(name)}”"
            }.onFailure { e ->
                // Files Nova didn't create need a one-time "Allow Nova to modify…?" from Android.
                if (!asked && isMedia(v) && Build.VERSION.SDK_INT >= 30 && e is SecurityException) {
                    pendingRename = v to requested
                    writeConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(android.provider.MediaStore.createWriteRequest(contentResolver, listOf(uri)).intentSender).build())
                } else if (!asked && Build.VERSION.SDK_INT >= 29 && e is android.app.RecoverableSecurityException) {
                    pendingRename = v to requested
                    writeConsent.launch(androidx.activity.result.IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                } else NovaRuntime.notice.value = if (e.message == "exists") "A file with that name already exists" else "Couldn't rename this file"
            }
        }
    }

    private fun persist(uri: Uri) { runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.recoverCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    fun scanDevice() {
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE
        if (NovaRuntime.hasVideoPermission()) NovaRuntime.refreshLibrary(announce = true) else permissions.launch(arrayOf(permission))
    }
    fun openVideos() = videosPicker.launch(arrayOf("video/*", "application/octet-stream", "application/x-matroska"))
    fun addFolder() = folderPicker.launch(null)
    fun subtitle() = subtitlePicker.launch(arrayOf("*/*"))
    fun syncReference() = referencePicker.launch(arrayOf("*/*"))
    fun exportSubtitle() { val path = NovaRuntime.state.value.video?.externalSub.orEmpty(); if (path.isBlank()) { NovaRuntime.notice.value = "Load an external subtitle first"; return }; exportFile = File(path); exportPicker.launch("Nova-subtitle.${File(path).extension}") }
    fun play(video: Video, queue: List<String> = listOf(video.uri), startOver: Boolean = false) {
        showPlayer()
        NovaRuntime.play(video, queue, startOver)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }
    fun showPlayer() { playerVisible = true; fitOrientation(NovaRuntime.state.value.videoW, NovaRuntime.state.value.videoH) }
    /** Landscape for wide videos, portrait for vertical ones — the phone follows the picture. */
    fun fitOrientation(w: Int, h: Int) {
        if (!playerVisible || !NovaRuntime.pref("landscape", true)) return
        requestedOrientation = if (w > 0 && h > w) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    /** Leaving the player (Back or the arrow) pauses; the mini-player keeps it one tap away. */
    fun library(pause: Boolean = true) {
        if (pause && NovaRuntime.state.value.video != null && !NovaRuntime.state.value.paused) NovaRuntime.pause(true)
        playerVisible = false; requestedOrientation = libraryOrientation()
        window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
    }
    /** Phones browse in portrait like YouTube/MX; tablets (≥600 dp) may rotate freely. Only the player turns sideways. */
    private fun libraryOrientation() = if (resources.configuration.smallestScreenWidthDp >= 600) ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    fun rotate() { requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE }

    private fun pipParams(): PictureInPictureParams? {
        if (Build.VERSION.SDK_INT < 26) return null
        val s = NovaRuntime.state.value
        val ratio = if (s.videoW > 0 && s.videoH > 0) Rational(s.videoW, s.videoH).let { r -> when { r.toFloat() > 2.39f -> Rational(239, 100); r.toFloat() < .42f -> Rational(42, 100); else -> r } } else Rational(16, 9)
        fun action(name: String, icon: Int, label: String) = RemoteAction(Icon.createWithResource(this, icon), label, label,
            PendingIntent.getService(this, name.hashCode(), Intent(this, PlaybackService::class.java).setAction(name), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        val actions = buildList {
            if (s.queue.size > 1) add(action("previous", android.R.drawable.ic_media_previous, "Previous"))
            add(if (s.paused) action("toggle", android.R.drawable.ic_media_play, "Play") else action("toggle", android.R.drawable.ic_media_pause, "Pause"))
            if (s.queue.size > 1) add(action("next", android.R.drawable.ic_media_next, "Next"))
        }
        return PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(actions).apply {
            if (Build.VERSION.SDK_INT >= 31) { setAutoEnterEnabled(playerVisible && s.video != null && !s.paused && NovaRuntime.pref("autoPip", true)); setSeamlessResizeEnabled(true) }
        }.build()
    }
    fun pip() {
        if (Build.VERSION.SDK_INT >= 26 && NovaRuntime.state.value.video != null) runCatching { enterPictureInPictureMode(pipParams()!!) }.onFailure { NovaRuntime.notice.value = "Picture-in-picture is turned off for Nova in Android settings" }
        else NovaRuntime.notice.value = "Picture-in-picture needs Android 8 or later"
    }
    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (Build.VERSION.SDK_INT in 26..30 && playerVisible && !NovaRuntime.state.value.paused && NovaRuntime.pref("autoPip", true)) pip() }
    override fun onPictureInPictureModeChanged(value: Boolean, config: Configuration) { super.onPictureInPictureModeChanged(value, config); inPip = value }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState); NovaRuntime.init(this)
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        volumeControlStream = AudioManager.STREAM_MUSIC
        if (!playerVisible) requestedOrientation = libraryOrientation()
        setContent {
            val videos by NovaRuntime.store.videos.collectAsState()
            val lists by NovaRuntime.store.playlists.collectAsState()
            val s by NovaRuntime.state.collectAsState()
            val storeNotice by NovaRuntime.store.message.collectAsState()
            revision
            val accent = Accents[NovaRuntime.store.prefs.getInt("accent", 0).coerceIn(0, Accents.lastIndex)]
            LaunchedEffect(storeNotice) { if (storeNotice.isNotBlank()) { NovaRuntime.notice.value = storeNotice; NovaRuntime.store.message.value = "" } }
            LaunchedEffect(s.videoW, s.videoH) { fitOrientation(s.videoW, s.videoH) }
            LaunchedEffect(s.paused, s.video?.uri, s.videoW, playerVisible) { if (Build.VERSION.SDK_INT >= 26) pipParams()?.let { runCatching { setPictureInPictureParams(it) } } }
            NovaTheme(accent) {
                val immersive = playerVisible && s.video != null
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        if (immersive) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
                    }
                    if (immersive && !s.paused) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                // Registered before the screens so their own handlers (open panel, lock, search) win.
                BackHandler(immersive && !inPip) { library() }
                val saved = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                Box(Modifier.fillMaxSize().background(Nova.Bg)) {
                    // The library is dropped while the video is up (no animations burning GPU under the
                    // picture) but its tabs, scroll and drill-down survive through the state holder.
                    if (!immersive) saved.SaveableStateProvider("library") { LibraryScreen(this@MainActivity, videos, lists, s) }
                    AnimatedVisibility(immersive, enter = fadeIn(tween(220)) + scaleIn(initialScale = .96f), exit = fadeOut(tween(180))) { PlayerScreen(this@MainActivity, s) }
                    if (!immersive) Toasts(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp))
                    if (deleteAsk.isNotEmpty()) DeleteDialog(deleteAsk, dismiss = { deleteAsk = emptyList() }) { val items = deleteAsk; deleteAsk = emptyList(); delete(items) }
                }
            }
        }
        handle(intent)
        // MX-style first run: ask once for video access so the library fills itself — no setup screen.
        if (!NovaRuntime.hasVideoPermission() && !NovaRuntime.pref("askedVideoAccess", false)) { NovaRuntime.setPref("askedVideoAccess", true); scanDevice() }
    }
    override fun onStart() { super.onStart(); NovaRuntime.refreshLibrary() } // pick up downloads made while Nova was away
    private fun handle(intent: Intent?) {
        val uri = intent?.data ?: (if (intent?.action == Intent.ACTION_SEND) (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)) else null) ?: return
        val name = runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: uri.lastPathSegment.orEmpty()
        if (name.substringAfterLast('.', "").lowercase() in setOf("srt", "ass", "ssa", "vtt", "sub")) {
            if (NovaRuntime.state.value.video == null) { NovaRuntime.notice.value = "Start a video first, then open the subtitle"; return }
            NovaRuntime.scope.launch { runCatching { NovaRuntime.importSubtitle(uri) }.onSuccess { NovaRuntime.notice.value = if (NovaRuntime.attachSubtitle(it, true)) "Subtitle loaded · $name" else "“$name” couldn't be read as a subtitle"; showPlayer() }
                .onFailure { NovaRuntime.notice.value = it.message ?: "Cannot read subtitle" } }
            return
        }
        persist(uri); val v = NovaRuntime.store.import(uri); play(v, NovaRuntime.store.folderQueue(v))
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); handle(intent) }
    override fun onStop() { NovaRuntime.saveProgress(); if (!inPip && !NovaRuntime.pref("backgroundPlayback", true) && NovaRuntime.state.value.video != null) NovaRuntime.pause(true); super.onStop() }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (BuildConfig.DEBUG) android.util.Log.d("NovaKey", "key $keyCode")
        if (!playerVisible || NovaRuntime.state.value.video == null) return super.onKeyDown(keyCode, event)
        val step = NovaRuntime.seekStep()
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                val am = getSystemService(AudioManager::class.java); val unit = 100.0 / am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val cur = NovaRuntime.level(); val up = keyCode == KeyEvent.KEYCODE_VOLUME_UP
                NovaRuntime.setLevel(when { up && cur >= 99.9 -> cur + 10; up -> cur + unit; cur > 100 -> (cur - 10).coerceAtLeast(100.0); else -> cur - unit })
            }
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER -> NovaRuntime.pause()
            KeyEvent.KEYCODE_DPAD_RIGHT -> NovaRuntime.seek(if (event.isShiftPressed) 60.0 else step, true)
            KeyEvent.KEYCODE_DPAD_LEFT -> NovaRuntime.seek(if (event.isShiftPressed) -60.0 else -step, true)
            KeyEvent.KEYCODE_DPAD_UP -> NovaRuntime.setLevel(NovaRuntime.level() + 5)
            KeyEvent.KEYCODE_DPAD_DOWN -> NovaRuntime.setLevel(NovaRuntime.level() - 5)
            KeyEvent.KEYCODE_LEFT_BRACKET -> NovaRuntime.speed(NovaRuntime.state.value.speed - .1)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> NovaRuntime.speed(NovaRuntime.state.value.speed + .1)
            KeyEvent.KEYCODE_DEL -> NovaRuntime.speed(1.0)
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_X -> NovaRuntime.property("sub-delay", NovaRuntime.engine.getDouble("sub-delay") + (if (keyCode == KeyEvent.KEYCODE_Z) -1 else 1) * (if (event.isShiftPressed) 1.0 else .1))
            KeyEvent.KEYCODE_L -> NovaRuntime.ab()
            KeyEvent.KEYCODE_V -> NovaRuntime.flag("sub-visibility", !NovaRuntime.state.value.subVisible)
            KeyEvent.KEYCODE_J -> { NovaRuntime.command("cycle", "sub"); NovaRuntime.fitSubFont() }
            KeyEvent.KEYCODE_POUND -> NovaRuntime.command("cycle", "audio")
            KeyEvent.KEYCODE_PERIOD -> NovaRuntime.command("frame-step")
            KeyEvent.KEYCODE_COMMA -> NovaRuntime.command("frame-back-step")
            KeyEvent.KEYCODE_S -> NovaRuntime.screenshot()
            KeyEvent.KEYCODE_M -> NovaRuntime.mute()
            KeyEvent.KEYCODE_T -> pip()
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_ENTER -> rotate()
            KeyEvent.KEYCODE_MEDIA_NEXT -> NovaRuntime.next(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> NovaRuntime.next(-1)
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_Q -> library()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }
}
