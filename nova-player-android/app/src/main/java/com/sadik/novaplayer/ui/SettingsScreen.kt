package com.sadik.novaplayer.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.sadik.novaplayer.BuildConfig
import com.sadik.novaplayer.MainActivity
import com.sadik.novaplayer.NovaRuntime

/** code3, code2 (for mpv alang/slang matching), name. */
val LANGUAGES = listOf(
    Triple("eng", "en", "English"), Triple("ben", "bn", "Bengali"), Triple("hin", "hi", "Hindi"), Triple("urd", "ur", "Urdu"), Triple("ara", "ar", "Arabic"),
    Triple("spa", "es", "Spanish"), Triple("fre", "fr", "French"), Triple("ger", "de", "German"), Triple("ita", "it", "Italian"), Triple("por", "pt", "Portuguese"),
    Triple("pob", "pt", "Portuguese (BR)"), Triple("rus", "ru", "Russian"), Triple("tur", "tr", "Turkish"), Triple("ind", "id", "Indonesian"), Triple("may", "ms", "Malay"),
    Triple("chi", "zh", "Chinese"), Triple("zht", "zh", "Chinese (Trad.)"), Triple("jpn", "ja", "Japanese"), Triple("kor", "ko", "Korean"), Triple("vie", "vi", "Vietnamese"),
    Triple("tha", "th", "Thai"), Triple("tam", "ta", "Tamil"), Triple("tel", "te", "Telugu"), Triple("per", "fa", "Persian"), Triple("heb", "he", "Hebrew"),
    Triple("pol", "pl", "Polish"), Triple("dut", "nl", "Dutch"), Triple("swe", "sv", "Swedish"), Triple("nor", "no", "Norwegian"), Triple("dan", "da", "Danish"),
    Triple("fin", "fi", "Finnish"), Triple("gre", "el", "Greek"), Triple("rum", "ro", "Romanian"), Triple("hun", "hu", "Hungarian"), Triple("cze", "cs", "Czech"), Triple("ukr", "uk", "Ukrainian"),
)

