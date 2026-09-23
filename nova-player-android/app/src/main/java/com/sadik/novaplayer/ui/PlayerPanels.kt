package com.sadik.novaplayer.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.sadik.novaplayer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.pow
import kotlin.math.roundToInt

private val TITLES = mapOf("speed" to "Playback speed", "audio" to "Audio", "subs" to "Subtitles", "online" to "Find subtitles", "sync" to "Auto-sync subtitles",
    "video" to "Display", "queue" to "Up next", "chapters" to "Chapters", "sleep" to "Sleep timer", "info" to "Media info", "more" to "More")

@Composable fun PlayerPanel(panel: String, landscape: Boolean, s: Playing, activity: MainActivity, setPanel0: (String) -> Unit) {
    var last by remember { mutableStateOf(panel) }
    var backTo by remember { mutableStateOf("") }
    if (panel.isNotBlank()) last = panel
    // Navigating from one panel to another remembers where to go back to.
    val setPanel: (String) -> Unit = { target -> backTo = if (target.isNotBlank() && panel.isNotBlank() && target != panel) panel else ""; setPanel0(target) }
    val open = panel.isNotBlank()
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .18f)).clickable(MutableInteractionSource(), null) { setPanel("") })
        }
        val align = if (landscape) Alignment.CenterEnd else Alignment.BottomCenter
        AnimatedVisibility(open, Modifier.align(align),
            enter = if (landscape) slideInHorizontally(spring(dampingRatio = .85f, stiffness = 500f)) { it } else slideInVertically(spring(dampingRatio = .85f, stiffness = 500f)) { it },
            exit = if (landscape) slideOutHorizontally { it } else slideOutVertically { it }) {
            val shape = if (landscape) RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column((if (landscape) Modifier.width(360.dp).fillMaxHeight() else Modifier.fillMaxWidth().fillMaxHeight(.62f))
                .clip(shape).background(Nova.Bg2).border(1.dp, Color.White.copy(alpha = .07f), shape)
                .clickable(MutableInteractionSource(), null) {}
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End)).navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 10.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (backTo.isNotBlank()) IconButton({ val b = backTo; backTo = ""; setPanel0(b) }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                    Text(TITLES[last] ?: "", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton({ setPanel("") }) { Icon(Icons.Rounded.Close, "Close", tint = Nova.Dim) }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    AnimatedContent(last, transitionSpec = { fadeIn(tween(200)).togetherWith(fadeOut(tween(120))) }, label = "panel") { p ->
                        Column {
                            when (p) {
                                "speed" -> SpeedPanel(s)
                                "audio" -> AudioPanel(s)
                                "subs" -> SubtitlePanel(s, activity, setPanel)
                                "online" -> OnlinePanel(s)
                                "sync" -> SyncPanel(activity)
                                "video" -> VideoPanel(activity)
                                "queue" -> QueuePanel(s, setPanel)
                                "chapters" -> ChaptersPanel(s, setPanel)
                                "sleep" -> SleepPanel(s, setPanel)
                                "info" -> InfoPanel()
                                "more" -> MorePanel(s, activity, setPanel)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun Label(text: String) = Text(text.uppercase(), style = Kicker, color = Nova.Dim, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp))

@Composable private fun FlowPills(content: @Composable () -> Unit) {
    @OptIn(ExperimentalLayoutApi::class) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

/* ------------------------------------------------------------------ speed */

@Composable private fun SpeedPanel(s: Playing) {
    val accent = LocalAccent.current
    Text("${fmtSpeed(s.speed)}×", fontSize = 52.sp, fontWeight = FontWeight.Black, color = accent.light, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(Icons.Rounded.Remove, "Slower") { NovaRuntime.speed(s.speed - .05) }
        Slider(s.speed.toFloat(), { NovaRuntime.speed(((it / .05f).roundToInt() * .05).toDouble()) }, Modifier.weight(1f).padding(horizontal = 8.dp), valueRange = .25f..4f)
        RoundIcon(Icons.Rounded.Add, "Faster") { NovaRuntime.speed(s.speed + .05) }
    }
    Label("Presets")
    FlowPills { listOf(.25, .5, .75, 1.0, 1.25, 1.5, 1.75, 2.0, 3.0).forEach { v -> Pill("${fmtSpeed(v)}×", kotlin.math.abs(s.speed - v) < .001) { NovaRuntime.speed(v) } } }
    Spacer(Modifier.height(16.dp))
    GhostButton("Default (${fmtSpeed(NovaRuntime.number("defaultSpeed", 1.0))}×)", Icons.Rounded.RestartAlt, Modifier.fillMaxWidth()) { NovaRuntime.speed(NovaRuntime.number("defaultSpeed", 1.0)) }
    Text("Tip: press and hold the video for a quick 2× burst.", color = Nova.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 14.dp))
}

/* ------------------------------------------------------------------ audio */

@Composable private fun AudioPanel(s: Playing) {
    Tracks(s, "audio", allowOff = false)
    Label("Volume")
    var level by remember { mutableFloatStateOf(NovaRuntime.level().toFloat()) }
    val max = NovaRuntime.volumeMax().toFloat()
    Row(verticalAlignment = Alignment.CenterVertically) {
        RoundIcon(if (s.muted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp, "Mute", tint = if (s.muted) Nova.Danger else Nova.Text) { NovaRuntime.mute() }
        Slider(level, { level = it; NovaRuntime.setLevel(it.roundToInt().toDouble(), show = false) }, Modifier.weight(1f).padding(horizontal = 10.dp), valueRange = 0f..max,
            colors = SliderDefaults.colors(activeTrackColor = if (level > 100) Nova.Boost else LocalAccent.current.light, thumbColor = if (level > 100) Nova.Boost else LocalAccent.current.light))
        Text("${level.roundToInt()}%", fontWeight = FontWeight.Bold, color = if (level > 100) Nova.Boost else Nova.Text, modifier = Modifier.width(52.dp), textAlign = TextAlign.End)
    }
    if (level > 100) Text("Boost is on — louder than the phone's maximum. Watch for distortion.", color = Nova.Boost, fontSize = 12.sp)
    Label("Audio timing")
    TimingCard("audio-delay", "Audio is early", "Audio is late")
}

@Composable private fun Tracks(s: Playing, type: String, allowOff: Boolean = true) {
    val rows = s.tracks.filter { it.type == type }
    val accent = LocalAccent.current
    if (rows.isEmpty()) Text(if (type == "sub") "This video has no subtitles yet." else "No audio tracks.", color = Nova.Dim, fontSize = 14.sp, modifier = Modifier.padding(vertical = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (allowOff && rows.isNotEmpty()) TrackRow("Off", "Hide subtitles", rows.none { it.selected }) { NovaRuntime.selectTrack(type, "no") }
        rows.forEach { t -> TrackRow(t.title, t.detail, t.selected) { NovaRuntime.selectTrack(type, t.id) } }
    }
}

@Composable private fun TrackRow(title: String, detail: String, selected: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (selected) accent.soft2 else Color.White.copy(alpha = .04f))
        .border(1.dp, if (selected) accent.line else Color.Transparent, RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (selected) accent.light else Nova.Dim, modifier = Modifier.size(20.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = Nova.Dim, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/** Desktop timing card: ±0.1/±1/±5 nudges, exact entry, reset. Works for sub-delay and audio-delay. */
@Composable private fun TimingCard(key: String, minusHint: String, plusHint: String) {
    var value by remember(key) { mutableDoubleStateOf(NovaRuntime.engine.getDouble(key)) }
    var edit by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    fun set(v: Double) { value = (Math.round(v.coerceIn(-600.0, 600.0) * 10) / 10.0); NovaRuntime.property(key, value) }
    Card {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${if (value > 0) "+" else ""}${"%.1f".format(value)} s", fontSize = 28.sp, fontWeight = FontWeight.Black, color = if (value == 0.0) Nova.Text else accent.light,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { edit = true })
            if (value != 0.0) TextButton({ set(0.0) }) { Text("Reset") }
        }
        Text(if (value < 0) minusHint.let { "Shown earlier" } else if (value > 0) "Shown later" else "In sync with the video", color = Nova.Dim, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(-5.0, -1.0, -.1, .1, 1.0, 5.0).forEach { d ->
                Box(Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .07f)).bouncy { set(value + d) }, contentAlignment = Alignment.Center) {
                    Text("${if (d > 0) "+" else "−"}${kotlin.math.abs(d).let { if (it < 1) "0.1" else it.toInt().toString() }}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
        Text("Tap the number to type an exact value.", color = Nova.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
    }
    if (edit) InputDialog("Exact delay (seconds)", "e.g. -2.5", "%.1f".format(value), "Set", { edit = false }) { it.replace(',', '.').toDoubleOrNull()?.let(::set); edit = false }
}

/* ------------------------------------------------------------------ subtitles */

@Composable private fun SubtitlePanel(s: Playing, activity: MainActivity, setPanel: (String) -> Unit) {
    val accent = LocalAccent.current
    PanelRow(Icons.Rounded.TravelExplore, "Find subtitles online", chevron = true) { setPanel("online") }
    PanelRow(Icons.Rounded.FolderOpen, "Open a subtitle file", chevron = true) { activity.subtitle() }
    PanelRow(Icons.Rounded.GraphicEq, "Auto-sync to the dialogue", chevron = true) { setPanel("sync") }
    Label("Tracks")
    Tracks(s, "sub")
    Label("Timing")
    TimingCard("sub-delay", "Early", "Late")
    Label("Speed correction")
    SubSpeed()
    Label("Look")
    Card {
        var scale by remember { mutableDoubleStateOf(NovaRuntime.engine.getDouble("sub-scale").takeIf { it > 0 } ?: 1.0) }
        var pos by remember { mutableDoubleStateOf(NovaRuntime.engine.getDouble("sub-pos").takeIf { it > 0 } ?: 90.0) }
        Stepper("Size", "${(scale * 100).roundToInt()}%", "A−", "A+", { scale = (scale - .05).coerceIn(.3, 3.0); NovaRuntime.property("sub-scale", scale, true) }) { scale = (scale + .05).coerceIn(.3, 3.0); NovaRuntime.property("sub-scale", scale, true) }
        Spacer(Modifier.height(10.dp))
        Stepper("Position", "${pos.roundToInt()}", "▲ Higher", "▼ Lower", { pos = (pos - 5).coerceIn(20.0, 150.0); NovaRuntime.property("sub-pos", pos, true) }) { pos = (pos + 5).coerceIn(20.0, 150.0); NovaRuntime.property("sub-pos", pos, true) }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Show subtitles", Modifier.weight(1f)); Switch(s.subVisible, { NovaRuntime.flag("sub-visibility", it) }, colors = SwitchDefaults.colors(checkedTrackColor = accent.light))
        }
    }
    if (s.video?.externalSub?.isNotBlank() == true) TextButton({ activity.exportSubtitle() }, Modifier.padding(top = 8.dp)) { Icon(Icons.Rounded.IosShare, null); Text("  Export current subtitle") }
}

@Composable private fun SubSpeed() {
    // Desktop maps a 50–200% UI to sub-speed = 100/percent (reciprocal).
    var pct by remember { mutableDoubleStateOf((100 / (NovaRuntime.engine.getDouble("sub-speed").takeIf { it > 0 } ?: 1.0)).let { Math.round(it * 2) / 2.0 }) }
    fun set(v: Double) { pct = v.coerceIn(50.0, 200.0); NovaRuntime.property("sub-speed", 100 / pct) }
    Card {
        Stepper("Stretch", "${"%.1f".format(pct).removeSuffix(".0")}%", "−", "+", { set(pct - .5) }) { set(pct + .5) }
        Text("For subtitles that drift further out of sync as the video goes on (e.g. 23.976 vs 25 fps). Text subtitles only.", color = Nova.Dim, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
            Pill("100%", pct == 100.0) { set(100.0) }; Pill("95.9% (25→23.976)", kotlin.math.abs(pct - 95.9) < .06) { set(95.9) }; Pill("104.3%", kotlin.math.abs(pct - 104.3) < .06) { set(104.3) }
        }
    }
}

@Composable private fun Stepper(title: String, value: String, minus: String, plus: String, dec: () -> Unit, inc: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, fontSize = 14.sp); Text(value, fontWeight = FontWeight.Black, fontSize = 18.sp, color = LocalAccent.current.light) }
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .07f)).bouncy(onClick = dec).padding(horizontal = 14.dp, vertical = 10.dp)) { Text(minus, fontWeight = FontWeight.Bold) }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .07f)).bouncy(onClick = inc).padding(horizontal = 14.dp, vertical = 10.dp)) { Text(plus, fontWeight = FontWeight.Bold) }
    }
}

