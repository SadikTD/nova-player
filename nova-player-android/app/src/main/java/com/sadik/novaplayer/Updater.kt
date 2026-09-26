package com.sadik.novaplayer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Keeps the GitHub build up to date from the project's GitHub releases, like the desktop app:
 * no dialogs, no nagging. A newer `android-v*` release downloads in the background (on Wi-Fi)
 * and installs when you leave Nova with nothing playing. Android asks to confirm the very
 * first self-update; after that Nova is the app's installer and updates silently (Android 12+).
 * Play builds (BuildConfig.SELF_UPDATE = false) are updated by Play and never run this.
 */
object Updater {
    private const val RELEASES = "https://api.github.com/repos/SadikTD/nova-player/releases?per_page=30"
    /** Checked each time Nova comes to the front (like the desktop app on launch), at most this often. */
    private const val EVERY = 30 * 60 * 1000L
    /** Passive line for Settings › About. */
    val status = MutableStateFlow("")
    val ready = MutableStateFlow<File?>(null)
    private var readyVersion = ""
    private var job: Job? = null
    private var offered = false
    var foreground = false; private set
    val enabled get() = BuildConfig.SELF_UPDATE && !BuildConfig.APPLICATION_ID.endsWith(".debug")
    private val app get() = NovaRuntime.app
    private val dir get() = File(app.cacheDir, "updates")

    fun onStart() {
        foreground = true
        if (!enabled) return
        if (ready.value != null) { offer(); return }
        if (job?.isActive == true || System.currentTimeMillis() - NovaRuntime.store.prefs.getLong("updateCheckedAt", 0) < EVERY) return
        job = NovaRuntime.scope.launch(Dispatchers.IO) {
            runCatching { fetch() }.onFailure { status.value = "" }
            if (ready.value != null) NovaRuntime.scope.launch { offer() }
        }
    }

    /** Leaving the app with nothing playing is the moment to swap versions (Android restarts Nova). */
    fun onStop() {
        foreground = false
        val file = ready.value ?: return
        val s = NovaRuntime.state.value
        if (canInstallSilently() && (s.video == null || s.paused)) install(file)
    }

    private fun canInstallSilently() = Build.VERSION.SDK_INT >= 31 &&
        runCatching { app.packageManager.getInstallSourceInfo(app.packageName).installingPackageName == app.packageName }.getOrDefault(false)

    /** Only when Android will ask anyway (the first self-update): one quiet snack per launch. */
    private fun offer() {
        if (offered || canInstallSilently() || !foreground || NovaRuntime.state.value.video?.let { !NovaRuntime.state.value.paused } == true) return
        val file = ready.value ?: return
        offered = true
        NovaRuntime.snack.value = Snack("Nova Player $readyVersion is ready", "Install", run = { install(file) })
    }

    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 30000
        setRequestProperty("User-Agent", "NovaPlayer-Android/${BuildConfig.VERSION_NAME}")
        setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }; val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) { val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }; if (d != 0) return d > 0 }
        return false
    }

    private fun fetch() {
        val releases = JSONArray(get(RELEASES).inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        val release = (0 until releases.length()).map { releases.getJSONObject(it) }.firstOrNull {
            !it.optBoolean("draft") && !it.optBoolean("prerelease") && it.optString("tag_name").startsWith("android-v")
        } ?: return
        val version = release.getString("tag_name").removePrefix("android-v")
        // Recorded only once a check has fully succeeded, so a failure is retried next time.
        fun checked() = NovaRuntime.store.prefs.edit().putLong("updateCheckedAt", System.currentTimeMillis()).apply()
        dir.listFiles()?.forEach { if (!it.name.contains(version)) it.delete() } // leftovers of installed versions
        if (!newer(version, BuildConfig.VERSION_NAME)) { status.value = "Up to date"; checked(); return }
        val assets = release.getJSONArray("assets").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val apk = assets.firstOrNull { it.getString("name").endsWith(".apk") } ?: return
        val name = apk.getString("name")
        val file = File(dir, name)
        if (!file.isFile) {
            // Needs ACCESS_NETWORK_STATE (github manifest); without it this threw and 1.2.0 never updated.
            val metered = runCatching { app.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)
            if (metered) { status.value = "Version $version will download on Wi-Fi"; return }
            status.value = "Downloading version $version…"
            dir.mkdirs()
            val part = File(dir, "$name.part")
            get(apk.getString("browser_download_url")).inputStream.use { input -> part.outputStream().use { input.copyTo(it) } }
            // The published checksum and Nova's own signing key must both match, or it is thrown away.
            assets.firstOrNull { it.getString("name") == "SHA256SUMS.txt" }?.let { sums ->
                val want = get(sums.getString("browser_download_url")).inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                    .lines().firstOrNull { it.trim().endsWith(name) }?.trim()?.substringBefore(' ')?.lowercase()
                val have = MessageDigest.getInstance("SHA-256").digest(part.readBytes()).joinToString("") { "%02x".format(it) }
                if (want != null && want != have) { part.delete(); status.value = ""; return }
            }
            if (!sameApp(part)) { part.delete(); status.value = ""; return }
            part.renameTo(file)
        }
        readyVersion = version
        ready.value = file
        checked()
        status.value = if (canInstallSilently()) "Version $version installs next time you leave Nova" else "Version $version is ready to install"
    }

    @Suppress("DEPRECATION")
    private fun sameApp(file: File): Boolean {
        val pm = app.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return false
        if (archive.packageName != app.packageName) return false
        fun signers(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)?.map { it.toCharsString() }?.toSet()
        val theirs = signers(archive) ?: return true // older Androids may not read it; the installer still refuses a different key
        return theirs == signers(pm.getPackageInfo(app.packageName, flags))
    }

    fun install(file: File) {
        if (!file.isFile) return
        NovaRuntime.scope.launch(Dispatchers.IO) {
            runCatching {
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName)
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("nova.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
                    val done = PendingIntent.getBroadcast(app, id, Intent(app, UpdateReceiver::class.java).setPackage(app.packageName), flags)
                    session.commit(done.intentSender)
                }
            }.onFailure { status.value = "The update could not be installed" }
        }
    }

    internal fun result(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                // Android only lets Nova show its confirmation while Nova is on screen.
                if (foreground) runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Android restarts Nova on the new version
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // declined: offered again next launch
            else -> { status.value = "The update could not be installed"; ready.value?.delete(); ready.value = null }
        }
    }
}

class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Updater.result(context, intent)
}
