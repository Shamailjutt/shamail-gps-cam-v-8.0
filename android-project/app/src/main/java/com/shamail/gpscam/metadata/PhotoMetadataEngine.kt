package com.shamail.gpscam.metadata

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

object PhotoMetadataEngine {

    private const val TAG = "ShamailMeta"

    const val IMAGE_DESCRIPTION = "SHAMAIL PERA | VERIFIED_DEVICE_CAPTURE"
    const val USER_COMMENT = "VERIFIED_DEVICE_CAPTURE"

    private const val GPS_TOLERANCE_DEGREES = 0.0001

    fun formatExifTimestamp(millis: Long): String {
        return SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(millis))
    }

    fun formatFileStamp(millis: Long): String {
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(millis))
    }

    private fun formatExifOffset(millis: Long): String {
        val offsetMs = TimeZone.getDefault().getOffset(millis)
        val totalMin = abs(offsetMs) / 60_000
        val sign = if (offsetMs >= 0) "+" else "-"
        return String.format(Locale.US, "%s%02d:%02d", sign, totalMin / 60, totalMin % 60)
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

        return try {
            val exif = ExifInterface(file.absolutePath)
            val formattedTimestamp = formatExifTimestamp(dateTimeMillis)
            val formattedOffset = formatExifOffset(dateTimeMillis)

            exif.setAttribute(ExifInterface.TAG_DATETIME, formattedTimestamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, formattedTimestamp)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, formattedTimestamp)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME, formattedOffset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, formattedOffset)
            exif.setAttribute(ExifInterface.TAG_OFFSET_TIME_DIGITIZED, formattedOffset)

            // Clear conflicting old sub-second fractions from previous captures
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME, null)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, null)
            exif.setAttribute(ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, null)

            if (latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite()) {
                exif.setLatLong(latitude, longitude)
                val utcTz = TimeZone.getTimeZone("UTC")
                val gpsDate = SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = utcTz }
                    .format(Date(dateTimeMillis))
                val gpsTime = SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = utcTz }
                    .format(Date(dateTimeMillis))
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

    fun verifyExportedMetadata(
        filePath: String,
        expectedMillis: Long,
        expectedLat: Double?,
        expectedLon: Double?,
        expectedFileName: String
    ): Boolean {
        val file = File(filePath)
        if (!file.exists() || !file.isFile) {
            Log.i(TAG, "VERIFY FAIL [$expectedFileName]: file missing at $filePath")
            return false
        }

        return try {
            val exif = ExifInterface(file.absolutePath)
            val expectedExifTime = formatExifTimestamp(expectedMillis)
            val expectedStamp = formatFileStamp(expectedMillis)

            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME)
            val dtOrig = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            val dtDig = exif.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED)
            val datesOk = dt == expectedExifTime &&
                dtOrig == expectedExifTime &&
                dtDig == expectedExifTime

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

            val expectedNamePattern = Regex(
                "^SHAMAIL_GPS_${Regex.escape(expectedStamp)}(_\\d+)?\\.jpg$",
                RegexOption.IGNORE_CASE
            )
            val fileNameOk = expectedNamePattern.matches(expectedFileName)

            val allOk = datesOk && gpsOk && imageDescOk && userCommentOk && fileNameOk
            Log.i(
                TAG,
                "VERIFY ${if (allOk) "PASS" else "FAIL"} [$expectedFileName] | " +
                    "datesOk=$datesOk (dt=$dt, orig=$dtOrig, dig=$dtDig, expected=$expectedExifTime) | " +
                    "gpsOk=$gpsOk (actual=${latLong?.let { "${it[0]},${it[1]}" } ?: "null"}, " +
                    "expected=${if (expectedLat != null && expectedLon != null) "$expectedLat,$expectedLon" else "none"}) | " +
                    "descOk=$imageDescOk | commentOk=$userCommentOk ($normalizedComment) | fileNameOk=$fileNameOk"
            )
            allOk
        } catch (e: Exception) {
            Log.i(TAG, "VERIFY FAIL [$expectedFileName]: ${e.message}")
            false
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
