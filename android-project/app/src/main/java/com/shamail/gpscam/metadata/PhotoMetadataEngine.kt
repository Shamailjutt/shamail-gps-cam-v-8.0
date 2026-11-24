package com.shamail.gpscam.metadata

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

enum class CreationTimeSyncStatus {
    UPDATED,
    IGNORED_BY_FILESYSTEM,
    UNSUPPORTED,
    FAILED_IO,
    FAILED_SECURITY,
    FAILED_OTHER,
    NOT_APPLICABLE_PRE_API_26
}

data class TimestampSyncResult(
    val fileExists: Boolean,
    val requestedMillis: Long,
    val lastModifiedSuccess: Boolean,
    val actualLastModifiedMillis: Long?,
    val creationTimeSuccess: Boolean,
    val creationTimeStatus: CreationTimeSyncStatus,
    val failureReason: String? = null
)

data class MetadataVerificationReport(
    val isValid: Boolean,
    val expectedMillis: Long,
    val exifDateTime: String?,
    val exifDateTimeOriginal: String?,
    val exifDateTimeDigitized: String?,
    val exifOffsetTime: String?,
    val exifOffsetTimeOriginal: String?,
    val exifOffsetTimeDigitized: String?,
    val exifDatesMatch: Boolean,
    val exifOffsetsMatch: Boolean,
    val subSecCleared: Boolean,
    val gpsMatch: Boolean,
    val signatureMatch: Boolean,
    val fileNameMatch: Boolean,
    val fileLastModifiedMillis: Long?,
    val fileLastModifiedMatch: Boolean?,
    val mediaStoreDateTakenMillis: Long?,
    val mediaStoreDateModifiedSec: Long?,
    val mediaStoreMatch: Boolean?,
    val failureReason: String? = null
)

/**
 * Reusable filesystem timestamp synchronization helper:
 * - Updates File.lastModified and verifies its boolean return value
 * - On Android API 26+, attempts Files.setAttribute(..., "basic:creationTime", ...)
 * - Gracefully handles UnsupportedOperationException, IOException, SecurityException
 * - Never falsely reports creationTime success if unsupported or ignored by filesystem
 */
fun syncImageTimestamps(file: File, timestampMillis: Long, context: Context): TimestampSyncResult {
    return PhotoMetadataEngine.syncImageTimestamps(file, timestampMillis, context)
}

object PhotoMetadataEngine {

    private const val TAG = "ShamailMeta"

    const val IMAGE_DESCRIPTION = "SHAMAIL PERA | VERIFIED_DEVICE_CAPTURE"
    const val USER_COMMENT = "VERIFIED_DEVICE_CAPTURE"

    private const val GPS_TOLERANCE_DEGREES = 0.0001
    private const val FS_TIMESTAMP_TOLERANCE_MS = 2000L

    fun normalizeToSecondMillis(millis: Long): Long {
        return Math.floorDiv(millis, 1000L) * 1000L
    }

