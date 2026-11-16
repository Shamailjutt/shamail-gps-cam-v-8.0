package com.shamail.gpscam

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shamail.gpscam.data.AppPreferences
import com.shamail.gpscam.data.AuthManager
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.LocationSource
import com.shamail.gpscam.data.SavedLocationDatabase
import com.shamail.gpscam.data.TimestampMode
import com.shamail.gpscam.data.VideoGpsKeyframe
import com.shamail.gpscam.data.WatermarkConfig
import com.shamail.gpscam.databinding.ActivityPostCaptureEditBinding
import com.shamail.gpscam.location.LocationInputResolver
import com.shamail.gpscam.storage.PhotoStorageManager
import com.shamail.gpscam.ui.SequentialDateTimePicker
import com.shamail.gpscam.watermark.WatermarkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

class PostCaptureEditActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPostCaptureEditBinding
    private lateinit var prefs: AppPreferences

    private var workingConfig = WatermarkConfig()
    private var workingGps = GpsSnapshot()
    private var originalCaptureGps = GpsSnapshot()
    private var gpsTimeline: List<VideoGpsKeyframe> = emptyList()
    private var hasCustomLocationOverride: Boolean = false
    private var initialTitleInput: String = ""
    private var initialAddressInput: String = ""
    private var initialLatInput: String = ""
    private var initialLonInput: String = ""
    private var captureMomentMillis: Long = System.currentTimeMillis()

    private var sourceTempFile: File? = null
    private var isVideoMode: Boolean = false
    private var previewBaseBitmap: Bitmap? = null
    private var currentPreviewWatermarked: Bitmap? = null
    private var logoBitmap: Bitmap? = null
    private var isSaving = false

    private val isoDateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
    private val isoTimeFmt = SimpleDateFormat("HH:mm:ss", Locale.US).apply { isLenient = false }

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data ?: return@registerForActivityResult
            val title = data.getStringExtra("picked_title").orEmpty()
            val address = data.getStringExtra("picked_address").orEmpty()
            val lat = data.getDoubleExtra("picked_lat", workingGps.latitude)
            val lon = data.getDoubleExtra("picked_lon", workingGps.longitude)
            val wasBookmark = data.getBooleanExtra("picked_was_saved_bookmark", false)

            hasCustomLocationOverride = true
            workingGps = workingGps.copy(
                hasFix = true,
                latitude = lat,
                longitude = lon,
                accuracyMeters = 0f,
                city = title,
                district = "",
                province = "",
                country = "",
                streetAddress = address,
                isOfflineAddress = false,
                locationSource = if (wasBookmark) LocationSource.SAVED_LOCATION else LocationSource.MANUAL_MAP
            )
            binding.etPostLocationTitle.setText(title)
            binding.etPostStreetAddress.setText(address)
            binding.etPostLat.setText(String.format(Locale.US, "%.6f", lat))
            binding.etPostLon.setText(String.format(Locale.US, "%.6f", lon))
            updateLocationSourceLabel()
            renderWatermarkedPreview()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.isAdmin(this)) {
            intent.getStringExtra("temp_photo_path")?.let { path ->
                runCatching { File(path).delete() }
            }
            intent.getStringExtra("temp_video_path")?.let { path ->
                runCatching { File(path).delete() }
            }
            finish()
            return
        }
        binding = ActivityPostCaptureEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        workingConfig = prefs.loadConfig()

        val vidPath = intent.getStringExtra("temp_video_path")
        val photoPath = intent.getStringExtra("temp_photo_path")
        isVideoMode = !vidPath.isNullOrBlank()
        val tempPath = vidPath?.ifBlank { null } ?: photoPath
        if (tempPath.isNullOrBlank()) {
            finish()
            return
        }
        val f = File(tempPath)
        if (!f.exists()) {
            finish()
            return
        }
        sourceTempFile = f

        if (isVideoMode) {
            binding.btnSaveTop.text = "Save Video"
            binding.btnPostSaveBottom.text = "Save Video"
            binding.btnPostPlayVideo.visibility = View.VISIBLE
            binding.vvPostVideo.setVideoPath(f.absolutePath)
        }

        captureMomentMillis = intent.getLongExtra("capture_millis", System.currentTimeMillis())
        workingGps = GpsSnapshot(
            hasFix = intent.getBooleanExtra("gps_has_fix", false),
            latitude = intent.getDoubleExtra("gps_lat", 0.0),
            longitude = intent.getDoubleExtra("gps_lon", 0.0),
            accuracyMeters = intent.getFloatExtra("gps_acc", 0f),
            gpsTimestampMillis = intent.getLongExtra("gps_time", captureMomentMillis),
            city = intent.getStringExtra("gps_city").orEmpty(),
            district = intent.getStringExtra("gps_district").orEmpty(),
            province = intent.getStringExtra("gps_province").orEmpty(),
            country = intent.getStringExtra("gps_country").orEmpty(),
            streetAddress = intent.getStringExtra("gps_street").orEmpty(),
            isOfflineAddress = intent.getBooleanExtra("gps_offline", false)
        )
        originalCaptureGps = workingGps.copy()
        gpsTimeline = PhotoStorageManager.jsonStringToGpsTimeline(intent.getStringExtra("gps_timeline_json"))
        hasCustomLocationOverride = false

        val initialMillis = workingConfig.resolveTimestampMillis(workingGps, captureMomentMillis)
        workingConfig = workingConfig.copy(manualTimestampMillis = initialMillis)

        logoBitmap = WatermarkRenderer.loadLogoBitmap(this, workingConfig)
        populateInitialFields(initialMillis)
        bindActions()
        loadPreviewBitmapAsync()
    }

    override fun onResume() {
        super.onResume()
        if (!AuthManager.isAdmin(this)) {
            discardAndFinish()
        }
    }

    private fun populateInitialFields(initialMillis: Long) {
        syncDateAndTimeInputs(initialMillis)
        binding.switchPost24Hour.isChecked = workingConfig.is24HourFormat
        initialTitleInput = if (workingGps.hasFix) workingGps.buildLocationHeadline() else ""
        initialAddressInput = workingGps.streetAddress
        initialLatInput = if (workingGps.hasFix) String.format(Locale.US, "%.6f", workingGps.latitude) else ""
        initialLonInput = if (workingGps.hasFix) String.format(Locale.US, "%.6f", workingGps.longitude) else ""

        binding.etPostLocationTitle.setText(initialTitleInput)
        binding.etPostStreetAddress.setText(initialAddressInput)
        if (workingGps.hasFix) {
            binding.etPostLat.setText(initialLatInput)
            binding.etPostLon.setText(initialLonInput)
        }
        updateLocationSourceLabel()
    }

    private fun updateLocationSourceLabel() {
        binding.tvPostLocationSource.text = when (workingGps.locationSource) {
            LocationSource.SAVED_LOCATION -> "Editor Source: Saved Location"
            LocationSource.MANUAL_MAP -> "Editor Source: Selected Map Location"
            LocationSource.LIVE_GPS -> "Editor Source: Live Device GPS"
        }
    }

    private fun bindActions() {
        binding.btnRetakeTop.setOnClickListener { discardAndFinish() }
        binding.btnPostRetake.setOnClickListener { discardAndFinish() }

        binding.btnSaveTop.setOnClickListener { saveFinalWatermarkedPhoto() }
        binding.btnPostSaveBottom.setOnClickListener { saveFinalWatermarkedPhoto() }

        binding.btnPostPlayVideo.setOnClickListener {
            if (!isVideoMode) return@setOnClickListener
            binding.ivCapturedPreview.visibility = View.GONE
            binding.btnPostPlayVideo.visibility = View.GONE
            binding.vvPostVideo.visibility = View.VISIBLE
            binding.vvPostVideo.start()
        }
        binding.vvPostVideo.setOnClickListener {
            if (binding.vvPostVideo.isPlaying) {
                binding.vvPostVideo.pause()
            }
            binding.vvPostVideo.visibility = View.GONE
            binding.ivCapturedPreview.visibility = View.VISIBLE
            binding.btnPostPlayVideo.visibility = View.VISIBLE
        }
        binding.vvPostVideo.setOnCompletionListener {
            binding.vvPostVideo.visibility = View.GONE
            binding.ivCapturedPreview.visibility = View.VISIBLE
            binding.btnPostPlayVideo.visibility = View.VISIBLE
        }

        binding.btnPostPickDate.setOnClickListener {
            val baseMs = workingConfig.resolveTimestampMillis(workingGps, captureMomentMillis)
            SequentialDateTimePicker.launch(
                activity = this,
                initialMillis = baseMs,
                is24HourFormat = workingConfig.is24HourFormat,
                dateFormatPattern = workingConfig.dateFormatPattern
            ) { confirmedMillis ->
                workingConfig = workingConfig.copy(
                    timestampMode = TimestampMode.MANUAL,
                    manualTimestampMillis = confirmedMillis
                )
                syncDateAndTimeInputs(confirmedMillis)
                renderWatermarkedPreview()
            }
        }

        binding.btnPostPickTime.setOnClickListener {
            val baseMs = workingConfig.resolveTimestampMillis(workingGps, captureMomentMillis)
            SequentialDateTimePicker.launchTimeOnlyWithConfirm(
                activity = this,
                initialMillis = baseMs,
                is24HourFormat = workingConfig.is24HourFormat,
                dateFormatPattern = workingConfig.dateFormatPattern
            ) { confirmedMillis ->
                workingConfig = workingConfig.copy(
                    timestampMode = TimestampMode.MANUAL,
                    manualTimestampMillis = confirmedMillis
                )
                syncDateAndTimeInputs(confirmedMillis)
                renderWatermarkedPreview()
            }
        }

        binding.btnPostResetTime.setOnClickListener {
            val now = System.currentTimeMillis()
            workingConfig = workingConfig.copy(
                timestampMode = TimestampMode.AUTO_DEVICE,
                manualTimestampMillis = now
            )
            captureMomentMillis = now
            syncDateAndTimeInputs(now)
            renderWatermarkedPreview()
            Toast.makeText(this, "Reset to current date & time", Toast.LENGTH_SHORT).show()
        }

        binding.btnPostApplyTyped.setOnClickListener {
            val parsed = parseTypedDateTime()
            if (parsed == null) {
                Toast.makeText(this, "Use YYYY-MM-DD and HH:MM:SS format", Toast.LENGTH_SHORT).show()
            } else {
                workingConfig = workingConfig.copy(
                    timestampMode = TimestampMode.MANUAL,
                    manualTimestampMillis = parsed
                )
                syncDateAndTimeInputs(parsed)
                renderWatermarkedPreview()
                Toast.makeText(this, "Updated date & time on photo", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchPost24Hour.setOnCheckedChangeListener { _, isChecked ->
            workingConfig = workingConfig.copy(is24HourFormat = isChecked)
            renderWatermarkedPreview()
        }

        binding.btnPostSavedLocations.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) {
                val items = SavedLocationDatabase.getInstance(this@PostCaptureEditActivity)
                    .savedLocationDao()
                    .getAll()
                withContext(Dispatchers.Main) {
                    if (items.isEmpty()) {
                        Toast.makeText(
                            this@PostCaptureEditActivity,
                            "No saved locations yet. Use Select on Map to save bookmarks.",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@withContext
                    }
                    val labels = items.map { "${it.label} — ${it.locationTitle}" }.toTypedArray()
                    MaterialAlertDialogBuilder(this@PostCaptureEditActivity)
                        .setTitle("Select Saved Location")
                        .setItems(labels) { _, which ->
                            val picked = items[which]
                            hasCustomLocationOverride = true
                            workingGps = picked.toGpsSnapshot()
                            binding.etPostLocationTitle.setText(picked.locationTitle)
                            binding.etPostStreetAddress.setText(picked.fullAddress)
                            binding.etPostLat.setText(String.format(Locale.US, "%.6f", picked.latitude))
                            binding.etPostLon.setText(String.format(Locale.US, "%.6f", picked.longitude))
                            updateLocationSourceLabel()
                            renderWatermarkedPreview()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        binding.btnPostPickOnMap.setOnClickListener {
            applyEditedLocationFields()
            val intent = Intent(this, LocationMapPickerActivity::class.java).apply {
                putExtra("initial_lat", workingGps.latitude)
                putExtra("initial_lon", workingGps.longitude)
                putExtra("initial_title", workingGps.buildLocationHeadline())
                putExtra("initial_address", workingGps.streetAddress)
            }
            mapPickerLauncher.launch(intent)
        }

        binding.btnPostLookupLocation.setOnClickListener {
            lookupPostLocation()
        }

        binding.etPostQuickLookup.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                lookupPostLocation()
                true
            } else {
                false
            }
        }

        binding.btnPostUpdateLocation.setOnClickListener {
            hasCustomLocationOverride = true
            applyEditedLocationFields()
            renderWatermarkedPreview()
            Toast.makeText(this, "Location applied on watermark", Toast.LENGTH_SHORT).show()
        }
    }

    private fun lookupPostLocation() {
        val quickText = binding.etPostQuickLookup.text?.toString()?.trim().orEmpty()
        val latRaw = binding.etPostLat.text?.toString()?.trim().orEmpty()
        val lonRaw = binding.etPostLon.text?.toString()?.trim().orEmpty()
        val queryToResolve = quickText.ifBlank {
            if (latRaw.isNotEmpty() && lonRaw.isNotEmpty()) "$latRaw, $lonRaw" else ""
        }
        if (queryToResolve.isEmpty()) {
            Toast.makeText(this, "Enter a Plus Code or Latitude/Longitude first", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnPostLookupLocation.isEnabled = false
        lifecycleScope.launch {
            val resolved = LocationInputResolver.resolveInput(
                context = this@PostCaptureEditActivity,
                rawInput = queryToResolve,
                referenceLat = if (workingGps.hasFix) workingGps.latitude else 31.520370,
                referenceLon = if (workingGps.hasFix) workingGps.longitude else 74.358749
            )
            binding.btnPostLookupLocation.isEnabled = true
            if (resolved != null) {
                hasCustomLocationOverride = true
                workingGps = workingGps.copy(
                    hasFix = true,
                    latitude = resolved.latitude,
                    longitude = resolved.longitude,
                    accuracyMeters = 0f,
                    city = resolved.city.ifBlank { resolved.locationTitle },
                    district = resolved.district,
                    province = resolved.province,
                    country = resolved.country,
                    streetAddress = resolved.fullAddress,
                    isOfflineAddress = false,
                    locationSource = LocationSource.MANUAL_MAP
                )
                binding.etPostLocationTitle.setText(resolved.locationTitle)
                binding.etPostStreetAddress.setText(resolved.fullAddress)
                binding.etPostLat.setText(String.format(Locale.US, "%.6f", resolved.latitude))
                binding.etPostLon.setText(String.format(Locale.US, "%.6f", resolved.longitude))
                updateLocationSourceLabel()
                renderWatermarkedPreview()
                Toast.makeText(
                    this@PostCaptureEditActivity,
                    "Location found: ${resolved.locationTitle}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@PostCaptureEditActivity,
                    "Could not resolve Plus Code or coordinates",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun applyEditedLocationFields() {
        val headline = binding.etPostLocationTitle.text?.toString()?.trim().orEmpty()
        val street = binding.etPostStreetAddress.text?.toString()?.trim().orEmpty()
        val latStr = binding.etPostLat.text?.toString()?.trim().orEmpty()
        val lonStr = binding.etPostLon.text?.toString()?.trim().orEmpty()
        val latVal = latStr.toDoubleOrNull()
        val lonVal = lonStr.toDoubleOrNull()

        if (headline != initialTitleInput ||
            street != initialAddressInput ||
            latStr != initialLatInput ||
            lonStr != initialLonInput
        ) {
            hasCustomLocationOverride = true
        }

        val hasCoords = latVal != null && lonVal != null
        workingGps = workingGps.copy(
            hasFix = workingGps.hasFix || hasCoords || headline.isNotEmpty() || street.isNotEmpty(),
            latitude = latVal ?: workingGps.latitude,
            longitude = lonVal ?: workingGps.longitude,
            city = if (headline.isNotEmpty()) headline else workingGps.city,
            district = if (headline.isNotEmpty()) "" else workingGps.district,
            province = if (headline.isNotEmpty()) "" else workingGps.province,
            country = if (headline.isNotEmpty()) "" else workingGps.country,
            streetAddress = street
        )
    }

    private fun parseTypedDateTime(): Long? {
        val dStr = binding.etPostDate.text?.toString()?.trim().orEmpty()
        var tStr = binding.etPostTime.text?.toString()?.trim().orEmpty()
        if (dStr.isEmpty() || tStr.isEmpty()) return null
        if (tStr.count { it == ':' } == 1) tStr = "$tStr:00"
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { isLenient = false }
                .parse("$dStr $tStr")?.time
        }.getOrNull()
    }

    private fun syncDateAndTimeInputs(ms: Long) {
        val dt = Date(ms)
        binding.etPostDate.setText(isoDateFmt.format(dt))
        binding.etPostTime.setText(isoTimeFmt.format(dt))
    }

    private fun loadPreviewBitmapAsync() {
        val file = sourceTempFile ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            val decoded = if (isVideoMode) {
                PhotoStorageManager.loadVideoFrameBitmap(
                    context = this@PostCaptureEditActivity,
                    uri = null,
                    filePath = file.absolutePath,
                    timeUs = 0L
                )
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, bounds)
                val longEdge = max(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longEdge / sample > 1280) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(
                    file.absolutePath,
                    BitmapFactory.Options().apply { inSampleSize = sample }
                )
            }
            previewBaseBitmap = decoded
            withContext(Dispatchers.Main) {
                renderWatermarkedPreview()
            }
        }
    }

    private fun renderWatermarkedPreview() {
        val base = previewBaseBitmap ?: return
        val stampStr = workingConfig.formatTimestamp(workingGps, captureMomentMillis)
        binding.tvPostTimestampStatus.text = if (isVideoMode) {
            "Date & Time on Video: $stampStr"
        } else {
            "Date & Time on Photo: $stampStr"
        }

        if (isVideoMode && binding.vvPostVideo.isPlaying) {
            binding.vvPostVideo.pause()
            binding.vvPostVideo.visibility = View.GONE
            binding.ivCapturedPreview.visibility = View.VISIBLE
            binding.btnPostPlayVideo.visibility = View.VISIBLE
        }

        lifecycleScope.launch(Dispatchers.Default) {
            val rendered = WatermarkRenderer.renderOntoBitmap(
                source = base,
                config = workingConfig,
                gps = workingGps,
                logoBitmap = logoBitmap,
                captureMillis = captureMomentMillis
            )
            withContext(Dispatchers.Main) {
                val old = currentPreviewWatermarked
                currentPreviewWatermarked = rendered
                binding.ivCapturedPreview.setImageBitmap(rendered)
                if (old != null && old != base && !old.isRecycled) {
                    old.recycle()
                }
            }
        }
    }

    private fun saveFinalWatermarkedPhoto() {
        if (!AuthManager.isAdmin(this)) {
            discardAndFinish()
            return
        }
        if (isSaving) return
        val file = sourceTempFile ?: return
        isSaving = true
        binding.btnSaveTop.isEnabled = false
        binding.btnPostSaveBottom.isEnabled = false

        // Apply any typed changes in the date/time or location fields before saving
        parseTypedDateTime()?.let { typedMs ->
            val currentResolved = workingConfig.resolveTimestampMillis(workingGps, captureMomentMillis)
            if (kotlin.math.abs(typedMs - currentResolved) > 1500L) {
                workingConfig = workingConfig.copy(
                    timestampMode = TimestampMode.MANUAL,
                    manualTimestampMillis = typedMs
                )
            }
        }
        applyEditedLocationFields()

        val finalConfig = workingConfig.copy()
        val finalGps = workingGps.copy()
        val capturedOriginalGps = originalCaptureGps.copy()

        if (isVideoMode) {
            if (binding.vvPostVideo.isPlaying) {
                binding.vvPostVideo.stopPlayback()
            }
            binding.progressPostVideo.progress = 0
            binding.progressPostVideo.visibility = View.VISIBLE
            binding.tvPostVideoProgress.visibility = View.VISIBLE
            binding.tvPostVideoProgress.text = "Rendering GPS watermark on video (0%)..."

            val finalTimeline = if (hasCustomLocationOverride) emptyList() else gpsTimeline

            lifecycleScope.launch(Dispatchers.IO) {
                val savedPair = try {
                    PhotoStorageManager.processAndSaveWatermarkedVideo(
                        context = this@PostCaptureEditActivity,
                        sourceFile = file,
                        config = finalConfig,
                        gps = finalGps,
                        gpsTimeline = finalTimeline,
                        captureDeviceMillis = captureMomentMillis,
                        replaceExistingWatermark = false
                    ) { progress ->
                        val pct = (progress * 100f).toInt().coerceIn(0, 100)
                        runOnUiThread {
                            binding.progressPostVideo.progress = pct
                            binding.tvPostVideoProgress.text = "Rendering GPS watermark on video ($pct%)..."
                        }
                    }
                } finally {
                    if (file.exists()) file.delete()
                }
                withContext(Dispatchers.Main) {
                    isSaving = false
                    binding.progressPostVideo.visibility = View.GONE
                    binding.tvPostVideoProgress.visibility = View.GONE
                    if (savedPair != null) {
                        Toast.makeText(
                            this@PostCaptureEditActivity,
                            "Saved watermarked video to ${PhotoStorageManager.RELATIVE_MOVIES_PATH}",
                            Toast.LENGTH_SHORT
                        ).show()
                        setResult(RESULT_OK)
                        finish()
                    } else {
                        binding.btnSaveTop.isEnabled = true
                        binding.btnPostSaveBottom.isEnabled = true
                        Toast.makeText(
                            this@PostCaptureEditActivity,
                            "Failed to save watermarked video",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }

        val hasCustomLocation = finalGps.locationSource != LocationSource.LIVE_GPS ||
            finalGps.buildLocationHeadline() != capturedOriginalGps.buildLocationHeadline() ||
            finalGps.streetAddress != capturedOriginalGps.streetAddress

        lifecycleScope.launch(Dispatchers.IO) {
            val fullResBitmap = BitmapFactory.decodeFile(file.absolutePath)
            val savedUri = if (fullResBitmap != null) {
                val uri = PhotoStorageManager.processAndSaveWatermarkedPhoto(
                    context = this@PostCaptureEditActivity,
                    sourceBitmap = fullResBitmap,
                    config = finalConfig,
                    gps = finalGps,
                    captureDeviceMillis = captureMomentMillis,
                    isGalleryImport = false,
                    hasCustomOverrides = hasCustomLocation,
                    originalCaptureGps = capturedOriginalGps
                )
                fullResBitmap.recycle()
                uri
            } else {
                null
            }
            if (file.exists()) file.delete()

            withContext(Dispatchers.Main) {
                isSaving = false
                if (savedUri != null) {
                    Toast.makeText(
                        this@PostCaptureEditActivity,
                        "Saved watermarked photo to ${PhotoStorageManager.RELATIVE_PICTURES_PATH}",
                        Toast.LENGTH_SHORT
                    ).show()
                    setResult(RESULT_OK)
                    finish()
                } else {
                    binding.btnSaveTop.isEnabled = true
                    binding.btnPostSaveBottom.isEnabled = true
                    Toast.makeText(
                        this@PostCaptureEditActivity,
                        "Failed to save watermarked photo",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun discardAndFinish() {
        if (isVideoMode) {
            runCatching { binding.vvPostVideo.stopPlayback() }
        }
        sourceTempFile?.let { if (it.exists()) it.delete() }
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isVideoMode) {
            runCatching { binding.vvPostVideo.stopPlayback() }
        }
        previewBaseBitmap?.let { if (!it.isRecycled) it.recycle() }
    }
}
