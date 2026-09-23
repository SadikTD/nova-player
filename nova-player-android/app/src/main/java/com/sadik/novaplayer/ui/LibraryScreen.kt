@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
package com.sadik.novaplayer.ui

import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.*
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.sadik.novaplayer.*
import kotlinx.coroutines.delay
import java.util.Calendar

private val SORTS = listOf("name" to "Name", "added" to "Date added", "played" to "Recently played", "largest" to "Size", "longest" to "Most videos")
private data class Tab(val label: String, val icon: ImageVector)
private val TABS = listOf(Tab("Home", Icons.Rounded.Home), Tab("Playlists", Icons.AutoMirrored.Rounded.PlaylistPlay),
    Tab("History", Icons.Rounded.History), Tab("Settings", Icons.Rounded.Tune))

fun Modifier.bleed(h: Dp) = layout { m, c ->
    val p = h.roundToPx()
    val pl = m.measure(c.copy(minWidth = c.minWidth + 2 * p, maxWidth = c.maxWidth + 2 * p))
    layout(pl.width - 2 * p, pl.height) { pl.place(-p, 0) }
}

/** When the current screen appeared; [rise] only animates the entrance, never rows that scroll in later. */
val LocalScreenStart = compositionLocalOf { 0L }

/** Items float up into place as they appear — staggered for the first screenful.
 *  Rows arriving later by scrolling appear instantly: animating those (with a stagger delay!) is what made lists feel laggy. */
@Composable fun Modifier.rise(index: Int): Modifier {
    val start = LocalScreenStart.current
    val entering = remember { index < 10 && SystemClock.uptimeMillis() - start < 450 }
    if (!entering) return this
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(index.coerceIn(0, 9) * 40L); a.animateTo(1f, spring(dampingRatio = .82f, stiffness = 260f)) }
    val lift = with(LocalDensity.current) { 34.dp.toPx() }
    return graphicsLayer { alpha = a.value; translationY = (1 - a.value) * lift; scaleX = .96f + .04f * a.value; scaleY = scaleX }
}

fun List<Video>.continueWatching() = filter { it.position > 10 && it.duration > 0 && it.position / it.duration < .97 }.sortedByDescending { it.played }.take(12)
fun sorted(rows: List<Video>, sort: String) = when (sort) {
    "added" -> rows.sortedByDescending { it.added }; "played" -> rows.sortedByDescending { it.played }
    "longest" -> rows.sortedByDescending { it.duration }; "largest" -> rows.sortedByDescending { it.size }
    else -> rows.sortedWith { a, b -> naturalCompare(a.title, b.title) }
}

