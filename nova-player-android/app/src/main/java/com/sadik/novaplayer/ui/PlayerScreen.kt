package com.sadik.novaplayer.ui

import android.content.res.Configuration
import android.provider.Settings
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import com.sadik.novaplayer.*
import kotlinx.coroutines.*
import kotlin.math.*

private enum class Drag { None, Seek, Bright, Volume, Hold, Pinch }
private data class Ripple(val side: Int, val seconds: Int, val at: Long)

@Composable fun PlayerScreen(activity: MainActivity, s: Playing) {
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf("") }
    var ripple by remember { mutableStateOf<Ripple?>(null) }
    var seekPreview by remember { mutableStateOf<Pair<Double, Double>?>(null) } // target, delta
    var boost by remember { mutableStateOf<Double?>(null) }
    var flash by remember { mutableStateOf(0L) }
    var lockHint by remember { mutableStateOf(0L) }
    val current by rememberUpdatedState(s)
    val scope = rememberCoroutineScope()
    val hud by NovaRuntime.hud.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val accent = LocalAccent.current

    // Desktop auto-hide: 2.6 s, suspended while paused, a panel is open or at the end screen.
    LaunchedEffect(controls, s.paused, panel, s.ended, s.position.toInt() / 30) { if (controls && !s.paused && panel.isBlank() && !s.ended) { delay(2600); controls = false } }
    LaunchedEffect(hud?.at) { if (hud != null) { delay(900); NovaRuntime.hud.value = null } }
    LaunchedEffect(ripple?.at) { if (ripple != null) { delay(700); ripple = null } }
    LaunchedEffect(s.video?.uri) { panel = ""; controls = true }
    LaunchedEffect(s.paused) { if (s.paused && !locked) controls = true } // pausing from a headset/keyboard/notification shows where you are
    // Lift subtitles above the control bar while it is showing, MX-style, without touching the user's sub-pos.
    LaunchedEffect(controls, locked, landscape) { NovaRuntime.engine.setLong("sub-margin-y", if (controls && !locked) (if (landscape) 150L else 90L) else 22L) }
    BackHandler(panel.isNotBlank() || locked) { if (panel.isNotBlank()) panel = "" else lockHint = System.nanoTime() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { context -> SurfaceView(context).apply { holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) { NovaRuntime.engine.attachSurface(h.surface) }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) { NovaRuntime.engine.resizeSurface(width, height) }
            override fun surfaceDestroyed(h: SurfaceHolder) { NovaRuntime.engine.detachSurface() }
        }) } }, modifier = Modifier.fillMaxSize())
        if (activity.inPip) return@Box

        /* ------------------------------------------------------------ gesture engine */
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().pointerInput(locked) {
            val slop = with(density) { 18.dp.toPx() }
            val holdMs = viewConfiguration.longPressTimeoutMillis
            var lastTap = 0L; var lastTapX = 0f; var single: Job? = null; var streakUntil = 0L
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (locked) { waitForUpOrCancellation(); lockHint = System.nanoTime(); return@awaitEachGesture }
                val w = size.width.toFloat(); val h = size.height.toFloat()
                var mode = Drag.None; var drag = Offset.Zero
                var startPos = current.position; var startLevel = 0.0; var startBright = .5f; var startZoom = 0.0; var pinch = 1f
                var lastSeekSent = 0L; var originalSpeed = current.speed
                val edge = down.position.y < h * .08f || down.position.y > h * .92f
                val hold = scope.launch { delay(holdMs); if (mode == Drag.None && !current.paused) {
                    mode = Drag.Hold; originalSpeed = current.speed; val b = min(4.0, max(2.0, current.speed * 2)); NovaRuntime.engine.setDouble("speed", b); boost = b } }
                while (true) {
                    val event = awaitPointerEvent()
                    val pressed = event.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2 && (mode == Drag.None || mode == Drag.Pinch)) {
                        if (mode == Drag.None) { mode = Drag.Pinch; hold.cancel(); startZoom = NovaRuntime.engine.getDouble("video-zoom") }
                        pinch *= event.calculateZoom()
                        val z = (startZoom + log2(pinch.toDouble())).coerceIn(-.5, 1.5)
                        NovaRuntime.property("video-zoom", z)
                        NovaRuntime.hud.value = Hud("zoom", z + .5, 2.0, "Zoom ${(2.0.pow(z) * 100).roundToInt()}%")
                        event.changes.forEach { it.consume() }; continue
                    }
                    if (mode == Drag.Hold || mode == Drag.Pinch) continue
                    val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                    drag += change.positionChange()
                    if (mode == Drag.None && drag.getDistance() > slop) {
                        hold.cancel()
                        mode = when {
                            abs(drag.x) > abs(drag.y) -> Drag.Seek.also { startPos = current.position }
                            edge -> Drag.None
                            down.position.x < w / 2 -> Drag.Bright.also {
                                startBright = activity.window.attributes.screenBrightness.takeIf { it >= 0 }
                                    ?: (Settings.System.getInt(activity.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f).coerceIn(.02f, 1f) }
                            else -> Drag.Volume.also { startLevel = NovaRuntime.level() }
                        }
                        if (mode == Drag.None) break
                    }
                    when (mode) {
                        Drag.Seek -> {
                            val span = current.duration.coerceIn(60.0, 600.0)
                            val target = (startPos + drag.x / w * span).coerceIn(0.0, current.duration.coerceAtLeast(0.0))
                            seekPreview = target to (target - startPos)
                            val now = System.currentTimeMillis()
                            if (now - lastSeekSent > 150) { lastSeekSent = now; NovaRuntime.seek(target, fast = true) }
                        }
                        Drag.Bright -> {
                            val b = (startBright - drag.y / h * 1.5f).coerceIn(.01f, 1f)
                            activity.window.attributes = activity.window.attributes.apply { screenBrightness = b }
                            NovaRuntime.hud.value = Hud("brightness", b.toDouble(), 1.0, "Brightness ${(b * 100).roundToInt()}%")
                        }
                        Drag.Volume -> NovaRuntime.setLevel((startLevel - drag.y / h * 150).roundToInt().toDouble())
                        else -> {}
                    }
                    if (mode != Drag.None) change.consume()
                }
                hold.cancel()
                when (mode) {
                    Drag.Hold -> { NovaRuntime.engine.setDouble("speed", originalSpeed); boost = null }
                    Drag.Seek -> { seekPreview?.let { NovaRuntime.seek(it.first) }; seekPreview = null }
                    Drag.Pinch -> { val z = NovaRuntime.engine.getDouble("video-zoom"); if (abs(z) < .06) NovaRuntime.property("video-zoom", 0.0) }
                    Drag.None -> if (drag.getDistance() <= slop) {
                        val x = down.position.x; val now = System.currentTimeMillis()
                        val side = when { x < w * .35f -> -1; x > w * .65f -> 1; else -> 0 }
                        val streak = side != 0 && now < streakUntil && ripple?.side == side
                        if (streak || (now - lastTap < 300 && abs(x - lastTapX) < w * .3f)) {
                            single?.cancel()
                            if (side == 0) { NovaRuntime.pause(); flash = System.nanoTime() }
                            else {
                                val step = (NovaRuntime.seekStep() * 2).toInt()
                                NovaRuntime.seek(step * side.toDouble(), true)
                                ripple = Ripple(side, (if (streak) ripple!!.seconds else 0) + step, System.nanoTime())
                                streakUntil = now + 650
                            }
                            lastTap = 0
                        } else {
                            lastTap = now; lastTapX = x
                            single = scope.launch { delay(260); controls = !controls; if (panel.isNotBlank()) panel = "" }
                        }
                    }
                    else -> {}
                }
            }
        })

        /* ------------------------------------------------------------ scrims + chrome */
        val scrim by animateFloatAsState(if (controls && !locked) 1f else 0f, tween(250), label = "scrim")
        Box(Modifier.fillMaxSize().alpha(scrim).background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .75f), .25f to Color.Transparent, .65f to Color.Transparent, 1f to Color.Black.copy(alpha = .85f))))

        val safe = Modifier.windowInsetsPadding(WindowInsets.displayCutout).padding(horizontal = if (landscape) 20.dp else 10.dp)
        AnimatedVisibility(controls && !locked, Modifier.align(Alignment.TopCenter), enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
            TopBar(s, safe, activity, openPanel = { panel = it })
        }
        AnimatedVisibility(controls && !locked && !s.ended && s.error.isBlank(), Modifier.align(Alignment.Center), enter = scaleIn(initialScale = .8f) + fadeIn(), exit = scaleOut(targetScale = .8f) + fadeOut()) {
            CenterControls(s, landscape)
        }
        AnimatedVisibility(controls && !locked, Modifier.align(Alignment.BottomCenter), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            BottomBar(s, safe.navigationBarsPadding(), activity, lock = { locked = true; controls = false; lockHint = System.nanoTime() }, openPanel = { panel = it })
        }

        /* ------------------------------------------------------------ overlays */
        PlayFlash(flash, s.paused, Modifier.align(Alignment.Center))
        ripple?.let { RippleOverlay(it) }
        AnimatedVisibility(boost != null, Modifier.align(Alignment.TopCenter).padding(top = 28.dp), enter = slideInVertically { -it } + fadeIn(), exit = fadeOut()) { BoostPill(boost ?: 2.0) }
        AnimatedVisibility(seekPreview != null, Modifier.align(Alignment.Center), enter = fadeIn(tween(90)), exit = fadeOut()) {
            seekPreview?.let { (t, d) ->
                Column(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 28.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${if (d >= 0) "+" else "−"}${timeLabel(abs(d))}", fontSize = 34.sp, fontWeight = FontWeight.Black, color = Color.White)
                    Text("${timeLabel(t)} / ${timeLabel(s.duration)}", color = Color.White.copy(alpha = .75f), fontSize = 14.sp)
                    chapterAt(s, t)?.let { Text(it, color = accent.light, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
        }
        hud?.let { HudView(it) }
        if ((s.loading || s.buffering) && s.error.isBlank()) Loading(s.loading, Modifier.align(Alignment.Center))
        if (s.error.isNotBlank()) ErrorCard(s, activity, Modifier.align(Alignment.Center))
        if (s.ended) EndCard(s, activity, Modifier.align(Alignment.Center))
        NextUp(s, controls, Modifier.align(Alignment.BottomEnd).then(safe).navigationBarsPadding().padding(bottom = if (controls) 170.dp else 24.dp))

        // Lock: controls disappear; a tap shows the unlock button for a few seconds.
        var showUnlock by remember { mutableStateOf(false) }
        LaunchedEffect(lockHint) { if (locked && lockHint != 0L) { showUnlock = true; delay(2500); showUnlock = false } }
        AnimatedVisibility(locked && showUnlock, Modifier.align(Alignment.CenterStart).then(safe).padding(start = 24.dp), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(accent.grad).bouncy { locked = false; controls = true }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.LockOpen, "Unlock", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Text("Tap to unlock", color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Toasts(Modifier.align(Alignment.TopCenter).padding(top = if (controls && !locked) 76.dp else 20.dp))
        PlayerPanel(panel, landscape, s, activity, setPanel0 = { panel = it })
    }
}

fun chapterAt(s: Playing, t: Double): String? = s.chapters.lastOrNull { it.time <= t + .01 }?.title?.takeIf { !Regex("^[\\d:.]+$|^Chapter \\d+$").matches(it) }

/* ------------------------------------------------------------------ chrome */

/** Round, generous touch target used across the player chrome (48 dp minimum, icon-only). */
@Composable private fun PlayerIcon(icon: ImageVector, label: String, active: Boolean = false, size: Dp = 48.dp, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Box(Modifier.size(size).clip(CircleShape).background(if (active) accent.light.copy(alpha = .22f) else Color.Transparent).bouncy(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(size * .52f), tint = if (active) accent.light else Color.White)
    }
}

@Composable private fun TopBar(s: Playing, modifier: Modifier, activity: MainActivity, openPanel: (String) -> Unit) {
    val sync by SubtitleJobs.state.collectAsState()
    val parsed = remember(s.video?.title) { s.video?.title?.let { OnlineSubtitles.parseTitle(it) } }
    val subOn = s.tracks.any { it.type == "sub" && it.selected }
    Row(modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        PlayerIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back to library") { activity.library() }
        Column(Modifier.weight(1f).padding(start = 6.dp, end = 10.dp)) {
            Text(s.video?.let { prettyTitle(it.title) }.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
            Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tag(if (s.decoder.startsWith("Hardware")) "HW" else if (s.decoder.isBlank()) "…" else "SW", LocalAccent.current.light)
                val bits = listOfNotNull(parsed?.episode?.let { "S%02d · E%02d".format(parsed.season ?: 1, it) }, if (s.queue.size > 1) "${s.queueIndex + 1} of ${s.queue.size}" else null)
                if (bits.isNotEmpty()) Text(bits.joinToString("  ·  "), color = Color.White.copy(alpha = .7f), fontSize = 12.sp, maxLines = 1)
                SyncBadge(s, sync) { openPanel("sync") }
                if (s.sleepAt > 0 || s.sleepEnd) SleepBadge(s)
                if (s.loop) Badge(Icons.Rounded.RepeatOne, "Loop")
                if (s.abStart >= 0) Badge(Icons.Rounded.Repeat, if (s.abEnd >= 0) "A–B" else "A…")
            }
        }
        PlayerIcon(Icons.Rounded.ClosedCaption, "Subtitles", active = subOn) { openPanel("subs") }
        PlayerIcon(Icons.Rounded.MusicNote, "Audio", active = s.volume > 100 || s.muted) { openPanel("audio") }
        PlayerIcon(Icons.Rounded.MoreVert, "More") { openPanel("more") }
    }
}

/**
 * Desktop parity: "Syncing subtitles…" while the job runs, "Subtitles synced" while the corrected
 * file is the one on screen, "Review subtitle sync" when a result waits for you. Tap opens the sync panel.
 */
@Composable private fun SyncBadge(s: Playing, sync: SyncState, onClick: () -> Unit) {
    val mine = sync.video == s.video?.uri
    val synced = s.video?.externalSub?.let { "/sync-" in it } == true && s.tracks.any { it.type == "sub" && it.selected && it.external }
    val (text, color) = when {
        mine && sync.running -> "Syncing subtitles…" to LocalAccent.current.light
        mine && sync.result != null -> "Review subtitle sync" to Nova.Boost
        synced -> "Subtitles synced" to Color(0xFF34D399)
        else -> return
    }
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = .18f)).border(1.dp, color.copy(alpha = .45f), RoundedCornerShape(10.dp))
        .clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        if (mine && sync.running) Equalizer(true, color) else Icon(if (synced && !(mine && sync.result != null)) Icons.Rounded.CheckCircle else Icons.Rounded.GraphicEq, null, Modifier.size(14.dp), tint = color)
        Text(text, fontSize = 12.sp, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 5.dp), maxLines = 1)
    }
}