@Composable private fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = LocalAccent.current.light, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = .05f)).border(1.dp, Color.White.copy(alpha = .06f), RoundedCornerShape(18.dp))
        .bouncy(onClick = onClick).padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(tint.copy(alpha = .15f)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = tint) }
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable private fun OnlinePanel(s: Playing) {
    val parsed = remember(s.video?.title) { s.video?.title?.let { OnlineSubtitles.parseTitle(it) } }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SubtitleResult>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val accent = LocalAccent.current
    val langs = NovaRuntime.text("subLangs", "eng").split(',')
    if (!NovaRuntime.pref("onlineSubs", true)) { Text("Online subtitles are turned off in Settings.", color = Nova.Dim); return }
    fun search() { val video = s.video ?: return; scope.launch { busy = true; status = "Searching OpenSubtitles…"
        runCatching { withContext(Dispatchers.IO) { OnlineSubtitles.search(video, query, langs) } }
            .onSuccess { results = it; status = if (it.isEmpty()) "Nothing found. Try a simpler title." else "${plural(it.size, "subtitle")} found" }.onFailure { status = it.message.orEmpty() }
        busy = false } }
    LaunchedEffect(Unit) { if (results.isEmpty()) search() }
    Text(parsed?.let { listOfNotNull(it.title, it.episode?.let { e -> "S%02dE%02d".format(it.season ?: 1, e) }).joinToString(" · ") } ?: "", color = Nova.Dim, fontSize = 13.sp)
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 10.dp), placeholder = { Text("Different title? Type it here") }, singleLine = true, shape = RoundedCornerShape(14.dp),
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { if (!busy) search() }),
        trailingIcon = { IconButton({ search() }, enabled = !busy) { Icon(Icons.Rounded.Search, "Search") } })
    Text("Languages: " + langs.mapNotNull { c -> LANGUAGES.find { it.first == c }?.third }.joinToString(", ") + " · change in Settings", color = Nova.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(CircleShape), color = accent.light)
    if (status.isNotBlank()) Text(status, color = Nova.Dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        results.take(60).forEach { r ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (r.exact) accent.soft else Color.White.copy(alpha = .04f)).clickable(enabled = !busy) {
                scope.launch { busy = true; status = "Downloading…"
                    runCatching { withContext(Dispatchers.IO) { OnlineSubtitles.download(s.video!!, r) } }
                        .onSuccess { if (NovaRuntime.state.value.video?.uri == s.video?.uri) {
                            if (NovaRuntime.attachSubtitle(it, true)) {
                                status = "Loaded ✓ ${r.name}"; NovaRuntime.notice.value = "Subtitle loaded"
                                if (NovaRuntime.pref("subSyncDownloads", false) && !r.exact) SubtitleJobs.start(NovaRuntime.text("subSyncMode", "smart"))
                            } else status = "This upload is damaged and can't be shown. Pick another one from the list."
                        } }
                        .onFailure { status = it.message.orEmpty() }
                    busy = false }
            }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text("${r.language} · ${r.format.uppercase()} · ${r.downloads} downloads", color = Nova.Dim, fontSize = 11.sp)
                }
                if (r.exact) Text("EXACT", style = Kicker, fontSize = 9.sp, color = Color.White, modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(accent.grad).padding(horizontal = 6.dp, vertical = 3.dp))
            }
        }
    }
}