@Composable fun LibraryScreen(activity: MainActivity, videos: List<Video>, lists: List<VideoList>, playing: Playing) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var folder by rememberSaveable { mutableStateOf<String?>(null) }
    var listId by rememberSaveable { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<Video?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }
    var cleanup by rememberSaveable { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var playlistFor by remember { mutableStateOf<List<Video>>(emptyList()) }
    var detailsFor by remember { mutableStateOf<Video?>(null) }
    var renameFor by remember { mutableStateOf<Video?>(null) }
    val selScope = remember { Selection.Scope() }
    val sel = Selection(picked, { picked = it }, selScope)
    val tree = remember(videos) { buildFolderTree(videos) }
    val node = folder?.let { tree.find(it) }
    LaunchedEffect(node) { if (folder != null && node == null) folder = null } // folder vanished (deleted/moved)
    BackHandler(folder != null || listId != null || tab != 0 || cleanup) {
        when { cleanup -> cleanup = false; folder != null -> folder = tree.parentOf(folder!!)?.path?.takeIf { it.isNotEmpty() }; listId != null -> listId = null; else -> tab = 0 }
    }
    // Registered last so it wins: Back first leaves selection mode.
    BackHandler(sel.active) { sel.clear() }
    // Deleted or renamed videos drop out of the selection by themselves.
    LaunchedEffect(videos) { if (picked.isNotEmpty()) { val live = videos.mapTo(HashSet()) { it.uri }; picked = picked.filterTo(HashSet()) { it in live } } }
    val play: (Video, List<String>) -> Unit = { v, q -> activity.play(v, q) }
    // The selection bar is taller than the nav bar: leave room so the last rows can scroll clear of it.
    val bottomPad = when { sel.active -> 196.dp; playing.video != null -> 176.dp; else -> 108.dp }

    Box(Modifier.fillMaxSize().background(Nova.Bg)) {
        Aurora()
        val key = when { tab == 0 && cleanup -> "c:"; tab == 0 && folder != null -> "f:$folder"; tab == 1 && listId != null -> "p:$listId"; else -> "t:$tab" }
        AnimatedContent(key, transitionSpec = {
            val depth = { k: String -> if (k.startsWith("t:")) 0 else 1 + k.count { it == '/' } }

            if (targetState.startsWith("t:") && initialState.startsWith("t:")) fadeIn(tween(220)).togetherWith(fadeOut(tween(120)))
            else {
                val forward = depth(targetState) >= depth(initialState)
                (slideInHorizontally(tween(340, easing = FastOutSlowInEasing)) { if (forward) it / 3 else -it / 3 } + fadeIn(tween(260)))
                    .togetherWith(slideOutHorizontally(tween(300)) { if (forward) -it / 6 else it / 6 } + fadeOut(tween(160)))
            }
        }, label = "screen") { k ->
            val shown = remember { SystemClock.uptimeMillis() }
            LaunchedEffect(Unit) { picked = emptySet() } // a new screen starts with nothing ticked
            CompositionLocalProvider(LocalScreenStart provides shown, LocalSelection provides sel) { when {
                k == "c:" -> CleanupScreen(activity, videos, bottomPad, back = { cleanup = false })
                k.startsWith("f:") -> tree.find(k.removePrefix("f:"))?.let { n ->
                    FolderScreen(n, bottomPad, open = { folder = it }, back = { folder = tree.parentOf(n.path)?.path?.takeIf { it.isNotEmpty() } },
                        onMenu = { menuFor = it }, onDelete = { activity.askDelete(it) }, play = play)
                }
                k.startsWith("p:") -> lists.find { it.id == k.removePrefix("p:") }?.let { list ->
                    VideoListScreen(list.name, list.items.mapNotNull { id -> videos.find { it.uri == id } }, bottomPad, back = { listId = null }, onMenu = { menuFor = it },
                        play = { v, _ -> play(v, list.items) }, playlist = list)
                }
                k == "t:1" -> PlaylistsScreen(videos, lists, bottomPad, open = { listId = it }, create = { dialog = "playlist" })
                k == "t:2" -> HistoryScreen(videos, bottomPad, onMenu = { menuFor = it }, play = play)
                k == "t:3" -> SettingsScreen(activity, bottomPad, cleanup = { tab = 0; folder = null; cleanup = true })
                else -> HomeScreen(activity, videos, tree, playing, bottomPad, onMenu = { menuFor = it }, open = { folder = it }, add = { addOpen = true }, cleanup = { cleanup = true }, play = play)
            } }
        }
        // Keeps scrolled content from running under the status bar.
        Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(Brush.verticalGradient(listOf(Nova.Bg, Nova.Bg.copy(alpha = .88f)))))
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
          AnimatedContent(sel.active && key != "c:", transitionSpec = { (slideInVertically { it / 2 } + fadeIn()).togetherWith(slideOutVertically { it / 2 } + fadeOut()) }, label = "bottomBar") { selecting ->
           if (selecting) SelectionBar(activity, videos, sel) { playlistFor = it } else Column {
            AnimatedVisibility(playing.video != null, enter = slideInVertically(spring(dampingRatio = .75f)) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                MiniPlayer(playing, open = { activity.showPlayer() })
            }
            Spacer(Modifier.height(8.dp))
            NavBar(tab) { if (it == tab) { folder = null; listId = null; cleanup = false } else tab = it }
           }
          }
        }
    }

    if (addOpen) ModalBottomSheet({ addOpen = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Nova.Bg3, dragHandle = { BottomSheetDefaults.DragHandle(color = Nova.Dim) }) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Add to Nova", style = MaterialTheme.typography.headlineSmall)
            Text("Everything stays on your phone.", color = Nova.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            val accent = LocalAccent.current
            ListRow(Icons.Rounded.VideoFile, "Open videos", "Pick one or more files", accent.light) { addOpen = false; activity.openVideos() }
            ListRow(Icons.Rounded.CreateNewFolder, "Add a folder", "For SD cards, USB drives and other storage", Color(0xFF34D399)) { addOpen = false; activity.addFolder() }
            ListRow(Icons.Rounded.PhoneAndroid, "Scan this phone", "Find every video on the device", Color(0xFFFBBF24)) { addOpen = false; activity.scanDevice() }
            ListRow(Icons.Rounded.Stream, "Play a network stream", "HTTP, HLS, RTSP, SMB, FTP…", Color(0xFFFB7185)) { addOpen = false; dialog = "url" }
        }
    }
    if (dialog == "url") InputDialog("Play a network stream", "https://…", confirm = "Play", dismiss = { dialog = "" }) { value ->
        if (Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(value)) { val v = NovaRuntime.store.import(Uri.parse(value)); activity.play(v, listOf(v.uri)) }
        else NovaRuntime.notice.value = "Enter a full address like https://…"
        dialog = ""
    }
    if (dialog == "playlist") InputDialog("New playlist", "Playlist name", confirm = "Create", dismiss = { dialog = "" }) { NovaRuntime.store.addPlaylist(it); dialog = "" }
    menuFor?.let { v -> VideoMenu(activity, v, lists, dismiss = { menuFor = null }, newPlaylist = { menuFor = null; dialog = "playlist" },
        select = { menuFor = null; picked = setOf(v.uri) }, details = { menuFor = null; detailsFor = v }, rename = { menuFor = null; renameFor = v }) }
    if (playlistFor.isNotEmpty()) PlaylistPicker(playlistFor, lists, dismiss = { playlistFor = emptyList() }, added = { picked = emptySet() })
    detailsFor?.let { v -> DetailsSheet(v) { detailsFor = null } }
    renameFor?.let { v -> InputDialog("Rename", "File name", v.title.substringBeforeLast('.').ifBlank { v.title }, "Rename", { renameFor = null }) { activity.rename(v, it); renameFor = null } }
}

@Composable private fun Aurora() {
    val accent = LocalAccent.current
    // The glow drifts so slowly (a full sweep takes 18 s) that 8 updates a second look identical to 120 —
    // and the screen is no longer repainted every frame while you scroll.
    var drift by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val begin = SystemClock.uptimeMillis()
        while (true) { val t = ((SystemClock.uptimeMillis() - begin) % 36000L) / 18000f; drift = if (t <= 1f) t else 2f - t; delay(125) }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        val a = Offset(size.width * (.1f + .5f * drift), -size.height * .02f)
        val b = Offset(size.width * (1f - .4f * drift), size.height * .45f)
        drawCircle(Brush.radialGradient(listOf(accent.light.copy(alpha = .16f), Color.Transparent), center = a, radius = size.width * .95f), radius = size.width * .95f, center = a)
        drawCircle(Brush.radialGradient(listOf(accent.deep.copy(alpha = .12f), Color.Transparent), center = b, radius = size.width * .8f), radius = size.width * .8f, center = b)
    }
}

/* ------------------------------------------------------------------ nav + mini player */

@Composable private fun NavBar(selected: Int, pick: (Int) -> Unit) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().height(66.dp).shadow(24.dp, RoundedCornerShape(26.dp), spotColor = Color.Black)
        .clip(RoundedCornerShape(26.dp)).background(Nova.Bg3).border(1.dp, Color.White.copy(alpha = .08f), RoundedCornerShape(26.dp))
        .padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
        TABS.forEachIndexed { i, tab ->
            val on = i == selected
            val tint by animateColorAsState(if (on) accent.light else Nova.Dim, label = "tabTint")
            val lift by animateFloatAsState(if (on) 1.08f else 1f, spring(dampingRatio = .5f), label = "tabLift")
            Row(Modifier.height(46.dp).clip(RoundedCornerShape(17.dp)).background(if (on) accent.soft2 else Color.Transparent)
                .bouncy { pick(i) }.animateContentSize(spring(dampingRatio = .7f, stiffness = 500f)).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(tab.icon, tab.label, Modifier.size(24.dp).scale(lift), tint = tint)
                if (on) Text(tab.label, color = accent.light, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
            }
        }
    }
}