@Composable private fun Tag(text: String, color: Color) {
    Text(text, Modifier.border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp), color = color, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .5.sp)
}

@Composable private fun Badge(icon: ImageVector, text: String) {
    val accent = LocalAccent.current
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(accent.soft2).padding(horizontal = 6.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(12.dp), tint = accent.light); Text(text, fontSize = 11.sp, color = accent.light, modifier = Modifier.padding(start = 3.dp), fontWeight = FontWeight.Bold)
    }
}
@Composable private fun SleepBadge(s: Playing) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s.sleepAt) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Badge(Icons.Rounded.Bedtime, if (s.sleepEnd) "End" else timeLabel((s.sleepAt - now) / 1000.0))
}

@Composable private fun CenterControls(s: Playing, landscape: Boolean) {
    val accent = LocalAccent.current
    val hasQueue = s.queue.size > 1
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (landscape) 56.dp else 36.dp)) {
        NavCircle(Icons.Rounded.SkipPrevious, "Previous", hasQueue && s.queueIndex > 0) { NovaRuntime.next(-1) }
        val scale by animateFloatAsState(if (s.paused) 1.06f else 1f, spring(dampingRatio = .4f), label = "playScale")
        Box(Modifier.size(84.dp).scale(scale).shadow(28.dp, CircleShape, ambientColor = accent.glow, spotColor = accent.glow).clip(CircleShape).background(accent.grad)
            .bouncy { NovaRuntime.pause() }, contentAlignment = Alignment.Center) {
            AnimatedContent(s.paused, transitionSpec = { (scaleIn(initialScale = .4f) + fadeIn()).togetherWith(scaleOut(targetScale = .4f) + fadeOut()) }, label = "pp") { p ->
                Icon(if (p) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (p) "Play" else "Pause", Modifier.size(46.dp), tint = Color.White)
            }
        }
        NavCircle(Icons.Rounded.SkipNext, "Next", hasQueue && s.queueIndex + 1 < s.queue.size) { NovaRuntime.next(1) }
    }
}