@Composable private fun SyncPanel(activity: MainActivity) {
    val state by SubtitleJobs.state.collectAsState()
    var mode by remember { mutableStateOf(NovaRuntime.text("subSyncMode", "smart")) }
    val accent = LocalAccent.current
    Text("Nova listens to the dialogue on your phone and lines up an external SRT or ASS subtitle with it. Your original file is kept.", color = Nova.Dim, fontSize = 13.sp, lineHeight = 19.sp)
    Label("Mode")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("smart" to "Smart · fixes drift and cuts", "gentle" to "Gentle · fewer changes", "offset" to "Offset only · one shift for the whole file").forEach { (k, d) ->
            TrackRow(d.substringBefore(" ·"), d.substringAfter("· "), mode == k) { mode = k; NovaRuntime.setPref("subSyncMode", k) }
        }
    }
    Spacer(Modifier.height(16.dp))
    AnimatedContent(state.running, label = "sync") { running ->
        if (running) Card {
            Row(verticalAlignment = Alignment.CenterVertically) { Equalizer(true); Text(state.message, Modifier.padding(start = 12.dp).weight(1f), fontSize = 14.sp) }
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp).clip(CircleShape), color = accent.light)
            GhostButton("Cancel", Icons.Rounded.Close, Modifier.fillMaxWidth()) { SubtitleJobs.cancel() }
        } else Column {
            GradientButton("Sync to the audio", Icons.Rounded.GraphicEq, Modifier.fillMaxWidth()) { SubtitleJobs.start(mode, manual = true) }
            Spacer(Modifier.height(8.dp))
            GhostButton("Use a correctly timed subtitle", Icons.Rounded.Description, Modifier.fillMaxWidth()) { activity.syncReference() }
        }
    }
    if (!state.running && state.message.isNotBlank()) Card(Modifier.padding(top = 14.dp)) {
        Text(state.message, fontSize = 13.sp, lineHeight = 19.sp)
        if (state.result != null) GradientButton("Apply correction", Icons.Rounded.Check, Modifier.fillMaxWidth().padding(top = 12.dp)) { SubtitleJobs.apply() }
    }
    if (NovaRuntime.state.value.video?.originalSub?.isNotBlank() == true) TextButton({ NovaRuntime.restoreSubtitle() }, Modifier.padding(top = 8.dp)) { Icon(Icons.Rounded.Undo, null); Text("  Restore the original subtitle") }
}

