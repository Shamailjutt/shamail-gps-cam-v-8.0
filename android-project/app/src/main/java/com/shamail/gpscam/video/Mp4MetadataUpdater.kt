package com.shamail.gpscam.video

import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class Mp4VideoMetadata(
    val creationTimeMillis: Long?,
    val latitude: Double?,
    val longitude: Double?,
    val durationMillis: Long
)

object Mp4MetadataUpdater {

    private const val TAG = "Mp4MetadataUpdater"
    private const val MP4_EPOCH_OFFSET_SECONDS = 2_082_844_800L

    fun readMetadata(file: File): Mp4VideoMetadata {
        if (!file.exists()) return Mp4VideoMetadata(null, null, null, 0L)
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            val locStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

            val parsedTime = parseMp4Date(dateStr) ?: readCreationTimeFromMoov(file)
            val parsedCoords = parseIso6709(locStr)
            val durationMs = durStr?.toLongOrNull() ?: 0L
            Mp4VideoMetadata(parsedTime, parsedCoords?.first, parsedCoords?.second, durationMs)
        } catch (_: Exception) {
            Mp4VideoMetadata(readCreationTimeFromMoov(file), null, null, 0L)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Updates MP4 container metadata cleanly to match a freshly recorded native video:
     * - mvhd / tkhd / mdhd creation_time & modification_time
     * - moov/udta/©xyz ISO 6709 GPS tag (if coordinates valid)
     * - moov/meta QuickTime keys: com.apple.quicktime.creationdate & com.apple.quicktime.location.ISO6709
     * Never writes any "Edited", "Modified", "Updated", or app/version marker atoms.
     */
    fun writeMetadata(
        mp4File: File,
        creationMillis: Long,
        latitude: Double?,
        longitude: Double?
    ): Boolean {
        if (!mp4File.exists() || mp4File.length() < 32L) return false
        return try {
            RandomAccessFile(mp4File, "rw").use { raf ->
                val boxes = scanTopLevelBoxes(raf)
                val moovInfo = boxes.firstOrNull { it.type == "moov" } ?: return false
                val hasMdatAfterMoov = boxes.any { it.type == "mdat" && it.offset > moovInfo.offset }

                val moovBytes = ByteArray(moovInfo.size.toInt())
                raf.seek(moovInfo.offset)
                raf.readFully(moovBytes)

                val mp4TimeSec = ((creationMillis / 1000L) + MP4_EPOCH_OFFSET_SECONDS).coerceAtLeast(0L)
                val newMoov = rebuildMoovWithMetadata(
                    moovBoxBytes = moovBytes,
                    mp4TimeSec = mp4TimeSec,
                    creationMillis = creationMillis,
                    latitude = latitude,
                    longitude = longitude
                )

                val delta = newMoov.size.toLong() - moovInfo.size
                if (!hasMdatAfterMoov && moovInfo.offset + moovInfo.size == raf.length()) {
                    // Standard MediaMuxer layout (ftyp + mdat + moov): replace moov in-place at tail
                    raf.seek(moovInfo.offset)
                    raf.write(newMoov)
                    raf.setLength(moovInfo.offset + newMoov.size)
                } else {
                    val shiftedMoov = if (delta != 0L && hasMdatAfterMoov) {
                        adjustChunkOffsetsInMoov(newMoov, delta)
                    } else {
                        newMoov
                    }
                    rewriteFileWithReplacedBox(mp4File, moovInfo.offset, moovInfo.size, shiftedMoov)
                }
            }
            mp4File.setLastModified(creationMillis)
            true
        } catch (e: Exception) {
            Log.w(TAG, "MP4 metadata update warning: ${e.message}")
            false
        }
    }

    private data class BoxSpan(
        val type: String,
        val offset: Long,
        val size: Long,
        val headerSize: Int
    )

    private fun scanTopLevelBoxes(raf: RandomAccessFile): List<BoxSpan> {
        val list = mutableListOf<BoxSpan>()
        val len = raf.length()
        var pos = 0L
        val hdr = ByteArray(16)
        while (pos + 8L <= len) {
            raf.seek(pos)
            raf.readFully(hdr, 0, 8)
            val size32 = ByteBuffer.wrap(hdr, 0, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var boxSize = size32
            var headerSize = 8
            if (size32 == 1L && pos + 16L <= len) {
                raf.readFully(hdr, 8, 8)
                boxSize = ByteBuffer.wrap(hdr, 8, 8).order(ByteOrder.BIG_ENDIAN).long
                headerSize = 16
            } else if (size32 == 0L) {
                boxSize = len - pos
            }
            if (boxSize < headerSize) break
            list.add(BoxSpan(type, pos, boxSize, headerSize))
            pos += boxSize
        }
        return list
    }

    private fun readCreationTimeFromMoov(file: File): Long? {
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val moov = scanTopLevelBoxes(raf).firstOrNull { it.type == "moov" } ?: return null
                if (moov.size > 20L * 1024L * 1024L) return null
                val moovBytes = ByteArray(moov.size.toInt())
                raf.seek(moov.offset)
                raf.readFully(moovBytes)
                var pos = 8
                while (pos + 8 <= moovBytes.size) {
                    val sz = readUInt32(moovBytes, pos).toInt()
                    val t = readFourCC(moovBytes, pos + 4)
                    if (sz < 8 || pos + sz > moovBytes.size) break
                    if (t == "mvhd" && sz >= 20) {
                        val ver = moovBytes[pos + 8].toInt() and 0xFF
                        val mp4Sec = if (ver == 1 && sz >= 28) {
                            readInt64(moovBytes, pos + 12)
                        } else {
                            readUInt32(moovBytes, pos + 12)
                        }
                        if (mp4Sec > MP4_EPOCH_OFFSET_SECONDS) {
                            return (mp4Sec - MP4_EPOCH_OFFSET_SECONDS) * 1000L
                        }
                    }
                    pos += sz
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun rebuildMoovWithMetadata(
        moovBoxBytes: ByteArray,
        mp4TimeSec: Long,
        creationMillis: Long,
        latitude: Double?,
        longitude: Double?
    ): ByteArray {
        val out = ByteArrayOutputStream(moovBoxBytes.size + 512)
        var pos = 8
        while (pos + 8 <= moovBoxBytes.size) {
            val childSize = readUInt32(moovBoxBytes, pos).toInt()
            val childType = readFourCC(moovBoxBytes, pos + 4)
            if (childSize < 8 || pos + childSize > moovBoxBytes.size) break

            when (childType) {
                "udta", "meta" -> {
                    // Will be replaced with clean udta + meta below
                }
                else -> {
                    val copy = moovBoxBytes.copyOfRange(pos, pos + childSize)
                    updateTimestampsRecursive(copy, 0, copy.size, mp4TimeSec)
                    out.write(copy)
                }
            }
            pos += childSize
        }

        val hasGps = latitude != null && longitude != null &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !(latitude == 0.0 && longitude == 0.0)

        val iso6709 = if (hasGps) {
            String.format(Locale.US, "%+010.6f%+011.6f/", latitude, longitude)
        } else {
            null
        }

        if (iso6709 != null) {
            out.write(buildUdtaWithXyz(iso6709))
        }
        out.write(buildQuickTimeMetaBox(creationMillis, iso6709))

        return wrapBox("moov", out.toByteArray())
    }

    private fun updateTimestampsRecursive(
        buf: ByteArray,
        start: Int,
        end: Int,
        mp4TimeSec: Long
    ) {
        if (start + 8 > end) return
        val size = readUInt32(buf, start).toInt()
        val type = readFourCC(buf, start + 4)
        if (size < 8 || start + size > end) return

        when (type) {
            "mvhd", "tkhd", "mdhd" -> {
                if (start + 12 <= start + size) {
                    val ver = buf[start + 8].toInt() and 0xFF
                    if (ver == 0 && start + 20 <= start + size) {
                        writeUInt32(buf, start + 12, mp4TimeSec)
                        writeUInt32(buf, start + 16, mp4TimeSec)
                    } else if (ver == 1 && start + 28 <= start + size) {
                        writeInt64(buf, start + 12, mp4TimeSec)
                        writeInt64(buf, start + 20, mp4TimeSec)
                    }
                }
            }
            "trak", "mdia", "minf", "stbl", "edts" -> {
                var childPos = start + 8
                val boxEnd = start + size
                while (childPos + 8 <= boxEnd) {
                    val childSize = readUInt32(buf, childPos).toInt()
                    if (childSize < 8 || childPos + childSize > boxEnd) break
                    updateTimestampsRecursive(buf, childPos, childPos + childSize, mp4TimeSec)
                    childPos += childSize
                }
            }
        }
    }

    private fun buildUdtaWithXyz(iso6709: String): ByteArray {
        val textBytes = iso6709.toByteArray(Charsets.UTF_8)
        val xyzPayload = ByteArrayOutputStream(4 + textBytes.size)
        xyzPayload.write((textBytes.size shr 8) and 0xFF)
        xyzPayload.write(textBytes.size and 0xFF)
        // QuickTime English language code 0x15C7
        xyzPayload.write(0x15)
        xyzPayload.write(0xC7)
        xyzPayload.write(textBytes)

        val xyzType = byteArrayOf(0xA9.toByte(), 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte())
        val xyzBox = wrapRawTypeBox(xyzType, xyzPayload.toByteArray())
        return wrapBox("udta", xyzBox)
    }

    private fun buildQuickTimeMetaBox(creationMillis: Long, iso6709: String?): ByteArray {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }
        val qtDate = fmt.format(Date(creationMillis))

        val keyPairs = mutableListOf<Pair<String, String>>()
        keyPairs.add("com.apple.quicktime.creationdate" to qtDate)
        if (!iso6709.isNullOrBlank()) {
            keyPairs.add("com.apple.quicktime.location.ISO6709" to iso6709)
        }

        // hdlr box for meta (QuickTime 'mdta')
        val hdlrPayload = ByteArrayOutputStream(25)
        hdlrPayload.write(ByteArray(8)) // version/flags + predefined
        hdlrPayload.write("mdta".toByteArray(Charsets.ISO_8859_1))
        hdlrPayload.write(ByteArray(13)) // reserved (12) + empty name null byte
        val hdlrBox = wrapBox("hdlr", hdlrPayload.toByteArray())

        // keys box
        val keysPayload = ByteArrayOutputStream()
        keysPayload.write(ByteArray(4)) // version/flags
        keysPayload.write(int32Bytes(keyPairs.size))
        for ((keyName, _) in keyPairs) {
            val keyBytes = keyName.toByteArray(Charsets.UTF_8)
            keysPayload.write(int32Bytes(8 + keyBytes.size))
            keysPayload.write("mdta".toByteArray(Charsets.ISO_8859_1))
            keysPayload.write(keyBytes)
        }
        val keysBox = wrapBox("keys", keysPayload.toByteArray())

        // ilst box
        val ilstPayload = ByteArrayOutputStream()
        keyPairs.forEachIndexed { idx, (_, valStr) ->
            val valBytes = valStr.toByteArray(Charsets.UTF_8)
            val dataPayload = ByteArrayOutputStream(8 + valBytes.size)
            dataPayload.write(int32Bytes(1)) // UTF-8 type indicator
            dataPayload.write(int32Bytes(0)) // locale 0
            dataPayload.write(valBytes)
            val dataBox = wrapBox("data", dataPayload.toByteArray())

            val oneBasedIdx = idx + 1
            val itemType = int32Bytes(oneBasedIdx)
            val itemBox = wrapRawTypeBox(itemType, dataBox)
            ilstPayload.write(itemBox)
        }
        val ilstBox = wrapBox("ilst", ilstPayload.toByteArray())

        val metaPayload = ByteArrayOutputStream()
        metaPayload.write(hdlrBox)
        metaPayload.write(keysBox)
        metaPayload.write(ilstBox)
        return wrapBox("meta", metaPayload.toByteArray())
    }

    private fun adjustChunkOffsetsInMoov(moov: ByteArray, delta: Long): ByteArray {
        val copy = moov.copyOf()
        shiftChunkOffsetsRecursive(copy, 0, copy.size, delta)
        return copy
    }

    private fun shiftChunkOffsetsRecursive(buf: ByteArray, start: Int, end: Int, delta: Long) {
        if (start + 8 > end) return
        val size = readUInt32(buf, start).toInt()
        val type = readFourCC(buf, start + 4)
        if (size < 8 || start + size > end) return

        when (type) {
            "stco" -> {
                if (start + 16 <= start + size) {
                    val count = readUInt32(buf, start + 12).toInt()
                    var p = start + 16
                    repeat(count) {
                        if (p + 4 <= start + size) {
                            val oldOff = readUInt32(buf, p)
                            writeUInt32(buf, p, oldOff + delta)
                            p += 4
                        }
                    }
                }
            }
            "co64" -> {
                if (start + 16 <= start + size) {
                    val count = readUInt32(buf, start + 12).toInt()
                    var p = start + 16
                    repeat(count) {
                        if (p + 8 <= start + size) {
                            val oldOff = readInt64(buf, p)
                            writeInt64(buf, p, oldOff + delta)
                            p += 8
                        }
                    }
                }
            }
            "moov", "trak", "mdia", "minf", "stbl" -> {
                var childPos = start + 8
                val boxEnd = start + size
                while (childPos + 8 <= boxEnd) {
                    val childSize = readUInt32(buf, childPos).toInt()
                    if (childSize < 8 || childPos + childSize > boxEnd) break
                    shiftChunkOffsetsRecursive(buf, childPos, childPos + childSize, delta)
                    childPos += childSize
                }
            }
        }
    }

    private fun rewriteFileWithReplacedBox(
        targetFile: File,
        replaceOffset: Long,
        replaceSize: Long,
        newBoxBytes: ByteArray
    ) {
        val tempFile = File(targetFile.parentFile ?: targetFile.absoluteFile.parentFile, "${targetFile.name}.tmp")
        FileInputStream(targetFile).use { input ->
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(65536)
                var remainingPrefix = replaceOffset
                while (remainingPrefix > 0L) {
                    val toRead = minOf(remainingPrefix, buffer.size.toLong()).toInt()
                    val read = input.read(buffer, 0, toRead)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    remainingPrefix -= read
                }
                output.write(newBoxBytes)
                var skipped = 0L
                while (skipped < replaceSize) {
                    val s = input.skip(replaceSize - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
        if (tempFile.exists() && tempFile.length() > 0L) {
            targetFile.delete()
            tempFile.renameTo(targetFile)
        }
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        return wrapRawTypeBox(type.toByteArray(Charsets.ISO_8859_1), payload)
    }

    private fun wrapRawTypeBox(type4: ByteArray, payload: ByteArray): ByteArray {
        val total = 8 + payload.size
        val out = ByteArray(total)
        System.arraycopy(int32Bytes(total), 0, out, 0, 4)
        System.arraycopy(type4, 0, out, 4, 4)
        System.arraycopy(payload, 0, out, 8, payload.size)
        return out
    }

    private fun int32Bytes(v: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
    }

    private fun readUInt32(buf: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    private fun writeUInt32(buf: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(buf, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value.toInt())
    }

    private fun readInt64(buf: ByteArray, offset: Int): Long {
        return ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.BIG_ENDIAN).long
    }

    private fun writeInt64(buf: ByteArray, offset: Int, value: Long) {
        ByteBuffer.wrap(buf, offset, 8).order(ByteOrder.BIG_ENDIAN).putLong(value)
    }

    private fun readFourCC(buf: ByteArray, offset: Int): String {
        return String(buf, offset, 4, Charsets.ISO_8859_1)
    }

    private fun parseMp4Date(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val patterns = listOf(
            "yyyyMMdd'T'HHmmss.SSS'Z'",
            "yyyyMMdd'T'HHmmss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (p in patterns) {
            runCatching {
                val sdf = SimpleDateFormat(p, Locale.US).apply {
                    isLenient = false
                    if (p.endsWith("'Z'")) timeZone = TimeZone.getTimeZone("UTC")
                }
                sdf.parse(s)?.time
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun parseIso6709(raw: String?): Pair<Double, Double>? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        val m = Regex("""^([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(s) ?: return null
        val lat = m.groupValues[1].toDoubleOrNull() ?: return null
        val lon = m.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return Pair(lat, lon)
    }
}