@Composable private fun MiniPlayer(s: Playing, open: () -> Unit) {
    val v = s.video ?: return
    val accent = LocalAccent.current
    Column(Modifier.fillMaxWidth().shadow(20.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
        .background(Nova.Bg3).background(Brush.horizontalGradient(listOf(accent.deep.copy(alpha = .55f), Color.Transparent))).border(1.dp, accent.line.copy(alpha = .3f), RoundedCornerShape(22.dp))
        .bouncy(onClick = open)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Poster(v, Modifier.width(84.dp).height(50.dp), corner = 13.dp, showProgress = false, badge = false)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(prettyTitle(v.title), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Equalizer(!s.paused && !s.loading)
                    Text(if (s.paused) "Paused · ${timeLabel(s.position)}" else "${timeLabel(s.position)} / ${timeLabel(s.duration)}", color = Nova.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }
            // Play returns to the full player (video, not just sound); pause stays put.
            Box(Modifier.size(44.dp).clip(CircleShape).background(accent.grad).bouncy { if (s.paused) { open(); NovaRuntime.pause(false) } else NovaRuntime.pause(true) }, contentAlignment = Alignment.Center) {
                Icon(if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, "Play or pause", Modifier.size(26.dp), tint = Color.White)
            }
            IconButton({ NovaRuntime.close() }) { Icon(Icons.Rounded.Close, "Close", tint = Nova.Dim) }
        }
        val pct = if (s.duration > 0) (s.position / s.duration).toFloat().coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .08f))) { Box(Modifier.fillMaxWidth(pct).fillMaxHeight().background(accent.grad)) }
    }
}

