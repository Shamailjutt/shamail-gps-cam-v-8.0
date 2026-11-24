package com.shamail.gpscam.storage

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.shamail.gpscam.data.CoordFormat
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.LocationSource
import com.shamail.gpscam.data.ResolutionPreset
import com.shamail.gpscam.data.SavedPhotoItem
import com.shamail.gpscam.data.TimestampMode
import com.shamail.gpscam.data.VideoGpsKeyframe
import com.shamail.gpscam.data.WatermarkConfig
import com.shamail.gpscam.data.WatermarkPosition
import com.shamail.gpscam.data.WatermarkSize
import com.shamail.gpscam.metadata.PhotoMetadataEngine
import com.shamail.gpscam.video.Mp4MetadataUpdater
import com.shamail.gpscam.video.Mp4VideoMetadata
import com.shamail.gpscam.video.VideoWatermarkProcessor
import com.shamail.gpscam.watermark.WatermarkRenderer
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max

data class SourceExifSnapshot(
    val dateTimeMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val dateTimeOriginal: String? = null,
    val dateTimeDigitized: String? = null,
    val dateTime: String? = null,
    val make: String? = null,
    val model: String? = null,
    val software: String? = null,
    val exposureTime: String? = null,
    val fNumber: String? = null,
    val isoSpeed: String? = null,
    val focalLength: String? = null,
    val flash: String? = null,
    val whiteBalance: String? = null
)

data class PhotoEditHistoryEntry(
    val recordedAtMillis: Long,
    val actionLabel: String,
    val sourcePhotoName: String?,
    val customDateTimeMillis: Long?,
    val customLocationTitle: String?,
    val customAddress: String?,
    val customLat: Double?,
    val customLon: Double?
)

data class PhotoMetadataRecord(
    val fileName: String,
    val sourcePhotoName: String?,
    val originalCaptureMillis: Long?,
    val originalLatitude: Double?,
    val originalLongitude: Double?,
    val originalLocationTitle: String?,
    val originalAddress: String?,
    val cameraInfo: String?,
    val watermarkDateTimeMillis: Long,
    val watermarkLocationTitle: String,
    val watermarkAddress: String,
    val watermarkLatitude: Double?,
    val watermarkLongitude: Double?,
    val isCustomTimestamp: Boolean,
    val isCustomLocation: Boolean,
    val history: List<PhotoEditHistoryEntry>
)

data class EditablePhotoContext(
    val sourceBitmap: Bitmap,
    val gps: GpsSnapshot,
    val config: WatermarkConfig,
    val originalDisplayMillis: Long,
    val originalCaptureMillis: Long,
    val originalSourceName: String,
    val hasCleanOriginal: Boolean,
    val sourceExif: SourceExifSnapshot,
    val cameraMake: String?,
    val cameraModel: String?
)

data class EditableVideoContext(
    val sourceUri: Uri,
    val cachedSourceFile: File?,
    val filePath: String?,
    val previewFrameBitmap: Bitmap,
    val gps: GpsSnapshot,
    val gpsTimeline: List<VideoGpsKeyframe> = emptyList(),
    val config: WatermarkConfig,
    val originalDisplayMillis: Long,
    val originalCaptureMillis: Long,
    val originalSourceName: String,
    val hasCleanOriginal: Boolean,
    val durationMillis: Long
)

object PhotoStorageManager {

    const val SUBFOLDER_NAME = "GpsCamByShamail"
    const val RELATIVE_PICTURES_PATH = "Pictures/GpsCamByShamail"
    const val RELATIVE_MOVIES_PATH = "Movies/GpsCamByShamail"
    private const val SOURCE_CACHE_DIR = "watermark_sources"
    private const val VIDEO_SOURCE_CACHE_DIR = "watermark_video_sources"
    private const val HISTORY_DIR = "photo_history"
    private const val MAX_CACHED_SOURCES = 80
    private const val MAX_CACHED_VIDEO_SOURCES = 15
    private const val MAX_HISTORY_RECORDS = 200

    fun decodeGalleryUriBitmap(
        context: Context,
        sourceUri: Uri,
        preset: ResolutionPreset
    ): Bitmap? {
        return try {
            val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, boundsOpts)
            }
            if (boundsOpts.outWidth <= 0 || boundsOpts.outHeight <= 0) return null