@Composable private fun NavCircle(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = .38f)).border(1.dp, Color.White.copy(alpha = .1f), CircleShape)
        .bouncy { if (enabled) onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(30.dp), tint = if (enabled) Color.White else Color.White.copy(alpha = .28f))
    }
}

private val FITS = listOf("Fit" to "-1", "Fill" to "fill", "16:9" to "16:9", "4:3" to "4:3", "2.35:1" to "2.35:1")

@Composable private fun BottomBar(s: Playing, modifier: Modifier, activity: MainActivity, lock: () -> Unit, openPanel: (String) -> Unit) {
    var remaining by remember { mutableStateOf(false) }
    var fit by remember { mutableIntStateOf(0) }
    val chapter = chapterAt(s, s.position)
    val accent = LocalAccent.current
    Column(modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        if (chapter != null) Text(chapter, Modifier.padding(start = 10.dp, bottom = 2.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = .12f)).padding(horizontal = 8.dp, vertical = 3.dp),
            color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(timeLabel(s.position), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 44.dp))
            Box(Modifier.weight(1f).padding(horizontal = 8.dp)) { SeekBar(s) }
            Text(if (remaining) "−${timeLabel(s.duration - s.position)}" else timeLabel(s.duration), color = Color.White.copy(alpha = .8f), fontSize = 13.sp,
                modifier = Modifier.widthIn(min = 44.dp).clip(RoundedCornerShape(6.dp)).clickable { remaining = !remaining }.padding(vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerIcon(Icons.Rounded.Lock, "Lock controls", onClick = lock)
            if (s.queue.size > 1) PlayerIcon(Icons.AutoMirrored.Rounded.QueueMusic, "Up next") { openPanel("queue") }
            Spacer(Modifier.weight(1f))
            Box(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(if (s.speed != 1.0) accent.light.copy(alpha = .22f) else Color.White.copy(alpha = .1f))
                .bouncy { openPanel("speed") }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                Text("${fmtSpeed(s.speed)}×", color = if (s.speed != 1.0) accent.light else Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }
            Spacer(Modifier.width(6.dp))
            PlayerIcon(Icons.Rounded.AspectRatio, "Fit to screen") {
                fit = (fit + 1) % FITS.size
                val (label, v) = FITS[fit]
                if (v == "fill") { NovaRuntime.engine.setStr("video-aspect-override", "-1"); NovaRuntime.engine.setStr("panscan", "1") }
                else { NovaRuntime.engine.setStr("panscan", "0"); NovaRuntime.engine.setStr("video-aspect-override", v) }
                NovaRuntime.hud.value = Hud("fit", 0.0, 1.0, if (label == "Fill") "Fill screen" else label)
            }
            PlayerIcon(Icons.Rounded.ScreenRotation, "Rotate screen") { activity.rotate() }
            PlayerIcon(Icons.Rounded.PictureInPictureAlt, "Picture in picture") { activity.pip() }
        }
    }
}
fun fmtSpeed(v: Double) = if (v % 1.0 == 0.0) "%.1f".format(v) else "%.2f".format(v).trimEnd('0')

/** Chapter-segmented seek bar with buffered range, scrub bubble and live keyframe preview. */
@Composable private fun SeekBar(s: Playing) {
    val accent = LocalAccent.current
    var scrub by remember { mutableStateOf<Float?>(null) }
    var hold by remember { mutableStateOf<Pair<Float, Long>?>(null) }
    val dur = s.duration.coerceAtLeast(0.001)
    val live = (s.position / dur).toFloat().coerceIn(0f, 1f)
    val shown = scrub ?: hold?.takeIf { System.currentTimeMillis() - it.second < 800 && abs(it.first - live) > .002f }?.first ?: live
    val thick by animateDpAsState(if (scrub != null) 9.dp else 5.dp, label = "thick")
    val knob by animateDpAsState(if (scrub != null) 22.dp else 15.dp, spring(dampingRatio = .5f), label = "knob")
    var lastSent by remember { mutableLongStateOf(0L) }
    val cuts = remember(s.chapters, s.duration) { s.chapters.map { it.time }.filter { it > 1 && it < s.duration - 1 }.distinctBy { (it).toInt() }.map { (it / dur).toFloat() } }
    fun commit(f: Float) { NovaRuntime.seek(f * dur); hold = f to System.currentTimeMillis(); scrub = null }
    BoxWithConstraints(Modifier.fillMaxWidth().height(44.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        if (scrub != null) {
            val t = scrub!! * dur
            val bubbleW = 132.dp
            val x = with(LocalDensity.current) { (scrub!! * widthPx).toDp() - bubbleW / 2 }.coerceIn(0.dp, maxWidth - bubbleW)
            Column(Modifier.offset(x = x, y = (-46).dp).width(bubbleW).clip(RoundedCornerShape(12.dp)).background(Nova.Bg3.copy(alpha = .95f)).padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(timeLabel(t), fontWeight = FontWeight.Black, fontSize = 16.sp, color = Color.White)
                chapterAt(s, t)?.let { Text(it, fontSize = 11.sp, color = accent.light, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp)) }
            }
        }
        Canvas(Modifier.fillMaxSize().pointerInput(dur) {
            detectTapGestures { o -> commit((o.x / size.width).coerceIn(0f, 1f)) }
        }.pointerInput(dur) {
            detectHorizontalDragGestures(onDragStart = { o -> scrub = (o.x / size.width).coerceIn(0f, 1f) },
                onDragEnd = { scrub?.let(::commit) }, onDragCancel = { scrub = null }) { change, dx ->
                change.consume()
                scrub = ((scrub ?: 0f) + dx / size.width).coerceIn(0f, 1f)
                val now = System.currentTimeMillis()
                if (now - lastSent > 150) { lastSent = now; NovaRuntime.seek(scrub!! * dur, fast = true) }
            }
        }) {
            val y = size.height / 2; val th = thick.toPx(); val gap = 3.dp.toPx()
            val bounds = listOf(0f) + cuts + listOf(1f)
            val buffered = ((s.position + s.cached) / dur).toFloat().coerceIn(0f, 1f)
            for (i in 0 until bounds.size - 1) {
                val a = bounds[i] * size.width + if (i > 0) gap / 2 else 0f
                val b = bounds[i + 1] * size.width - if (i < bounds.size - 2) gap / 2 else 0f
                if (b <= a) continue
                val r = CornerRadius(th / 2)
                drawRoundRect(Color.White.copy(alpha = .22f), Offset(a, y - th / 2), Size(b - a, th), r)
                val bufEnd = min(b, buffered * size.width); if (bufEnd > a) drawRoundRect(Color.White.copy(alpha = .22f), Offset(a, y - th / 2), Size(bufEnd - a, th), r)
                val end = min(b, shown * size.width); if (end > a) drawRoundRect(Brush.horizontalGradient(listOf(accent.light, accent.deep), 0f, size.width), Offset(a, y - th / 2), Size(end - a, th), r)
            }
            if (s.abStart >= 0) drawCircle(Nova.Boost, 3.dp.toPx(), Offset((s.abStart / dur).toFloat() * size.width, y - th - 4.dp.toPx()))
            if (s.abEnd >= 0) drawCircle(Nova.Boost, 3.dp.toPx(), Offset((s.abEnd / dur).toFloat() * size.width, y - th - 4.dp.toPx()))
            val cx = shown * size.width
            drawCircle(accent.light.copy(alpha = .3f), knob.toPx() * .9f, Offset(cx, y))
            drawCircle(Color.White, knob.toPx() / 2, Offset(cx, y))
        }
    }
}

/* ------------------------------------------------------------------ overlays */

@Composable private fun PlayFlash(key: Long, paused: Boolean, modifier: Modifier) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) { if (key != 0L) { a.snapTo(1f); a.animateTo(0f, tween(600)) } }
    if (a.value > 0f) Box(modifier.size(96.dp).scale(1.4f - .4f * a.value).alpha(a.value).clip(CircleShape).background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) {
        Icon(if (paused) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(52.dp), tint = Color.White)
    }
}

