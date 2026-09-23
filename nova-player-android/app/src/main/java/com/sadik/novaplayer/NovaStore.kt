package com.sadik.novaplayer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import android.graphics.Bitmap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

private val HEX = "0123456789abcdef".toCharArray()
fun stableId(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) { val b = bytes[i].toInt() and 255; out[i * 2] = HEX[b shr 4]; out[i * 2 + 1] = HEX[b and 15] }
    return String(out)
}

data class Video(
    val uri: String, val title: String, val folder: String = "Opened videos", val size: Long = 0,
    val duration: Double = 0.0, val position: Double = 0.0, val played: Long = 0,
    val audio: String = "auto", val subtitle: String = "auto", val externalSub: String = "",
    val originalSub: String = "", val width: Int = 0, val height: Int = 0, val added: Long = System.currentTimeMillis(),
) {
    fun json() = JSONObject().put("uri", uri).put("title", title).put("folder", folder).put("size", size)
        .put("duration", duration).put("position", position).put("played", played).put("audio", audio)
        .put("subtitle", subtitle).put("externalSub", externalSub).put("originalSub", originalSub).put("width", width).put("height", height).put("added", added)
    companion object {
        fun from(o: JSONObject) = Video(o.getString("uri"), o.optString("title"), o.optString("folder", "Opened videos"), o.optLong("size"),
            o.optDouble("duration", 0.0), o.optDouble("position", 0.0), o.optLong("played"), o.optString("audio", "auto"),
            o.optString("subtitle", "auto"), o.optString("externalSub"), o.optString("originalSub"), o.optInt("width"), o.optInt("height"), o.optLong("added", 0))
    }
}
data class VideoList(val id: String, val name: String, val items: List<String>)

