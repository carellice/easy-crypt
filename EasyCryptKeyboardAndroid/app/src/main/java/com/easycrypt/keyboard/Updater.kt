package com.easycrypt.keyboard

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Aggiornamento dall'ultima release su GitHub. Si collega a Internet solo quando l'utente lo chiede.
 * L'APK viene passato a PackageInstaller, che chiede sempre conferma prima di installare.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/carellice/easy-crypt/releases/latest"
    const val ACTION_STATUS = "com.easycrypt.keyboard.UPDATE_STATUS"

    data class Release(val version: String, val apkUrl: String, val size: Long)

    /** Ultima release se più recente di [current], altrimenti null. */
    fun check(current: String): Release? {
        val json = JSONObject(read(LATEST))
        val version = json.getString("tag_name").removePrefix("v")
        if (!isNewer(version, current)) return null
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.startsWith("EasyCrypt-Android-") && name.endsWith(".apk")) {
                return Release(version, asset.getString("browser_download_url"), asset.optLong("size", -1))
            }
        }
        throw IOException("la release $version non contiene l'APK per Android")
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    /** Scarica l'APK direttamente nella sessione di installazione; l'esito arriva a SettingsActivity. */
    fun install(context: Context, release: Release, progress: (Int) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val connection = open(release.apkUrl)
                try {
                    val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                    connection.inputStream.use { input ->
                        session.openWrite("EasyCrypt.apk", 0, total).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                done += count
                                if (total > 0) progress((done * 100 / total).toInt())
                            }
                            session.fsync(output)
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
                val status = Intent(context, SettingsActivity::class.java).setAction(ACTION_STATUS)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                session.commit(PendingIntent.getActivity(context, id, status, flags).intentSender)
            }
        } catch (error: Exception) {
            installer.abandonSession(id)
            throw error
        }
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream")
        connection.setRequestProperty("User-Agent", "EasyCrypt-Android")
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("risposta HTTP $code da GitHub")
        }
        return connection
    }

    private fun read(url: String): String {
        val connection = open(url)
        try {
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
