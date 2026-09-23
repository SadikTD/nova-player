package com.sadik.novaplayer.ui

import com.sadik.novaplayer.Video
import com.sadik.novaplayer.naturalCompare

/**
 * The library as the phone stores it: Download › The.Mentalist.COMPLETE… › S01.
 * Folders that hold no videos and exactly one sub-folder are merged into their child
 * ("Android › media › … › WhatsApp Video") so nobody taps through five empty levels,
 * while real branching (a season pack with S01…S07) is kept exactly as on disk.
 */
class FolderNode(val path: String, val label: String, val folders: List<FolderNode>, val videos: List<Video>) {
    /** The folder's own name, and the merged parent chain shown small above it. */
    val title get() = label.substringAfterLast(" › ")
    val crumb get() = label.substringBeforeLast(" › ", "")
    val all: List<Video> by lazy { videos + folders.flatMap { it.all } }
    val count get() = all.size
    val size by lazy { all.sumOf { it.size } }
    val watched by lazy { all.count { it.isWatched } }
    val fresh by lazy { all.count { it.isNew } }
    val lastPlayed by lazy { all.maxOfOrNull { it.played } ?: 0L }
    val lastAdded by lazy { all.maxOfOrNull { it.added } ?: 0L }
    val cover: Video? by lazy { all.filter { it.played > 0 }.maxByOrNull { it.played } ?: videos.sortedWith { a, b -> naturalCompare(a.title, b.title) }.firstOrNull() ?: all.firstOrNull() }
    fun find(target: String): FolderNode? = if (path == target) this else folders.firstNotNullOfOrNull { it.find(target) }
    fun parentOf(target: String): FolderNode? = if (folders.any { it.path == target }) this else folders.firstNotNullOfOrNull { it.parentOf(target) }
}

val Video.isWatched get() = duration > 0 && position / duration >= .97
val Video.isNew get() = played == 0L && added > System.currentTimeMillis() - 3 * 86_400_000L

private class Builder(val path: String, val name: String) {
    val children = LinkedHashMap<String, Builder>()
    val videos = mutableListOf<Video>()
}

fun buildFolderTree(videos: List<Video>): FolderNode {
    val root = Builder("", "")
    for (v in videos) {
        var node = root
        var acc = ""
        for (part in v.folder.split('/').filter { it.isNotBlank() }) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            node = node.children.getOrPut(part) { Builder(acc, part) }
        }
        node.videos += v
    }
    fun compact(b: Builder): FolderNode {
        var cur = b; var label = b.name
        while (cur !== root && cur.videos.isEmpty() && cur.children.size == 1) { cur = cur.children.values.first(); label += " › " + cur.name }
        return FolderNode(cur.path, label, cur.children.values.map(::compact), cur.videos.sortedWith { a, c -> naturalCompare(a.title, c.title) })
    }
    return compact(root)
}

fun sortFolders(list: List<FolderNode>, sort: String) = when (sort) {
    "added" -> list.sortedByDescending { it.lastAdded }
    "played" -> list.sortedByDescending { it.lastPlayed }
    "largest" -> list.sortedByDescending { it.size }
    "longest" -> list.sortedByDescending { it.count }
    else -> list.sortedWith { a, b -> naturalCompare(a.label, b.label) }
}