@Composable fun Equalizer(active: Boolean, color: Color = LocalAccent.current.light) {
    val t = rememberInfiniteTransition(label = "eq")
    val bars = List(3) { i -> t.animateFloat(.25f, 1f, infiniteRepeatable(tween(380 + i * 140, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$i") }
    Row(Modifier.height(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        bars.forEach { b -> Box(Modifier.width(3.dp).fillMaxHeight(if (active) b.value else .3f).clip(RoundedCornerShape(2.dp)).background(color)) }
    }
}

/* ------------------------------------------------------------------ shared list chrome */

@Composable private fun Refreshable(content: @Composable () -> Unit) {
    val busy by NovaRuntime.store.busy.collectAsState()
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(busy) { if (!busy) { delay(250); pulled = false } }
    val state = rememberPullToRefreshState()
    PullToRefreshBox(pulled, onRefresh = { pulled = true; NovaRuntime.refreshLibrary(announce = true) }, state = state,
        indicator = {
            PullToRefreshDefaults.Indicator(state = state, isRefreshing = pulled, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp),
                containerColor = Nova.Bg3, color = LocalAccent.current.light)
        }) { content() }
}

@Composable private fun SortButton(sort: String, grid: Boolean, onSort: (String) -> Unit, onGrid: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box {
            Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.Sort, null, Modifier.size(18.dp), tint = LocalAccent.current.light)
                Text(SORTS.first { it.first == sort }.second, color = LocalAccent.current.light, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
            }
            DropdownMenu(open, { open = false }, containerColor = Nova.Bg3, shape = RoundedCornerShape(16.dp)) {
                SORTS.forEach { (k, label) -> DropdownMenuItem({ Text(label, fontWeight = if (k == sort) FontWeight.Bold else FontWeight.Normal) }, { onSort(k); open = false },
                    leadingIcon = { Icon(if (k == sort) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null, tint = if (k == sort) LocalAccent.current.light else Nova.Dim) }) }
            }
        }
        IconButton(onGrid) { Icon(if (grid) Icons.AutoMirrored.Rounded.ViewList else Icons.Rounded.GridView, "Change layout", tint = Nova.Dim) }
    }
}

/* ------------------------------------------------------------------ home = continue watching + folder tree */

@Composable private fun HomeScreen(activity: MainActivity, videos: List<Video>, tree: FolderNode, playing: Playing, bottomPad: Dp, onMenu: (Video) -> Unit,
                                   open: (String) -> Unit, add: () -> Unit, cleanup: () -> Unit, play: (Video, List<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var sort by remember { mutableStateOf(NovaRuntime.text("folderSort", "name")) }
    var grid by remember { mutableStateOf(NovaRuntime.pref("folderGrid", false)) }
    val busy by NovaRuntime.store.busy.collectAsState()
    val list = rememberLazyGridState()
    val accent = LocalAccent.current
    val cont = remember(videos) { videos.continueWatching() }
    val hero = cont.firstOrNull()
    val folders = remember(tree, sort) { sortFolders(tree.folders, sort) }
    val results = remember(videos, query) { if (query.isBlank()) emptyList() else sorted(videos.filter { it.title.contains(query, true) || it.folder.contains(query, true) }, "name").take(200) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(searching) { if (searching) { delay(120); runCatching { focus.requestFocus() } } }
    BackHandler(searching) { searching = false; query = "" }
    val permitted = remember(videos.size) { NovaRuntime.hasVideoPermission() }
    LocalSelection.current?.onScreen(if (searching && query.isNotBlank()) results else tree.all)

    Box(Modifier.fillMaxSize()) {
        Refreshable {
            LazyVerticalGrid(if (grid && !searching) GridCells.Adaptive(158.dp) else GridCells.Fixed(1), Modifier.fillMaxSize(), list,
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPad), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "header") {
                    Column(Modifier.statusBarsPadding().padding(top = 14.dp, bottom = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(greeting().uppercase(), style = Kicker, color = accent.light)
                                Text("Nova", style = TextStyle(brush = Brush.linearGradient(listOf(Color.White, accent.light, accent.deep)), fontSize = 40.sp, fontWeight = FontWeight.Black, letterSpacing = (-1.5).sp))
                            }
                            RoundIcon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, "Search") { searching = !searching; if (!searching) query = "" }
                            Spacer(Modifier.width(10.dp))
                            Box(Modifier.size(44.dp).clip(CircleShape).background(accent.grad).bouncy(onClick = add), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Add, "Add videos", tint = Color.White) }
                        }
                        Text(if (videos.isEmpty()) "Your screen. Your rules." else "${plural(videos.size, "video")} in ${plural(countFolders(tree), "folder")}", color = Nova.Dim, fontSize = 13.sp)
                        AnimatedVisibility(searching, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(top = 14.dp).focusRequester(focus), placeholder = { Text("Find a film, episode or folder…") },
                                leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, shape = RoundedCornerShape(18.dp),
                                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Nova.Card, focusedContainerColor = Nova.Card, unfocusedBorderColor = Nova.Line))
                        }
                        AnimatedVisibility(busy && videos.isEmpty()) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp).clip(CircleShape), color = accent.light, trackColor = Nova.Bg3) }
                    }
                }
                if (searching && query.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { Text(if (results.isEmpty()) "Nothing matches “$query”" else plural(results.size, "result"), color = Nova.Dim, fontSize = 13.sp) }
                    itemsIndexed(results, key = { _, v -> "s" + v.uri }) { i, v -> VideoRow(v, Modifier.animateItem().rise(i), onMenu = { onMenu(v) }) { play(v, NovaRuntime.store.folderQueue(v)) } }
                    return@LazyVerticalGrid
                }
                if (videos.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyState(Icons.Rounded.PlayArrow, if (permitted) "No videos yet" else "Let's find your videos",
                            if (permitted) "Download or copy a video to this phone and it appears here by itself. Pull down to refresh."
                            else "Allow Nova to see the videos on this phone.\nNew downloads then appear automatically — nothing to set up.") {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                GradientButton(if (permitted) "Refresh" else "Allow access", Icons.Rounded.AutoAwesome, Modifier.fillMaxWidth()) { activity.scanDevice() }
                                Spacer(Modifier.height(10.dp))
                                GhostButton("Choose a folder instead", Icons.Rounded.FolderOpen, Modifier.fillMaxWidth()) { activity.addFolder() }
                            }
                        }
                    }
                    return@LazyVerticalGrid
                }
                if (hero != null) item(span = { GridItemSpan(maxLineSpan) }, key = "hero") {
                    Spotlight(hero, Modifier.padding(top = 6.dp).rise(0), resume = { play(hero, NovaRuntime.store.folderQueue(hero)) }, over = { activity.play(hero, NovaRuntime.store.folderQueue(hero), true) })
                }
                if (cont.size > 1) item(span = { GridItemSpan(maxLineSpan) }, key = "cont") {
                    Column(Modifier.rise(1)) {
                        SectionTitle("Continue watching")
                        LazyRow(Modifier.bleed(18.dp), contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(cont.drop(1), key = { it.uri }) { v -> WideCard(v, Modifier.width(220.dp), onMenu = { onMenu(v) }) { play(v, NovaRuntime.store.folderQueue(v)) } }
                        }
                    }
                }
                if (!searching) item(span = { GridItemSpan(maxLineSpan) }, key = "storage") { StorageCard(videos, Modifier.rise(2), open = cleanup) }
                item(span = { GridItemSpan(maxLineSpan) }, key = "foldersTitle") {
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Folders", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        SortButton(sort, grid, { sort = it; NovaRuntime.setPref("folderSort", it) }) { grid = !grid; NovaRuntime.setPref("folderGrid", grid) }
                    }
                }
                itemsIndexed(folders, key = { _, f -> "d" + f.path }) { i, f ->
                    if (grid) FolderTile(f, Modifier.animateItem().rise(i)) { open(f.path) } else FolderCard(f, Modifier.animateItem().rise(i)) { open(f.path) }
                }
                // Videos that live at the very top of storage (rare) or were opened from elsewhere.
                itemsIndexed(tree.videos, key = { _, v -> v.uri }) { i, v -> VideoRow(v, Modifier.animateItem().rise(i), onMenu = { onMenu(v) }) { play(v, tree.videos.map { it.uri }) } }
            }
        }
        // One resume affordance at a time: the mini-player wins; the chip only returns once it is closed.
        val scrolled by remember { derivedStateOf { list.firstVisibleItemIndex > 1 } }
        AnimatedVisibility(hero != null && playing.video == null && scrolled && !searching, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = bottomPad - 4.dp),
            enter = scaleIn(spring(dampingRatio = .6f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
            hero?.let { h ->
                Row(Modifier.shadow(16.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(accent.grad).bouncy { play(h, NovaRuntime.store.folderQueue(h)) }
                    .padding(start = 14.dp, end = 18.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White)
                    Column(Modifier.padding(start = 8.dp).widthIn(max = 190.dp)) {
                        Text("Resume · ${timeLabel(h.position)}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Text(prettyTitle(h.title), color = Color.White.copy(alpha = .85f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
private fun countFolders(n: FolderNode): Int = n.folders.size + n.folders.sumOf { countFolders(it) }
private fun greeting() = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..21 -> "Good evening"; else -> "Late-night session" }

/* ------------------------------------------------------------------ folder screen */

@Composable private fun FolderScreen(n: FolderNode, bottomPad: Dp, open: (String) -> Unit, back: () -> Unit, onMenu: (Video) -> Unit, onDelete: (List<Video>) -> Unit, play: (Video, List<String>) -> Unit) {
    var sort by remember { mutableStateOf(NovaRuntime.text("videoSort", "name")) }
    var grid by remember { mutableStateOf(NovaRuntime.pref("videoGrid", false)) }
    val batch by OnlineSubtitles.batchProgress.collectAsState()
    val subfolders = remember(n, sort) { sortFolders(n.folders, sort) }
    val rows = remember(n, sort) { sorted(n.videos, sort) }
    val queue = remember(n) { n.videos.map { it.uri } } // queue always in natural episode order
    val trail = remember(n.path) { n.path.split('/').filter { it.isNotBlank() }.dropLast(1) }
    val accent = LocalAccent.current
    LocalSelection.current?.onScreen(n.all)
    Refreshable {
        LazyVerticalGrid(if (grid) GridCells.Adaptive(158.dp) else GridCells.Fixed(1), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPad), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "head") {
                Column(Modifier.statusBarsPadding().padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = back)
                        if (trail.isNotEmpty()) Text(trail.joinToString("  ›  "), color = Nova.Dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    }
                    Text(prettyFolder(n.title), style = MaterialTheme.typography.headlineMedium.copy(fontSize = if (n.title.length > 28) 22.sp else 28.sp, lineHeight = if (n.title.length > 28) 28.sp else 34.sp), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                    Text(listOfNotNull(n.folders.size.takeIf { it > 0 }?.let { plural(it, "folder") }, plural(n.count, "video"), sizeLabel(n.size).ifBlank { null },
                        n.fresh.takeIf { it > 0 }?.let { "$it new" }).joinToString(" · "), color = Nova.Dim, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                    if (n.count > 1 && n.watched > 0) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f))) { Box(Modifier.fillMaxWidth(n.watched / n.count.toFloat()).fillMaxHeight().background(accent.grad)) }
                        Text("${n.watched}/${n.count} watched", color = Nova.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 10.dp))
                    }
                    if (n.videos.isNotEmpty()) Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val next = n.videos.let { r -> r.firstOrNull { it.position > 10 && !it.isWatched } ?: r.firstOrNull { !it.isWatched } ?: r.first() }
                        GradientButton(if (next.position > 10) "Continue" else "Play all", Icons.Rounded.PlayArrow, Modifier.weight(1f)) { play(next, queue) }
                        GhostButton("Shuffle", Icons.Rounded.Shuffle, Modifier.weight(1f)) { val q = n.videos.shuffled(); play(q.first(), q.map { it.uri }) }
                    }
                    if (n.videos.isNotEmpty()) {
                        if (batch.isNotBlank()) Box(Modifier.padding(top = 12.dp)) { BatchBanner(batch) }
                        else Row(Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(accent.soft).clickable { OnlineSubtitles.batch(n.videos) }.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Subtitles, null, tint = accent.light)
                            Text("Get subtitles for every video here", Modifier.padding(start = 10.dp).weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Icon(Icons.Rounded.Download, null, tint = accent.light, modifier = Modifier.size(20.dp))
                        }
                    }
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // Finished a season? Clear the watched episodes in one go (MX-style housekeeping).
                        val watched = n.videos.filter { it.isWatched }
                        if (watched.isNotEmpty()) Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable { onDelete(watched) }.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp), tint = Nova.Danger)
                            Text("Delete watched (${watched.size})", color = Nova.Danger, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        SortButton(sort, grid, { sort = it; NovaRuntime.setPref("videoSort", it) }) { grid = !grid; NovaRuntime.setPref("videoGrid", grid) }
                    }
                }
            }
            itemsIndexed(subfolders, key = { _, f -> "d" + f.path }) { i, f ->
                if (grid) FolderTile(f, Modifier.animateItem().rise(i)) { open(f.path) } else FolderCard(f, Modifier.animateItem().rise(i)) { open(f.path) }
            }
            itemsIndexed(rows, key = { _, v -> v.uri }) { i, v ->
                val go = { play(v, queue) }
                if (grid) VideoCard(v, Modifier.animateItem().rise(i + subfolders.size), onMenu = { onMenu(v) }, onClick = go)
                else VideoRow(v, Modifier.animateItem().rise(i + subfolders.size), onMenu = { onMenu(v) }, onClick = go)
            }
        }
    }
}

