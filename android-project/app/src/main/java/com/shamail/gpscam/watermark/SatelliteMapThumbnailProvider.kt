package com.shamail.gpscam.watermark

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import com.shamail.gpscam.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Fetches and caches real Google Maps Static API satellite map thumbnails for a given GPS coordinate.
 * Never generates synthetic/fake maps: returns null whenever Maps API key, network, or tile data
 * is unavailable so callers can render an honest unavailable state.
 */
object SatelliteMapThumbnailProvider {

    private const val DISK_CACHE_DIR = "sat_map_thumbs"
    private const val FAILURE_COOLDOWN_MS = 20_000L
    private const val MAX_DISK_CACHE_FILES = 48

    private val memoryCache = object : LruCache<String, Bitmap>(24) {}
    private val recentFailures = ConcurrentHashMap<String, Long>()
    private val inFlightKeys = ConcurrentHashMap.newKeySet<String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun hasMapsApiKey(context: Context): Boolean {
        return resolveMapsApiKey(context).isNotBlank()
    }

    private fun resolveMapsApiKey(context: Context): String {
        val fromBuildConfig = BuildConfig.MAPS_API_KEY.trim()
        if (fromBuildConfig.isNotEmpty()) return fromBuildConfig

        return runCatching {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getApplicationInfo(
                    context.packageName,
                    PackageManager.GET_META_DATA
                )
            }
            appInfo.metaData?.getString("com.google.android.geo.API_KEY")?.trim().orEmpty()
        }.getOrDefault("")
    }

    private fun coordKey(latitude: Double, longitude: Double): String {
        return String.format(Locale.US, "%.5f_%.5f", latitude, longitude)
    }

    fun getCached(latitude: Double, longitude: Double): Bitmap? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        val key = coordKey(latitude, longitude)
        val bmp = memoryCache.get(key)
        return if (bmp != null && !bmp.isRecycled) bmp else null
    }

    fun getOrFetchBlocking(
        context: Context,
        latitude: Double,
        longitude: Double,
        sizePx: Int = 240
    ): Bitmap? {
        if (!latitude.isFinite() || !longitude.isFinite()) return null
        val key = coordKey(latitude, longitude)

        getCached(latitude, longitude)?.let { return it }

        val cacheDir = File(context.cacheDir, DISK_CACHE_DIR)
        val diskFile = File(cacheDir, "$key.jpg")
        if (diskFile.exists() && diskFile.length() > 512L) {
            val decoded = runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            if (decoded != null && decoded.width > 32 && decoded.height > 32) {
                memoryCache.put(key, decoded)
                return decoded
            }
        }

        val apiKey = resolveMapsApiKey(context)
        if (apiKey.isBlank()) {
            return null
        }

        val now = System.currentTimeMillis()
        val lastFail = recentFailures[key]
        if (lastFail != null && now - lastFail < FAILURE_COOLDOWN_MS) {
            return null
        }

        val fetched = downloadStaticSatelliteMap(apiKey, latitude, longitude, sizePx.coerceIn(128, 320))
        if (fetched != null) {
            recentFailures.remove(key)
            memoryCache.put(key, fetched)
            saveToDiskCache(cacheDir, diskFile, fetched)
            return fetched
        }

        recentFailures[key] = now
        return null
    }

    fun fetchAsync(
        context: Context,
        latitude: Double,
        longitude: Double,
        onComplete: () -> Unit
    ) {
        if (!latitude.isFinite() || !longitude.isFinite()) return
        if (getCached(latitude, longitude) != null) return

        val key = coordKey(latitude, longitude)
        val now = System.currentTimeMillis()
        val lastFail = recentFailures[key]
        if (lastFail != null && now - lastFail < FAILURE_COOLDOWN_MS) {
            return
        }
        if (!inFlightKeys.add(key)) {
            return
        }

        val appCtx = context.applicationContext
        executor.execute {
            try {
                val loaded = getOrFetchBlocking(appCtx, latitude, longitude)
                if (loaded != null) {
                    mainHandler.post { onComplete() }
                }
            } finally {
                inFlightKeys.remove(key)
            }
        }
    }

    private fun downloadStaticSatelliteMap(
        apiKey: String,
        latitude: Double,
        longitude: Double,
        sizePx: Int
    ): Bitmap? {
        var conn: HttpURLConnection? = null
        return try {
            val centerParam = URLEncoder.encode(
                String.format(Locale.US, "%.6f,%.6f", latitude, longitude),
                "UTF-8"
            )
            val keyParam = URLEncoder.encode(apiKey, "UTF-8")
            val urlStr =
                "https://maps.googleapis.com/maps/api/staticmap" +
                    "?center=$centerParam" +
                    "&zoom=17" +
                    "&size=${sizePx}x${sizePx}" +
                    "&scale=2" +
                    "&maptype=satellite" +
                    "&key=$keyParam"

            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 5000
                requestMethod = "GET"
                doInput = true
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                return null
            }
            conn.inputStream.use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null && bmp.width >= 64 && bmp.height >= 64) bmp else null
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun saveToDiskCache(cacheDir: File, diskFile: File, bitmap: Bitmap) {
        runCatching {
            if (!cacheDir.exists()) cacheDir.mkdirs()
            FileOutputStream(diskFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            val files = cacheDir.listFiles()?.sortedByDescending { it.lastModified() } ?: return@runCatching
            if (files.size > MAX_DISK_CACHE_FILES) {
                files.drop(MAX_DISK_CACHE_FILES).forEach { runCatching { it.delete() } }
            }
        }
    }
}