@Composable fun SettingsScreen(activity: MainActivity, bottomPad: Dp, cleanup: () -> Unit = {}) {
    val roots by NovaRuntime.store.roots.collectAsState()
    var tick by remember { mutableIntStateOf(0) } // forces re-read of prefs after edits
    var langPicker by remember { mutableStateOf("") }
    val accent = LocalAccent.current
    fun num(k: String, d: Double) = NovaRuntime.number(k, d).also { tick }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPad), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Settings", "Make Nova feel right for the way you watch") }
        item {
            Group("Look & feel", Icons.Rounded.Palette) {
                Text("Accent colour", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Accents.forEachIndexed { i, a ->
                        val on = NovaRuntime.store.prefs.getInt("accent", 0) == i
                        val s by animateFloatAsState(if (on) 1.12f else 1f, spring(dampingRatio = .45f), label = "swatch")
                        Box(Modifier.size(44.dp).scale(s).clip(CircleShape).background(a.grad).border(if (on) 3.dp else 0.dp, Color.White, CircleShape)
                            .clickable { NovaRuntime.setPref("accent", i); activity.revision++ }, contentAlignment = Alignment.Center) {
                            if (on) Icon(Icons.Rounded.Check, a.name, tint = Color.White)
                        }
                    }
                }
                Text(accent.name, color = Nova.Dim, fontSize = 12.sp)
            }
        }
        item {
            Group("Playback", Icons.Rounded.PlayCircle) {
                Toggle("Hardware decoding", "Use the phone's video chip. Turn off if a video shows glitches.", "hwdec", true)
                Toggle("High-quality video", "Sharper scaling and debanding. Uses more battery.", "highQuality", false)
                Choice("Skip interval", listOf(3.0, 5.0, 10.0, 15.0, 30.0), num("seekStep", 5.0), { "${it.toInt()} s" }) { NovaRuntime.setNumber("seekStep", it); tick++ }
                Choice("Default speed", listOf(.5, .75, 1.0, 1.25, 1.5, 2.0), num("defaultSpeed", 1.0), { "${it.fmt()}×" }) {
                    NovaRuntime.setNumber("defaultSpeed", it); NovaRuntime.store.prefs.edit().remove("p:speed").apply(); tick++ }
                Choice("Maximum volume", listOf(100.0, 150.0, 200.0, 300.0), num("volumeMax", 200.0), { "${it.toInt()}%" }) {
                    NovaRuntime.setNumber("volumeMax", it); NovaRuntime.engine.setStr("volume-max", it.toInt().toString()); tick++ }
                Toggle("Remember where I stopped", "Resume each video from its own position", "rememberPosition", true)
                Toggle("Remember adjustments", "Keep speed, volume and subtitle size between videos", "rememberPlayerState", true) { if (!it) NovaRuntime.clearSticky() }
                Toggle("Next-up card", "Offer the next episode as this one ends", "nextUp", true)
                Toggle("Rotate to fit the video", "Landscape for wide videos, portrait for vertical ones", "landscape", true)
                Toggle("Background playback", "Keep listening after you leave Nova", "backgroundPlayback", true)
                Toggle("Picture-in-picture", "Float the video when you go home", "autoPip", true)
            }
        }
        item {
            Group("Subtitles", Icons.Rounded.Subtitles) {
                Slide("Text size", num("subScale", 1.0), .6f..2f, 27, { "${(it * 100).toInt()}%" }) { NovaRuntime.setNumber("subScale", it); NovaRuntime.store.prefs.edit().remove("p:sub-scale").apply() }
                Slide("Outline", num("subBorder", 2.0), 0f..5f, 24, { it.fmt() }) { NovaRuntime.setNumber("subBorder", it) }
                LangRow("Preferred audio language", NovaRuntime.text("audioLang").also { tick }) { langPicker = "audioLang" }
                LangRow("Preferred subtitle language", NovaRuntime.text("subLang").also { tick }) { langPicker = "subLang" }
                HorizontalDivider(color = Nova.Line, modifier = Modifier.padding(vertical = 8.dp))
                Toggle("Online subtitles", "Allow searches on OpenSubtitles.org — no account needed", "onlineSubs", true)
                Toggle("Fetch automatically", "Search when a video opens without subtitles", "autoSubs", true)
                LangRow("Search languages", NovaRuntime.text("subLangs", "eng").also { tick }.split(',').mapNotNull { c -> LANGUAGES.find { it.first == c }?.third }.joinToString(", ")) { langPicker = "subLangs" }
            }
        }
        item {
            Group("Automatic sync", Icons.Rounded.GraphicEq) {
                Text("Nova listens to the dialogue on your phone and lines the subtitles up with it. Nothing is uploaded.", color = Nova.Dim, fontSize = 13.sp, lineHeight = 19.sp)
                Choice("Mode", listOf(0.0, 1.0, 2.0), listOf("smart", "gentle", "offset").indexOf(NovaRuntime.text("subSyncMode", "smart").also { tick }).toDouble(),
                    { listOf("Smart", "Gentle", "Offset only")[it.toInt()] }) { NovaRuntime.setPref("subSyncMode", listOf("smart", "gentle", "offset")[it.toInt()]); tick++ }
                Toggle("Sync new downloads", "Unless it is an exact file match", "subSyncDownloads", false)
                Toggle("Sync local subtitles on open", "Checks external SRT/ASS files", "subSyncLocal", false)
                Toggle("Apply background syncs automatically", "Syncs you start are always applied when confident; uncertain ones ask first", "subSyncAutoApply", true)
                Toggle("Reuse approved corrections", "Remember fixes for each video", "subSyncReuse", true)
            }
        }
        item {
            Group("Library", Icons.Rounded.VideoLibrary) {
                if (roots.isEmpty()) Text("No watched folders yet.", color = Nova.Dim, fontSize = 13.sp)
                roots.forEach { root -> ListRow(Icons.Rounded.Folder, NovaRuntime.store.rootName(root), "Watched folder", accent.light, trailing = {
                    IconButton({ NovaRuntime.store.removeRoot(root) }) { Icon(Icons.Rounded.RemoveCircleOutline, "Remove folder", tint = Nova.Dim) } }) {} }
                ListRow(Icons.Rounded.CreateNewFolder, "Add a folder", null, Color(0xFF34D399)) { activity.addFolder() }
                ListRow(Icons.Rounded.PhoneAndroid, "Scan the whole phone", null, Color(0xFFFBBF24)) { activity.scanDevice() }
                ListRow(Icons.Rounded.CleaningServices, "Clean up watched videos", "Review and delete what you've finished", Color(0xFFFBBF24), onClick = cleanup)
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    // Android's "media management" access: deletes then need only Nova's own confirmation, like MX.
                    var manage by remember { mutableStateOf(android.provider.MediaStore.canManageMedia(activity)) }
                    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { manage = android.provider.MediaStore.canManageMedia(activity); onPauseOrDispose {} }
                    ListRow(Icons.Rounded.DeleteSweep, "Quick delete", if (manage) "On · Nova asks once, Android doesn't ask again" else "Skip Android's extra prompt when deleting videos", Nova.Danger,
                        trailing = { Switch(manage, null) }) {
                        runCatching { activity.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_MANAGE_MEDIA, android.net.Uri.parse("package:" + activity.packageName))) }
                            .onFailure { NovaRuntime.notice.value = "This phone doesn't offer media management" }
                    }
                }
                ListRow(Icons.Rounded.RestartAlt, "Reset player adjustments", "Speed, volume, subtitle size and position", Color(0xFFA78BFA)) { NovaRuntime.clearSticky(); NovaRuntime.notice.value = "Player adjustments reset" }
            }
        }
        item {
            Group("Gestures", Icons.Rounded.TouchApp) {
                listOf("Tap" to "Show or hide controls", "Double-tap left / right" to "Skip back / forward", "Double-tap centre" to "Play or pause",
                    "Swipe sideways" to "Seek through the video", "Swipe up/down on the left" to "Brightness", "Swipe up/down on the right" to "Volume, boost above 100%",
                    "Press and hold" to "2× speed while held", "Pinch" to "Zoom to fill the screen").forEach { (g, d) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Text(g, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(d, Modifier.weight(1.2f), fontSize = 13.sp, color = Nova.Dim) }
                }
            }
        }
        item {
            Group("About", Icons.Rounded.Info) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Color(0xFF6FB8FF), Color(0xFF2F5FE6)))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(32.dp), tint = Color.White)
                    }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Nova Player", fontWeight = FontWeight.Black, fontSize = 19.sp)
                        Text("Version ${BuildConfig.VERSION_NAME}", color = Nova.Dim, fontSize = 12.sp)
                    }
                }
                Row(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(14.dp)).background(accent.soft).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Favorite, null, Modifier.size(18.dp), tint = accent.light)
                    Text(buildAnnotatedString { append("Made by "); withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = accent.light)) { append("Sadik Hossain") } },
                        fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp))
                }
                Text("Free. No ads. No accounts. No tracking.\nUpdates are delivered automatically by Google Play.", color = Nova.Dim, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 4.dp))
                Text("Online subtitle searches send the video's title, episode number, chosen languages and a file fingerprint to OpenSubtitles.org. Your videos and audio never leave the phone.",
                    color = Nova.Dim, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
                Text("Subtitles by OpenSubtitles.org · Playback by mpv, FFmpeg and libass (GPL/LGPL) · Speech detection by libfvad (BSD).",
                    color = Nova.Dim, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp))
            }
        }
    }
    if (langPicker.isNotBlank()) LanguageSheet(langPicker) { langPicker = ""; tick++ }
}