@Composable private fun NewBadge(count: Int, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "new").animateFloat(.75f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "p")
    Text(if (count > 1) "$count NEW" else "NEW", modifier.graphicsLayer { alpha = pulse.value }.background(Brush.linearGradient(listOf(Color(0xFFFB7185), Color(0xFFF59E0B))), RoundedCornerShape(7.dp))
        .padding(horizontal = 7.dp, vertical = 2.dp), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = .6.sp)
}

@Composable private fun FolderCard(f: FolderNode, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val sel = LocalSelection.current
    val selecting = sel?.active == true
    val on = selecting && sel!!.hasAll(f.all)
    // Long-press a folder to select everything in it (a whole season at once).
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (on) accent.soft2 else Nova.Card).border(1.dp, if (on) accent.line else Nova.Line, RoundedCornerShape(22.dp))
        .bouncy(onLongClick = { sel?.toggle(f.all) }, onClick = { if (selecting) sel!!.toggle(f.all) else onClick() }).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(118.dp).aspectRatio(1.45f)) {
            // Stacked sheets peek out behind the cover — a folder at a glance.
            Box(Modifier.fillMaxSize().padding(horizontal = 12.dp).offset(y = (-6).dp).clip(RoundedCornerShape(12.dp)).background(accent.soft2))
            Box(Modifier.fillMaxSize().padding(horizontal = 6.dp).offset(y = (-3).dp).clip(RoundedCornerShape(12.dp)).background(accent.soft))
            f.cover?.let { Poster(it, Modifier.fillMaxSize(), corner = 12.dp, showProgress = false, badge = false) }
                ?: Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(Nova.Bg3), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Folder, null, tint = accent.light) }
            if (f.folders.isNotEmpty()) Icon(Icons.Rounded.FolderCopy, null, Modifier.align(Alignment.BottomStart).padding(6.dp).size(22.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(7.dp)).padding(3.dp), tint = Color.White)
            if (f.fresh > 0) NewBadge(f.fresh, Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            if (f.crumb.isNotBlank()) Text(f.crumb, fontSize = 11.sp, color = Nova.Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(prettyFolder(f.title), fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 19.sp)
            Text(listOfNotNull(f.folders.size.takeIf { it > 0 }?.let { plural(it, "folder") }, plural(f.count, "video"), sizeLabel(f.size).takeIf { it.isNotBlank() && f.folders.isEmpty() }).joinToString(" · "),
                fontSize = 12.sp, color = Nova.Dim, modifier = Modifier.padding(top = 3.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f.count > 1 && f.watched > 0) Box(Modifier.padding(top = 8.dp).fillMaxWidth(.85f).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .08f))) {
                Box(Modifier.fillMaxWidth(f.watched / f.count.toFloat()).fillMaxHeight().background(accent.grad))
            }
        }
        if (selecting) SelectMark(on, Modifier.padding(end = 4.dp)) else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Nova.Dim)
    }
}