            val targetMax = if (preset.maxLongEdge > 0) preset.maxLongEdge else 3840
            val rawLong = max(boundsOpts.outWidth, boundsOpts.outHeight)
            var sampleSize = 1
            while (rawLong / sampleSize > targetMax * 2) {
                sampleSize *= 2
            }

            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val raw = context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOpts)
            } ?: return null

            val orientation = runCatching {
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    ExifInterface(stream).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL
                    )
                }
            }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
            }

            val oriented = if (!matrix.isIdentity) {
                val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
                if (rotated != raw) raw.recycle()
                rotated
            } else {
                raw
            }

            val currentLong = max(oriented.width, oriented.height)
            if (currentLong <= targetMax) return oriented

            val scale = targetMax.toFloat() / currentLong.toFloat()
            val targetW = (oriented.width * scale).toInt().coerceAtLeast(1)
            val targetH = (oriented.height * scale).toInt().coerceAtLeast(1)
            val resized = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
            if (resized != oriented) oriented.recycle()
            resized
        } catch (_: Exception) {
            null
        }
    }

    fun decodeAndOrientTempCapture(
        tempFile: File,
        isFrontCamera: Boolean,
        preset: ResolutionPreset
    ): Bitmap? {
        val raw = BitmapFactory.decodeFile(tempFile.absolutePath) ?: return null
        val exif = runCatching { ExifInterface(tempFile.absolutePath) }.getOrNull()
        val orientation = exif?.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        ) ?: ExifInterface.ORIENTATION_NORMAL

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.preScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.preScale(-1f, 1f)
            }
        }
        if (isFrontCamera &&
            orientation != ExifInterface.ORIENTATION_FLIP_HORIZONTAL &&
            orientation != ExifInterface.ORIENTATION_TRANSPOSE &&
            orientation != ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            matrix.postScale(-1f, 1f)
        }

        val oriented = if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
            if (rotated != raw) raw.recycle()
            rotated
        } else {
            raw
        }

        val maxEdge = preset.maxLongEdge
        if (maxEdge <= 0) return oriented

        val currentLongEdge = max(oriented.width, oriented.height)
        if (currentLongEdge <= maxEdge) return oriented

        val scale = maxEdge.toFloat() / currentLongEdge.toFloat()
        val targetW = (oriented.width * scale).toInt().coerceAtLeast(1)
        val targetH = (oriented.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
        if (resized != oriented) oriented.recycle()
        return resized
    }

    fun readSourcePhotoExif(context: Context, uri: Uri, filePath: String? = null): SourceExifSnapshot {
        return runCatching {
            val exif = if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                ExifInterface(filePath)
            } else {
                context.contentResolver.openInputStream(uri)?.use { stream -> ExifInterface(stream) }
            }
            if (exif == null) return@runCatching SourceExifSnapshot(null, null, null)

            val dtOrig = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val dtDig = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME)
            val offsetOrig = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            val offsetDig = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
            val offsetDt = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
            val dtMillis = when {
                !dtOrig.isNullOrBlank() -> PhotoMetadataEngine.parseExifTimestampMillis(dtOrig, offsetOrig)
                !dtDig.isNullOrBlank() -> PhotoMetadataEngine.parseExifTimestampMillis(dtDig, offsetDig)
                else -> PhotoMetadataEngine.parseExifTimestampMillis(dt, offsetDt)
            } ?: PhotoMetadataEngine.parseExifTimestampMillis(dtOrig ?: dtDig ?: dt)
            val coords = exif.latLong

            SourceExifSnapshot(
                dateTimeMillis = dtMillis,
                latitude = coords?.getOrNull(0),
                longitude = coords?.getOrNull(1),
                dateTimeOriginal = dtOrig,
                dateTimeDigitized = dtDig,
                dateTime = dt,
                make = exif.getAttribute(ExifInterface.TAG_MAKE),
                model = exif.getAttribute(ExifInterface.TAG_MODEL),
                software = exif.getAttribute(ExifInterface.TAG_SOFTWARE),
                exposureTime = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME),
                fNumber = exif.getAttribute(ExifInterface.TAG_F_NUMBER),
                isoSpeed = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY),
                focalLength = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH),
                flash = exif.getAttribute(ExifInterface.TAG_FLASH),
                whiteBalance = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)
            )
        }.getOrElse {
            SourceExifSnapshot(null, null, null)
        }
    }

    fun processAndSaveWatermarkedPhoto(
        context: Context,
        sourceBitmap: Bitmap,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        captureDeviceMillis: Long = System.currentTimeMillis(),
        isGalleryImport: Boolean = false,
        hasCustomOverrides: Boolean = false,
        originalCaptureGps: GpsSnapshot? = null,
        originalSourceExif: SourceExifSnapshot? = null,
        sourceDisplayName: String? = null
    ): Uri? {
        val displayedMillis = PhotoMetadataEngine.normalizeToSecondMillis(
            config.resolveTimestampMillis(gps, captureDeviceMillis)
        )
        val logoBitmap = WatermarkRenderer.loadLogoBitmap(context, config)
        val watermarked = WatermarkRenderer.renderOntoBitmap(
            source = sourceBitmap,
            config = config,
            gps = gps,
            logoBitmap = logoBitmap,
            captureMillis = displayedMillis
        )

        val fileStamp = PhotoMetadataEngine.formatFileStamp(displayedMillis)
        val baseName = "SHAMAIL_GPS_$fileStamp.jpg"
        val fileName = ensureUniqueOutputFileName(context, baseName)
        val quality = config.jpegQuality.coerceIn(70, 100)

        val effectiveOriginalMillis = if (isGalleryImport) {
            originalSourceExif?.dateTimeMillis ?: captureDeviceMillis
        } else {
            captureDeviceMillis
        }
        val effectiveOriginalGps = when {
            isGalleryImport -> {
                if (originalSourceExif?.latitude != null && originalSourceExif.longitude != null) {
                    GpsSnapshot(
                        hasFix = true,
                        latitude = originalSourceExif.latitude,
                        longitude = originalSourceExif.longitude,
                        gpsTimestampMillis = effectiveOriginalMillis,
                        locationSource = LocationSource.LIVE_GPS
                    )
                } else {
                    GpsSnapshot(hasFix = false)
                }
            }
            originalCaptureGps != null -> originalCaptureGps
            gps.locationSource == LocationSource.LIVE_GPS -> gps
            else -> GpsSnapshot(hasFix = false)
        }

        val customTimestamp = config.timestampMode == TimestampMode.MANUAL ||
            (originalSourceExif?.dateTimeMillis != null && abs(displayedMillis - originalSourceExif.dateTimeMillis) > 60_000L)
        val customLocation = hasCustomOverrides ||
            gps.locationSource != LocationSource.LIVE_GPS ||
            (originalCaptureGps != null && originalCaptureGps.hasFix && (
                abs(gps.latitude - originalCaptureGps.latitude) > 0.0001 ||
                    abs(gps.longitude - originalCaptureGps.longitude) > 0.0001
                ))

        val exportedGps = if (gps.hasFix) {
            gps
        } else if (originalSourceExif?.latitude != null && originalSourceExif.longitude != null) {
            GpsSnapshot(
                hasFix = true,
                latitude = originalSourceExif.latitude,
                longitude = originalSourceExif.longitude,
                gpsTimestampMillis = displayedMillis,
                locationSource = LocationSource.LIVE_GPS
            )
        } else {
            GpsSnapshot(hasFix = false)
        }

        val savedUri = exportWatermarkedPhoto(
            context = context,
            watermarkedBitmap = watermarked,
            fileName = fileName,
            quality = quality,
            config = config,
            originalExif = originalSourceExif,
            exportedGps = exportedGps,
            displayedMillis = displayedMillis,
            isGalleryImport = isGalleryImport
        )

        if (savedUri != null) {
            persistCleanSourceBackup(
                context = context,
                displayName = fileName,
                cleanSourceBitmap = sourceBitmap,
                config = config,
                gps = gps,
                displayMillis = displayedMillis,
                originalCaptureMillis = effectiveOriginalMillis
            )

            val historyEntries = mutableListOf<PhotoEditHistoryEntry>()
            val existingSourceRecord = sourceDisplayName?.let { loadHistoryRecordByName(context, it) }
            if (existingSourceRecord != null) {
                historyEntries.addAll(existingSourceRecord.history)
            }
            if (isGalleryImport || customTimestamp || customLocation) {
                val actionDesc = when {
                    isGalleryImport && (customTimestamp || customLocation) -> "Gallery GPS Custom Watermark"
                    isGalleryImport -> "Gallery GPS Watermark Copy"
                    customTimestamp && customLocation -> "Custom Date/Time & Location Watermark"
                    customTimestamp -> "Custom Date & Time Watermark"
                    else -> "Custom Location Watermark"
                }
                historyEntries.add(
                    PhotoEditHistoryEntry(
                        recordedAtMillis = System.currentTimeMillis(),
                        actionLabel = actionDesc,
                        sourcePhotoName = sourceDisplayName ?: if (isGalleryImport) "Device Gallery" else null,
                        customDateTimeMillis = displayedMillis,
                        customLocationTitle = if (gps.hasFix) gps.buildLocationHeadline() else "",
                        customAddress = if (gps.hasFix) gps.buildFullAddressLine() else "",
                        customLat = if (gps.hasFix) gps.latitude else null,
                        customLon = if (gps.hasFix) gps.longitude else null
                    )
                )
            }

            val makeModel = listOfNotNull(
                originalSourceExif?.make ?: Build.MANUFACTURER?.takeIf { !isGalleryImport },
                originalSourceExif?.model ?: Build.MODEL?.takeIf { !isGalleryImport }
            ).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { null }

            val record = PhotoMetadataRecord(
                fileName = fileName,
                sourcePhotoName = existingSourceRecord?.sourcePhotoName ?: sourceDisplayName,
                originalCaptureMillis = existingSourceRecord?.originalCaptureMillis
                    ?: (if (isGalleryImport) originalSourceExif?.dateTimeMillis else captureDeviceMillis),
                originalLatitude = existingSourceRecord?.originalLatitude
                    ?: if (effectiveOriginalGps.hasFix) effectiveOriginalGps.latitude else null,
                originalLongitude = existingSourceRecord?.originalLongitude
                    ?: if (effectiveOriginalGps.hasFix) effectiveOriginalGps.longitude else null,
                originalLocationTitle = existingSourceRecord?.originalLocationTitle
                    ?: if (!isGalleryImport && effectiveOriginalGps.hasFix) effectiveOriginalGps.buildLocationHeadline() else null,
                originalAddress = existingSourceRecord?.originalAddress
                    ?: if (!isGalleryImport && effectiveOriginalGps.hasFix) effectiveOriginalGps.buildFullAddressLine() else null,
                cameraInfo = existingSourceRecord?.cameraInfo ?: makeModel,
                watermarkDateTimeMillis = displayedMillis,
                watermarkLocationTitle = if (gps.hasFix) gps.buildLocationHeadline() else "",
                watermarkAddress = if (gps.hasFix) gps.buildFullAddressLine() else "",
                watermarkLatitude = if (gps.hasFix) gps.latitude else null,
                watermarkLongitude = if (gps.hasFix) gps.longitude else null,
                isCustomTimestamp = customTimestamp || (existingSourceRecord?.isCustomTimestamp == true),
                isCustomLocation = customLocation || (existingSourceRecord?.isCustomLocation == true),
                history = historyEntries
            )
            persistPhotoMetadataRecord(context, record)
        }
        if (watermarked != sourceBitmap && !watermarked.isRecycled) {
            watermarked.recycle()
        }
        return savedUri
    }

    fun loadEditablePhotoContext(
        context: Context,
        photoUri: Uri,
        displayName: String,
        filePath: String?,
        fallbackConfig: WatermarkConfig
    ): EditablePhotoContext? {
        val safeKey = sanitizeCacheKey(displayName)
        val cacheDir = File(context.filesDir, SOURCE_CACHE_DIR)
        val cleanImgFile = File(cacheDir, "$safeKey.jpg")
        val cleanMetaFile = File(cacheDir, "$safeKey.json")

        val sourceExif = readSourcePhotoExif(context, photoUri, filePath)
        val existingRecord = loadHistoryRecordByName(context, displayName)

        if (cleanImgFile.exists() && cleanMetaFile.exists()) {
            val cachedBitmap = BitmapFactory.decodeFile(cleanImgFile.absolutePath)
            val parsedMeta = runCatching { JSONObject(cleanMetaFile.readText()) }.getOrNull()
            if (cachedBitmap != null && parsedMeta != null) {
                val gps = jsonToGps(parsedMeta.optJSONObject("gps"))
                val cfg = jsonToConfig(parsedMeta.optJSONObject("config"), fallbackConfig)
                val storedDisplayMillis = parsedMeta.optLong("displayMillis", System.currentTimeMillis())
                val storedCaptureMillis = parsedMeta.optLong(
                    "originalCaptureMillis",
                    existingRecord?.originalCaptureMillis ?: sourceExif.dateTimeMillis ?: storedDisplayMillis
                )
                return EditablePhotoContext(
                    sourceBitmap = cachedBitmap,
                    gps = gps,
                    config = cfg,
                    originalDisplayMillis = storedDisplayMillis,
                    originalCaptureMillis = storedCaptureMillis,
                    originalSourceName = displayName,
                    hasCleanOriginal = true,
                    sourceExif = sourceExif,
                    cameraMake = sourceExif.make,
                    cameraModel = sourceExif.model
                )
            }
        }

        val decoded = if (!filePath.isNullOrBlank() && File(filePath).exists()) {
            val f = File(filePath)
            decodeAndOrientTempCapture(f, isFrontCamera = false, preset = ResolutionPreset.MAX)
        } else {
            decodeGalleryUriBitmap(context, photoUri, ResolutionPreset.MAX)
        } ?: return null

        val lat = sourceExif.latitude
        val lon = sourceExif.longitude
        var gps = if (lat != null && lon != null) {
            GpsSnapshot(
                hasFix = true,
                latitude = lat,
                longitude = lon,
                gpsTimestampMillis = sourceExif.dateTimeMillis ?: System.currentTimeMillis(),
                locationSource = LocationSource.LIVE_GPS
            )
        } else {
            GpsSnapshot(
                hasFix = false,
                gpsTimestampMillis = sourceExif.dateTimeMillis ?: System.currentTimeMillis()
            )
        }

        if (lat != null && lon != null && Geocoder.isPresent()) {
            runCatching {
                @Suppress("DEPRECATION")
                val list = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
                val addr = list?.firstOrNull()
                if (addr != null) {
                    val city = addr.locality ?: addr.subAdminArea ?: ""
                    val district = addr.subAdminArea ?: ""
                    val province = addr.adminArea ?: ""
                    val country = addr.countryName ?: ""
                    val street = (0..addr.maxAddressLineIndex).joinToString(", ") {
                        addr.getAddressLine(it).orEmpty()
                    }.trim()
                    gps = gps.copy(
                        city = city,
                        district = district,
                        province = province,
                        country = country,
                        streetAddress = street
                    )
                }
            }
        }

        if (existingRecord != null &&
            (existingRecord.watermarkLocationTitle.isNotBlank() || existingRecord.watermarkAddress.isNotBlank())
        ) {
            gps = gps.copy(
                hasFix = (existingRecord.watermarkLatitude ?: lat) != null &&
                    (existingRecord.watermarkLongitude ?: lon) != null,
                latitude = existingRecord.watermarkLatitude ?: lat ?: 0.0,
                longitude = existingRecord.watermarkLongitude ?: lon ?: 0.0,
                city = existingRecord.watermarkLocationTitle.ifBlank { gps.city },
                district = if (existingRecord.watermarkLocationTitle.isNotBlank()) "" else gps.district,
                province = if (existingRecord.watermarkLocationTitle.isNotBlank()) "" else gps.province,
                country = if (existingRecord.watermarkLocationTitle.isNotBlank()) "" else gps.country,
                streetAddress = existingRecord.watermarkAddress.ifBlank { gps.streetAddress },
                locationSource = if (existingRecord.isCustomLocation) {
                    LocationSource.MANUAL_MAP
                } else {
                    gps.locationSource
                }
            )
        }

        val origMillis = existingRecord?.originalCaptureMillis
            ?: sourceExif.dateTimeMillis
            ?: System.currentTimeMillis()

        return EditablePhotoContext(
            sourceBitmap = decoded,
            gps = gps,
            config = fallbackConfig,
            originalDisplayMillis = existingRecord?.watermarkDateTimeMillis ?: origMillis,
            originalCaptureMillis = origMillis,
            originalSourceName = displayName,
            hasCleanOriginal = false,
            sourceExif = sourceExif,
            cameraMake = sourceExif.make,
            cameraModel = sourceExif.model
        )
    }

    fun saveEditedDateTimeCopy(
        context: Context,
        editable: EditablePhotoContext,
        editedMillis: Long,
        is24HourFormat: Boolean,
        dateFormatPattern: String,
        editedGps: GpsSnapshot = editable.gps
    ): Pair<Uri, String>? {
        val normalizedEditedMillis = PhotoMetadataEngine.normalizeToSecondMillis(editedMillis)
        val updatedConfig = editable.config.copy(
            timestampMode = TimestampMode.MANUAL,
            manualTimestampMillis = normalizedEditedMillis,
            is24HourFormat = is24HourFormat,
            dateFormatPattern = dateFormatPattern,
            showWatermark = true
        )
        val logoBitmap = WatermarkRenderer.loadLogoBitmap(context, updatedConfig)
        val cleanBaseForCopy = if (editable.hasCleanOriginal) {
            editable.sourceBitmap
        } else {
            WatermarkRenderer.prepareCleanBaseForReWatermark(
                source = editable.sourceBitmap,
                config = updatedConfig,
                oldGps = editable.gps,
                captureMillis = normalizedEditedMillis
            )
        }
        val watermarked = WatermarkRenderer.renderOntoBitmap(
            source = cleanBaseForCopy,
            config = updatedConfig,
            gps = editedGps,
            logoBitmap = logoBitmap,
            captureMillis = normalizedEditedMillis,
            forceOpaqueBackground = false,
            replaceExistingWatermark = false
        )

        val stamp = PhotoMetadataEngine.formatFileStamp(normalizedEditedMillis)
        val fileName = ensureUniqueOutputFileName(context, "SHAMAIL_GPS_$stamp.jpg")
        val quality = updatedConfig.jpegQuality.coerceIn(70, 100)

        val originalGps = if (editable.sourceExif.latitude != null && editable.sourceExif.longitude != null) {
            GpsSnapshot(
                hasFix = true,
                latitude = editable.sourceExif.latitude,
                longitude = editable.sourceExif.longitude,
                gpsTimestampMillis = editable.originalCaptureMillis,
                locationSource = LocationSource.LIVE_GPS
            )
        } else if (editable.gps.hasFix && editable.gps.locationSource == LocationSource.LIVE_GPS) {
            editable.gps
        } else {
            GpsSnapshot(hasFix = false)
        }

        val exportedGps = if (editedGps.hasFix) {
            editedGps
        } else {
            originalGps
        }

        val savedUri = exportWatermarkedPhoto(
            context = context,
            watermarkedBitmap = watermarked,
            fileName = fileName,
            quality = quality,
            config = updatedConfig,
            originalExif = editable.sourceExif,
            exportedGps = exportedGps,
            displayedMillis = normalizedEditedMillis,
            isGalleryImport = false
        )

        if (savedUri != null) {
            persistCleanSourceBackup(
                context = context,
                displayName = fileName,
                cleanSourceBitmap = cleanBaseForCopy,
                config = updatedConfig,
                gps = editedGps,
                displayMillis = normalizedEditedMillis,
                originalCaptureMillis = editable.originalCaptureMillis
            )

            val historyEntries = mutableListOf<PhotoEditHistoryEntry>()
            val existingSourceRecord = loadHistoryRecordByName(context, editable.originalSourceName)
            val prevRecord = existingSourceRecord
            if (prevRecord != null) {
                historyEntries.addAll(prevRecord.history)
            }

            val normOrigDisplay = PhotoMetadataEngine.normalizeToSecondMillis(editable.originalDisplayMillis)
            val hasTimestampChange = abs(normalizedEditedMillis - normOrigDisplay) >= 1000L ||
                abs(normalizedEditedMillis - editable.originalCaptureMillis) > 60_000L
            val hasLocationChange = editedGps.locationSource != LocationSource.LIVE_GPS ||
                editedGps.buildLocationHeadline() != editable.gps.buildLocationHeadline() ||
                editedGps.streetAddress != editable.gps.streetAddress ||
                abs(editedGps.latitude - editable.gps.latitude) > 0.0001 ||
                abs(editedGps.longitude - editable.gps.longitude) > 0.0001

            val actionLabel = when {
                hasTimestampChange && hasLocationChange -> "Custom Date/Time & Location Watermark"
                hasLocationChange -> "Custom Location Watermark"
                else -> "Custom Date & Time Watermark"
            }

            historyEntries.add(
                PhotoEditHistoryEntry(
                    recordedAtMillis = System.currentTimeMillis(),
                    actionLabel = actionLabel,
                    sourcePhotoName = editable.originalSourceName,
                    customDateTimeMillis = normalizedEditedMillis,
                    customLocationTitle = if (editedGps.hasFix) editedGps.buildLocationHeadline() else "",
                    customAddress = if (editedGps.hasFix) editedGps.buildFullAddressLine() else "",
                    customLat = if (editedGps.hasFix) editedGps.latitude else null,
                    customLon = if (editedGps.hasFix) editedGps.longitude else null
                )
            )

            val makeModel = listOfNotNull(
                editable.cameraMake,
                editable.cameraModel
            ).filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { null }

            val record = PhotoMetadataRecord(
                fileName = fileName,
                sourcePhotoName = prevRecord?.sourcePhotoName ?: editable.originalSourceName,
                originalCaptureMillis = prevRecord?.originalCaptureMillis ?: editable.originalCaptureMillis,
                originalLatitude = prevRecord?.originalLatitude
                    ?: if (originalGps.hasFix) originalGps.latitude else null,
                originalLongitude = prevRecord?.originalLongitude
                    ?: if (originalGps.hasFix) originalGps.longitude else null,
                originalLocationTitle = prevRecord?.originalLocationTitle
                    ?: if (originalGps.hasFix && editable.gps.locationSource == LocationSource.LIVE_GPS) {
                        editable.gps.buildLocationHeadline()
                    } else null,
                originalAddress = prevRecord?.originalAddress
                    ?: if (originalGps.hasFix && editable.gps.locationSource == LocationSource.LIVE_GPS) {
                        editable.gps.buildFullAddressLine()
                    } else null,
                cameraInfo = prevRecord?.cameraInfo ?: makeModel,
                watermarkDateTimeMillis = normalizedEditedMillis,
                watermarkLocationTitle = if (editedGps.hasFix) editedGps.buildLocationHeadline() else "",
                watermarkAddress = if (editedGps.hasFix) editedGps.buildFullAddressLine() else "",
                watermarkLatitude = if (editedGps.hasFix) editedGps.latitude else null,
                watermarkLongitude = if (editedGps.hasFix) editedGps.longitude else null,
                isCustomTimestamp = hasTimestampChange || (prevRecord?.isCustomTimestamp == true),
                isCustomLocation = hasLocationChange || (prevRecord?.isCustomLocation == true),
                history = historyEntries
            )
            persistPhotoMetadataRecord(context, record)
        }
        if (cleanBaseForCopy != editable.sourceBitmap && !cleanBaseForCopy.isRecycled) {
            cleanBaseForCopy.recycle()
        }
        if (watermarked != editable.sourceBitmap && watermarked != cleanBaseForCopy && !watermarked.isRecycled) {
            watermarked.recycle()
        }

        return savedUri?.let { Pair(it, fileName) }
    }

    fun loadPhotoMetadataRecord(
        context: Context,
        photoUri: Uri,
        displayName: String,
        filePath: String?
    ): PhotoMetadataRecord {
        val sidecar = loadHistoryRecordByName(context, displayName)
        val exif = readSourcePhotoExif(context, photoUri, filePath)
        val makeModel = listOfNotNull(exif.make, exif.model)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
            .ifBlank { null }

        if (sidecar != null) {
            return sidecar.copy(
                originalCaptureMillis = sidecar.originalCaptureMillis ?: exif.dateTimeMillis,
                originalLatitude = sidecar.originalLatitude ?: exif.latitude,
                originalLongitude = sidecar.originalLongitude ?: exif.longitude,
                cameraInfo = sidecar.cameraInfo ?: makeModel
            )
        }

        return PhotoMetadataRecord(
            fileName = displayName,
            sourcePhotoName = null,
            originalCaptureMillis = exif.dateTimeMillis,
            originalLatitude = exif.latitude,
            originalLongitude = exif.longitude,
            originalLocationTitle = null,
            originalAddress = null,
            cameraInfo = makeModel,
            watermarkDateTimeMillis = exif.dateTimeMillis ?: System.currentTimeMillis(),
            watermarkLocationTitle = "",
            watermarkAddress = "",
            watermarkLatitude = exif.latitude,
            watermarkLongitude = exif.longitude,
            isCustomTimestamp = false,
            isCustomLocation = false,
            history = emptyList()
        )
    }

    private data class MediaStoreRowInfo(
        val dataPath: String?,
        val dateTakenMillis: Long?,
        val dateModifiedSec: Long?,
        val dateAddedSec: Long?
    )

    private fun exportWatermarkedPhoto(
        context: Context,
        watermarkedBitmap: Bitmap,
        fileName: String,
        quality: Int,
        config: WatermarkConfig,
        originalExif: SourceExifSnapshot?,
        exportedGps: GpsSnapshot,
        displayedMillis: Long,
        isGalleryImport: Boolean
    ): Uri? {
        val targetMillis = PhotoMetadataEngine.normalizeToSecondMillis(displayedMillis)
        val tempFile = File(context.cacheDir, "shamail_export_${System.nanoTime()}.jpg")
        return try {
            FileOutputStream(tempFile).use { out ->
                val compressed = watermarkedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (!compressed) return null
            }

            runCatching {
                val exif = ExifInterface(tempFile.absolutePath)
                applyPreservedHardwareExif(
                    exif = exif,
                    config = config,
                    originalExif = originalExif,
                    exportedGps = exportedGps,
                    isGalleryImport = isGalleryImport
                )
                exif.saveAttributes()
            }

            val exportedLat = if (exportedGps.hasFix) exportedGps.latitude else originalExif?.latitude
            val exportedLon = if (exportedGps.hasFix) exportedGps.longitude else originalExif?.longitude

            val metaApplied = PhotoMetadataEngine.applyShamailMetadata(
                filePath = tempFile.absolutePath,
                dateTimeMillis = targetMillis,
                latitude = exportedLat,
                longitude = exportedLon
            )
            if (!metaApplied) {
                Log.w("ShamailMeta", "applyShamailMetadata returned false for $fileName")
            }
            PhotoMetadataEngine.syncImageTimestamps(tempFile, targetMillis, context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val savedUri = saveViaMediaStoreQ(
                    context = context,
                    preparedJpegFile = tempFile,
                    fileName = fileName,
                    displayedMillis = targetMillis
                )
                if (savedUri != null) {
                    verifySavedMediaStorePhoto(
                        context = context,
                        savedUri = savedUri,
                        expectedMillis = targetMillis,
                        expectedLat = exportedLat,
                        expectedLon = exportedLon,
                        expectedFileName = fileName
                    )
                }
                savedUri
            } else {
                val savedResult = saveViaLegacyPublicPictures(
                    context = context,
                    preparedJpegFile = tempFile,
                    fileName = fileName,
                    displayedMillis = targetMillis
                ) ?: return null

                val mediaUri = queryExistingMediaUriByPath(context, savedResult.second.absolutePath)
                val rowInfo = mediaUri?.let { queryMediaStoreRowInfo(context, it) }
                PhotoMetadataEngine.verifyExportedMetadataDetailed(
                    filePath = savedResult.second.absolutePath,
                    expectedMillis = targetMillis,
                    expectedLat = exportedLat,
                    expectedLon = exportedLon,
                    expectedFileName = fileName,
                    checkFilesystemMtime = true,
                    mediaStoreDateTakenMillis = rowInfo?.dateTakenMillis,
                    mediaStoreDateModifiedSec = rowInfo?.dateModifiedSec
                )
                savedResult.first
            }
        } catch (e: Exception) {
            Log.e("ShamailMeta", "exportWatermarkedPhoto failed for $fileName: ${e.message}", e)
            null
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun verifySavedMediaStorePhoto(
        context: Context,
        savedUri: Uri,
        expectedMillis: Long,
        expectedLat: Double?,
        expectedLon: Double?,
        expectedFileName: String
    ) {
        val rowInfo = queryMediaStoreRowInfo(context, savedUri)
        val resolvedDataFile = rowInfo?.dataPath?.let { File(it) }?.takeIf { it.exists() && it.canRead() }
        if (resolvedDataFile != null) {
            PhotoMetadataEngine.verifyExportedMetadataDetailed(
                filePath = resolvedDataFile.absolutePath,
                expectedMillis = expectedMillis,
                expectedLat = expectedLat,
                expectedLon = expectedLon,
                expectedFileName = expectedFileName,
                checkFilesystemMtime = true,
                mediaStoreDateTakenMillis = rowInfo.dateTakenMillis,
                mediaStoreDateModifiedSec = rowInfo.dateModifiedSec
            )
            return
        }

        val verifyTemp = File(context.cacheDir, "shamail_verify_${System.nanoTime()}.jpg")
        try {
            context.contentResolver.openInputStream(savedUri)?.use { input ->
                FileOutputStream(verifyTemp).use { out ->
                    input.copyTo(out)
                }
            }
            if (verifyTemp.exists() && verifyTemp.length() > 0L) {
                PhotoMetadataEngine.verifyExportedMetadataDetailed(
                    filePath = verifyTemp.absolutePath,
                    expectedMillis = expectedMillis,
                    expectedLat = expectedLat,
                    expectedLon = expectedLon,
                    expectedFileName = expectedFileName,
                    checkFilesystemMtime = false,
                    mediaStoreDateTakenMillis = rowInfo?.dateTakenMillis,
                    mediaStoreDateModifiedSec = rowInfo?.dateModifiedSec
                )
            } else {
                Log.i("ShamailMeta", "VERIFY FAIL [$expectedFileName]: could not read back saved MediaStore stream")
            }
        } catch (e: Exception) {
            Log.i("ShamailMeta", "VERIFY FAIL [$expectedFileName]: ${e.message}")
        } finally {
            if (verifyTemp.exists()) {
                verifyTemp.delete()
            }
        }
    }

    private fun saveViaMediaStoreQ(
        context: Context,
        preparedJpegFile: File,
        fileName: String,
        displayedMillis: Long
    ): Uri? {
        val targetMillis = PhotoMetadataEngine.normalizeToSecondMillis(displayedMillis)
        val timeSec = (targetMillis / 1000L).coerceAtLeast(1L)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER_NAME")
            put(MediaStore.Images.Media.DATE_TAKEN, targetMillis)
            put(MediaStore.Images.Media.DATE_ADDED, timeSec)
            put(MediaStore.Images.Media.DATE_MODIFIED, timeSec)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                preparedJpegFile.inputStream().use { input ->
                    input.copyTo(out)
                    out.flush()
                }
            } ?: run {
                runCatching { resolver.delete(uri, null, null) }
                return null
            }

            // Try syncing pending file mtime before clearing IS_PENDING where FUSE allows it
            queryMediaStoreRowInfo(context, uri)?.dataPath?.let { pendingPath ->
                val pendingFile = File(pendingPath)
                if (pendingFile.exists()) {
                    PhotoMetadataEngine.syncImageTimestamps(pendingFile, targetMillis, context)
                }
            }

            val doneValues = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
                put(MediaStore.Images.Media.DATE_TAKEN, targetMillis)
                put(MediaStore.Images.Media.DATE_ADDED, timeSec)
                put(MediaStore.Images.Media.DATE_MODIFIED, timeSec)
            }
            val updatedRows = runCatching { resolver.update(uri, doneValues, null, null) }.getOrDefault(0)
            if (updatedRows == 0) {
                val minimalDone = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                runCatching { resolver.update(uri, minimalDone, null, null) }
            }

            // Re-sync underlying physical file mtime and MediaStore columns AFTER IS_PENDING=0 scan completes
            val finalPath = queryMediaStoreRowInfo(context, uri)?.dataPath
            syncMediaStoreImageTimestamps(context, uri, finalPath, targetMillis)
            uri
        } catch (e: Exception) {
            Log.e("ShamailMeta", "saveViaMediaStoreQ failed for $fileName: ${e.message}", e)
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveViaLegacyPublicPictures(
        context: Context,
        preparedJpegFile: File,
        fileName: String,
        displayedMillis: Long
    ): Pair<Uri, File>? {
        val targetMillis = PhotoMetadataEngine.normalizeToSecondMillis(displayedMillis)
        return try {
            @Suppress("DEPRECATION")
            val picturesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val targetDir = File(picturesRoot, SUBFOLDER_NAME)
            if (!targetDir.exists()) targetDir.mkdirs()

            val outFile = File(targetDir, fileName)
            preparedJpegFile.copyTo(outFile, overwrite = true)
            PhotoMetadataEngine.syncImageTimestamps(outFile, targetMillis, context)

            val scannedUri = scanImageFileAndAwaitUri(context, outFile.absolutePath)
                ?: queryExistingMediaUriByPath(context, outFile.absolutePath)

            if (scannedUri != null) {
                updateMediaStoreDateColumns(context, scannedUri, targetMillis)
                // Re-apply filesystem timestamps in case MediaScanner touched file attributes
                PhotoMetadataEngine.syncImageTimestamps(outFile, targetMillis, context)
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
            Pair(uri, outFile)
        } catch (e: Exception) {
            Log.e("ShamailMeta", "saveViaLegacyPublicPictures failed for $fileName: ${e.message}", e)
            null
        }
    }

    /**
     * Reusable MediaStore + filesystem synchronization helper for existing or newly exported images.
     * Avoids creating duplicate MediaStore entries and respects Scoped Storage restrictions gracefully.
     */
    fun syncExistingPhotoTimestamps(
        context: Context,
        uri: Uri,
        filePath: String?,
        timestampMillis: Long
    ): Boolean {
        val targetMillis = PhotoMetadataEngine.normalizeToSecondMillis(timestampMillis)
        val resolvedPath = filePath ?: queryMediaStoreRowInfo(context, uri)?.dataPath
        val mediaUri = if (uri.authority == MediaStore.AUTHORITY) {
            uri
        } else if (!resolvedPath.isNullOrBlank()) {
            queryExistingMediaUriByPath(context, resolvedPath)
        } else {
            null
        }
        return syncMediaStoreImageTimestamps(context, mediaUri, resolvedPath, targetMillis)
    }

    private fun syncMediaStoreImageTimestamps(
        context: Context,
        mediaUri: Uri?,
        filePath: String?,
        timestampMillis: Long
    ): Boolean {
        val targetMillis = PhotoMetadataEngine.normalizeToSecondMillis(timestampMillis)
        val targetSec = (targetMillis / 1000L).coerceAtLeast(1L)
        var fsSynced = false

        if (!filePath.isNullOrBlank()) {
            val targetFile = File(filePath)
            if (targetFile.exists()) {
                fsSynced = PhotoMetadataEngine.syncImageTimestamps(targetFile, targetMillis, context).lastModifiedSuccess
            }
        }

        var targetUri = mediaUri
        if (targetUri == null && !filePath.isNullOrBlank()) {
            targetUri = queryExistingMediaUriByPath(context, filePath)
        }

        var msUpdated = false
        if (targetUri != null) {
            msUpdated = updateMediaStoreDateColumns(context, targetUri, targetMillis)

            val afterInfo = queryMediaStoreRowInfo(context, targetUri)
            val dateModMismatch = afterInfo?.dateModifiedSec != null &&
                abs(afterInfo.dateModifiedSec - targetSec) > 2L

            if (dateModMismatch && !filePath.isNullOrBlank() && fsSynced) {
                // Trigger scanner on existing path so MediaProvider refreshes DATE_MODIFIED from synced file mtime
                val rescannedUri = scanImageFileAndAwaitUri(context, filePath) ?: targetUri
                msUpdated = updateMediaStoreDateColumns(context, rescannedUri, targetMillis) || msUpdated
                val finalFile = File(filePath)
                if (finalFile.exists()) {
                    PhotoMetadataEngine.syncImageTimestamps(finalFile, targetMillis, context)
                }
            }
        } else if (!filePath.isNullOrBlank()) {
            val scanned = scanImageFileAndAwaitUri(context, filePath)
            if (scanned != null) {
                msUpdated = updateMediaStoreDateColumns(context, scanned, targetMillis)
                val finalFile = File(filePath)
                if (finalFile.exists()) {
                    PhotoMetadataEngine.syncImageTimestamps(finalFile, targetMillis, context)
                }
            }
        }

        return fsSynced || msUpdated
    }

    private fun updateMediaStoreDateColumns(
        context: Context,
        uri: Uri,
        timestampMillis: Long
    ): Boolean {
        val targetMillis = PhotoMetadataEngine.normalizeToSecondMillis(timestampMillis)
        val timeSec = (targetMillis / 1000L).coerceAtLeast(1L)
        val resolver = context.contentResolver

        val fullValues = ContentValues().apply {
            put(MediaStore.Images.Media.DATE_TAKEN, targetMillis)
            put(MediaStore.Images.Media.DATE_ADDED, timeSec)
            put(MediaStore.Images.Media.DATE_MODIFIED, timeSec)
        }
        val fullCount = runCatching { resolver.update(uri, fullValues, null, null) }.getOrNull()
        if (fullCount != null && fullCount > 0) return true

        val takenAndMod = ContentValues().apply {
            put(MediaStore.Images.Media.DATE_TAKEN, targetMillis)
            put(MediaStore.Images.Media.DATE_MODIFIED, timeSec)
        }
        val partialCount = runCatching { resolver.update(uri, takenAndMod, null, null) }.getOrNull()
        if (partialCount != null && partialCount > 0) return true

        val takenOnly = ContentValues().apply {
            put(MediaStore.Images.Media.DATE_TAKEN, targetMillis)
        }
        val takenCount = runCatching { resolver.update(uri, takenOnly, null, null) }.getOrNull()
        if (takenCount == null || takenCount == 0) {
            Log.w("ShamailMeta", "Unable to update MediaStore date columns for $uri (Scoped Storage restriction)")
            return false
        }
        return true
    }

    private fun scanImageFileAndAwaitUri(context: Context, path: String): Uri? {
        val latch = CountDownLatch(1)
        var scannedUri: Uri? = null
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(path),
                arrayOf("image/jpeg")
            ) { _, uri ->
                scannedUri = uri
                latch.countDown()
            }
            latch.await(2500L, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w("ShamailMeta", "MediaScannerConnection.scanFile warning for $path: ${e.message}")
        }
        return scannedUri
    }

    @Suppress("DEPRECATION")
    private fun queryExistingMediaUriByPath(context: Context, path: String): Uri? {
        return runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(path),
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                    ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                } else null
            }
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun queryMediaStoreRowInfo(context: Context, uri: Uri): MediaStoreRowInfo? {
        return runCatching {
            val cols = arrayOf(
                MediaStore.MediaColumns.DATA,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.DATE_ADDED
            )
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                val dataIdx = c.getColumnIndex(MediaStore.MediaColumns.DATA)
                val takenIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val modIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
                val addedIdx = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                MediaStoreRowInfo(
                    dataPath = if (dataIdx >= 0 && !c.isNull(dataIdx)) c.getString(dataIdx) else null,
                    dateTakenMillis = if (takenIdx >= 0 && !c.isNull(takenIdx)) c.getLong(takenIdx) else null,
                    dateModifiedSec = if (modIdx >= 0 && !c.isNull(modIdx)) c.getLong(modIdx) else null,
                    dateAddedSec = if (addedIdx >= 0 && !c.isNull(addedIdx)) c.getLong(addedIdx) else null
                )
            }
        }.getOrNull()
    }

    private fun applyPreservedHardwareExif(
        exif: ExifInterface,
        @Suppress("UNUSED_PARAMETER") config: WatermarkConfig,
        originalExif: SourceExifSnapshot?,
        exportedGps: GpsSnapshot,
        isGalleryImport: Boolean
    ) {
        if (originalExif != null) {
            if (!originalExif.make.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MAKE, originalExif.make)
            if (!originalExif.model.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MODEL, originalExif.model)
            if (!originalExif.exposureTime.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, originalExif.exposureTime)
            if (!originalExif.fNumber.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_F_NUMBER, originalExif.fNumber)
            if (!originalExif.isoSpeed.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, originalExif.isoSpeed)
            if (!originalExif.focalLength.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, originalExif.focalLength)
            if (!originalExif.flash.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_FLASH, originalExif.flash)
            if (!originalExif.whiteBalance.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_WHITE_BALANCE, originalExif.whiteBalance)
        } else if (!isGalleryImport) {
            if (!Build.MANUFACTURER.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MAKE, Build.MANUFACTURER)
            if (!Build.MODEL.isNullOrBlank()) exif.setAttribute(ExifInterface.TAG_MODEL, Build.MODEL)
        }

        if (exportedGps.hasFix && exportedGps.locationSource == LocationSource.LIVE_GPS && !isGalleryImport) {
            exif.setAttribute(
                ExifInterface.TAG_GPS_PROCESSING_METHOD,
                if (exportedGps.isOfflineAddress) "GPS_OFFLINE" else "FUSED_GPS"
            )
        }

        exif.setAttribute(ExifInterface.TAG_SOFTWARE, PhotoMetadataEngine.SOFTWARE_NAME)
        exif.setAttribute(ExifInterface.TAG_ARTIST, PhotoMetadataEngine.ARTIST_NAME)
    }

    fun listSavedPhotos(context: Context): List<SavedPhotoItem> {
        val items = mutableListOf<SavedPhotoItem>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val imgProjection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.RELATIVE_PATH
            )
            val imgSelection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%$SUBFOLDER_NAME%")
            val imgSortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            runCatching {
                context.contentResolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    imgProjection,
                    imgSelection,
                    selectionArgs,
                    imgSortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val dateTakenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                    val dateModCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "SHAMAIL_GPS.jpg"
                        val dateAddedSec = cursor.getLong(dateAddedCol)
                        val dateTakenMs = if (dateTakenCol >= 0 && !cursor.isNull(dateTakenCol)) cursor.getLong(dateTakenCol) else 0L
                        val dateModSec = if (dateModCol >= 0 && !cursor.isNull(dateModCol)) cursor.getLong(dateModCol) else 0L
                        val effectiveDateSec = (dateTakenMs / 1000L).takeIf { it > 0L }
                            ?: dateModSec.takeIf { it > 0L }
                            ?: dateAddedSec
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        val provenance = readProvenanceBadge(context, contentUri, null, name)
                        items.add(
                            SavedPhotoItem(
                                uri = contentUri,
                                displayName = name,
                                dateAddedSeconds = effectiveDateSec,
                                filePath = null,
                                timestampProvenance = provenance,
                                isVideo = false,
                                durationMillis = 0L
                            )
                        )
                    }
                }
            }

            val vidProjection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.RELATIVE_PATH
            )
            val vidSelection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val vidSortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

            runCatching {
                context.contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    vidProjection,
                    vidSelection,
                    selectionArgs,
                    vidSortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                    val durCol = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val name = cursor.getString(nameCol) ?: "SHAMAIL_VID.mp4"
                        val dateSec = cursor.getLong(dateCol)
                        val durationMs = if (durCol >= 0) cursor.getLong(durCol) else 0L
                        val contentUri = ContentUris.withAppendedId(
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                            id
                        )
                        items.add(
                            SavedPhotoItem(
                                uri = contentUri,
                                displayName = name,
                                dateAddedSeconds = dateSec,
                                filePath = null,
                                timestampProvenance = "",
                                isVideo = true,
                                durationMillis = durationMs
                            )
                        )
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val picDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                SUBFOLDER_NAME
            )
            if (picDir.exists()) {
                picDir.listFiles { f -> f.isFile && (f.name.endsWith(".jpg", true) || f.name.endsWith(".jpeg", true)) }
                    ?.forEach { file ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val provenance = readProvenanceBadge(context, uri, file, file.name)
                        items.add(
                            SavedPhotoItem(
                                uri = uri,
                                displayName = file.name,
                                dateAddedSeconds = file.lastModified() / 1000L,
                                filePath = file.absolutePath,
                                timestampProvenance = provenance,
                                isVideo = false,
                                durationMillis = 0L
                            )
                        )
                    }
            }

            @Suppress("DEPRECATION")
            val movDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                SUBFOLDER_NAME
            )
            if (movDir.exists()) {
                movDir.listFiles { f -> f.isFile && f.name.endsWith(".mp4", true) }
                    ?.forEach { file ->
                        val uri = FileProvider.getUriForFile(
                            context,
                            "${context.packageName}.fileprovider",
                            file
                        )
                        val durationMs = Mp4MetadataUpdater.readMetadata(file).durationMillis
                        items.add(
                            SavedPhotoItem(
                                uri = uri,
                                displayName = file.name,
                                dateAddedSeconds = file.lastModified() / 1000L,
                                filePath = file.absolutePath,
                                timestampProvenance = "",
                                isVideo = true,
                                durationMillis = durationMs
                            )
                        )
                    }
            }
        }
        return items.sortedByDescending { it.dateAddedSeconds }
    }

    fun readProvenanceBadge(context: Context, uri: Uri, file: File?, displayName: String? = null): String {
        val targetName = displayName ?: file?.name
        if (!targetName.isNullOrBlank()) {
            val sidecar = loadHistoryRecordByName(context, targetName)
            if (sidecar != null) {
                return when {
                    sidecar.isCustomTimestamp && sidecar.isCustomLocation -> "Custom Location & Time"
                    sidecar.isCustomTimestamp -> "Custom Date & Time"
                    sidecar.isCustomLocation -> "Custom Location Info"
                    else -> "Verified Capture Info"
                }
            }
        }

        return try {
            val exif = if (file != null && file.exists()) {
                ExifInterface(file.absolutePath)
            } else {
                context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
            }
            val comment = exif?.getAttribute(ExifInterface.TAG_USER_COMMENT)
                ?: exif?.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
                ?: ""
            when {
                comment.contains("CUSTOM_WATERMARK", ignoreCase = true) ||
                    comment.contains("CUSTOM_DISPLAY_TIME", ignoreCase = true) -> "Custom Watermark Info"
                comment.contains("GALLERY_GPS", ignoreCase = true) -> "Gallery Watermark Copy"
                comment.contains("VERIFIED_GPS", ignoreCase = true) -> "Verified GPS Time"
                else -> "Verified Capture Info"
            }
        } catch (_: Exception) {
            "Saved in $RELATIVE_PICTURES_PATH"
        }
    }

    fun deletePhoto(context: Context, uri: Uri, filePath: String?): Boolean {
        return try {
            if (!filePath.isNullOrBlank()) {
                val f = File(filePath)
                if (f.exists() && f.delete()) return true
            }
            context.contentResolver.delete(uri, null, null) > 0
        } catch (_: Exception) {
            false
        }
    }

    private fun parseExifDateTime(raw: String?): Long? {
        return PhotoMetadataEngine.parseExifTimestampMillis(raw)
    }

    private fun ensureUniqueOutputFileName(context: Context, requestedName: String): String {
        val existingNames = listSavedPhotos(context).map { it.displayName }.toHashSet()
        if (!existingNames.contains(requestedName)) return requestedName
        val dotIdx = requestedName.lastIndexOf('.')
        val stem = if (dotIdx > 0) requestedName.substring(0, dotIdx) else requestedName
        val ext = if (dotIdx > 0) requestedName.substring(dotIdx) else ".jpg"
        var index = 1
        while (existingNames.contains("${stem}_$index$ext")) {
            index++
        }
        return "${stem}_$index$ext"
    }

    private fun sanitizeCacheKey(displayName: String): String {
        return displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }

    private fun persistPhotoMetadataRecord(context: Context, record: PhotoMetadataRecord) {
        runCatching {
            val dir = File(context.filesDir, HISTORY_DIR)
            if (!dir.exists()) dir.mkdirs()

            val histArray = JSONArray()
            record.history.forEach { entry ->
                histArray.put(JSONObject().apply {
                    put("recordedAtMillis", entry.recordedAtMillis)
                    put("actionLabel", entry.actionLabel)
                    if (entry.sourcePhotoName != null) put("sourcePhotoName", entry.sourcePhotoName)
                    if (entry.customDateTimeMillis != null) put("customDateTimeMillis", entry.customDateTimeMillis)
                    if (entry.customLocationTitle != null) put("customLocationTitle", entry.customLocationTitle)
                    if (entry.customAddress != null) put("customAddress", entry.customAddress)
                    if (entry.customLat != null) put("customLat", entry.customLat)
                    if (entry.customLon != null) put("customLon", entry.customLon)
                })
            }

            val json = JSONObject().apply {
                put("fileName", record.fileName)
                if (record.sourcePhotoName != null) put("sourcePhotoName", record.sourcePhotoName)
                if (record.originalCaptureMillis != null) put("originalCaptureMillis", record.originalCaptureMillis)
                if (record.originalLatitude != null) put("originalLatitude", record.originalLatitude)
                if (record.originalLongitude != null) put("originalLongitude", record.originalLongitude)
                if (record.originalLocationTitle != null) put("originalLocationTitle", record.originalLocationTitle)
                if (record.originalAddress != null) put("originalAddress", record.originalAddress)
                if (record.cameraInfo != null) put("cameraInfo", record.cameraInfo)
                put("watermarkDateTimeMillis", record.watermarkDateTimeMillis)
                put("watermarkLocationTitle", record.watermarkLocationTitle)
                put("watermarkAddress", record.watermarkAddress)
                if (record.watermarkLatitude != null) put("watermarkLatitude", record.watermarkLatitude)
                if (record.watermarkLongitude != null) put("watermarkLongitude", record.watermarkLongitude)
                put("isCustomTimestamp", record.isCustomTimestamp)
                put("isCustomLocation", record.isCustomLocation)
                put("history", histArray)
            }

            val safeKey = sanitizeCacheKey(record.fileName)
            File(dir, "$safeKey.json").writeText(json.toString())

            val files = dir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            if (files.size > MAX_HISTORY_RECORDS) {
                files.drop(MAX_HISTORY_RECORDS).forEach { it.delete() }
            }
        }
    }

    private fun loadHistoryRecordByName(context: Context, displayName: String): PhotoMetadataRecord? {
        return runCatching {
            val dir = File(context.filesDir, HISTORY_DIR)
            val file = File(dir, "${sanitizeCacheKey(displayName)}.json")
            if (!file.exists()) return null
            val obj = JSONObject(file.readText())
            val histArr = obj.optJSONArray("history") ?: JSONArray()
            val histList = mutableListOf<PhotoEditHistoryEntry>()
            for (i in 0 until histArr.length()) {
                val item = histArr.optJSONObject(i) ?: continue
                histList.add(
                    PhotoEditHistoryEntry(
                        recordedAtMillis = item.optLong("recordedAtMillis", 0L),
                        actionLabel = item.optString("actionLabel", "Custom Watermark"),
                        sourcePhotoName = item.optString("sourcePhotoName").ifBlank { null },
                        customDateTimeMillis = if (item.has("customDateTimeMillis")) item.optLong("customDateTimeMillis") else null,
                        customLocationTitle = item.optString("customLocationTitle").ifBlank { null },
                        customAddress = item.optString("customAddress").ifBlank { null },
                        customLat = if (item.has("customLat")) item.optDouble("customLat") else null,
                        customLon = if (item.has("customLon")) item.optDouble("customLon") else null
                    )
                )
            }
            PhotoMetadataRecord(
                fileName = obj.optString("fileName", displayName),
                sourcePhotoName = obj.optString("sourcePhotoName").ifBlank { null },
                originalCaptureMillis = if (obj.has("originalCaptureMillis")) obj.optLong("originalCaptureMillis") else null,
                originalLatitude = if (obj.has("originalLatitude")) obj.optDouble("originalLatitude") else null,
                originalLongitude = if (obj.has("originalLongitude")) obj.optDouble("originalLongitude") else null,
                originalLocationTitle = obj.optString("originalLocationTitle").ifBlank { null },
                originalAddress = obj.optString("originalAddress").ifBlank { null },
                cameraInfo = obj.optString("cameraInfo").ifBlank { null },
                watermarkDateTimeMillis = obj.optLong("watermarkDateTimeMillis", System.currentTimeMillis()),
                watermarkLocationTitle = obj.optString("watermarkLocationTitle", ""),
                watermarkAddress = obj.optString("watermarkAddress", ""),
                watermarkLatitude = if (obj.has("watermarkLatitude")) obj.optDouble("watermarkLatitude") else null,
                watermarkLongitude = if (obj.has("watermarkLongitude")) obj.optDouble("watermarkLongitude") else null,
                isCustomTimestamp = obj.optBoolean("isCustomTimestamp", false),
                isCustomLocation = obj.optBoolean("isCustomLocation", false),
                history = histList
            )
        }.getOrNull()
    }

    private fun persistCleanSourceBackup(
        context: Context,
        displayName: String,
        cleanSourceBitmap: Bitmap,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        displayMillis: Long,
        originalCaptureMillis: Long
    ) {
        runCatching {
            if (cleanSourceBitmap.isRecycled) return@runCatching
            val dir = File(context.filesDir, SOURCE_CACHE_DIR)
            if (!dir.exists()) dir.mkdirs()

            val safeKey = sanitizeCacheKey(displayName)
            val imgFile = File(dir, "$safeKey.jpg")
            FileOutputStream(imgFile).use { out ->
                cleanSourceBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            val metaJson = JSONObject().apply {
                put("displayMillis", displayMillis)
                put("originalCaptureMillis", originalCaptureMillis)
                put("gps", JSONObject().apply {
                    put("hasFix", gps.hasFix)
                    put("latitude", gps.latitude)
                    put("longitude", gps.longitude)
                    put("accuracyMeters", gps.accuracyMeters.toDouble())
                    put("altitudeMeters", gps.altitudeMeters)
                    put("gpsTimestampMillis", gps.gpsTimestampMillis)
                    put("city", gps.city)
                    put("district", gps.district)
                    put("province", gps.province)
                    put("country", gps.country)
                    put("streetAddress", gps.streetAddress)
                    put("isOfflineAddress", gps.isOfflineAddress)
                    put("locationSource", gps.locationSource.name)
                })
                put("config", JSONObject().apply {
                    put("organizationName", config.organizationName)
                    put("customLogoPath", config.customLogoPath ?: "")
                    put("is24HourFormat", config.is24HourFormat)
                    put("dateFormatPattern", config.dateFormatPattern)
                    put("watermarkSize", config.watermarkSize.name)
                    put("watermarkOpacity", config.watermarkOpacity)
                    put("fontScale", config.fontScale.toDouble())
                    put("watermarkPosition", config.watermarkPosition.name)
                    put("showLocationTitle", config.showLocationTitle)
                    put("showFullAddress", config.showFullAddress)
                    put("showCoordinates", config.showCoordinates)
                    put("showDateTime", config.showDateTime)
                    put("showLogo", config.showLogo)
                    put("coordFormat", config.coordFormat.name)
                    put("jpegQuality", config.jpegQuality)
                })
            }
            File(dir, "$safeKey.json").writeText(metaJson.toString())

            val metaFiles = dir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            if (metaFiles.size > MAX_CACHED_SOURCES) {
                metaFiles.drop(MAX_CACHED_SOURCES).forEach { oldMeta ->
                    val stem = oldMeta.name.removeSuffix(".json")
                    oldMeta.delete()
                    File(dir, "$stem.jpg").delete()
                }
            }
        }
    }

    private fun gpsToJson(gps: GpsSnapshot): JSONObject {
        return JSONObject().apply {
            put("hasFix", gps.hasFix)
            put("latitude", gps.latitude)
            put("longitude", gps.longitude)
            put("accuracyMeters", gps.accuracyMeters.toDouble())
            put("altitudeMeters", gps.altitudeMeters)
            put("gpsTimestampMillis", gps.gpsTimestampMillis)
            put("city", gps.city)
            put("district", gps.district)
            put("province", gps.province)
            put("country", gps.country)
            put("streetAddress", gps.streetAddress)
            put("isOfflineAddress", gps.isOfflineAddress)
            put("locationSource", gps.locationSource.name)
        }
    }

    fun gpsTimelineToJsonString(timeline: List<VideoGpsKeyframe>): String {
        val arr = gpsTimelineToJsonArray(timeline)
        return arr.toString()
    }

    fun jsonStringToGpsTimeline(raw: String?): List<VideoGpsKeyframe> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { jsonArrayToGpsTimeline(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun gpsTimelineToJsonArray(timeline: List<VideoGpsKeyframe>): JSONArray {
        val arr = JSONArray()
        timeline.forEach { kf ->
            arr.put(JSONObject().apply {
                put("offsetMs", kf.offsetMs)
                put("gps", gpsToJson(kf.gps))
            })
        }
        return arr
    }

    private fun jsonArrayToGpsTimeline(arr: JSONArray?): List<VideoGpsKeyframe> {
        if (arr == null || arr.length() == 0) return emptyList()
        val list = ArrayList<VideoGpsKeyframe>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val offset = item.optLong("offsetMs", 0L).coerceAtLeast(0L)
            val snap = jsonToGps(item.optJSONObject("gps"))
            if (snap.hasFix) {
                list.add(VideoGpsKeyframe(offsetMs = offset, gps = snap))
            }
        }
        return list.sortedBy { it.offsetMs }
    }

    private fun jsonToGps(obj: JSONObject?): GpsSnapshot {
        if (obj == null) return GpsSnapshot()
        val sourceStr = obj.optString("locationSource", LocationSource.LIVE_GPS.name)
        return GpsSnapshot(
            hasFix = obj.optBoolean("hasFix", false),
            latitude = obj.optDouble("latitude", 0.0),
            longitude = obj.optDouble("longitude", 0.0),
            accuracyMeters = obj.optDouble("accuracyMeters", 0.0).toFloat(),
            altitudeMeters = obj.optDouble("altitudeMeters", 0.0),
            gpsTimestampMillis = obj.optLong("gpsTimestampMillis", 0L),
            city = obj.optString("city", ""),
            district = obj.optString("district", ""),
            province = obj.optString("province", ""),
            country = obj.optString("country", ""),
            streetAddress = obj.optString("streetAddress", ""),
            isOfflineAddress = obj.optBoolean("isOfflineAddress", false),
            locationSource = runCatching { LocationSource.valueOf(sourceStr) }
                .getOrDefault(LocationSource.LIVE_GPS)
        )
    }

    private fun jsonToConfig(obj: JSONObject?, fallback: WatermarkConfig): WatermarkConfig {
        if (obj == null) return fallback
        val sizeStr = obj.optString("watermarkSize", fallback.watermarkSize.name)
        val posStr = obj.optString("watermarkPosition", fallback.watermarkPosition.name)
        val coordStr = obj.optString("coordFormat", fallback.coordFormat.name)
        val logoPath = obj.optString("customLogoPath", "").ifBlank { null }
        return fallback.copy(
            organizationName = obj.optString("organizationName", fallback.organizationName),
            customLogoPath = logoPath,
            is24HourFormat = obj.optBoolean("is24HourFormat", fallback.is24HourFormat),
            dateFormatPattern = obj.optString("dateFormatPattern", fallback.dateFormatPattern),
            watermarkSize = runCatching { WatermarkSize.valueOf(sizeStr) }.getOrDefault(fallback.watermarkSize),
            watermarkOpacity = obj.optInt("watermarkOpacity", fallback.watermarkOpacity).coerceIn(30, 95),
            fontScale = obj.optDouble("fontScale", fallback.fontScale.toDouble()).toFloat().coerceIn(0.8f, 1.35f),
            watermarkPosition = runCatching { WatermarkPosition.valueOf(posStr) }.getOrDefault(fallback.watermarkPosition),
            showLocationTitle = obj.optBoolean("showLocationTitle", fallback.showLocationTitle),
            showFullAddress = obj.optBoolean("showFullAddress", fallback.showFullAddress),
            showCoordinates = obj.optBoolean("showCoordinates", fallback.showCoordinates),
            showDateTime = obj.optBoolean("showDateTime", fallback.showDateTime),
            showLogo = obj.optBoolean("showLogo", fallback.showLogo),
            coordFormat = runCatching { CoordFormat.valueOf(coordStr) }.getOrDefault(fallback.coordFormat),
            jpegQuality = obj.optInt("jpegQuality", fallback.jpegQuality).coerceIn(70, 100)
        )
    }

    // ==================== VIDEO RECORDING & WATERMARKING ====================

    fun isVideoMedia(context: Context, uri: Uri, displayName: String? = null): Boolean {
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        if (mime.startsWith("video/", ignoreCase = true)) return true
        if (uri.toString().contains("/video/", ignoreCase = true)) return true
        val name = (displayName ?: uri.lastPathSegment.orEmpty()).lowercase(Locale.US)
        return name.endsWith(".mp4") ||
            name.endsWith(".mov") ||
            name.endsWith(".mkv") ||
            name.endsWith(".3gp") ||
            name.endsWith(".webm") ||
            name.endsWith(".m4v")
    }

    fun loadMediaThumbnail(
        context: Context,
        uri: Uri,
        isVideo: Boolean,
        filePath: String? = null
    ): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                return context.contentResolver.loadThumbnail(uri, Size(480, 480), null)
            }
        }
        if (isVideo) {
            return loadVideoFrameBitmap(context, uri, filePath, 0L)
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                BitmapFactory.decodeFile(filePath, bounds)
            } else {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
            }
            var sample = 1
            val longEdge = max(bounds.outWidth, bounds.outHeight)
            while (longEdge / sample > 480) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                BitmapFactory.decodeFile(filePath, opts)
            } else {
                context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            }
        }.getOrNull()
    }

    fun loadVideoFrameBitmap(
        context: Context,
        uri: Uri?,
        filePath: String? = null,
        timeUs: Long = 0L
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            if (!filePath.isNullOrBlank() && File(filePath).exists()) {
                retriever.setDataSource(filePath)
            } else if (uri != null) {
                retriever.setDataSource(context, uri)
            } else {
                return null
            }
            val raw = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
                ?: return null
            if (max(raw.width, raw.height) <= 1440) {
                raw
            } else {
                val scale = 1440f / max(raw.width, raw.height).toFloat()
                val scaled = Bitmap.createScaledBitmap(
                    raw,
                    (raw.width * scale).toInt().coerceAtLeast(1),
                    (raw.height * scale).toInt().coerceAtLeast(1),
                    true
                )
                if (scaled != raw) raw.recycle()
                scaled
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun readSourceVideoMetadata(
        context: Context,
        uri: Uri?,
        filePath: String? = null
    ): Mp4VideoMetadata {
        if (!filePath.isNullOrBlank()) {
            val f = File(filePath)
            if (f.exists()) {
                return Mp4MetadataUpdater.readMetadata(f)
            }
        }
        if (uri == null) return Mp4VideoMetadata(null, null, null, 0L)

        var tempCopy: File? = null
        return try {
            tempCopy = File(context.cacheDir, "probe_vid_${System.nanoTime()}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempCopy).use { out ->
                    input.copyTo(out)
                }
            }
            if (tempCopy.exists() && tempCopy.length() > 32L) {
                Mp4MetadataUpdater.readMetadata(tempCopy)
            } else {
                Mp4VideoMetadata(null, null, null, 0L)
            }
        } catch (_: Exception) {
            Mp4VideoMetadata(null, null, null, 0L)
        } finally {
            runCatching { tempCopy?.delete() }
        }
    }

    fun processAndSaveWatermarkedVideo(
        context: Context,
        sourceFile: File? = null,
        sourceUri: Uri? = null,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        gpsTimeline: List<VideoGpsKeyframe> = emptyList(),
        captureDeviceMillis: Long = System.currentTimeMillis(),
        replaceExistingWatermark: Boolean = false,
        oldGpsForRemoval: GpsSnapshot? = null,
        onProgress: ((Float) -> Unit)? = null
    ): Pair<Uri, String>? {
        val logoBitmap = WatermarkRenderer.loadLogoBitmap(context, config)
        val displayedMillis = config.resolveTimestampMillis(gps, captureDeviceMillis)
        val fileStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(displayedMillis))
        val fileName = ensureUniqueOutputFileName(context, "SHAMAIL_VID_$fileStamp.mp4")

        val effectiveTimeline = gpsTimeline.filter { it.gps.hasFix }.sortedBy { it.offsetMs }
        val primaryGps = effectiveTimeline.firstOrNull()?.gps ?: gps

        val tempRendered = File(context.cacheDir, "rendered_vid_${System.nanoTime()}.mp4")
        try {
            val procResult = VideoWatermarkProcessor.processVideo(
                context = context,
                sourceFile = sourceFile,
                sourceUri = sourceUri,
                outputFile = tempRendered,
                config = config,
                gps = primaryGps,
                gpsTimeline = effectiveTimeline,
                logoBitmap = logoBitmap,
                baseTimestampMillis = displayedMillis,
                replaceExistingWatermark = replaceExistingWatermark,
                oldGpsForRemoval = oldGpsForRemoval,
                onProgress = onProgress
            )
            if (!procResult.success || !tempRendered.exists() || tempRendered.length() < 1024L) {
                return null
            }

            val savedUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveVideoViaMediaStoreQ(context, tempRendered, fileName, displayedMillis)
            } else {
                saveVideoViaLegacyPublicMovies(context, tempRendered, fileName, displayedMillis)
            }

            if (savedUri != null) {
                persistCleanVideoSourceBackup(
                    context = context,
                    displayName = fileName,
                    cleanSourceFile = if (!replaceExistingWatermark) sourceFile else null,
                    config = config,
                    gps = primaryGps,
                    gpsTimeline = effectiveTimeline,
                    displayMillis = displayedMillis,
                    originalCaptureMillis = displayedMillis,
                    durationMillis = procResult.durationMillis
                )
            }

            return savedUri?.let { Pair(it, fileName) }
        } finally {
            runCatching { if (tempRendered.exists()) tempRendered.delete() }
        }
    }

    fun loadEditableVideoContext(
        context: Context,
        videoUri: Uri,
        displayName: String,
        filePath: String?,
        fallbackConfig: WatermarkConfig
    ): EditableVideoContext? {
        val safeKey = sanitizeCacheKey(displayName)
        val cacheDir = File(context.filesDir, VIDEO_SOURCE_CACHE_DIR)
        val cleanVidFile = File(cacheDir, "$safeKey.mp4")
        val cleanMetaFile = File(cacheDir, "$safeKey.json")

        val metaObj = if (cleanMetaFile.exists()) {
            runCatching { JSONObject(cleanMetaFile.readText()) }.getOrNull()
        } else null
        val cachedTimeline = jsonArrayToGpsTimeline(metaObj?.optJSONArray("gpsTimeline"))

        if (cleanVidFile.exists() && metaObj != null) {
            val frame = loadVideoFrameBitmap(context, videoUri, cleanVidFile.absolutePath, 0L)
            if (frame != null) {
                val gps = jsonToGps(metaObj.optJSONObject("gps"))
                val cfg = jsonToConfig(metaObj.optJSONObject("config"), fallbackConfig)
                val dispMs = metaObj.optLong("displayMillis", System.currentTimeMillis())
                val origMs = metaObj.optLong("originalCaptureMillis", dispMs)
                val durMs = metaObj.optLong("durationMillis", 0L)
                return EditableVideoContext(
                    sourceUri = videoUri,
                    cachedSourceFile = cleanVidFile,
                    filePath = filePath,
                    previewFrameBitmap = frame,
                    gps = gps,
                    gpsTimeline = cachedTimeline,
                    config = cfg,
                    originalDisplayMillis = dispMs,
                    originalCaptureMillis = origMs,
                    originalSourceName = displayName,
                    hasCleanOriginal = true,
                    durationMillis = durMs
                )
            }
        }

        val frame = loadVideoFrameBitmap(context, videoUri, filePath, 0L) ?: return null
        val vidMeta = readSourceVideoMetadata(context, videoUri, filePath)
        val cachedGps = metaObj?.let { jsonToGps(it.optJSONObject("gps")) }
        val cachedCfg = metaObj?.let { jsonToConfig(it.optJSONObject("config"), fallbackConfig) } ?: fallbackConfig

        var gps = cachedGps ?: run {
            val lat = vidMeta.latitude
            val lon = vidMeta.longitude
            if (lat != null && lon != null) {
                GpsSnapshot(
                    hasFix = true,
                    latitude = lat,
                    longitude = lon,
                    gpsTimestampMillis = vidMeta.creationTimeMillis ?: System.currentTimeMillis(),
                    locationSource = LocationSource.LIVE_GPS
                )
            } else {
                GpsSnapshot(hasFix = false, gpsTimestampMillis = vidMeta.creationTimeMillis ?: System.currentTimeMillis())
            }
        }

        if (cachedGps == null && gps.hasFix && Geocoder.isPresent()) {
            runCatching {
                @Suppress("DEPRECATION")
                val list = Geocoder(context, Locale.getDefault()).getFromLocation(gps.latitude, gps.longitude, 1)
                val addr = list?.firstOrNull()
                if (addr != null) {
                    val city = addr.locality ?: addr.subAdminArea ?: ""
                    val district = addr.subAdminArea ?: ""
                    val province = addr.adminArea ?: ""
                    val country = addr.countryName ?: ""
                    val street = (0..addr.maxAddressLineIndex).joinToString(", ") {
                        addr.getAddressLine(it).orEmpty()
                    }.trim()
                    gps = gps.copy(
                        city = city,
                        district = district,
                        province = province,
                        country = country,
                        streetAddress = street
                    )
                }
            }
        }

        val origMillis = metaObj?.optLong("displayMillis", 0L)?.takeIf { it > 0L }
            ?: vidMeta.creationTimeMillis
            ?: System.currentTimeMillis()

        return EditableVideoContext(
            sourceUri = videoUri,
            cachedSourceFile = null,
            filePath = filePath,
            previewFrameBitmap = frame,
            gps = gps,
            gpsTimeline = cachedTimeline,
            config = cachedCfg,
            originalDisplayMillis = origMillis,
            originalCaptureMillis = origMillis,
            originalSourceName = displayName,
            hasCleanOriginal = false,
            durationMillis = vidMeta.durationMillis
        )
    }

    fun saveEditedVideoCopy(
        context: Context,
        editable: EditableVideoContext,
        editedMillis: Long,
        is24HourFormat: Boolean,
        dateFormatPattern: String,
        editedGps: GpsSnapshot,
        preserveGpsTimeline: Boolean = false,
        onProgress: ((Float) -> Unit)? = null
    ): Pair<Uri, String>? {
        val updatedConfig = editable.config.copy(
            timestampMode = TimestampMode.MANUAL,
            manualTimestampMillis = editedMillis,
            is24HourFormat = is24HourFormat,
            dateFormatPattern = dateFormatPattern,
            showWatermark = true
        )
        val cleanSource = editable.cachedSourceFile?.takeIf { it.exists() }
        val rawFile = if (cleanSource == null && !editable.filePath.isNullOrBlank()) {
            File(editable.filePath).takeIf { it.exists() }
        } else null

        val timelineToUse = if (preserveGpsTimeline && editable.gpsTimeline.isNotEmpty()) {
            editable.gpsTimeline
        } else {
            emptyList()
        }
        val baseGps = if (timelineToUse.isNotEmpty()) timelineToUse.first().gps else editedGps

        return processAndSaveWatermarkedVideo(
            context = context,
            sourceFile = cleanSource ?: rawFile,
            sourceUri = if (cleanSource == null && rawFile == null) editable.sourceUri else null,
            config = updatedConfig,
            gps = baseGps,
            gpsTimeline = timelineToUse,
            captureDeviceMillis = editedMillis,
            replaceExistingWatermark = cleanSource == null,
            oldGpsForRemoval = editable.gps,
            onProgress = onProgress
        )
    }

    private fun saveVideoViaMediaStoreQ(
        context: Context,
        renderedFile: File,
        fileName: String,
        timestampMillis: Long
    ): Uri? {
        val resolver = context.contentResolver
        val timeSec = (timestampMillis / 1000L).coerceAtLeast(1L)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$SUBFOLDER_NAME")
            put(MediaStore.Video.Media.DATE_TAKEN, timestampMillis)
            put(MediaStore.Video.Media.DATE_ADDED, timeSec)
            put(MediaStore.Video.Media.DATE_MODIFIED, timeSec)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                renderedFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            val doneValues = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.DATE_TAKEN, timestampMillis)
                put(MediaStore.Video.Media.DATE_MODIFIED, timeSec)
            }
            resolver.update(uri, doneValues, null, null)
            uri
        } catch (_: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    private fun saveVideoViaLegacyPublicMovies(
        context: Context,
        renderedFile: File,
        fileName: String,
        timestampMillis: Long
    ): Uri? {
        return try {
            @Suppress("DEPRECATION")
            val moviesRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val targetDir = File(moviesRoot, SUBFOLDER_NAME)
            if (!targetDir.exists()) targetDir.mkdirs()

            val outFile = File(targetDir, fileName)
            renderedFile.copyTo(outFile, overwrite = true)
            outFile.setLastModified(timestampMillis)

            MediaScannerConnection.scanFile(
                context,
                arrayOf(outFile.absolutePath),
                arrayOf("video/mp4"),
                null
            )

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun persistCleanVideoSourceBackup(
        context: Context,
        displayName: String,
        cleanSourceFile: File?,
        config: WatermarkConfig,
        gps: GpsSnapshot,
        gpsTimeline: List<VideoGpsKeyframe>,
        displayMillis: Long,
        originalCaptureMillis: Long,
        durationMillis: Long
    ) {
        runCatching {
            val dir = File(context.filesDir, VIDEO_SOURCE_CACHE_DIR)
            if (!dir.exists()) dir.mkdirs()

            val safeKey = sanitizeCacheKey(displayName)
            if (cleanSourceFile != null && cleanSourceFile.exists()) {
                val cachedVid = File(dir, "$safeKey.mp4")
                if (cleanSourceFile.absolutePath != cachedVid.absolutePath) {
                    cleanSourceFile.copyTo(cachedVid, overwrite = true)
                }
            }

            val metaJson = JSONObject().apply {
                put("displayMillis", displayMillis)
                put("originalCaptureMillis", originalCaptureMillis)
                put("durationMillis", durationMillis)
                put("gps", gpsToJson(gps))
                put("gpsTimeline", gpsTimelineToJsonArray(gpsTimeline))
                put("config", JSONObject().apply {
                    put("organizationName", config.organizationName)
                    put("customLogoPath", config.customLogoPath ?: "")
                    put("is24HourFormat", config.is24HourFormat)
                    put("dateFormatPattern", config.dateFormatPattern)
                    put("watermarkSize", config.watermarkSize.name)
                    put("watermarkOpacity", config.watermarkOpacity)
                    put("fontScale", config.fontScale.toDouble())
                    put("watermarkPosition", config.watermarkPosition.name)
                    put("showLocationTitle", config.showLocationTitle)
                    put("showFullAddress", config.showFullAddress)
                    put("showCoordinates", config.showCoordinates)
                    put("showDateTime", config.showDateTime)
                    put("showLogo", config.showLogo)
                    put("coordFormat", config.coordFormat.name)
                    put("jpegQuality", config.jpegQuality)
                })
            }
            File(dir, "$safeKey.json").writeText(metaJson.toString())

            val metaFiles = dir.listFiles { f -> f.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
            if (metaFiles.size > MAX_CACHED_VIDEO_SOURCES) {
                metaFiles.drop(MAX_CACHED_VIDEO_SOURCES).forEach { oldMeta ->
                    val stem = oldMeta.name.removeSuffix(".json")
                    oldMeta.delete()
                    File(dir, "$stem.mp4").delete()
                }
            }
        }
    }
}