class NovaStore(val context: Context) {
    val prefs = context.getSharedPreferences("nova-settings", Context.MODE_PRIVATE)
    val videos = MutableStateFlow<List<Video>>(emptyList())
    val playlists = MutableStateFlow<List<VideoList>>(emptyList())
    val roots = MutableStateFlow<List<String>>(emptyList())
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow("")
    private val file = AtomicFile(File(context.filesDir, "nova-library.json"))
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val thumbnails = File(context.cacheDir, "thumbnails").apply { mkdirs() }
    init {
        runCatching {
            val data = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            val rows = data.optJSONArray("videos") ?: JSONArray()
            videos.value = (0 until rows.length()).map { Video.from(rows.getJSONObject(it)) }
            val lists = data.optJSONArray("playlists") ?: JSONArray()
            playlists.value = (0 until lists.length()).map { val o = lists.getJSONObject(it); VideoList(o.getString("id"), o.getString("name"), strings(o.getJSONArray("items"))) }
            roots.value = strings(data.optJSONArray("roots") ?: JSONArray())
        }.onFailure { if (file.baseFile.exists()) message.value = "Library could not be read. Your saved file was kept." }
    }
    private fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
    @Synchronized private fun save() {
        val data = JSONObject().put("videos", JSONArray(videos.value.map { it.json() }))
            .put("roots", JSONArray(roots.value)).put("playlists", JSONArray(playlists.value.map { JSONObject().put("id", it.id).put("name", it.name).put("items", JSONArray(it.items)) }))
        val stream = file.startWrite()
        try { stream.write(data.toString().toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); message.value = "Could not save library: ${e.message}" }
    }
    @Synchronized fun update(video: Video) {
        videos.value = videos.value.filterNot { it.uri == video.uri } + video
        save()
    }
    @Synchronized fun progress(uri: String, position: Double, duration: Double, audio: String, subtitle: String) {
        val old = videos.value.find { it.uri == uri } ?: return
        update(old.copy(position = position.coerceAtLeast(0.0), duration = duration, played = System.currentTimeMillis(), audio = audio, subtitle = subtitle))
    }
    fun resetProgress(uri: String) { videos.value.find { it.uri == uri }?.let { update(it.copy(position = 0.0)) } }
    @Synchronized fun clearHistory() { videos.value = videos.value.map { it.copy(played = 0, position = 0.0) }; save() }
    @Synchronized fun addPlaylist(name: String, items: List<String> = emptyList()): String {
        val id = java.util.UUID.randomUUID().toString()
        playlists.value += VideoList(id, name.trim().ifBlank { "My playlist" }, items.distinct()); save(); return id
    }
    /** Batch versions for multi-select: one save for the whole selection. */
    @Synchronized fun addToPlaylist(id: String, uris: List<String>) { playlists.value = playlists.value.map { if (it.id == id) it.copy(items = (it.items + uris).distinct()) else it }; save() }
    @Synchronized fun markWatched(uris: Collection<String>, watched: Boolean) {
        val set = uris.toHashSet(); val now = System.currentTimeMillis()
        videos.value = videos.value.map { v -> if (v.uri !in set) v else if (watched) v.copy(position = v.duration, played = if (v.played > 0) v.played else now) else v.copy(position = 0.0) }; save()
    }
    /** A rename keeps history, resume point and playlists; document URIs change on rename, MediaStore ones don't. */
    @Synchronized fun renamed(old: String, new: String, title: String) {
        videos.value = videos.value.map { if (it.uri == old) it.copy(uri = new, title = title) else it }
        if (old != new) { playlists.value = playlists.value.map { l -> l.copy(items = l.items.map { if (it == old) new else it }) }; thumbFiles.remove(old)?.let { f -> runCatching { f.renameTo(thumb(videos.value.first { it.uri == new })) } } }
        save()
    }
    @Synchronized fun deletePlaylist(id: String) { playlists.value = playlists.value.filterNot { it.id == id }; save() }
    @Synchronized fun playlistItem(id: String, uri: String, add: Boolean = true) {
        playlists.value = playlists.value.map { if (it.id == id) it.copy(items = if (add) (it.items + uri).distinct() else it.items - uri) else it }; save()
    }
    @Synchronized fun movePlaylistItem(id: String, uri: String, delta: Int) {
        playlists.value = playlists.value.map { list ->
            if (list.id != id) list else { val items = list.items.toMutableList(); val index = items.indexOf(uri); val to = index + delta
                if (index >= 0 && to in items.indices) { items.removeAt(index); items.add(to, uri) }; list.copy(items = items) }
        }; save()
    }
    @Synchronized fun removeRoot(root: String) {
        roots.value -= root
        videos.value = videos.value.filterNot { it.folder.startsWith(rootName(root) + "/") || it.folder == rootName(root) }
        save()
    }
    /** MX-style: tapping a video queues its whole folder in natural episode order (E2 before E10). */
    fun folderQueue(video: Video): List<String> = videos.value.filter { it.folder == video.folder && !it.uri.startsWith("http") }
        .sortedWith { a, b -> naturalCompare(a.title, b.title) }.map { it.uri }.ifEmpty { listOf(video.uri) }
    fun remove(uri: String) { synchronized(this) { videos.value = videos.value.filterNot { it.uri == uri }; playlists.value = playlists.value.map { it.copy(items = it.items - uri) }; save() } }
    /** After the files themselves are gone: drop them everywhere and clean up what Nova made for them (thumbnails, subtitle copies). */
    fun forgetDeleted(items: List<Video>) {
        val gone = items.mapTo(HashSet()) { it.uri }
        synchronized(this) { videos.value = videos.value.filterNot { it.uri in gone }; playlists.value = playlists.value.map { it.copy(items = it.items.filterNot { u -> u in gone }) }; save() }
        worker.launch {
            val own = File(context.filesDir, "subtitles").canonicalPath
            for (v in items) {
                thumb(v).delete()
                for (path in setOf(v.externalSub, v.originalSub)) if (path.isNotBlank()) runCatching { File(path).takeIf { it.canonicalPath.startsWith(own) }?.let { File(it.path + ".approved").delete(); it.delete(); it.parentFile?.takeIf { d -> d.canonicalPath != own && d.list()?.isEmpty() == true }?.delete() } }
            }
        }
    }
    @Synchronized fun renamePlaylist(id: String, name: String) { playlists.value = playlists.value.map { if (it.id == id) it.copy(name = name) else it }; save() }
    private val thumbFiles = java.util.concurrent.ConcurrentHashMap<String, File>()
    /** Hashing is cheap but not free; posters ask for this on every frame of a scroll, so remember it. */
    fun thumb(video: Video): File = thumbFiles.getOrPut(video.uri) { File(thumbnails, stableId(video.uri) + ".jpg") }
    fun import(uri: Uri): Video {
        videos.value.find { it.uri == uri.toString() }?.let { return it }
        var title = uri.lastPathSegment ?: "Video"
        var size = 0L
        if (uri.scheme == "content") runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { title = it.getString(0) ?: title; size = it.getLong(1) }
            }
        }
        val video = Video(uri.toString(), title, if (uri.scheme?.startsWith("http") == true) "Streams" else "Opened videos", size)
        update(video)
        worker.launch { metadata(video) }
        return video
    }
    fun rootName(root: String): String = runCatching {
        val uri = Uri.parse(root)
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else "Folder" } ?: "Folder"
    }.getOrDefault("Folder")
    fun addRoot(uri: Uri) { if (uri.toString() !in roots.value) { roots.value += uri.toString(); save() }; scan() }
    /** [announce] = the user asked (pull-to-refresh, button); background rescans stay silent unless something new arrived. */
    fun scan(device: Boolean = prefs.getBoolean("deviceLibrary", false), announce: Boolean = false) {
        if (busy.value) return
        worker.launch {
            busy.value = true
            try {
                val found = mutableListOf<Video>()
                if (device) {
                    context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        arrayOf("_id", "_display_name", "_size", "duration", "bucket_display_name", "width", "height", if (android.os.Build.VERSION.SDK_INT >= 29) "relative_path" else "_data", "date_added"), null, null, "_display_name ASC")?.use { c ->
                        // Group by the real directory, not its name: two different "Season 1" folders must not merge.
                        while (c.moveToNext()) {
                            val raw = c.getString(7).orEmpty()
                            val folder = (if (android.os.Build.VERSION.SDK_INT >= 29) raw else raw.substringBeforeLast('/').substringAfter("/emulated/0/")).trim('/').ifBlank { c.getString(4) ?: "Videos" }
                            found += Video(Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0).toString()).toString(),
                                c.getString(1) ?: "Video", folder, c.getLong(2), c.getLong(3) / 1000.0, width = c.getInt(5), height = c.getInt(6), added = c.getLong(8) * 1000)
                        }
                    }
                    prefs.edit().putBoolean("deviceLibrary", true).apply()
                }
                for (root in roots.value) {
                    val uri = Uri.parse(root)
                    scanTree(uri, DocumentsContract.getTreeDocumentId(uri), rootName(root), found, 0)
                }
                val before = videos.value.mapTo(HashSet()) { it.uri }
                synchronized(this@NovaStore) {
                    val old = videos.value.associateBy { it.uri }
                    val merged = old.toMutableMap()
                    for (v in found) { val previous = old[v.uri]; merged[v.uri] = if (previous == null) v else v.copy(duration = if (v.duration > 0) v.duration else previous.duration, width = if (v.width > 0) v.width else previous.width, height = if (v.height > 0) v.height else previous.height, position = previous.position, played = previous.played, added = if (v.added > 0) v.added else previous.added,
                        audio = previous.audio, subtitle = previous.subtitle, externalSub = previous.externalSub.ifBlank { v.externalSub }, originalSub = previous.originalSub.ifBlank { v.originalSub }) }
                    // Desktop rule: an item disappears only when it lives under a scanned source and is gone from disk.
                    // Streams and individually opened files are never pruned.
                    val seen = found.mapTo(HashSet()) { it.uri }
                    val treeRoots = roots.value.map { Uri.parse(it).let { r -> DocumentsContract.buildDocumentUriUsingTree(r, DocumentsContract.getTreeDocumentId(r)).toString().substringBeforeLast("/document/") } }
                    merged.keys.removeAll { uri -> uri !in seen && ((device && uri.startsWith(MediaStore.Video.Media.EXTERNAL_CONTENT_URI.toString())) || treeRoots.any { uri.startsWith(it) }) }
                    videos.value = merged.values.toList(); save()
                }
                val added = found.count { it.uri !in before }
                if (added > 0 && before.isNotEmpty()) message.value = if (added == 1) "1 new video added" else "$added new videos added"
                else if (announce) message.value = "Library is up to date · ${videos.value.size} videos"
                for (v in found) { ensureActive(); if (!thumb(v).exists()) metadata(v) }
            } catch (e: Exception) { message.value = "Folder access failed. Grant access again or remove the folder. ${e.message}" }
            finally { busy.value = false }
        }
    }
    private fun scanTree(tree: Uri, id: String, folder: String, out: MutableList<Video>, depth: Int) {
        if (depth > 24) return
        val siblings = mutableListOf<Pair<String, Uri>>()
        val firstIndex = out.size
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        context.contentResolver.query(children, arrayOf("document_id", "_display_name", "mime_type", "_size", "last_modified"), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val childId = c.getString(0); val title = c.getString(1) ?: "Video"; val type = c.getString(2) ?: ""
                if (title.substringAfterLast('.').lowercase() in setOf("srt","ass","ssa")) siblings += title to DocumentsContract.buildDocumentUriUsingTree(tree,childId)
                if (type == DocumentsContract.Document.MIME_TYPE_DIR) scanTree(tree, childId, "$folder/$title", out, depth + 1)
                else if (type.startsWith("video/") || title.substringAfterLast('.').lowercase() in setOf("mkv","mp4","m4v","avi","mov","wmv","flv","webm","ts","m2ts","mts","3gp","mpg","mpeg","vob","ogv"))
                    out += Video(DocumentsContract.buildDocumentUriUsingTree(tree, childId).toString(), title, folder, c.getLong(3), added = c.getLong(4).takeIf { it > 0 } ?: System.currentTimeMillis())
            }
        }
        for (i in firstIndex until out.size) {
            val v = out[i]
            if (v.folder != folder) continue
            val base = v.title.substringBeforeLast('.').lowercase()
            val match = siblings.sortedBy { it.first.length }.firstOrNull { val name=it.first.substringBeforeLast('.').lowercase(); name==base || name.startsWith("$base.") } ?: continue
            runCatching {
                val file=NovaRuntime.subtitleFile(stableId(match.second.toString()), match.first)
                context.contentResolver.openInputStream(match.second)?.use { file.writeBytes(it.readBytesLimited(8*1024*1024)) }
                if(file.isFile) out[i]=v.copy(externalSub=file.path,originalSub=file.path)
            }
        }
    }
    private fun metadata(video: Video) {
        if (video.uri.startsWith("http")) return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(video.uri))
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull()?.div(1000) ?: video.duration
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            // Desktop grabs the frame at 20% of the duration: past intros and black openers.
            val at = if (duration > 5) (duration * 0.2 * 1_000_000).toLong() else 1_000_000L
            (retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: retriever.getFrameAtTime(0))?.let { raw ->
                val w = raw.width.coerceAtMost(640)
                val small = Bitmap.createScaledBitmap(raw, w, (raw.height * w / raw.width.toFloat()).toInt().coerceAtLeast(1), true)
                thumb(video).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
                if (small !== raw) small.recycle(); raw.recycle()
            }
            synchronized(this) { videos.value.find { it.uri == video.uri }?.let { update(it.copy(duration = duration, width = width, height = height)) } }
        } catch (_: Exception) { /* Some codecs have no Android thumbnail decoder; playback still uses mpv. */ }
        finally { runCatching { retriever.release() } }
    }
}

fun naturalCompare(a: String, b: String): Int {
    val chunks = Regex("""\d+|\D+""")
    val x = chunks.findAll(a.lowercase()).map { it.value }.toList()
    val y = chunks.findAll(b.lowercase()).map { it.value }.toList()
    for (i in 0 until minOf(x.size, y.size)) {
        val p = x[i]; val q = y[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) (p.trimStart('0').length - q.trimStart('0').length).takeIf { it != 0 } ?: p.trimStart('0').compareTo(q.trimStart('0')) else p.compareTo(q)
        if (c != 0) return c
    }
    return x.size - y.size
}