private fun Double.fmt() = if (this % 1.0 == 0.0) toInt().toString() else "%.2f".format(this).trimEnd('0').trimEnd('.')

@Composable private fun Group(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    val accent = LocalAccent.current
    Card {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(accent.soft2), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = accent.light) }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
        }
        content()
    }
}

@Composable private fun Toggle(title: String, subtitle: String, key: String, default: Boolean, changed: (Boolean) -> Unit = {}) {
    var value by remember { mutableStateOf(NovaRuntime.pref(key, default)) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { value = !value; NovaRuntime.setPref(key, value); changed(value) }.padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 14.dp)) { Text(title, fontSize = 15.sp); Text(subtitle, color = Nova.Dim, fontSize = 12.sp, lineHeight = 16.sp) }
        Switch(value, { value = it; NovaRuntime.setPref(key, it); changed(it) }, colors = SwitchDefaults.colors(checkedTrackColor = LocalAccent.current.light, uncheckedTrackColor = Nova.Bg3))
    }
}

@Composable private fun Choice(title: String, options: List<Double>, current: Double, label: (Double) -> String, pick: (Double) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, fontSize = 15.sp)
        LazyRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(options) { o -> Pill(label(o), kotlin.math.abs(o - current) < 1e-6) { pick(o) } } }
    }
}