@Composable private fun BoxScope.RippleOverlay(r: Ripple) {
    val grow = remember(r.at) { Animatable(0f) }
    LaunchedEffect(r.at) { grow.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
    val chevron = rememberInfiniteTransition(label = "chev").animateFloat(0f, 3f, infiniteRepeatable(tween(600, easing = LinearEasing)), label = "c")
    Box(Modifier.align(if (r.side < 0) Alignment.CenterStart else Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(.38f)
        .clip(if (r.side < 0) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50) else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50))
        .background(Color.White.copy(alpha = .1f)).drawBehind {
            val c = Offset(if (r.side < 0) size.width * .45f else size.width * .55f, size.height / 2)
            drawCircle(Color.White.copy(alpha = .16f * (1 - grow.value)), size.height * .6f * grow.value, c)
        }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row { repeat(3) { i -> val lit = (if (r.side > 0) i else 2 - i) == chevron.value.toInt()
                Icon(if (r.side < 0) Icons.Rounded.ChevronLeft else Icons.Rounded.ChevronRight, null, Modifier.size(26.dp).alpha(if (lit) 1f else .35f), tint = Color.White) } }
            Text("${r.seconds} seconds", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
    }
}

@Composable private fun BoostPill(speed: Double) {
    val accent = LocalAccent.current
    val t = rememberInfiniteTransition(label = "boost").animateFloat(0f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing)), label = "b")
    Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .6f)).border(1.dp, accent.line, CircleShape).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${fmtSpeed(speed)}×", fontWeight = FontWeight.Black, color = Color.White)
        Icon(Icons.Rounded.FastForward, null, Modifier.padding(start = 6.dp).offset(x = (t.value * 4).dp).alpha(1 - t.value * .5f), tint = accent.light)
    }
}