@Composable private fun FolderTile(f: FolderNode, modifier: Modifier, onClick: () -> Unit) {
    val accent = LocalAccent.current
    val sel = LocalSelection.current
    val selecting = sel?.active == true
    Column(modifier.bouncy(onLongClick = { sel?.toggle(f.all) }, onClick = { if (selecting) sel!!.toggle(f.all) else onClick() })) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.35f)) {
            Box(Modifier.fillMaxSize().padding(horizontal = 14.dp).offset(y = (-8).dp).clip(RoundedCornerShape(14.dp)).background(accent.soft2))
            Box(Modifier.fillMaxSize().padding(horizontal = 7.dp).offset(y = (-4).dp).clip(RoundedCornerShape(14.dp)).background(accent.soft))
            f.cover?.let { Poster(it, Modifier.fillMaxSize(), corner = 14.dp, showProgress = false, badge = false) }
            Text("${f.count}", Modifier.align(Alignment.BottomEnd).padding(8.dp).background(accent.grad, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            if (f.fresh > 0 && !selecting) NewBadge(f.fresh, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (selecting) SelectMark(sel!!.hasAll(f.all), Modifier.align(Alignment.TopEnd).padding(8.dp))
        }
        Text(prettyFolder(f.title), fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable private fun Spotlight(v: Video, modifier: Modifier, resume: () -> Unit, over: () -> Unit) {
    val img = rememberThumb(v)
    val accent = LocalAccent.current
    val mood = img?.mood(v.uri) ?: accent.deep
    val parsed = remember(v.title) { OnlineSubtitles.parseTitle(v.title) }
    Box(modifier.fillMaxWidth()) {
        // Colour spill: a blurred copy of the poster glows out from under the card.
        if (img != null && Build.VERSION.SDK_INT >= 31) Image(img, null, Modifier.matchParentSize().padding(horizontal = 16.dp).offset(y = 14.dp).blur(38.dp, BlurredEdgeTreatment.Unbounded).alpha(.75f), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxWidth().aspectRatio(1.55f).clip(RoundedCornerShape(28.dp)).background(mood).bouncy(onClick = resume)) {
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            // Deep enough behind the title that white text stays readable on bright posters (snow, sky, white rooms).
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = .25f), .3f to Color.Black.copy(alpha = .12f), .55f to Color.Black.copy(alpha = .6f), 1f to Color.Black.copy(alpha = .94f))))
            Text("CONTINUE WATCHING", Modifier.padding(16.dp).background(Color.Black.copy(alpha = .45f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                style = Kicker, color = Color.White, fontSize = 10.sp)
            Column(Modifier.align(Alignment.BottomStart).padding(18.dp)) {
                Text(if (parsed.episode != null) parsed.title.ifBlank { prettyTitle(v.title) } else prettyTitle(v.title), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 26.sp, color = Color.White)
                Text(listOfNotNull(parsed.episode?.let { "S%02dE%02d".format(parsed.season ?: 1, it) }, leftLabel(v), resLabel(v.shortSide).ifBlank { null }).joinToString("  ·  "),
                    color = Color.White.copy(alpha = .75f), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = .2f))) {
                    Box(Modifier.fillMaxWidth((v.position / v.duration).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(accent.grad))
                }
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton("Resume", Icons.Rounded.PlayArrow, Modifier.weight(1f), onClick = resume)
                    GhostButton("Start over", Icons.Rounded.Replay, Modifier.weight(1f), onClick = over)
                }
            }
        }
    }
}

@Composable fun VideoCard(v: Video, modifier: Modifier = Modifier, onMenu: () -> Unit, onClick: () -> Unit) {
    val sel = LocalSelection.current
    val selecting = sel?.active == true
    Column(modifier.bouncy(onLongClick = { sel?.toggle(v) ?: onMenu() }, onClick = { if (selecting) sel!!.toggle(v) else onClick() })) {
        Box {
            Poster(v, Modifier.fillMaxWidth().aspectRatio(1.6f).then(if (selecting && sel!!.has(v)) Modifier.border(2.5.dp, LocalAccent.current.light, RoundedCornerShape(16.dp)) else Modifier))
            if (v.isNew && !selecting) NewBadge(1, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (selecting) SelectMark(sel!!.has(v), Modifier.align(Alignment.TopEnd).padding(7.dp))
        }
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 8.dp)) {
                Text(prettyTitle(v.title), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, lineHeight = 17.sp)
                Text(meta(v), fontSize = 11.sp, color = Nova.Dim, modifier = Modifier.padding(top = 3.dp), maxLines = 1)
            }
            if (!selecting) Icon(Icons.Rounded.MoreVert, "Options", Modifier.padding(top = 6.dp).size(30.dp).clip(CircleShape).clickable(onClick = onMenu).padding(5.dp), tint = Nova.Dim)
        }
    }
}
private fun meta(v: Video) = listOf(resLabel(v.shortSide), sizeLabel(v.size), if (v.isWatched) "Watched" else "").filter { it.isNotBlank() }.joinToString(" · ")

@Composable fun VideoRow(v: Video, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, onMenu: () -> Unit, onClick: () -> Unit) {
    val sel = LocalSelection.current
    val selecting = sel?.active == true
    val on = selecting && sel!!.has(v)
    val tint by animateColorAsState(if (on) LocalAccent.current.soft2 else Color.Transparent, label = "picked")
    // MX-style: long-press starts selecting; the ⋮ button keeps the per-video menu.
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(tint).bouncy(onLongClick = { sel?.toggle(v) ?: onMenu() }, onClick = { if (selecting) sel!!.toggle(v) else onClick() })
        .padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box {
            Poster(v, Modifier.width(138.dp).aspectRatio(1.6f), corner = 14.dp)
            if (v.isNew) NewBadge(1, Modifier.align(Alignment.TopStart).padding(5.dp))
            if (v.isWatched) Icon(Icons.Rounded.CheckCircle, "Watched", Modifier.align(Alignment.TopEnd).padding(5.dp).size(18.dp), tint = Color.White.copy(alpha = .85f))
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(prettyTitle(v.title), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
            Text(meta(v).ifBlank { timeLabel(v.duration) }, fontSize = 12.sp, color = Nova.Dim, modifier = Modifier.padding(top = 4.dp))
            if (v.position > 10 && v.duration > 0 && !v.isWatched) Text(leftLabel(v), fontSize = 11.sp, color = LocalAccent.current.light, modifier = Modifier.padding(top = 2.dp))
        }
        if (selecting) SelectMark(on, Modifier.padding(end = 8.dp))
        else trailing?.invoke() ?: Icon(Icons.Rounded.MoreVert, "Options", Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onMenu).padding(7.dp), tint = Nova.Dim)
    }
}

