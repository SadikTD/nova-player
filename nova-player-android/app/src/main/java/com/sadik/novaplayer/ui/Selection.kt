@file:OptIn(ExperimentalMaterial3Api::class)
package com.sadik.novaplayer.ui

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.sadik.novaplayer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/* ------------------------------------------------------------------ multi-select */

/** Which videos are ticked. Long-press anything to start; every row and folder reads this. */
class Selection(val picked: Set<String>, private val set: (Set<String>) -> Unit, private val scope: Scope) {
    /** The videos on the current screen — what "Select all" means. */
    class Scope { var videos: List<Video> = emptyList() }
    val active get() = picked.isNotEmpty()
    fun has(v: Video) = v.uri in picked
    fun hasAll(vs: List<Video>) = vs.isNotEmpty() && vs.all { it.uri in picked }
    fun toggle(vs: List<Video>) { val uris = vs.map { it.uri }; set(if (hasAll(vs)) picked - uris.toSet() else picked + uris) }
    fun toggle(v: Video) = toggle(listOf(v))
    fun clear() = set(emptySet())
    fun selectAll() = set(picked + scope.videos.map { it.uri })
    val allSelected get() = scope.videos.isNotEmpty() && scope.videos.all { it.uri in picked }
    fun onScreen(videos: List<Video>) { scope.videos = videos }
}
val LocalSelection = compositionLocalOf<Selection?> { null }

/** The tick shown on posters and rows while selecting. */
@Composable fun SelectMark(on: Boolean, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val scale by androidx.compose.animation.core.animateFloatAsState(if (on) 1f else .85f, androidx.compose.animation.core.spring(dampingRatio = .45f, stiffness = 700f), label = "tick")
    Box(modifier.size(26.dp).graphicsLayer { scaleX = scale; scaleY = scale }.clip(CircleShape).background(if (on) accent.light else Color.Black.copy(alpha = .45f))
        .border(2.dp, if (on) accent.light else Color.White.copy(alpha = .8f), CircleShape), contentAlignment = Alignment.Center) {
        if (on) Icon(Icons.Rounded.Check, "Selected", Modifier.size(18.dp), tint = Color.White)
    }
}

@Composable fun SelectionBar(activity: MainActivity, videos: List<Video>, sel: Selection, pickPlaylist: (List<Video>) -> Unit) {
    val accent = LocalAccent.current
    val items = remember(sel.picked, videos) { videos.filter { it.uri in sel.picked } }
    val allWatched = items.isNotEmpty() && items.all { it.isWatched }
    Column(Modifier.fillMaxWidth().shadow(24.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp)).background(Nova.Bg3)
        .border(1.dp, accent.line.copy(alpha = .4f), RoundedCornerShape(26.dp)).padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ sel.clear() }) { Icon(Icons.Rounded.Close, "Cancel selection", tint = Nova.Text) }
            Column(Modifier.weight(1f)) {
                AnimatedContent(items.size, transitionSpec = { (slideInVertically { if (targetState > initialState) it else -it } + fadeIn()).togetherWith(slideOutVertically { if (targetState > initialState) -it else it } + fadeOut()) }, label = "count") { n ->
                    Text("$n selected", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
                Text(listOf(sizeLabel(items.sumOf { it.size }), items.sumOf { it.duration }.takeIf { it > 0 }?.let { timeLabel(it) } ?: "").filter { it.isNotBlank() }.joinToString(" · "), color = Nova.Dim, fontSize = 12.sp)
            }
            TextButton({ if (sel.allSelected) sel.clear() else sel.selectAll() }) { Text(if (sel.allSelected) "Select none" else "Select all", color = accent.light, fontWeight = FontWeight.SemiBold) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceAround) {
            BarAction(Icons.Rounded.PlayArrow, "Play", accent.light) { val q = items.sortedWith { a, b -> naturalCompare(a.folder + "/" + a.title, b.folder + "/" + b.title) }; sel.clear(); activity.play(q.first(), q.map { it.uri }) }
            BarAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Playlist", Color(0xFFA78BFA)) { pickPlaylist(items) }
            BarAction(if (allWatched) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (allWatched) "Unwatched" else "Watched", Color(0xFF34D399)) {
                NovaRuntime.store.markWatched(items.map { it.uri }, !allWatched)
                NovaRuntime.notice.value = "Marked ${plural(items.size, "video")} as ${if (allWatched) "unwatched" else "watched"}"; sel.clear()
            }
            BarAction(Icons.Rounded.Share, "Share", Color(0xFF22D3EE)) { activity.share(items) }
            BarAction(Icons.Rounded.DeleteForever, "Delete", Nova.Danger) { activity.askDelete(items); sel.clear() }
        }
    }
}