@Composable private fun BoxScope.HudView(h: Hud) {
    val accent = LocalAccent.current
    val vertical = h.kind == "volume" || h.kind == "brightness"
    val icon = when (h.kind) { "volume" -> if (h.value <= 0) Icons.AutoMirrored.Rounded.VolumeOff else if (h.value > 100) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeDown
        "brightness" -> Icons.Rounded.BrightnessMedium; "fit" -> Icons.Rounded.AspectRatio; else -> Icons.Rounded.ZoomIn }
    val frac = (h.value / h.max).toFloat().coerceIn(0f, 1f)
    val anim by animateFloatAsState(frac, spring(stiffness = 900f), label = "hud")
    if (vertical) {
        Column(Modifier.align(if (h.kind == "volume") Alignment.CenterEnd else Alignment.CenterStart).padding(horizontal = 44.dp).width(58.dp)
            .clip(RoundedCornerShape(29.dp)).background(Color.Black.copy(alpha = .55f)).padding(vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${h.value.let { if (h.kind == "brightness") it * 100 else it }.roundToInt()}", color = if (h.kind == "volume" && h.value > 100) Nova.Boost else Color.White, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Box(Modifier.padding(vertical = 10.dp).width(8.dp).height(150.dp).clip(CircleShape).background(Color.White.copy(alpha = .2f)), contentAlignment = Alignment.BottomCenter) {
                val boostCut = if (h.kind == "volume") (100 / h.max).toFloat() else 2f
                Box(Modifier.fillMaxWidth().fillMaxHeight(anim).clip(CircleShape).background(
                    if (anim > boostCut) Brush.verticalGradient(listOf(Nova.Boost, accent.light)) else Brush.verticalGradient(listOf(accent.light, accent.deep))))
            }
            Icon(icon, null, tint = Color.White)
        }
    } else Row(Modifier.align(Alignment.Center).clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White); Text(h.text, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 10.dp))
    }
}

