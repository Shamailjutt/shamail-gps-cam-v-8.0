package com.shamail.gpscam.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.floor
import kotlin.math.pow

data class ResolvedLocationResult(
    val latitude: Double,
    val longitude: Double,
    val locationTitle: String,
    val fullAddress: String,
    val city: String = "",
    val district: String = "",
    val province: String = "",
    val country: String = ""
)

object LocationInputResolver {

    private const val OLC_ALPHABET = "23456789CFGHJMPQRVWX"
    private const val OLC_PADDING = '0'
    private const val OLC_SEPARATOR = '+'
    private val PAIR_RESOLUTIONS = doubleArrayOf(20.0, 1.0, 0.05, 0.0025, 0.000125)
    private const val GRID_ROWS = 5
    private const val GRID_COLUMNS = 4
    private val PLUS_CODE_REGEX = Regex(
        """(^|[\s,;(])([23456789CFGHJMPQRVWX0]{2,8}\+[23456789CFGHJMPQRVWX]{0,5})(?=$|[\s,;)])""",
        RegexOption.IGNORE_CASE
    )
    private val DMS_REGEX = Regex(
        """(\d{1,3})\s*[°º]\s*(\d{1,2})\s*['′]\s*(\d{1,2}(?:\.\d+)?)\s*(?:["″]|'')?\s*([NSEW])""",
        RegexOption.IGNORE_CASE
    )
    private val SIGNED_DECIMAL_WITH_HEMI_REGEX = Regex(
        """([+-]?\d{1,3}(?:\.\d+)?)\s*[°º]?\s*([NSEW])?""",
        RegexOption.IGNORE_CASE
    )

    suspend fun resolveInput(
        context: Context,
        rawInput: String,
        referenceLat: Double = 31.520370,
        referenceLon: Double = 74.358749
    ): ResolvedLocationResult? = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return@withContext null

        // 1. Check if input contains a Google Plus Code (full or short + optional locality)
        val plusMatch = extractPlusCodeAndLocality(trimmed)
        if (plusMatch != null) {
            val (codeToken, locality) = plusMatch
            val decoded = resolvePlusCode(context, codeToken, locality, referenceLat, referenceLon)
            if (decoded != null) {
                return@withContext reverseGeocodeToResult(context, decoded.first, decoded.second)
            }
        }

        // 2. Check if input is coordinates (Decimal, DMS, Lat/Lon labeled, Maps URL)
        val parsedCoords = parseCoordinates(trimmed)
        if (parsedCoords != null) {
            return@withContext reverseGeocodeToResult(context, parsedCoords.first, parsedCoords.second)
        }

