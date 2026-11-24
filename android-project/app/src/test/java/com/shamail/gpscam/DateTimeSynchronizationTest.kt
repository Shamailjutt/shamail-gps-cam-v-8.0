package com.shamail.gpscam

import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.TimestampMode
import com.shamail.gpscam.data.WatermarkConfig
import com.shamail.gpscam.metadata.PhotoMetadataEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

class DateTimeSynchronizationTest {

    @Test
    fun backwardMidnightAdjustment_0105AM_to_1205AM_matchesExifAndDisplayFormats() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Karachi"))

            val initialCal = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.NOVEMBER, 23, 1, 5, 42)
                set(Calendar.MILLISECOND, 789)
            }
            val editedCal = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.NOVEMBER, 23, 0, 5, 0)
                set(Calendar.MILLISECOND, 654)
            }

            val normalizedInitial = PhotoMetadataEngine.normalizeToSecondMillis(initialCal.timeInMillis)
            val normalizedEdited = PhotoMetadataEngine.normalizeToSecondMillis(editedCal.timeInMillis)

            assertTrue("Normalized edited timestamp should move 1h05m42s backward", normalizedEdited < normalizedInitial)
            assertEquals(0L, normalizedEdited % 1000L)

            // EXIF formatting + OffsetTime + filename stamp
            assertEquals("2026:11:23 00:05:00", PhotoMetadataEngine.formatExifTimestamp(normalizedEdited))
            assertEquals("+05:00", PhotoMetadataEngine.formatExifOffset(normalizedEdited))
            assertEquals("20261123_000500", PhotoMetadataEngine.formatFileStamp(normalizedEdited))

            // Round-trip EXIF parse
            val parsedMillis = PhotoMetadataEngine.parseExifTimestampMillis("2026:11:23 00:05:00", "+05:00")
            assertNotNull(parsedMillis)
            assertEquals(normalizedEdited, parsedMillis)

            // 12-hour and 24-hour UI watermark formatting
            val gps = GpsSnapshot(hasFix = false)
            val cfg12h = WatermarkConfig(
                timestampMode = TimestampMode.MANUAL,
                manualTimestampMillis = normalizedEdited,
                is24HourFormat = false,
                dateFormatPattern = "dd MMM yyyy"
            )
            val cfg24h = cfg12h.copy(is24HourFormat = true)

            assertEquals(normalizedEdited, cfg12h.resolveTimestampMillis(gps, initialCal.timeInMillis))
            assertEquals("23 Nov 2026, 12:05:00 AM", cfg12h.formatTimestamp(gps, initialCal.timeInMillis))
            assertEquals("23 Nov 2026, 00:05:00", cfg24h.formatTimestamp(gps, initialCal.timeInMillis))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun forwardDateAdjustment_crossingMonthAndMidnight_formatsConsistently() {
        val originalTz = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val forwardCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                clear()
                set(2026, Calendar.DECEMBER, 1, 23, 59, 0)
            }
            val normalized = PhotoMetadataEngine.normalizeToSecondMillis(forwardCal.timeInMillis)
            assertEquals("2026:12:01 23:59:00", PhotoMetadataEngine.formatExifTimestamp(normalized))
            assertEquals("+00:00", PhotoMetadataEngine.formatExifOffset(normalized))
            assertEquals("20261201_235900", PhotoMetadataEngine.formatFileStamp(normalized))
            assertEquals(normalized, PhotoMetadataEngine.parseExifTimestampMillis("2026:12:01 23:59:00", "+00:00"))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun filesystemLastModified_persistsEditedBackwardTimestamp() {
        val tempFile = Files.createTempFile("shamail_ts_test_", ".jpg").toFile()
        try {
            tempFile.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            val targetCal = Calendar.getInstance().apply {
                clear()
                set(2026, Calendar.NOVEMBER, 23, 0, 5, 0)
            }
            val targetMillis = PhotoMetadataEngine.normalizeToSecondMillis(targetCal.timeInMillis)
            val setResult = tempFile.setLastModified(targetMillis)
            assertTrue("File.setLastModified should succeed on local temp file", setResult)
            assertTrue(
                "Persisted lastModified should match targetMillis within filesystem granularity",
                abs(tempFile.lastModified() - targetMillis) <= 2000L
            )

            // Verify creationTime attempt never throws fatal exceptions
            runCatching {
                Files.setAttribute(tempFile.toPath(), "basic:creationTime", FileTime.fromMillis(targetMillis))
            }
        } finally {
            tempFile.delete()
        }
    }
}