@Composable private fun Loading(opening: Boolean, modifier: Modifier) {
    val accent = LocalAccent.current
    val spin = rememberInfiniteTransition(label = "load").animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "s")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(54.dp).rotate(spin.value)) {
            drawArc(Brush.sweepGradient(listOf(Color.Transparent, accent.light)), 0f, 300f, false, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        }
        Text(if (opening) "Opening…" else "Buffering…", color = Color.White.copy(alpha = .8f), modifier = Modifier.padding(top = 12.dp), fontSize = 13.sp)
    }
}

@Composable private fun ErrorCard(s: Playing, activity: MainActivity, modifier: Modifier) {
    Column(modifier.padding(32.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(26.dp)).background(Nova.Bg3).padding(24.dp)) {
        Icon(Icons.Rounded.ErrorOutline, null, Modifier.size(36.dp), tint = Nova.Danger)
        Text("This one won't play", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 10.dp))
        Text(s.error, color = Nova.Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GradientButton("Try again", Icons.Rounded.Refresh, Modifier.weight(1f)) { s.video?.let { activity.play(it, s.queue) } }
            GhostButton("Library", modifier = Modifier.weight(1f)) { NovaRuntime.close(); activity.library() }
        }
    }
}

@Composable private fun EndCard(s: Playing, activity: MainActivity, modifier: Modifier) {
    Column(modifier.padding(32.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(28.dp)).background(Nova.Bg3.copy(alpha = .94f)).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Rounded.CheckCircle, null, Modifier.size(44.dp), tint = LocalAccent.current.light)
        Text("That's a wrap", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 10.dp))
        Text(s.video?.title?.substringBeforeLast('.').orEmpty(), color = Nova.Dim, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(vertical = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
            GradientButton("Replay", Icons.Rounded.Replay, Modifier.weight(1f)) { NovaRuntime.replay() }
            GhostButton("Library", Icons.Rounded.VideoLibrary, Modifier.weight(1f)) { NovaRuntime.close(); activity.library() }
        }
    }
}