        // 3. Fallback to Android Geocoder place / address lookup
        val addr = geocodeNameSingle(context, trimmed) ?: return@withContext null
        addressToResult(addr.latitude, addr.longitude, addr)
    }

    suspend fun reverseGeocodeToResult(
        context: Context,
        latitude: Double,
        longitude: Double
    ): ResolvedLocationResult = withContext(Dispatchers.IO) {
        val lat = latitude.coerceIn(-90.0, 90.0)
        val lon = longitude.coerceIn(-180.0, 180.0)
        val addr = reverseGeocodeSingle(context, lat, lon)
        if (addr != null) {
            addressToResult(lat, lon, addr)
        } else {
            val fallbackLabel = String.format(Locale.US, "GPS %.5f°, %.5f°", lat, lon)
            val fallbackAddr = String.format(Locale.US, "%.6f°, %.6f°", lat, lon)
            ResolvedLocationResult(
                latitude = lat,
                longitude = lon,
                locationTitle = fallbackLabel,
                fullAddress = fallbackAddr
            )
        }
    }

    fun parseCoordinates(raw: String): Pair<Double, Double>? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        // Google Maps URL patterns (@lat,lon or q=lat,lon or ll=lat,lon)
        Regex("""(?:@|[?&](?:q|ll|query)=)\s*([+-]?\d{1,2}\.\d+)\s*,\s*([+-]?\d{1,3}\.\d+)""")
            .find(text)
            ?.let { m ->
                val lat = m.groupValues[1].toDoubleOrNull()
                val lon = m.groupValues[2].toDoubleOrNull()
                if (lat != null && lon != null && isValidLatLon(lat, lon)) {
                    return Pair(lat, lon)
                }
            }

        // DMS coordinates (must have 2 DMS tokens)
        val dmsMatches = DMS_REGEX.findAll(text).toList()
        if (dmsMatches.size == 2) {
            val first = dmsMatchToSigned(dmsMatches[0])
            val second = dmsMatchToSigned(dmsMatches[1])
            if (first != null && second != null) {
                val (lat, lon) = assignLatLonByHemisphere(first, second)
                if (isValidLatLon(lat, lon)) return Pair(lat, lon)
            }
        }

        // Reject strings that look like addresses with street numbers unless labeled or strictly two numbers
        val cleaned = text
            .replace(Regex("""\b(?:lat(?:itude)?|lon(?:gitude)?|lng|long)\b\s*[:=]?""", RegexOption.IGNORE_CASE), " ")
            .trim()

        val parts = cleaned.split(Regex("""[\s,;/|]+""")).filter { it.isNotBlank() }
        if (parts.size in 2..4) {
            // Try merging standalone hemisphere markers (e.g., "32.714°" "N" "74.284°" "E")
            val normalizedTokens = mergeHemisphereTokens(parts)
            if (normalizedTokens.size == 2) {
                val a = parseDecimalWithHemisphere(normalizedTokens[0])
                val b = parseDecimalWithHemisphere(normalizedTokens[1])
                if (a != null && b != null) {
                    val (lat, lon) = assignLatLonByHemisphere(a, b)
                    if (isValidLatLon(lat, lon)) return Pair(lat, lon)
                }
            }
        }

        return null
    }

    private fun mergeHemisphereTokens(tokens: List<String>): List<String> {
        val merged = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val cur = tokens[i]
            val next = tokens.getOrNull(i + 1)
            if (next != null && next.length == 1 && next[0].uppercaseChar() in charArrayOf('N', 'S', 'E', 'W') &&
                cur.any { it.isDigit() }
            ) {
                merged.add("$cur$next")
                i += 2
            } else {
                merged.add(cur)
                i++
            }
        }
        return merged
    }

    private fun dmsMatchToSigned(m: MatchResult): Pair<Double, Char?>? {
        val deg = m.groupValues[1].toDoubleOrNull() ?: return null
        val min = m.groupValues[2].toDoubleOrNull() ?: return null
        val sec = m.groupValues[3].toDoubleOrNull() ?: return null
        if (min >= 60.0 || sec >= 60.0) return null
        val hemi = m.groupValues[4].firstOrNull()?.uppercaseChar()
        val absVal = deg + (min / 60.0) + (sec / 3600.0)
        val signed = if (hemi == 'S' || hemi == 'W') -absVal else absVal
        return Pair(signed, hemi)
    }

    private fun parseDecimalWithHemisphere(token: String): Pair<Double, Char?>? {
        val match = SIGNED_DECIMAL_WITH_HEMI_REGEX.matchEntire(token.trim()) ?: return null
        val value = match.groupValues[1].toDoubleOrNull() ?: return null
        val hemi = match.groupValues[2].firstOrNull()?.uppercaseChar()
        val signed = when (hemi) {
            'S', 'W' -> -kotlin.math.abs(value)
            'N', 'E' -> kotlin.math.abs(value)
            else -> value
        }
        return Pair(signed, hemi)
    }

    private fun assignLatLonByHemisphere(
        first: Pair<Double, Char?>,
        second: Pair<Double, Char?>
    ): Pair<Double, Double> {
        val h1 = first.second
        val h2 = second.second
        return if ((h1 == 'E' || h1 == 'W') && (h2 == 'N' || h2 == 'S')) {
            Pair(second.first, first.first)
        } else {
            Pair(first.first, second.first)
        }
    }

    private fun isValidLatLon(lat: Double, lon: Double): Boolean {
        return !lat.isNaN() && !lon.isNaN() && lat in -90.0..90.0 && lon in -180.0..180.0
    }

    fun extractPlusCodeAndLocality(raw: String): Pair<String, String>? {
        val match = PLUS_CODE_REGEX.find(raw) ?: return null
        val rawCode = match.groupValues[2].uppercase(Locale.US)
        if (!isValidPlusCodeSyntax(rawCode)) return null

        val matchStart = match.groups[2]?.range?.first ?: 0
        val matchEnd = (match.groups[2]?.range?.last ?: 0) + 1
        val before = raw.substring(0, matchStart).trim(' ', ',', ';')
        val after = raw.substring(matchEnd).trim(' ', ',', ';')
        val locality = listOf(before, after).filter { it.isNotBlank() }.joinToString(", ")
        return Pair(rawCode, locality)
    }

    private fun isValidPlusCodeSyntax(code: String): Boolean {
        val plusIdx = code.indexOf(OLC_SEPARATOR)
        if (plusIdx < 2 || plusIdx > 8 || plusIdx % 2 != 0) return false
        val before = code.substring(0, plusIdx)
        val after = code.substring(plusIdx + 1)

        if (before.contains(OLC_PADDING)) {
            if (plusIdx != 8 || after.isNotEmpty()) return false
            val firstPad = before.indexOf(OLC_PADDING)
            if (firstPad < 2 || firstPad % 2 != 0) return false
            if (!before.substring(0, firstPad).all { it in OLC_ALPHABET }) return false
            if (!before.substring(firstPad).all { it == OLC_PADDING }) return false
            return true
        }

        if (!before.all { it in OLC_ALPHABET }) return false
        if (after.length == 1) return false
        if (!after.all { it in OLC_ALPHABET }) return false
        return true
    }

    private suspend fun resolvePlusCode(
        context: Context,
        code: String,
        locality: String,
        fallbackRefLat: Double,
        fallbackRefLon: Double
    ): Pair<Double, Double>? {
        val plusIdx = code.indexOf(OLC_SEPARATOR)
        if (plusIdx == 8) {
            return decodeFullPlusCode(code)
        }

        // Short Plus Code: determine reference point from locality if provided, else fallback reference
        var refLat = fallbackRefLat.takeIf { it in -90.0..90.0 && it != 0.0 } ?: 31.520370
        var refLon = fallbackRefLon.takeIf { it in -180.0..180.0 && it != 0.0 } ?: 74.358749

        if (locality.isNotBlank()) {
            val localityAddr = geocodeNameSingle(context, locality)
            if (localityAddr != null) {
                refLat = localityAddr.latitude
                refLon = localityAddr.longitude
            } else {
                // Also try letting Android Geocoder resolve the combined string directly
                val directAddr = geocodeNameSingle(context, "$code, $locality")
                if (directAddr != null) {
                    return Pair(directAddr.latitude, directAddr.longitude)
                }
            }
        }

        val recovered = recoverNearest(code, refLat, refLon)
        return decodeFullPlusCode(recovered)
    }

    private fun encodeTo10Chars(latitude: Double, longitude: Double): String {
        val lat = latitude.coerceIn(-90.0, 90.0).let { if (it == 90.0) 89.999999 else it }
        var lon = longitude % 360.0
        if (lon < -180.0) lon += 360.0
        if (lon >= 180.0) lon -= 360.0

        var latVal = floor((lat + 90.0) * 8000.0).toLong().coerceIn(0L, 180L * 8000L - 1L)
        var lonVal = floor((lon + 180.0) * 8000.0).toLong().coerceIn(0L, 360L * 8000L - 1L)

        val chars = CharArray(11)
        chars[8] = OLC_SEPARATOR
        for (pair in 4 downTo 0) {
            val latDigit = (latVal % 20L).toInt()
            val lonDigit = (lonVal % 20L).toInt()
            latVal /= 20L
            lonVal /= 20L
            val baseIdx = if (pair < 4) pair * 2 else 9
            chars[baseIdx] = OLC_ALPHABET[latDigit]
            chars[baseIdx + 1] = OLC_ALPHABET[lonDigit]
        }
        return String(chars)
    }

    private fun recoverNearest(shortCode: String, refLat: Double, refLon: Double): String {
        val plusIdx = shortCode.indexOf(OLC_SEPARATOR)
        val prefixLen = 8 - plusIdx
        if (prefixLen <= 0) return shortCode

        val refFull = encodeTo10Chars(refLat, refLon)
        val combined = refFull.substring(0, prefixLen) + shortCode
        val decoded = decodeFullPlusCode(combined) ?: return combined

        var lat = decoded.first
        var lon = decoded.second
        val resolution = 20.0.pow(2 - (prefixLen / 2))
        val halfResolution = resolution / 2.0

        if (lat - refLat > halfResolution && lat - resolution >= -90.0) {
            lat -= resolution
        } else if (lat - refLat < -halfResolution && lat + resolution <= 90.0) {
            lat += resolution
        }

        if (lon - refLon > halfResolution) {
            lon -= resolution
        } else if (lon - refLon < -halfResolution) {
            lon += resolution
        }

        val adjustedCode = encodeTo10Chars(lat, lon)
        return adjustedCode.substring(0, prefixLen) + shortCode
    }

    fun decodeFullPlusCode(code: String): Pair<Double, Double>? {
        val upper = code.uppercase(Locale.US).trim()
        val plusIdx = upper.indexOf(OLC_SEPARATOR)
        if (plusIdx != 8) return null

        val stripped = upper.replace("+", "").replace("0", "")
        if (stripped.length < 2) return null

        var southLat = 0.0
        var westLon = 0.0
        var latRes = 400.0
        var lonRes = 400.0

        val pairChars = minOf(stripped.length, 10)
        var i = 0
        while (i + 1 < pairChars) {
            val latIdx = OLC_ALPHABET.indexOf(stripped[i])
            val lonIdx = OLC_ALPHABET.indexOf(stripped[i + 1])
            if (latIdx < 0 || lonIdx < 0) return null
            val res = PAIR_RESOLUTIONS[i / 2]
            southLat += latIdx * res
            westLon += lonIdx * res
            latRes = res
            lonRes = res
            i += 2
        }

        if (stripped.length > 10) {
            for (j in 10 until stripped.length) {
                val digit = OLC_ALPHABET.indexOf(stripped[j])
                if (digit < 0) return null
                val row = digit / GRID_COLUMNS
                val col = digit % GRID_COLUMNS
                latRes /= GRID_ROWS
                lonRes /= GRID_COLUMNS
                southLat += row * latRes
                westLon += col * lonRes
            }
        }

        val centerLat = (southLat + latRes / 2.0) - 90.0
        val centerLon = (westLon + lonRes / 2.0) - 180.0
        if (!isValidLatLon(centerLat, centerLon)) return null
        return Pair(centerLat, centerLon)
    }

    private suspend fun geocodeNameSingle(context: Context, query: String): Address? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocationName(query, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocationName(query, 1)?.firstOrNull()
            }
        }.getOrNull()
    }

    private suspend fun reverseGeocodeSingle(context: Context, lat: Double, lon: Double): Address? {
        if (!Geocoder.isPresent()) return null
        return runCatching {
            val geocoder = Geocoder(context, Locale.getDefault())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { cont ->
                    geocoder.getFromLocation(lat, lon, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (cont.isActive) cont.resume(addresses.firstOrNull())
                        }

                        override fun onError(errorMessage: String?) {
                            if (cont.isActive) cont.resume(null)
                        }
                    })
                }
            } else {
                @Suppress("DEPRECATION")
                geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
            }
        }.getOrNull()
    }

    private fun addressToResult(lat: Double, lon: Double, address: Address): ResolvedLocationResult {
        val city = (address.locality ?: address.subLocality ?: address.subAdminArea ?: "").trim()
        val district = (address.subAdminArea ?: "").trim()
        val province = (address.adminArea ?: "").trim()
        val country = (address.countryName ?: "").trim()

        val parts = linkedSetOf<String>()
        listOf(city, district, province, country)
            .filter { it.isNotBlank() }
            .forEach { parts.add(it) }

        val headline = if (parts.isNotEmpty()) {
            parts.joinToString(", ")
        } else {
            address.featureName?.trim()?.takeIf { it.isNotEmpty() }
                ?: String.format(Locale.US, "GPS %.5f°, %.5f°", lat, lon)
        }

        val fullLine = address.getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(address.featureName, address.thoroughfare, address.subLocality, headline)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .joinToString(", ")

        return ResolvedLocationResult(
            latitude = lat,
            longitude = lon,
            locationTitle = headline,
            fullAddress = fullLine,
            city = city,
            district = district,
            province = province,
            country = country
        )
    }
}