@Composable private fun WideCard(v: Video, modifier: Modifier, onMenu: () -> Unit, onClick: () -> Unit) {
    Column(modifier.bouncy(onLongClick = onMenu, onClick = onClick)) {
        Box { Poster(v, Modifier.fillMaxWidth().aspectRatio(1.7f))
            Box(Modifier.align(Alignment.Center).size(40.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White) }
        }
        Text(prettyTitle(v.title), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
        Text(if (v.position > 10) leftLabel(v) else meta(v).ifBlank { timeLabel(v.duration) }, fontSize = 11.sp, color = Nova.Dim)
    }
}

@Composable private fun BatchBanner(text: String) {
    val accent = LocalAccent.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accent.soft).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Subtitles, null, tint = accent.light)
        Text(text, Modifier.weight(1f).padding(horizontal = 10.dp), fontSize = 12.sp, maxLines = 2)
        if (!text.startsWith("Downloaded") && !text.startsWith("Cancelled")) TextButton({ OnlineSubtitles.cancelBatch() }) { Text("Cancel") } else TextButton({ OnlineSubtitles.batchProgress.value = "" }) { Text("OK") }
    }
}

@Composable fun ScreenHeader(title: String, subtitle: String, back: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) { RoundIcon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = back); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Nova.Dim, fontSize = 13.sp)
        }
        actions()
    }
}

/** Playlist detail. */
@Composable private fun VideoListScreen(title: String, rows: List<Video>, bottomPad: Dp, back: () -> Unit, onMenu: (Video) -> Unit, play: (Video, List<String>) -> Unit, playlist: VideoList) {
    var rename by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val total = rows.sumOf { it.duration }
    LocalSelection.current?.onScreen(rows)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenHeader(title, "${plural(rows.size, "video")}${if (total > 0) " · ${timeLabel(total)}" else ""}", back) {
                IconButton({ rename = true }) { Icon(Icons.Rounded.Edit, "Rename", tint = Nova.Dim) }; IconButton({ confirmDelete = true }) { Icon(Icons.Rounded.DeleteOutline, "Delete playlist", tint = Nova.Dim) }
            }
        }
        if (rows.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val next = rows.firstOrNull { !it.isWatched } ?: rows.first()
                GradientButton(if (next.position > 10) "Continue" else "Play all", Icons.Rounded.PlayArrow, Modifier.weight(1f)) { play(next, rows.map { it.uri }) }
                GhostButton("Shuffle", Icons.Rounded.Shuffle, Modifier.weight(1f)) { val q = rows.shuffled(); play(q.first(), q.map { it.uri }) }
            }
        }
        if (rows.isEmpty()) item { EmptyState(Icons.AutoMirrored.Rounded.PlaylistAdd, "Nothing here yet", "Long-press any video and choose “Add to playlist”.") }
        itemsIndexed(rows, key = { _, v -> v.uri }) { i, v ->
            VideoRow(v, Modifier.animateItem().rise(i), onMenu = { onMenu(v) }, onClick = { play(v, rows.map { it.uri }) }, trailing = {
                Column {
                    Icon(Icons.Rounded.KeyboardArrowUp, "Move up", Modifier.size(30.dp).clip(CircleShape).clickable(enabled = i > 0) { NovaRuntime.store.movePlaylistItem(playlist.id, v.uri, -1) }.padding(4.dp), tint = if (i > 0) Nova.Text else Nova.Bg3)
                    Icon(Icons.Rounded.KeyboardArrowDown, "Move down", Modifier.size(30.dp).clip(CircleShape).clickable(enabled = i < rows.lastIndex) { NovaRuntime.store.movePlaylistItem(playlist.id, v.uri, 1) }.padding(4.dp), tint = if (i < rows.lastIndex) Nova.Text else Nova.Bg3)
                }
            })
        }
    }
    if (rename) InputDialog("Rename playlist", "Name", playlist.name, "Save", { rename = false }) { NovaRuntime.store.renamePlaylist(playlist.id, it); rename = false }
    if (confirmDelete) AlertDialog({ confirmDelete = false }, containerColor = Nova.Bg3, title = { Text("Delete “${playlist.name}”?") },
        text = { Text("Videos stay in your library.") }, confirmButton = { TextButton({ NovaRuntime.store.deletePlaylist(playlist.id); confirmDelete = false; back() }) { Text("Delete", color = Nova.Danger) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text("Keep") } })
}

/* ------------------------------------------------------------------ playlists */

private val LIST_HUES = listOf(Color(0xFF4FACFE) to Color(0xFF6D28D9), Color(0xFFFB7185) to Color(0xFFD97706), Color(0xFF34D399) to Color(0xFF0891B2),
    Color(0xFFA78BFA) to Color(0xFFE11D48), Color(0xFFFBBF24) to Color(0xFF059669), Color(0xFF22D3EE) to Color(0xFF2F6BFF))