/* ------------------------------------------------------------------ display */

@Composable private fun VideoPanel(activity: MainActivity) {
    var aspect by remember { mutableStateOf(NovaRuntime.engine.getStr("video-aspect-override") ?: "-1") }
    var fill by remember { mutableStateOf((NovaRuntime.engine.getDouble("panscan")) > 0) }
    var zoom by remember { mutableDoubleStateOf(NovaRuntime.engine.getDouble("video-zoom")) }
    var mirrored by remember { mutableStateOf(NovaRuntime.engine.getStr("vf")?.contains("hflip") == true) }
    Label("Fit")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        TrackRow("Fit", "Whole picture, no cropping", !fill && aspect.startsWith("-1")) { fill = false; aspect = "-1"; NovaRuntime.engine.setStr("panscan", "0"); NovaRuntime.engine.setStr("video-aspect-override", "-1") }
        TrackRow("Fill screen", "Crops the edges, no black bars", fill) { fill = true; NovaRuntime.engine.setStr("video-aspect-override", "-1"); NovaRuntime.engine.setStr("panscan", "1") }
        listOf("16:9" to "Widescreen", "4:3" to "Classic TV", "2.35:1" to "Cinema").forEach { (a, d) ->
            TrackRow(a, d, !fill && aspect == a) { fill = false; aspect = a; NovaRuntime.engine.setStr("panscan", "0"); NovaRuntime.engine.setStr("video-aspect-override", a) }
        }
    }
    Label("Zoom · ${(2.0.pow(zoom) * 100).roundToInt()}%")
    Slider(zoom.toFloat(), { zoom = ((it / .05f).roundToInt() * .05).toDouble(); NovaRuntime.property("video-zoom", zoom) }, valueRange = -.5f..1.5f)
    Text("Tip: pinch the video to zoom.", color = Nova.Dim, fontSize = 12.sp)
    Label("Orientation")
    PanelRow(Icons.Rounded.Rotate90DegreesCw, "Rotate video 90°") { NovaRuntime.property("video-rotate", (NovaRuntime.engine.getDouble("video-rotate") + 90) % 360) }
    PanelRow(Icons.Rounded.Flip, if (mirrored) "Unmirror" else "Mirror picture") { mirrored = !mirrored; NovaRuntime.engine.setStr("vf", if (mirrored) "hflip" else "") }
    PanelRow(Icons.Rounded.ScreenRotation, "Rotate screen") { activity.rotate() }
    Spacer(Modifier.height(12.dp))
    GhostButton("Reset everything to default", Icons.Rounded.RestartAlt, Modifier.fillMaxWidth()) { NovaRuntime.reset(); zoom = 0.0; aspect = "-1"; fill = false; mirrored = false }
}