@Composable private fun BarAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).bouncy(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(13.dp)).background(tint.copy(alpha = .14f)), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp), tint = tint) }
        Text(label, fontSize = 11.sp, color = Nova.Text, modifier = Modifier.padding(top = 4.dp), maxLines = 1)
    }
}

/** "Add to playlist" for one or many videos. */
@Composable fun PlaylistPicker(items: List<Video>, lists: List<VideoList>, dismiss: () -> Unit, added: () -> Unit = {}) {
    var naming by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    val uris = items.map { it.uri }
    if (naming) { InputDialog("New playlist", "Playlist name", confirm = "Create", dismiss = dismiss) { name ->
        NovaRuntime.store.addPlaylist(name, uris); NovaRuntime.notice.value = "Created “$name” with ${plural(items.size, "video")}"; added(); dismiss() }; return }
    ModalBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Nova.Bg3, dragHandle = { BottomSheetDefaults.DragHandle(color = Nova.Dim) }) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Add ${plural(items.size, "video")} to…", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 10.dp))
            ListRow(Icons.Rounded.Add, "New playlist", null, accent.light) { naming = true }
            lists.forEach { l ->
                val already = uris.all { it in l.items }
                ListRow(if (already) Icons.Rounded.CheckCircle else Icons.AutoMirrored.Rounded.PlaylistAdd, l.name, plural(l.items.size, "video") + if (already) " · already added" else "", if (already) accent.light else Color(0xFFA78BFA)) {
                    NovaRuntime.store.addToPlaylist(l.id, uris); NovaRuntime.notice.value = "Added to ${l.name}"; added(); dismiss()
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ storage clean-up */

/** Watched videos that could be deleted; shown on Home once they add up to something worth freeing. */
fun cleanupCandidates(videos: List<Video>) = videos.filter { it.isWatched && it.size > 0 && it.uri.startsWith("content://") }
private const val CLEANUP_MIN = 500L * 1024 * 1024

@Composable fun StorageCard(videos: List<Video>, modifier: Modifier = Modifier, open: () -> Unit) {
    val watched = remember(videos) { cleanupCandidates(videos) }
    val bytes = watched.sumOf { it.size }
    var snoozed by remember { mutableLongStateOf(NovaRuntime.store.prefs.getLong("cleanupSnooze", 0L)) }
    // Dismissing hides the card until another gigabyte of watched videos has piled up.
    AnimatedVisibility(bytes >= CLEANUP_MIN && bytes > snoozed + (1L shl 30), modifier, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        val accent = LocalAccent.current
        val free = remember { runCatching { android.os.StatFs(android.os.Environment.getExternalStorageDirectory().path).let { it.availableBytes to it.totalBytes } }.getOrNull() }
        Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(22.dp)).background(Nova.Card).border(1.dp, Nova.Line, RoundedCornerShape(22.dp)).bouncy(onClick = open).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFFBBF24).copy(alpha = .15f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.CleaningServices, null, tint = Color(0xFFFBBF24)) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text("Free up ${sizeLabel(bytes)}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${plural(watched.size, "video")} you've already watched", color = Nova.Dim, fontSize = 12.sp)
                }
                IconButton({ snoozed = bytes; NovaRuntime.store.prefs.edit().putLong("cleanupSnooze", bytes).apply() }) { Icon(Icons.Rounded.Close, "Not now", tint = Nova.Dim) }
            }
            if (free != null && free.second > 0) {
                val used = 1f - free.first / free.second.toFloat(); val after = (1f - (free.first + bytes) / free.second.toFloat()).coerceAtLeast(0f)
                Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f))) {
                    Box(Modifier.fillMaxWidth(used).fillMaxHeight().background(Color(0xFFFBBF24).copy(alpha = .55f)))
                    Box(Modifier.fillMaxWidth(after).fillMaxHeight().background(accent.grad))
                }
                Text("${sizeLabel(free.first)} free of ${sizeLabel(free.second)} · ${sizeLabel(free.first + bytes)} after clean-up", color = Nova.Dim, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Watched videos grouped by folder, all ticked; untick what you want to keep, then delete in one go. */
@Composable fun CleanupScreen(activity: MainActivity, videos: List<Video>, bottomPad: Dp, back: () -> Unit) {
    val all = remember(videos) { cleanupCandidates(videos) }
    val groups = remember(all) { all.groupBy { it.folder }.toList().sortedByDescending { (_, v) -> v.sumOf { it.size } } }
    var keep by remember { mutableStateOf(setOf<String>()) }
    val chosen = all.filter { it.uri !in keep }
    val accent = LocalAccent.current
    val sel = Selection(all.map { it.uri }.toSet() - keep, { now -> keep = all.map { it.uri }.toSet() - now }, remember { Selection.Scope() }).also { it.onScreen(all) }
    CompositionLocalProvider(LocalSelection provides sel) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPad + 80.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { ScreenHeader("Clean up", if (all.isEmpty()) "Nothing watched to clear" else "${plural(all.size, "watched video")} · ${sizeLabel(all.sumOf { it.size })}", back) {
                    if (all.isNotEmpty()) TextButton({ keep = if (keep.isEmpty()) all.map { it.uri }.toSet() else emptySet() }) { Text(if (keep.isEmpty()) "Keep all" else "Select all", color = accent.light) } } }
                if (all.isEmpty()) item { EmptyState(Icons.Rounded.CleaningServices, "All tidy", "Videos you finish watching show up here, ready to clear.") }
                groups.forEach { (folder, rows) ->
                    item(key = "g:$folder") {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp)).clickable { sel.toggle(rows) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(prettyFolder(folderLabel(folder)), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${plural(rows.size, "video")} · ${sizeLabel(rows.sumOf { it.size })}", color = Nova.Dim, fontSize = 12.sp)
                            }
                            SelectMark(sel.hasAll(rows))
                        }
                    }
                    items(rows.sortedWith { a, b -> naturalCompare(a.title, b.title) }, key = { "c" + it.uri }) { v -> VideoRow(v, Modifier.animateItem(), onMenu = {}) {} }
                }
            }
            AnimatedVisibility(all.isNotEmpty(), Modifier.align(Alignment.BottomCenter).padding(start = 18.dp, end = 18.dp, bottom = bottomPad - 4.dp), enter = slideInVertically { it } + fadeIn(), exit = fadeOut()) {
                Row(Modifier.fillMaxWidth().height(54.dp).shadow(16.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
                    .background(if (chosen.isEmpty()) Nova.Bg3 else Nova.Danger).bouncy { if (chosen.isNotEmpty()) activity.askDelete(chosen) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.DeleteForever, null, tint = Color.White)
                    Text(if (chosen.isEmpty()) "Nothing selected" else "Delete ${plural(chosen.size, "video")} · free ${sizeLabel(chosen.sumOf { it.size })}",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ file details */

private data class Details(val rows: List<Pair<String, String>>)

@Composable fun DetailsSheet(v: Video, dismiss: () -> Unit) {
    val context = LocalContext.current
    val details by produceState<Details?>(null, v.uri) { value = withContext(Dispatchers.IO) { runCatching { inspect(context, v) }.getOrNull() } }
    ModalBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Nova.Bg3, dragHandle = { BottomSheetDefaults.DragHandle(color = Nova.Dim) }) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poster(v, Modifier.width(110.dp).aspectRatio(1.6f), corner = 12.dp)
                Text(v.title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(14.dp))
            val basic = buildList {
                add("Location" to (if (v.uri.startsWith("http")) v.uri else "/" + v.folder.trim('/')))
                if (v.size > 0) add("Size" to "${sizeLabel(v.size)} (${"%,d".format(v.size)} bytes)")
                if (v.duration > 0) add("Length" to timeLabel(v.duration))
                if (v.width > 0) add("Resolution" to "${v.width} × ${v.height}" + resLabel(v.shortSide).let { if (it.isNotBlank()) " · $it" else "" })
                if (v.added > 0) add("Added" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(v.added)))
                if (v.played > 0) add("Last watched" to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(v.played)) + if (v.position > 10 && !v.isWatched) " · stopped at ${timeLabel(v.position)}" else if (v.isWatched) " · finished" else "")
            }
            basic.forEach { (k, value) -> DetailRow(k, value) }
            Text("STREAMS", style = Kicker, color = Nova.Dim, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            val d = details
            if (d == null) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape), color = LocalAccent.current.light, trackColor = Nova.Card)
            else if (d.rows.isEmpty()) Text("Android can't read this file's streams; Nova's player still can.", color = Nova.Dim, fontSize = 13.sp)
            else d.rows.forEach { (k, value) -> DetailRow(k, value) }
        }
    }
}