@Composable private fun PlaylistsScreen(videos: List<Video>, lists: List<VideoList>, bottomPad: Dp, open: (String) -> Unit, create: () -> Unit) {
    LazyVerticalGrid(GridCells.Adaptive(158.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPad),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { ScreenHeader("Playlists", if (lists.isEmpty()) "A place for every mood" else plural(lists.size, "playlist")) }
        item {
            Column(Modifier.rise(0).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).border(1.5.dp, LocalAccent.current.line, RoundedCornerShape(24.dp))
                .background(LocalAccent.current.soft).bouncy(onClick = create), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.Add, null, Modifier.size(40.dp), tint = LocalAccent.current.light)
                Text("New playlist", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
            }
        }
        itemsIndexed(lists, key = { _, l -> l.id }) { i, list ->
            val (a, b) = LIST_HUES[i % LIST_HUES.size]
            val first = list.items.firstNotNullOfOrNull { id -> videos.find { it.uri == id } }
            Box(Modifier.animateItem().rise(i + 1).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(a, b))).bouncy { open(list.id) }) {
                first?.let { Poster(it, Modifier.fillMaxSize().alpha(.35f), corner = 0.dp, showProgress = false, badge = false) }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .6f)))))
                Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, Modifier.padding(14.dp).size(30.dp), tint = Color.White)
                Column(Modifier.align(Alignment.BottomStart).padding(14.dp)) {
                    Text(list.name, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(plural(list.items.size, "video"), color = Color.White.copy(alpha = .8f), fontSize = 12.sp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ history */

@Composable private fun HistoryScreen(videos: List<Video>, bottomPad: Dp, onMenu: (Video) -> Unit, play: (Video, List<String>) -> Unit) {
    val rows = remember(videos) { videos.filter { it.played > 0 }.sortedByDescending { it.played }.take(200) }
    var confirm by remember { mutableStateOf(false) }
    val groups = remember(rows) { rows.groupBy { dayLabel(it.played) } }
    LocalSelection.current?.onScreen(rows)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = bottomPad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenHeader("History", if (rows.isEmpty()) "Nothing watched yet" else "${plural(rows.size, "video")} watched") { if (rows.isNotEmpty()) TextButton({ confirm = true }) { Text("Clear", color = Nova.Danger) } } }
        if (rows.isEmpty()) item { EmptyState(Icons.Rounded.History, "Your story starts here", "Everything you watch shows up here, newest first.") }
        var i = 0
        groups.forEach { (label, items) ->
            item(key = "h:$label") { Text(label.uppercase(), style = Kicker, color = LocalAccent.current.light, modifier = Modifier.padding(top = 10.dp)) }
            items(items, key = { "h" + it.uri }) { v -> VideoRow(v, Modifier.animateItem().rise(i++), onMenu = { onMenu(v) }) { play(v, NovaRuntime.store.folderQueue(v)) } }
        }
    }
    if (confirm) AlertDialog({ confirm = false }, containerColor = Nova.Bg3, title = { Text("Clear watch history?") }, text = { Text("Resume points are cleared too. Your videos stay.") },
        confirmButton = { TextButton({ NovaRuntime.store.clearHistory(); confirm = false }) { Text("Clear", color = Nova.Danger) } }, dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}
private fun dayLabel(t: Long): String {
    val c = Calendar.getInstance(); val now = Calendar.getInstance(); c.timeInMillis = t
    val days = ((now.apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0) }.timeInMillis - t) / 86_400_000L).toInt()
    return when { t >= now.timeInMillis -> "Today"; days < 1 -> "Yesterday"; days < 7 -> "This week"; days < 30 -> "This month"; else -> "Earlier" }
}

/* ------------------------------------------------------------------ video menu */

@Composable private fun VideoMenu(activity: MainActivity, v: Video, lists: List<VideoList>, dismiss: () -> Unit, newPlaylist: () -> Unit,
                                  select: () -> Unit, details: () -> Unit, rename: () -> Unit) {
    var pickList by remember { mutableStateOf(false) }
    val accent = LocalAccent.current
    ModalBottomSheet(dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Nova.Bg3, dragHandle = { BottomSheetDefaults.DragHandle(color = Nova.Dim) }) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Poster(v, Modifier.width(120.dp).aspectRatio(1.6f), corner = 14.dp)
                Column(Modifier.padding(start = 14.dp)) {
                    Text(prettyTitle(v.title), fontWeight = FontWeight.Bold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(listOf(timeLabel(v.duration), resLabel(v.shortSide), sizeLabel(v.size)).filter { it.isNotBlank() && it != "0:00" }.joinToString(" · "), color = Nova.Dim, fontSize = 12.sp)
                    Text(folderLabel(v.folder), color = Nova.Dim, fontSize = 12.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            val queue = NovaRuntime.store.folderQueue(v)
            if (v.position > 10) ListRow(Icons.Rounded.PlayArrow, "Resume from ${timeLabel(v.position)}", null, accent.light) { dismiss(); activity.play(v, queue) }
            ListRow(Icons.Rounded.Replay, if (v.position > 10) "Start from the beginning" else "Play", null, accent.light) { dismiss(); activity.play(v, queue, true) }
            ListRow(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist", null, Color(0xFFA78BFA)) { pickList = !pickList }
            AnimatedVisibility(pickList) {
                Column(Modifier.padding(start = 52.dp)) {
                    lists.forEach { l -> val inIt = v.uri in l.items
                        ListRow(if (inIt) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, l.name, plural(l.items.size, "video"), if (inIt) accent.light else Nova.Dim) {
                            NovaRuntime.store.playlistItem(l.id, v.uri, !inIt); NovaRuntime.notice.value = if (inIt) "Removed from ${l.name}" else "Added to ${l.name}" } }
                    ListRow(Icons.Rounded.Add, "New playlist…", null, Nova.Dim, onClick = newPlaylist)
                }
            }
            ListRow(if (v.isWatched) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (v.isWatched) "Mark as unwatched" else "Mark as watched", null, Color(0xFF34D399)) {
                if (v.isWatched) NovaRuntime.store.resetProgress(v.uri) else NovaRuntime.store.progress(v.uri, v.duration, v.duration, v.audio, v.subtitle); dismiss()
            }
            if (v.position > 0 && !v.isWatched) ListRow(Icons.Rounded.RestartAlt, "Clear progress", null, Color(0xFFFBBF24)) { NovaRuntime.store.resetProgress(v.uri); dismiss() }
            ListRow(Icons.Rounded.Subtitles, "Get subtitles", "Search OpenSubtitles for this video", Color(0xFF22D3EE)) { dismiss(); OnlineSubtitles.batch(listOf(v)) }
            ListRow(Icons.Rounded.Checklist, "Select", "Pick more videos to act on together", accent.light, onClick = select)
            ListRow(Icons.Rounded.Share, "Share", null, Color(0xFF22D3EE)) { dismiss(); activity.share(listOf(v)) }
            if (activity.canDelete(v)) ListRow(Icons.Rounded.DriveFileRenameOutline, "Rename", null, Color(0xFFFBBF24), onClick = rename)
            ListRow(Icons.Rounded.Info, "Details", "Codecs, bitrate, location", Color(0xFFA78BFA), onClick = details)
            if (activity.canDelete(v)) ListRow(Icons.Rounded.DeleteForever, "Delete file", "Permanently removes it from your phone" + sizeLabel(v.size).let { if (it.isBlank()) "" else " · frees $it" }, Nova.Danger) { dismiss(); activity.askDelete(listOf(v)) }
            ListRow(Icons.Rounded.RemoveCircleOutline, "Hide from library", "The file stays on your phone", Nova.Dim) { NovaRuntime.store.remove(v.uri); dismiss() }
        }
    }
}