/** One tappable line in a panel: tinted icon, title, optional value, chevron. */
@Composable fun PanelRow(icon: ImageVector, title: String, value: String? = null, tint: Color = LocalAccent.current.light, chevron: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = tint) }
        Text(title, Modifier.weight(1f).padding(horizontal = 14.dp), fontSize = 15.sp, fontWeight = FontWeight.Medium)
        if (value != null) Text(value, color = Nova.Dim, fontSize = 13.sp, maxLines = 1)
        if (chevron) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Nova.Dim)
    }
}

/* ------------------------------------------------------------------ lists */

@Composable private fun QueuePanel(s: Playing, setPanel: (String) -> Unit) {
    val all = NovaRuntime.store.videos.value
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        s.queue.forEachIndexed { i, uri ->
            val v = all.find { it.uri == uri } ?: return@forEachIndexed
            val now = i == s.queueIndex
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (now) LocalAccent.current.soft2 else Color.Transparent)
                .clickable { if (!now) NovaRuntime.playIndex(i); setPanel("") }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Poster(v, Modifier.width(96.dp).aspectRatio(1.6f), corner = 10.dp, badge = false)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(prettyTitle(v.title), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = if (now) FontWeight.Bold else FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (now) { Equalizer(!s.paused); Text("  Now playing", color = LocalAccent.current.light, fontSize = 11.sp) } else Text("${i + 1} · ${timeLabel(v.duration)}", color = Nova.Dim, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun ChaptersPanel(s: Playing, setPanel: (String) -> Unit) {
    if (s.chapters.isEmpty()) Text("This video has no chapters.", color = Nova.Dim)
    val currentIdx = s.chapters.indexOfLast { it.time <= s.position + .5 }
    s.chapters.forEachIndexed { i, c -> TrackRow(c.title, timeLabel(c.time), i == currentIdx) { NovaRuntime.seek(c.time); setPanel("") } }
}

@Composable private fun SleepPanel(s: Playing, setPanel: (String) -> Unit) {
    Text(when { s.sleepEnd -> "Nova will stop at the end of this video."; s.sleepAt > 0 -> "Nova will pause in about ${((s.sleepAt - System.currentTimeMillis()) / 60000 + 1)} min."; else -> "Fall asleep to something good. Nova pauses for you." }, color = Nova.Dim, fontSize = 13.sp)
    Spacer(Modifier.height(12.dp))
    FlowPills {
        listOf(15 to "15 min", 30 to "30 min", 45 to "45 min", 60 to "1 hour", 90 to "1½ hours", -1 to "End of video", 0 to "Off").forEach { (m, l) ->
            Pill(l, (m == -1 && s.sleepEnd) || (m == 0 && s.sleepAt == 0L && !s.sleepEnd), icon = if (m == 0) Icons.Rounded.Close else Icons.Rounded.Bedtime) { NovaRuntime.sleep(m); setPanel("") }
        }
    }
}

@Composable private fun InfoPanel() {
    val e = NovaRuntime.engine
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    tick
    fun g(k: String) = e.getStr(k)?.takeIf { it.isNotBlank() }
    val rows = listOf(
        "File" to g("filename"), "Container" to g("file-format"), "Size" to e.getLong("file-size").takeIf { it > 0 }?.let(::sizeLabel),
        "Video" to listOfNotNull(g("video-codec"), e.getLong("video-params/w").takeIf { it > 0 }?.let { "${it}×${e.getLong("video-params/h")}" }).joinToString(" · ").ifBlank { null },
        "Frame rate" to e.getDouble("container-fps").takeIf { it > 0 }?.let { "%.3f fps".format(it) }, "Video bitrate" to e.getLong("video-bitrate").takeIf { it > 0 }?.let { "${it / 1000} kbps" },
        "Decoder" to (g("hwdec-current")?.takeIf { it != "no" }?.let { "Hardware ($it)" } ?: "Software"), "Pixel format" to g("video-params/pixelformat"),
        "Audio" to listOfNotNull(g("audio-codec-name")?.uppercase(), e.getLong("audio-params/samplerate").takeIf { it > 0 }?.let { "$it Hz" }, e.getLong("audio-params/channel-count").takeIf { it > 0 }?.let { "$it ch" }).joinToString(" · ").ifBlank { null },
        "Audio bitrate" to e.getLong("audio-bitrate").takeIf { it > 0 }?.let { "${it / 1000} kbps" }, "Dropped frames" to "${e.getLong("frame-drop-count")} output · ${e.getLong("decoder-frame-drop-count")} decoder",
        "A/V sync" to "%.3f s".format(e.getDouble("avsync")), "Buffered" to "%.1f s".format(e.getDouble("demuxer-cache-duration")))
    Card {
        rows.forEach { (k, v) -> if (v != null) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(k, color = Nova.Dim, fontSize = 13.sp, modifier = Modifier.width(118.dp)); Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium) } }
    }
}

@Composable private fun MorePanel(s: Playing, activity: MainActivity, setPanel: (String) -> Unit) {
    Label("Watch")
    PanelRow(Icons.Rounded.AspectRatio, "Display & zoom", chevron = true) { setPanel("video") }
    PanelRow(Icons.Rounded.Speed, "Playback speed", "${fmtSpeed(s.speed)}×", chevron = true) { setPanel("speed") }
    if (s.chapters.isNotEmpty()) PanelRow(Icons.Rounded.Bookmarks, "Chapters", "${s.chapters.size}", chevron = true) { setPanel("chapters") }
    if (s.queue.size > 1) PanelRow(Icons.AutoMirrored.Rounded.QueueMusic, "Up next", "${s.queueIndex + 1} of ${s.queue.size}", chevron = true) { setPanel("queue") }
    PanelRow(Icons.Rounded.Bedtime, "Sleep timer", if (s.sleepEnd) "End of video" else if (s.sleepAt > 0) "On" else "Off", chevron = true) { setPanel("sleep") }
    Label("Repeat")
    PanelRow(Icons.Rounded.RepeatOne, "Loop this video", if (s.loop) "On" else "Off") { NovaRuntime.toggleLoop() }
    PanelRow(Icons.Rounded.Repeat, if (s.abStart < 0) "A–B repeat · set point A" else if (s.abEnd < 0) "A–B repeat · set point B" else "Clear A–B repeat") { NovaRuntime.ab() }
    Label("Tools")
    PanelRow(Icons.Rounded.PhotoCamera, "Save screenshot", tint = Color(0xFF34D399)) { NovaRuntime.screenshot(); setPanel("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)) { PanelRow(Icons.Rounded.KeyboardDoubleArrowLeft, "Frame", tint = Color(0xFF22D3EE)) { NovaRuntime.command("frame-back-step") } }
        Box(Modifier.weight(1f)) { PanelRow(Icons.Rounded.KeyboardDoubleArrowRight, "Frame", tint = Color(0xFF22D3EE)) { NovaRuntime.command("frame-step") } }
    }
    PanelRow(Icons.Rounded.Info, "Media info", tint = Color(0xFFA78BFA), chevron = true) { setPanel("info") }
    PanelRow(Icons.Rounded.PictureInPictureAlt, "Pop out (picture-in-picture)", tint = Color(0xFFA78BFA)) { setPanel(""); activity.pip() }
    s.video?.takeIf { activity.canDelete(it) }?.let { v ->
        Label("File")
        PanelRow(Icons.Rounded.Share, "Share", tint = Color(0xFF22D3EE)) { setPanel(""); activity.share(listOf(v)) }
        PanelRow(Icons.Rounded.DeleteForever, "Delete this video", if (s.queueIndex + 1 < s.queue.size) "Then play the next one" else "Then return to the library", tint = Nova.Danger) { setPanel(""); activity.askDelete(listOf(v)) }
    }
    PanelRow(Icons.Rounded.RestartAlt, "Reset all adjustments", tint = Color(0xFFFBBF24)) { NovaRuntime.reset() }
    PanelRow(Icons.Rounded.StopCircle, "Close video", tint = Nova.Danger) { NovaRuntime.close(); activity.library(pause = false) }
}