@Composable private fun NextUp(s: Playing, controls: Boolean, modifier: Modifier) {
    var dismissed by remember(s.video?.uri) { mutableStateOf(false) }
    if (!NovaRuntime.pref("nextUp", true) || s.queueIndex + 1 >= s.queue.size || s.duration < 60 || s.ended) return
    val lastChapter = s.chapters.lastOrNull()?.time
    val at = if (s.chapters.size >= 2 && lastChapter != null && s.duration - lastChapter <= max(360.0, s.duration * .12)) lastChapter else s.duration - 25
    val show = !dismissed && s.position >= at
    val next = remember(s.queue, s.queueIndex) { NovaRuntime.store.videos.value.find { it.uri == s.queue[s.queueIndex + 1] } } ?: return
    AnimatedVisibility(show, modifier, enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
        Row(Modifier.width(330.dp).clip(RoundedCornerShape(20.dp)).background(Nova.Bg3.copy(alpha = .95f)).border(1.dp, LocalAccent.current.line, RoundedCornerShape(20.dp)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Poster(next, Modifier.width(104.dp).aspectRatio(1.6f), corner = 12.dp, badge = false)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text("NEXT UP · in ${timeLabel(s.duration - s.position)}", style = Kicker, color = LocalAccent.current.light, fontSize = 10.sp)
                Text(prettyTitle(next.title), maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row {
                    TextButton({ NovaRuntime.next(1) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Play now", fontWeight = FontWeight.Bold) }
                    TextButton({ dismissed = true }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Dismiss", color = Nova.Dim) }
                }
            }
        }
    }
}

/** Snack/notice renderer shared by library and player. */
@Composable fun Toasts(modifier: Modifier) {
    val notice by NovaRuntime.notice.collectAsState()
    val snack by NovaRuntime.snack.collectAsState()
    var shown by remember { mutableStateOf<Snack?>(null) }
    LaunchedEffect(notice) { if (notice.isNotBlank()) { shown = Snack(notice); NovaRuntime.notice.value = "" } }
    LaunchedEffect(snack) { snack?.let { shown = it; NovaRuntime.snack.value = null } }
    LaunchedEffect(shown?.at) { if (shown != null) { delay(if (shown?.action != null) 6000 else 2400); shown = null } }
    val accent = LocalAccent.current
    AnimatedVisibility(shown != null, modifier.padding(horizontal = 24.dp), enter = slideInVertically { -it } + fadeIn(), exit = fadeOut() + slideOutVertically { -it / 2 }) {
        val s = shown ?: return@AnimatedVisibility
        Row(Modifier.shadow(12.dp, CircleShape).clip(CircleShape).background(Nova.Bg3).border(1.dp, Color.White.copy(alpha = .08f), CircleShape)
            .padding(start = 18.dp, end = if (s.action != null) 6.dp else 18.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.text, color = Nova.Text, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp).widthIn(max = 420.dp))
            if (s.action != null) TextButton({ s.run?.invoke(); shown = null }) { Text(s.action, color = accent.light, fontWeight = FontWeight.Bold) }
        }
    }
}