    fun formatExifTimestamp(millis: Long): String {
        val normalized = normalizeToSecondMillis(millis)
        return SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(normalized))
    }

    fun formatFileStamp(millis: Long): String {
        val normalized = normalizeToSecondMillis(millis)
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(normalized))
    }

    fun formatExifOffset(millis: Long): String {
        val normalized = normalizeToSecondMillis(millis)
        val offsetMs = TimeZone.getDefault().getOffset(normalized)
        val totalMin = abs(offsetMs) / 60_000
        val sign = if (offsetMs >= 0) "+" else "-"
        return String.format(Locale.US, "%s%02d:%02d", sign, totalMin / 60, totalMin % 60)
    }

    fun parseExifTimestampMillis(dateTimeRaw: String?, offsetRaw: String? = null): Long? {
        val text = dateTimeRaw?.trim().orEmpty()
        if (text.isEmpty()) return null
        val tz = resolveExifTimeZone(offsetRaw) ?: TimeZone.getDefault()
        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
                isLenient = false
                timeZone = tz
            }.parse(text)?.time?.let { normalizeToSecondMillis(it) }
        }.getOrNull()
    }

    private fun resolveExifTimeZone(offsetRaw: String?): TimeZone? {
        val raw = offsetRaw?.trim().orEmpty()
        if (raw.isEmpty()) return null
        if (raw.equals("Z", ignoreCase = true) || raw.equals("UTC", ignoreCase = true)) {
            return TimeZone.getTimeZone("UTC")
        }
        val match = Regex("^([+-])(\\d{2}):?(\\d{2})$").matchEntire(raw) ?: return null
        return TimeZone.getTimeZone("GMT${match.groupValues[1]}${match.groupValues[2]}:${match.groupValues[3]}")
    }

    fun syncImageTimestamps(file: File, timestampMillis: Long, context: Context): TimestampSyncResult {
        val appPkg = runCatching { context.applicationContext.packageName }.getOrDefault("")
        val targetMillis = normalizeToSecondMillis(timestampMillis).coerceAtLeast(1000L)

        if (!file.exists() || !file.isFile) {
            val msg = "syncImageTimestamps skipped ($appPkg): missing file ${file.absolutePath}"
            Log.w(TAG, msg)
            return TimestampSyncResult(
                fileExists = false,
                requestedMillis = targetMillis,
                lastModifiedSuccess = false,
                actualLastModifiedMillis = null,
                creationTimeSuccess = false,
                creationTimeStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    CreationTimeSyncStatus.FAILED_IO
                } else {
                    CreationTimeSyncStatus.NOT_APPLICABLE_PRE_API_26
                },
                failureReason = msg
            )
        }

        var lastModOk = false
        var failureNote: String? = null
        try {
            lastModOk = file.setLastModified(targetMillis)
            if (!lastModOk) {
                failureNote = "File.setLastModified($targetMillis) returned false for ${file.name}"
                Log.w(TAG, failureNote)
            }
        } catch (se: SecurityException) {
            failureNote = "File.setLastModified SecurityException for ${file.name}: ${se.message}"
            Log.w(TAG, failureNote)
        } catch (e: Exception) {
            failureNote = "File.setLastModified error for ${file.name}: ${e.message}"
            Log.w(TAG, failureNote)
        }

        val actualLastMod = runCatching { file.lastModified() }.getOrNull()
        val mtimeMatched = lastModOk && actualLastMod != null && abs(actualLastMod - targetMillis) <= FS_TIMESTAMP_TOLERANCE_MS
        if (lastModOk && !mtimeMatched) {
            Log.w(
                TAG,
                "File.setLastModified verification mismatch for ${file.name}: requested=$targetMillis, actual=$actualLastMod"
            )
        }

        var creationOk = false
        var creationStatus = CreationTimeSyncStatus.NOT_APPLICABLE_PRE_API_26
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val path = file.toPath()
                val fileTime = FileTime.fromMillis(targetMillis)
                Files.setAttribute(path, "basic:creationTime", fileTime)

                val readBackMillis = runCatching {
                    Files.readAttributes(path, BasicFileAttributes::class.java).creationTime().toMillis()
                }.getOrNull()

                if (readBackMillis != null && abs(readBackMillis - targetMillis) <= FS_TIMESTAMP_TOLERANCE_MS) {
                    creationOk = true
                    creationStatus = CreationTimeSyncStatus.UPDATED
                } else {
                    creationOk = false
                    creationStatus = CreationTimeSyncStatus.IGNORED_BY_FILESYSTEM
                    Log.i(
                        TAG,
                        "basic:creationTime not updated on filesystem for ${file.name} (requested=$targetMillis, readBack=$readBackMillis)"
                    )
                }
            } catch (uoe: UnsupportedOperationException) {
                creationStatus = CreationTimeSyncStatus.UNSUPPORTED
                Log.w(TAG, "basic:creationTime unsupported for ${file.name}: ${uoe.message}")
            } catch (ioe: IOException) {
                creationStatus = CreationTimeSyncStatus.FAILED_IO
                Log.w(TAG, "basic:creationTime IOException for ${file.name}: ${ioe.message}")
            } catch (se: SecurityException) {
                creationStatus = CreationTimeSyncStatus.FAILED_SECURITY
                Log.w(TAG, "basic:creationTime SecurityException for ${file.name}: ${se.message}")
            } catch (e: Exception) {
                creationStatus = CreationTimeSyncStatus.FAILED_OTHER
                Log.w(TAG, "basic:creationTime failed for ${file.name}: ${e.message}")
            }
        }

        Log.d(
            TAG,
            "syncImageTimestamps [${file.name}]: target=$targetMillis | mtimeOk=$mtimeMatched (actual=$actualLastMod) | creationStatus=$creationStatus"
        )

        return TimestampSyncResult(
            fileExists = true,
            requestedMillis = targetMillis,
            lastModifiedSuccess = mtimeMatched,
            actualLastModifiedMillis = actualLastMod,
            creationTimeSuccess = creationOk,
            creationTimeStatus = creationStatus,
            failureReason = failureNote
        )
    }

    fun applyShamailMetadata(
        filePath: String,
        dateTimeMillis: Long,
        latitude: Double?,
        longitude: Double?
    ): Boolean {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            Log.w(TAG, "applyShamailMetadata skipped: missing file $filePath")
            return false
        }

        val normalizedMillis = normalizeToSecondMillis(dateTimeMillis)

        return try {
            val exif = ExifInterface(file.absolutePath)
            val formattedTimestamp = formatExifTimestamp(normalizedMillis)
            val formattedOffset = formatExifOffset(normalizedMillis)

            exif.setAttribute(ExifInterface.TAG_DATETIME, formattedTimestamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedTimestamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedTimestamp)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, formattedOffset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, formattedOffset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, formattedOffset)

            // Clear conflicting old sub-second fractions so validators never see stale fractions
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, null)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, null)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, null)

            val hasInputCoords = latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite()
            val hasExistingCoords = exif.latLong != null
            if (hasInputCoords) {
                exif.setLatLong(latitude!!, longitude!!)
            }
            if (hasInputCoords || hasExistingCoords) {
                val utcTz = TimeZone.getTimeZone("UTC")
                val gpsDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = utcTz }
                    .format(Date(normalizedMillis))
                val gpsTime = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = utcTz }
                    .format(Date(normalizedMillis))
                exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, gpsDate)
                exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, gpsTime)
            }

            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, IMAGE_DESCRIPTION)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, USER_COMMENT)

            exif.saveAttributes()
            true
        } catch (e: Exception) {
            Log.e(TAG, "applyShamailMetadata failed for $filePath: ${e.message}", e)
            false
        }
    }

    /**
     * Verifies exported metadata against the authoritative user-selected/displayed timestamp.
     * Intentionally edited dates/times (including backward or midnight-crossing edits) are valid
     * whenever EXIF, signature tags, filename, and available filesystem/MediaStore metadata align
     * with [expectedMillis]. Never compares against an outdated original capture timestamp.
     */
    fun verifyExportedMetadata(
        filePath: String,
        expectedMillis: Long,
        expectedLat: Double?,
        expectedLon: Double?,
        expectedFileName: String
    ): Boolean {
        return verifyExportedMetadataDetailed(
            filePath = filePath,
            expectedMillis = expectedMillis,
            expectedLat = expectedLat,
            expectedLon = expectedLon,
            expectedFileName = expectedFileName,
            checkFilesystemMtime = false,
            mediaStoreDateTakenMillis = null,
            mediaStoreDateModifiedSec = null
        ).isValid
    }

    fun verifyExportedMetadataDetailed(
        filePath: String,
        expectedMillis: Long,
        expectedLat: Double?,
        expectedLon: Double?,
        expectedFileName: String,
        checkFilesystemMtime: Boolean = false,
        mediaStoreDateTakenMillis: Long? = null,
        mediaStoreDateModifiedSec: Long? = null
    ): MetadataVerificationReport {
        val targetMillis = normalizeToSecondMillis(expectedMillis)
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            val msg = "file missing at $filePath"
            Log.i(TAG, "VERIFY FAIL [$expectedFileName]: $msg")
            return MetadataVerificationReport(
                isValid = false,
                expectedMillis = targetMillis,
                exifDateTime = null,
                exifDateTimeOriginal = null,
                exifDateTimeDigitized = null,
                exifOffsetTime = null,
                exifOffsetTimeOriginal = null,
                exifOffsetTimeDigitized = null,
                exifDatesMatch = false,
                exifOffsetsMatch = false,
                subSecCleared = false,
                gpsMatch = false,
                signatureMatch = false,
                fileNameMatch = false,
                fileLastModifiedMillis = null,
                fileLastModifiedMatch = null,
                mediaStoreDateTakenMillis = mediaStoreDateTakenMillis,
                mediaStoreDateModifiedSec = mediaStoreDateModifiedSec,
                mediaStoreMatch = null,
                failureReason = msg
            )
        }

        return try {
            val exif = ExifInterface(file.absolutePath)
            val expectedExifTime = formatExifTimestamp(targetMillis)
            val expectedOffset = formatExifOffset(targetMillis)
            val expectedStamp = formatFileStamp(targetMillis)

            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME)
            val dtOrig = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val dtDig = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            val datesOk = dt == expectedExifTime &&
                dtOrig == expectedExifTime &&
                dtDig == expectedExifTime

            val off = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)
            val offOrig = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
            val offDig = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
            val offsetsOk = off == expectedOffset &&
                offOrig == expectedOffset &&
                offDig == expectedOffset

            val subSecCleared = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME).isNullOrBlank() &&
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL).isNullOrBlank() &&
                exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED).isNullOrBlank()

            val latLong = exif.latLong
            val gpsOk = if (expectedLat != null && expectedLon != null &&
                expectedLat.isFinite() && expectedLon.isFinite()
            ) {
                latLong != null &&
                    abs(latLong[0] - expectedLat) <= GPS_TOLERANCE_DEGREES &&
                    abs(latLong[1] - expectedLon) <= GPS_TOLERANCE_DEGREES
            } else {
                true
            }

            val imageDesc = exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
            val imageDescOk = imageDesc == IMAGE_DESCRIPTION

            val rawComment = exif.getAttribute(ExifInterface.TAG_USER_COMMENT)
            val normalizedComment = normalizeUserComment(rawComment)
            val userCommentOk = normalizedComment == USER_COMMENT
            val signatureOk = imageDescOk && userCommentOk

            val expectedNamePattern = Regex(
                "^SHAMAIL_GPS_${Regex.escape(expectedStamp)}(_\\d+)?\\.(jpg|jpeg)$",
                RegexOption.IGNORE_CASE
            )
            val fileNameOk = expectedNamePattern.matches(expectedFileName)

            val actualFileMtime = runCatching { file.lastModified() }.getOrNull()
            val mtimeOk: Boolean? = if (checkFilesystemMtime && actualFileMtime != null && actualFileMtime > 0L) {
                abs(actualFileMtime - targetMillis) <= FS_TIMESTAMP_TOLERANCE_MS
            } else {
                null
            }

            val mediaStoreOk: Boolean? = if (mediaStoreDateTakenMillis != null || mediaStoreDateModifiedSec != null) {
                val takenOk = mediaStoreDateTakenMillis == null ||
                    abs(normalizeToSecondMillis(mediaStoreDateTakenMillis) - targetMillis) <= FS_TIMESTAMP_TOLERANCE_MS
                val modOk = mediaStoreDateModifiedSec == null ||
                    abs((mediaStoreDateModifiedSec * 1000L) - targetMillis) <= FS_TIMESTAMP_TOLERANCE_MS
                takenOk && modOk
            } else {
                null
            }

            val allOk = datesOk &&
                offsetsOk &&
                subSecCleared &&
                gpsOk &&
                signatureOk &&
                fileNameOk &&
                (mtimeOk != false) &&
                (mediaStoreOk != false)

            val failureReason = if (allOk) {
                null
            } else {
                listOfNotNull(
                    if (!datesOk) "EXIF date mismatch (dt=$dt, orig=$dtOrig, dig=$dtDig, expected=$expectedExifTime)" else null,
                    if (!offsetsOk) "EXIF offset mismatch (off=$off, orig=$offOrig, dig=$offDig, expected=$expectedOffset)" else null,
                    if (!subSecCleared) "Stale EXIF SubSecTime present" else null,
                    if (!gpsOk) "GPS mismatch" else null,
                    if (!signatureOk) "Signature mismatch" else null,
                    if (!fileNameOk) "Filename stamp mismatch ($expectedFileName)" else null,
                    if (mtimeOk == false) "Filesystem mtime mismatch (actual=$actualFileMtime, expected=$targetMillis)" else null,
                    if (mediaStoreOk == false) "MediaStore timestamp mismatch (taken=$mediaStoreDateTakenMillis, modSec=$mediaStoreDateModifiedSec, expected=$targetMillis)" else null
                ).joinToString("; ")
            }

            Log.i(
                TAG,
                "VERIFY ${if (allOk) "PASS" else "FAIL"} [$expectedFileName] | " +
                    "datesOk=$datesOk (dt=$dt, orig=$dtOrig, dig=$dtDig, expected=$expectedExifTime) | " +
                    "offsetsOk=$offsetsOk ($expectedOffset) | subSecCleared=$subSecCleared | " +
                    "gpsOk=$gpsOk | sigOk=$signatureOk | fileNameOk=$fileNameOk | " +
                    "mtimeOk=$mtimeOk (actual=$actualFileMtime) | " +
                    "mediaStoreOk=$mediaStoreOk (taken=$mediaStoreDateTakenMillis, modSec=$mediaStoreDateModifiedSec)"
            )

            MetadataVerificationReport(
                isValid = allOk,
                expectedMillis = targetMillis,
                exifDateTime = dt,
                exifDateTimeOriginal = dtOrig,
                exifDateTimeDigitized = dtDig,
                exifOffsetTime = off,
                exifOffsetTimeOriginal = offOrig,
                exifOffsetTimeDigitized = offDig,
                exifDatesMatch = datesOk,
                exifOffsetsMatch = offsetsOk,
                subSecCleared = subSecCleared,
                gpsMatch = gpsOk,
                signatureMatch = signatureOk,
                fileNameMatch = fileNameOk,
                fileLastModifiedMillis = actualFileMtime,
                fileLastModifiedMatch = mtimeOk,
                mediaStoreDateTakenMillis = mediaStoreDateTakenMillis,
                mediaStoreDateModifiedSec = mediaStoreDateModifiedSec,
                mediaStoreMatch = mediaStoreOk,
                failureReason = failureReason
            )
        } catch (e: Exception) {
            Log.i(TAG, "VERIFY FAIL [$expectedFileName]: ${e.message}")
            MetadataVerificationReport(
                isValid = false,
                expectedMillis = targetMillis,
                exifDateTime = null,
                exifDateTimeOriginal = null,
                exifDateTimeDigitized = null,
                exifOffsetTime = null,
                exifOffsetTimeOriginal = null,
                exifOffsetTimeDigitized = null,
                exifDatesMatch = false,
                exifOffsetsMatch = false,
                subSecCleared = false,
                gpsMatch = false,
                signatureMatch = false,
                fileNameMatch = false,
                fileLastModifiedMillis = null,
                fileLastModifiedMatch = null,
                mediaStoreDateTakenMillis = mediaStoreDateTakenMillis,
                mediaStoreDateModifiedSec = mediaStoreDateModifiedSec,
                mediaStoreMatch = null,
                failureReason = e.message
            )
        }
    }

    private fun normalizeUserComment(raw: String?): String {
        val cleaned = (raw ?: "")
            .replace("\u0000", "")
            .trim()
        for (prefix in listOf("ASCII", "UNICODE", "JIS")) {
            if (cleaned.startsWith(prefix, ignoreCase = true)) {
                return cleaned.substring(prefix.length).trim()
            }
        }
        return cleaned
    }
}