@Composable private fun Slide(title: String, value: Double, range: ClosedFloatingPointRange<Float>, steps: Int, label: (Double) -> String, set: (Double) -> Unit) {
    var v by remember { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(vertical = 4.dp)) {
        Row { Text(title, fontSize = 15.sp, modifier = Modifier.weight(1f)); Text(label(v.toDouble()), color = LocalAccent.current.light, fontWeight = FontWeight.Bold) }
        Slider(v, { v = it }, valueRange = range, steps = steps, onValueChangeFinished = { set(Math.round(v * 100) / 100.0) })
    }
}

@Composable private fun LangRow(title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            Text(value.ifBlank { "Automatic" }.let { v -> LANGUAGES.find { it.first == v.substringBefore(',') && !title.startsWith("Search") }?.third ?: v }, color = LocalAccent.current.light, fontSize = 13.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = Nova.Dim)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun LanguageSheet(key: String, done: () -> Unit) {
    val multi = key == "subLangs"
    var picked by remember { mutableStateOf(NovaRuntime.text(key, if (multi) "eng" else "").split(',').filter { it.isNotBlank() }.map { it.take(3) }) }
    ModalBottomSheet(done, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Nova.Bg3) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(if (multi) "Search languages" else "Preferred language", style = MaterialTheme.typography.headlineSmall)
            Text(if (multi) "Pick up to five, in order of preference." else "Nova selects this track automatically when a video has it.", color = Nova.Dim, fontSize = 13.sp, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                if (!multi) item { ListRow(if (picked.isEmpty()) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, "Automatic", "Use the video's default track", LocalAccent.current.light) { NovaRuntime.setPref(key, ""); done() } }
                items(LANGUAGES.distinctBy { if (multi) it.first else it.second }) { (c3, c2, name) ->
                    val on = c3 in picked
                    ListRow(if (on) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, name, if (multi && on) "#${picked.indexOf(c3) + 1}" else null, if (on) LocalAccent.current.light else Nova.Dim) {
                        if (multi) {
                            picked = if (on) picked - c3 else if (picked.size < 5) picked + c3 else picked.also { NovaRuntime.notice.value = "Up to five languages" }
                            NovaRuntime.setPref(key, picked.ifEmpty { listOf("eng") }.joinToString(","))
                        } else { NovaRuntime.setPref(key, "$c3,$c2"); done() }
                    }
                }
            }
        }
    }
}