@Composable private fun DetailRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(k, color = Nova.Dim, fontSize = 13.sp, modifier = Modifier.width(104.dp))
        Text(v, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

private fun codecName(mime: String) = when (mime.lowercase()) {
    "video/hevc" -> "HEVC (H.265)"; "video/avc" -> "H.264 (AVC)"; "video/av01" -> "AV1"; "video/x-vnd.on2.vp9" -> "VP9"; "video/x-vnd.on2.vp8" -> "VP8"
    "video/mp4v-es" -> "MPEG-4"; "video/mpeg2" -> "MPEG-2"; "video/dolby-vision" -> "Dolby Vision"
    "audio/mp4a-latm" -> "AAC"; "audio/ac3" -> "Dolby Digital (AC-3)"; "audio/eac3" -> "Dolby Digital Plus (E-AC-3)"; "audio/eac3-joc" -> "Dolby Atmos (E-AC-3)"
    "audio/opus" -> "Opus"; "audio/vorbis" -> "Vorbis"; "audio/mpeg" -> "MP3"; "audio/flac" -> "FLAC"; "audio/vnd.dts", "audio/dts" -> "DTS"; "audio/vnd.dts.hd" -> "DTS-HD"; "audio/true-hd" -> "Dolby TrueHD"
    "audio/raw" -> "PCM"; "text/x-ssa" -> "ASS/SSA"; "application/x-subrip" -> "SRT"; "text/vtt" -> "WebVTT"
    else -> mime.substringAfter('/').uppercase()
}
private fun channels(n: Int) = when (n) { 1 -> "mono"; 2 -> "stereo"; 6 -> "5.1"; 8 -> "7.1"; else -> "$n ch" }
private fun bitrate(bps: Long) = if (bps >= 1_000_000) "%.1f Mb/s".format(bps / 1e6) else "${bps / 1000} kb/s"

private fun inspect(context: android.content.Context, v: Video): Details {
    val rows = mutableListOf<Pair<String, String>>()
    if (v.uri.startsWith("http")) return Details(rows)
    val uri = Uri.parse(v.uri)
    runCatching {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { rows += "Container" to (when (it) { "video/x-matroska" -> "Matroska (MKV)"; "video/mp4" -> "MP4"; "video/webm" -> "WebM"; "video/avi", "video/x-msvideo" -> "AVI"; "video/mp2ts" -> "MPEG-TS"; else -> it.substringAfter('/').uppercase() }) }
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }?.let { rows += "Bitrate" to bitrate(it) }
        } finally { runCatching { r.release() } }
    }
    val x = MediaExtractor()
    try {
        x.setDataSource(context, uri, null)
        var audio = 0; var subs = 0
        for (i in 0 until x.trackCount) {
            val f = x.getTrackFormat(i); val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            val lang = runCatching { f.getString(MediaFormat.KEY_LANGUAGE) }.getOrNull()?.takeIf { it.isNotBlank() && it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
            when {
                mime.startsWith("video/") -> {
                    val fps = runCatching { if (f.containsKey(MediaFormat.KEY_FRAME_RATE)) (runCatching { f.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }.getOrNull() ?: f.getFloat(MediaFormat.KEY_FRAME_RATE)) else null }.getOrNull()
                    val hdr = runCatching { if (f.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) when (f.getInteger(MediaFormat.KEY_COLOR_TRANSFER)) { MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10"; MediaFormat.COLOR_TRANSFER_HLG -> "HLG"; else -> null } else null }.getOrNull()
                    rows += "Video" to listOfNotNull(codecName(mime), fps?.takeIf { it > 0 }?.let { "%.3f".format(it).trimEnd('0').trimEnd('.') + " fps" }, hdr).joinToString(" · ")
                }
                mime.startsWith("audio/") -> { audio++
                    val ch = runCatching { f.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }.getOrNull(); val hz = runCatching { f.getInteger(MediaFormat.KEY_SAMPLE_RATE) }.getOrNull()
                    rows += "Audio $audio" to listOfNotNull(codecName(mime), ch?.let(::channels), hz?.let { "${it / 1000.0} kHz".replace(".0 ", " ") }, lang).joinToString(" · ")
                }
                else -> { subs++; rows += "Subtitle $subs" to listOfNotNull(codecName(mime), lang).joinToString(" · ") }
            }
        }
    } catch (_: Exception) { /* Android can't parse every container; mpv still plays it. */ } finally { x.release() }
    return Details(rows)
}
