package com.sadik.novaplayer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.sadik.novaplayer.NovaRuntime
import com.sadik.novaplayer.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun timeLabel(seconds: Double): String {
    val n = if (seconds.isFinite()) seconds.coerceAtLeast(0.0).toInt() else 0
    return if (n >= 3600) "%d:%02d:%02d".format(n / 3600, n / 60 % 60, n % 60) else "%d:%02d".format(n / 60, n % 60)
}
fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
fun sizeLabel(bytes: Long) = when {
    bytes <= 0 -> ""
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
    else -> "${(bytes / 1048576).coerceAtLeast(1)} MB"
}
fun leftLabel(v: Video): String { val s = (v.duration - v.position).toInt().coerceAtLeast(0); val m = s / 60
    return when { m >= 60 -> "${m / 60} h ${m % 60} min left"; m >= 1 -> "$m min left"; else -> "$s s left" } }
/** "Nova.Test.Show.S01E01.720p.mkv" → "Nova Test Show S01E01 720p": dotted release names read like titles. */
fun prettyTitle(name: String): String { val base = name.substringBeforeLast('.').ifBlank { name }; return if (' ' !in base && (base.count { it == '.' } + base.count { it == '_' }) >= 2) base.replace(Regex("[._]+"), " ").trim() else base }
/** "Marvels.The.Punisher.S01-S02.1080p.NF.WEB-DL" reads better with spaces; ordinary names are left alone. */
fun prettyFolder(name: String) = if (' ' !in name && name.count { it == '.' } >= 2) name.replace('.', ' ') else name
/** Folder keys are relative paths; show the last segment, plus its parent when that alone is ambiguous ("Show › S01"). */
fun folderLabel(folder: String): String { val parts = folder.trimEnd('/').split('/').filter { it.isNotBlank() }; val last = parts.lastOrNull() ?: folder
    return if (parts.size >= 2 && Regex("""(?i)^(s\d{1,2}|season.*|disc.*|cd\d|extras?|subs?|\d+)$""").matches(last)) "${parts[parts.size - 2]} › $last" else last }
val Video.shortSide get() = if (width > 0 && height > 0) minOf(width, height) else height
fun resLabel(h: Int) = when { h >= 2000 -> "4K"; h >= 1400 -> "1440p"; h >= 1000 -> "1080p"; h >= 700 -> "720p"; h > 0 -> "${h}p"; else -> "" }

/* ------------------------------------------------------------------ thumbnails */

private val thumbCache = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
}
private val tintCache = HashMap<String, Color>()

/** Scroll-safe: a cache hit costs one map lookup; disk checks and decoding never touch the main thread.
 *  Keyed on width/duration too, so a poster that was missing appears once the scanner fills in its metadata. */
@Composable fun rememberThumb(video: Video): ImageBitmap? {
    val key = video.uri
    val bitmap by produceState(thumbCache.get(key), key, video.width, video.duration) {
        if (value == null) value = withContext(Dispatchers.IO) {
            val file = NovaRuntime.store.thumb(video)
            if (!file.exists()) null
            else runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap()?.also { it.prepareToDraw() } }.getOrNull()?.also { thumbCache.put(key, it) }
        }
    }
    return bitmap
}

/** Average colour of the poster, darkened — tints hero backdrops so each video brings its own mood. */
fun ImageBitmap.mood(key: String): Color = tintCache.getOrPut(key) {
    val small = Bitmap.createScaledBitmap(asAndroidBitmap(), 8, 8, true)
    var r = 0; var g = 0; var b = 0
    for (x in 0 until 8) for (y in 0 until 8) { val p = small.getPixel(x, y); r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255 }
    val c = Color(r / 64, g / 64, b / 64)
    val hsv = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], (hsv[1] * 1.3f).coerceAtMost(.85f), .55f)))
}

@Composable fun Poster(video: Video, modifier: Modifier = Modifier, corner: Dp = 16.dp, showProgress: Boolean = true, badge: Boolean = true) {
    val image = rememberThumb(video)
    val accent = LocalAccent.current
    Box(modifier.clip(RoundedCornerShape(corner)).background(Brush.linearGradient(listOf(Nova.Bg3, Nova.Card)))) {
        Crossfade(image, animationSpec = tween(350), label = "poster") { img ->
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Movie, null, Modifier.size(34.dp), tint = Color.White.copy(alpha = .16f))
            }
        }
        if (badge && video.duration > 0) Text(timeLabel(video.duration), Modifier.align(Alignment.BottomEnd).padding(7.dp)
            .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        val res = resLabel(video.shortSide)
        if (badge && video.shortSide >= 1400) Text(res, Modifier.align(Alignment.TopStart).padding(7.dp)
            .background(accent.grad, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color.White)
        if (showProgress && video.position > 10 && video.duration > 0) {
            val pct = (video.position / video.duration).toFloat().coerceIn(0f, 1f)
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .18f))) {
                Box(Modifier.fillMaxWidth(pct).fillMaxHeight().background(accent.grad))
            }
        }
    }
}

/* ------------------------------------------------------------------ primitives */

/** Springy press feedback shared by every tappable card. */
@Composable fun Modifier.bouncy(onLongClick: (() -> Unit)? = null, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .95f else 1f, spring(dampingRatio = .5f, stiffness = 600f), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
        .combinedClickable(source, null, onLongClick = onLongClick, onClick = onClick)
}

@Composable fun GradientButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val accent = LocalAccent.current
    Row(modifier.height(48.dp).clip(RoundedCornerShape(16.dp))
        .background(if (enabled) accent.grad else SolidColor(Nova.Bg3))
        .bouncy { if (enabled) onClick() }.padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = Color.White); Spacer(Modifier.width(8.dp)) }
        Text(text, color = if (enabled) Color.White else Nova.Dim, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}

@Composable fun GhostButton(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(modifier.height(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = .08f))
        .border(1.dp, Color.White.copy(alpha = .1f), RoundedCornerShape(16.dp)).bouncy(onClick = onClick).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp), tint = Nova.Text); Spacer(Modifier.width(8.dp)) }
        Text(text, color = Nova.Text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@Composable fun RoundIcon(icon: ImageVector, label: String, modifier: Modifier = Modifier, size: Dp = 44.dp, tint: Color = Nova.Text,
                          background: Color = Color.White.copy(alpha = .07f), onClick: () -> Unit) {
    Box(modifier.size(size).clip(CircleShape).background(background).bouncy(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, Modifier.size(size * .5f), tint = tint)
    }
}

@Composable fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null) Text(action, color = LocalAccent.current.light, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(6.dp))
    }
}

@Composable fun Pill(text: String, selected: Boolean, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val bg by animateColorAsState(if (selected) accent.light.copy(alpha = .22f) else Color.White.copy(alpha = .06f), label = "pill")
    val fg by animateColorAsState(if (selected) accent.light else Nova.Text, label = "pillText")
    Row(modifier.height(38.dp).clip(RoundedCornerShape(12.dp)).background(bg)
        .border(1.dp, if (selected) accent.line else Color.White.copy(alpha = .06f), RoundedCornerShape(12.dp))
        .bouncy(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, Modifier.size(17.dp), tint = fg); Spacer(Modifier.width(6.dp)) }
        Text(text, color = fg, fontSize = 13.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
    }
}

@Composable fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Nova.Card)
        .border(1.dp, Nova.Line, RoundedCornerShape(20.dp)).padding(18.dp), content = content)
}

@Composable fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    val accent = LocalAccent.current
    val pulse = rememberInfiniteTransition(label = "orb")
    val glow by pulse.animateFloat(.35f, .8f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "glow")
    val spin by pulse.animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "spin")
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.dp).graphicsLayer { rotationZ = spin }.drawBehind {
                drawCircle(Brush.sweepGradient(listOf(accent.light.copy(alpha = 0f), accent.light.copy(alpha = glow), accent.deep.copy(alpha = 0f))), style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()))
            })
            Box(Modifier.size(104.dp).clip(RoundedCornerShape(34.dp)).background(Brush.linearGradient(listOf(accent.light.copy(alpha = .3f), Nova.Bg3))), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(52.dp), tint = accent.light)
            }
        }
        Spacer(Modifier.height(22.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Nova.Dim, textAlign = TextAlign.Center, lineHeight = 22.sp, fontSize = 14.sp)
        if (action != null) { Spacer(Modifier.height(24.dp)); action() }
    }
}

@Composable fun ListRow(icon: ImageVector, title: String, subtitle: String? = null, tint: Color = Nova.Text, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(20.dp), tint = tint)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, fontSize = 12.sp, color = Nova.Dim, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing?.invoke()
    }
}

@Composable fun InputDialog(title: String, hint: String, initial: String = "", confirm: String = "Continue", dismiss: () -> Unit, submit: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = dismiss, containerColor = Nova.Bg3, shape = RoundedCornerShape(26.dp),
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { OutlinedTextField(text, { text = it }, placeholder = { Text(hint) }, singleLine = true, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton({ submit(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}

/** Nova's own delete confirmation — used only where Android won't show its system prompt. */
@Composable fun DeleteDialog(items: List<Video>, dismiss: () -> Unit, confirm: () -> Unit) {
    val names = items.take(3).joinToString("\n") { "• " + prettyTitle(it.title) } + if (items.size > 3) "\n…and ${items.size - 3} more" else ""
    val size = sizeLabel(items.sumOf { it.size })
    AlertDialog(onDismissRequest = dismiss, containerColor = Nova.Bg3, shape = RoundedCornerShape(26.dp),
        icon = { Icon(Icons.Rounded.DeleteForever, null, tint = Nova.Danger) },
        title = { Text(if (items.size == 1) "Delete this video?" else "Delete ${items.size} videos?", fontWeight = FontWeight.Bold) },
        text = { Text(names + "\n\nThe file" + (if (items.size == 1) " is" else "s are") + " permanently removed from your phone" + (if (size.isNotBlank()) " · frees $size." else "."), color = Nova.Dim, lineHeight = 20.sp) },
        confirmButton = { TextButton(confirm) { Text("Delete", color = Nova.Danger, fontWeight = FontWeight.Bold) } },
        dismissButton = { TextButton(dismiss) { Text("Cancel") } })
}
