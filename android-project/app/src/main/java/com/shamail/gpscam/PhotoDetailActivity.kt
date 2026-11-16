package com.shamail.gpscam

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shamail.gpscam.data.AppPreferences
import com.shamail.gpscam.data.AuthManager
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.LocationSource
import com.shamail.gpscam.data.SavedLocationDatabase
import com.shamail.gpscam.data.TimestampMode
import com.shamail.gpscam.databinding.ActivityPhotoDetailBinding
import com.shamail.gpscam.location.LocationInputResolver
import com.shamail.gpscam.storage.EditablePhotoContext
import com.shamail.gpscam.storage.EditableVideoContext
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
import kotlin.math.abs

class PhotoDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotoDetailBinding
    private lateinit var prefs: AppPreferences

    private lateinit var currentPhotoUri: Uri
    private var currentPhotoName: String = "SHAMAIL_GPS.jpg"
    private var currentFilePath: String? = null
    private var currentProvenance: String = "Verified Capture Info"
    private var isVideo: Boolean = false
    private var isVideoPlaying: Boolean = false

    private var editableContext: EditablePhotoContext? = null
    private var editableVideoContext: EditableVideoContext? = null
    private var previewBitmap: Bitmap? = null
    private var videoThumbBitmap: Bitmap? = null
    private var pendingEditedMillis: Long? = null
    private var pendingEditedGps: GpsSnapshot? = null
    private var hasPendingLocationChange: Boolean = false
    private var isBusy = false

    private val mapPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (!AuthManager.isAdmin(this)) return@registerForActivityResult
        if (result.resultCode == RESULT_OK && result.data != null) {
            val data = result.data ?: return@registerForActivityResult
            val baseGps = pendingEditedGps
                ?: editableVideoContext?.gps
                ?: editableContext?.gps
                ?: return@registerForActivityResult
            val baseMillis = pendingEditedMillis
                ?: editableVideoContext?.originalDisplayMillis
                ?: editableContext?.originalDisplayMillis
                ?: System.currentTimeMillis()
            val title = data.getStringExtra("picked_title").orEmpty()
            val address = data.getStringExtra("picked_address").orEmpty()
            val lat = data.getDoubleExtra("picked_lat", baseGps.latitude)
            val lon = data.getDoubleExtra("picked_lon", baseGps.longitude)
            val wasBookmark = data.getBooleanExtra("picked_was_saved_bookmark", false)

            val updatedGps = baseGps.copy(
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
            hasPendingLocationChange = true
            renderEditPreview(
                editedMillis = baseMillis,
                editedGps = updatedGps
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotoDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)

        val uriStr = intent.getStringExtra("photo_uri") ?: run {
            finish()
            return
        }
        currentPhotoUri = Uri.parse(uriStr)
        currentPhotoName = intent.getStringExtra("photo_name") ?: "SHAMAIL_GPS.jpg"
        currentFilePath = intent.getStringExtra("photo_path")
        currentProvenance = intent.getStringExtra("photo_provenance") ?: "Verified Capture Info"
        isVideo = intent.getBooleanExtra("is_video", false) ||
            PhotoStorageManager.isVideoMedia(this, currentPhotoUri, currentPhotoName)

        if (isVideo) {
            binding.btnSharePhoto.text = "Share Video"
        }

        setupVideoControls()
        refreshHeaderAndPhotoView()
        applyAdminVisibility()

        binding.btnBackDetail.setOnClickListener { finish() }

        binding.btnSharePhoto.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (isVideo) "video/mp4" else "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, currentPhotoUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooserTitle = if (isVideo) "Share GPS Watermarked Video" else "Share GPS Watermarked Photo"
            startActivity(Intent.createChooser(shareIntent, chooserTitle))
        }

        binding.btnDeletePhoto.setOnClickListener {
            val mediaLabel = if (isVideo) "Video" else "Photo"
            val folderLabel = if (isVideo) PhotoStorageManager.RELATIVE_MOVIES_PATH else PhotoStorageManager.RELATIVE_PICTURES_PATH
            AlertDialog.Builder(this)
                .setTitle("Delete $mediaLabel?")
                .setMessage("Permanently remove $currentPhotoName from $folderLabel?")
                .setPositiveButton("Delete") { _, _ ->
                    if (isVideo && binding.vvFullVideo.isPlaying) {
                        binding.vvFullVideo.stopPlayback()
                    }
                    val deleted = PhotoStorageManager.deletePhoto(this, currentPhotoUri, currentFilePath)
                    if (deleted) {
                        Toast.makeText(this, "$mediaLabel deleted", Toast.LENGTH_SHORT).show()
                        finish()
                    } else {
                        Toast.makeText(this, "Unable to delete file on this Android version", Toast.LENGTH_LONG).show()
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        binding.btnEditPhotoDateTime.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            startDateTimeEditFlow()
        }

        binding.btnChangePhotoLocation.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            startLocationChangeFlow()
        }

        binding.btnViewMetadataHistory.setOnClickListener {
            showMetadataAndHistoryDialog()
        }

        binding.tvDetailMeta.setOnClickListener {
            showMetadataAndHistoryDialog()
        }

        binding.btnCancelEditPreview.setOnClickListener {
            cancelEditPreview()
        }

        binding.btnSaveEditedCopy.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            savePreviewedCopy()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isVideo && binding.vvFullVideo.isPlaying) {
            binding.vvFullVideo.pause()
            isVideoPlaying = false
            binding.btnPlayPauseVideo.visibility = View.VISIBLE
        }
    }

    override fun onResume() {
        super.onResume()
        applyAdminVisibility()
        if (!AuthManager.isAdmin(this) && (pendingEditedMillis != null || pendingEditedGps != null)) {
            cancelEditPreview()
        }
    }

    private fun applyAdminVisibility() {
        val isAdmin = AuthManager.isAdmin(this)
        binding.layoutAdminEditActions.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnEditPhotoDateTime.isEnabled = isAdmin
        binding.btnChangePhotoLocation.isEnabled = isAdmin
    }

    private fun setupVideoControls() {
        binding.btnPlayPauseVideo.setOnClickListener {
            toggleVideoPlayback()
        }
        binding.vvFullVideo.setOnClickListener {
            if (binding.layoutPreviewConfirmBar.visibility != View.VISIBLE) {
                toggleVideoPlayback()
            }
        }
        binding.vvFullVideo.setOnCompletionListener {
            isVideoPlaying = false
            binding.btnPlayPauseVideo.visibility = View.VISIBLE
        }
        binding.vvFullVideo.setOnErrorListener { _, _, _ ->
            isVideoPlaying = false
            binding.vvFullVideo.visibility = View.GONE
            binding.ivFullPhoto.visibility = View.VISIBLE
            binding.btnPlayPauseVideo.visibility = View.VISIBLE
            Toast.makeText(this, "Unable to play video clip", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private fun toggleVideoPlayback() {
        if (!isVideo || binding.layoutPreviewConfirmBar.visibility == View.VISIBLE) return
        if (binding.vvFullVideo.isPlaying) {
            binding.vvFullVideo.pause()
            isVideoPlaying = false
            binding.btnPlayPauseVideo.visibility = View.VISIBLE
        } else {
            binding.ivFullPhoto.visibility = View.GONE
            binding.vvFullVideo.visibility = View.VISIBLE
            binding.btnPlayPauseVideo.visibility = View.GONE
            binding.vvFullVideo.start()
            isVideoPlaying = true
        }
    }

    private fun refreshHeaderAndPhotoView() {
        binding.tvDetailTitle.text = currentPhotoName
        binding.tvDetailMeta.text = if (isVideo) {
            PhotoStorageManager.RELATIVE_MOVIES_PATH
        } else {
            PhotoStorageManager.RELATIVE_PICTURES_PATH
        }

        if (isVideo) {
            isVideoPlaying = false
            binding.vvFullVideo.stopPlayback()
            binding.vvFullVideo.visibility = View.GONE
            binding.ivFullPhoto.visibility = View.VISIBLE
            binding.btnPlayPauseVideo.visibility = View.VISIBLE
            val localFile = currentFilePath?.let { File(it) }?.takeIf { it.exists() }
            if (localFile != null) {
                binding.vvFullVideo.setVideoPath(localFile.absolutePath)
            } else {
                binding.vvFullVideo.setVideoURI(currentPhotoUri)
            }
            val uriSnap = currentPhotoUri
            val pathSnap = currentFilePath
            lifecycleScope.launch(Dispatchers.IO) {
                val frame = PhotoStorageManager.loadVideoFrameBitmap(
                    context = this@PhotoDetailActivity,
                    uri = uriSnap,
                    filePath = pathSnap,
                    timeUs = 0L
                )
                withContext(Dispatchers.Main) {
                    if (binding.layoutPreviewConfirmBar.visibility != View.VISIBLE && frame != null) {
                        val oldThumb = videoThumbBitmap
                        videoThumbBitmap = frame
                        binding.ivFullPhoto.setImageBitmap(frame)
                        if (oldThumb != null && oldThumb != frame && !oldThumb.isRecycled) {
                            oldThumb.recycle()
                        }
                    }
                }
            }
        } else {
            binding.vvFullVideo.visibility = View.GONE
            binding.btnPlayPauseVideo.visibility = View.GONE
            binding.ivFullPhoto.visibility = View.VISIBLE
            binding.ivFullPhoto.setImageURI(currentPhotoUri)
        }
    }

    private fun ensureAnyContextLoaded(onReady: (GpsSnapshot, Long) -> Unit) {
        if (!AuthManager.isAdmin(this) || isBusy) return
        if (isVideo) {
            val existingVid = editableVideoContext
            if (existingVid != null) {
                onReady(existingVid.gps, existingVid.originalDisplayMillis)
                return
            }
            val appCfg = prefs.loadConfig()
            isBusy = true
            binding.btnEditPhotoDateTime.isEnabled = false
            binding.btnChangePhotoLocation.isEnabled = false
            lifecycleScope.launch(Dispatchers.IO) {
                val loaded = PhotoStorageManager.loadEditableVideoContext(
                    context = this@PhotoDetailActivity,
                    videoUri = currentPhotoUri,
                    displayName = currentPhotoName,
                    filePath = currentFilePath,
                    fallbackConfig = appCfg
                )
                withContext(Dispatchers.Main) {
                    isBusy = false
                    val isAdminNow = AuthManager.isAdmin(this@PhotoDetailActivity)
                    binding.btnEditPhotoDateTime.isEnabled = isAdminNow
                    binding.btnChangePhotoLocation.isEnabled = isAdminNow
                    if (loaded == null) {
                        Toast.makeText(
                            this@PhotoDetailActivity,
                            "Unable to load video for watermark editing",
                            Toast.LENGTH_SHORT
                        ).show()
                        return@withContext
                    }
                    editableVideoContext = loaded
                    onReady(loaded.gps, loaded.originalDisplayMillis)
                }
            }
            return
        }

        val existingCtx = editableContext
        if (existingCtx != null) {
            onReady(existingCtx.gps, existingCtx.originalDisplayMillis)
            return
        }

        val appCfg = prefs.loadConfig()
        isBusy = true
        binding.btnEditPhotoDateTime.isEnabled = false
        binding.btnChangePhotoLocation.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = PhotoStorageManager.loadEditablePhotoContext(
                context = this@PhotoDetailActivity,
                photoUri = currentPhotoUri,
                displayName = currentPhotoName,
                filePath = currentFilePath,
                fallbackConfig = appCfg
            )
            withContext(Dispatchers.Main) {
                isBusy = false
                val isAdminNow = AuthManager.isAdmin(this@PhotoDetailActivity)
                binding.btnEditPhotoDateTime.isEnabled = isAdminNow
                binding.btnChangePhotoLocation.isEnabled = isAdminNow
                if (loaded == null) {
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Unable to load photo for watermark editing",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@withContext
                }
                editableContext = loaded
                onReady(loaded.gps, loaded.originalDisplayMillis)
            }
        }
    }

    private fun startDateTimeEditFlow() {
        ensureAnyContextLoaded { _, origMillis ->
            val appCfg = prefs.loadConfig()
            SequentialDateTimePicker.launch(
                activity = this,
                initialMillis = pendingEditedMillis ?: origMillis,
                is24HourFormat = appCfg.is24HourFormat,
                dateFormatPattern = appCfg.dateFormatPattern
            ) { confirmedMillis ->
                val baseGps = pendingEditedGps
                    ?: editableVideoContext?.gps
                    ?: editableContext?.gps
                    ?: return@launch
                renderEditPreview(
                    editedMillis = confirmedMillis,
                    editedGps = baseGps
                )
            }
        }
    }

    private fun startLocationChangeFlow() {
        ensureAnyContextLoaded { origGps, origMillis ->
            val currentGps = pendingEditedGps ?: origGps
            val options = arrayOf(
                "Pick on Map / Search Place",
                "Choose Saved Location",
                "Enter Plus Code, Coordinates, or Location"
            )
            MaterialAlertDialogBuilder(this)
                .setTitle("Change Watermark Location")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> launchMapPickerForPhoto(currentGps)
                        1 -> showSavedLocationsChooser(currentGps, origMillis)
                        2 -> showManualLocationDialog(currentGps, origMillis)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun launchMapPickerForPhoto(baseGps: GpsSnapshot) {
        if (!AuthManager.isAdmin(this)) return
        val intent = Intent(this, LocationMapPickerActivity::class.java).apply {
            if (baseGps.hasFix) {
                putExtra("initial_lat", baseGps.latitude)
                putExtra("initial_lon", baseGps.longitude)
                putExtra("initial_title", baseGps.buildLocationHeadline())
                putExtra("initial_address", baseGps.streetAddress)
            }
        }
        mapPickerLauncher.launch(intent)
    }

    private fun showSavedLocationsChooser(baseGps: GpsSnapshot, origDisplayMillis: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedItems = SavedLocationDatabase.getInstance(this@PhotoDetailActivity)
                .savedLocationDao()
                .getAll()
            withContext(Dispatchers.Main) {
                if (savedItems.isEmpty()) {
                    MaterialAlertDialogBuilder(this@PhotoDetailActivity)
                        .setTitle("No Saved Locations Yet")
                        .setMessage("Pick a location on the map first to save bookmarks or apply it directly.")
                        .setPositiveButton("Pick on Map") { _, _ ->
                            launchMapPickerForPhoto(baseGps)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    return@withContext
                }
                val labels = savedItems.map { "${it.label} — ${it.locationTitle}" }.toTypedArray()
                MaterialAlertDialogBuilder(this@PhotoDetailActivity)
                    .setTitle("Select Saved Location")
                    .setItems(labels) { _, idx ->
                        val chosen = savedItems[idx]
                        hasPendingLocationChange = true
                        renderEditPreview(
                            editedMillis = pendingEditedMillis ?: origDisplayMillis,
                            editedGps = chosen.toGpsSnapshot()
                        )
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun showManualLocationDialog(baseGps: GpsSnapshot, origDisplayMillis: Long) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }

        val initialTitleText = if (baseGps.hasFix) baseGps.buildLocationHeadline() else ""
        var lookupAppliedQuery: String? = null

        val etQuickLookup = EditText(this).apply {
            hint = "Plus Code (e.g. P77M+VVC, Gujrat) or Coordinates"
            setSingleLine(true)
        }
        val btnLookup = com.google.android.material.button.MaterialButton(this).apply {
            text = "Get Location"
        }
        val etTitle = EditText(this).apply {
            hint = "Location Title (e.g. Sarai Alamgir, Punjab, Pakistan)"
            setText(initialTitleText)
            setSingleLine(true)
        }
        val etAddress = EditText(this).apply {
            hint = "Full Street / Area Address"
            setText(baseGps.streetAddress)
        }
        val etLat = EditText(this).apply {
            hint = "Latitude (e.g. 32.905644)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            if (baseGps.hasFix) {
                setText(String.format(Locale.US, "%.6f", baseGps.latitude))
            }
            setSingleLine(true)
        }
        val etLon = EditText(this).apply {
            hint = "Longitude (e.g. 73.749995)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            if (baseGps.hasFix) {
                setText(String.format(Locale.US, "%.6f", baseGps.longitude))
            }
            setSingleLine(true)
        }

        container.addView(etQuickLookup)
        container.addView(btnLookup)
        container.addView(etTitle)
        container.addView(etAddress)
        container.addView(etLat)
        container.addView(etLon)

        btnLookup.setOnClickListener {
            val quickText = etQuickLookup.text?.toString()?.trim().orEmpty()
            val latRaw = etLat.text?.toString()?.trim().orEmpty()
            val lonRaw = etLon.text?.toString()?.trim().orEmpty()
            val queryToResolve = quickText.ifBlank {
                if (latRaw.isNotEmpty() && lonRaw.isNotEmpty()) "$latRaw, $lonRaw" else ""
            }
            if (queryToResolve.isEmpty()) {
                Toast.makeText(
                    this,
                    "Enter a Plus Code or Latitude/Longitude first",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            btnLookup.isEnabled = false
            btnLookup.text = "Getting Location..."
            lifecycleScope.launch {
                val resolved = LocationInputResolver.resolveInput(
                    context = this@PhotoDetailActivity,
                    rawInput = queryToResolve,
                    referenceLat = if (baseGps.hasFix) baseGps.latitude else 31.520370,
                    referenceLon = if (baseGps.hasFix) baseGps.longitude else 74.358749
                )
                btnLookup.isEnabled = true
                btnLookup.text = "Get Location"
                if (resolved != null) {
                    lookupAppliedQuery = quickText
                    etTitle.setText(resolved.locationTitle)
                    etAddress.setText(resolved.fullAddress)
                    etLat.setText(String.format(Locale.US, "%.6f", resolved.latitude))
                    etLon.setText(String.format(Locale.US, "%.6f", resolved.longitude))
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Location found: ${resolved.locationTitle}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Could not find location. Try a full Plus Code or Lat, Lon.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Plus Code, Coordinates & Location")
            .setView(container)
            .setPositiveButton("Preview") { _, _ ->
                val quickText = etQuickLookup.text?.toString()?.trim().orEmpty()
                val titleText = etTitle.text?.toString()?.trim().orEmpty()
                val addressText = etAddress.text?.toString()?.trim().orEmpty()
                val latStr = etLat.text?.toString()?.trim().orEmpty()
                val lonStr = etLon.text?.toString()?.trim().orEmpty()

                val parsedLat = if (latStr.isEmpty()) baseGps.latitude else latStr.toDoubleOrNull()
                val parsedLon = if (lonStr.isEmpty()) baseGps.longitude else lonStr.toDoubleOrNull()

                if (quickText.isNotEmpty() && quickText != lookupAppliedQuery && titleText == initialTitleText) {
                    lifecycleScope.launch {
                        val resolved = LocationInputResolver.resolveInput(
                            context = this@PhotoDetailActivity,
                            rawInput = quickText,
                            referenceLat = if (baseGps.hasFix) baseGps.latitude else 31.520370,
                            referenceLon = if (baseGps.hasFix) baseGps.longitude else 74.358749
                        )
                        if (resolved != null) {
                            val resolvedGps = baseGps.copy(
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
                            hasPendingLocationChange = true
                            renderEditPreview(
                                editedMillis = pendingEditedMillis ?: origDisplayMillis,
                                editedGps = resolvedGps
                            )
                        } else {
                            Toast.makeText(
                                this@PhotoDetailActivity,
                                "Could not resolve Plus Code or coordinates",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    return@setPositiveButton
                }

                if ((latStr.isNotEmpty() && (parsedLat == null || parsedLat !in -90.0..90.0)) ||
                    (lonStr.isNotEmpty() && (parsedLon == null || parsedLon !in -180.0..180.0))
                ) {
                    Toast.makeText(
                        this,
                        "Enter valid Latitude (-90..90) and Longitude (-180..180)",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setPositiveButton
                }

                val updatedGps = baseGps.copy(
                    hasFix = true,
                    latitude = parsedLat ?: baseGps.latitude,
                    longitude = parsedLon ?: baseGps.longitude,
                    accuracyMeters = 0f,
                    city = titleText.ifBlank { baseGps.city },
                    district = if (titleText.isNotBlank()) "" else baseGps.district,
                    province = if (titleText.isNotBlank()) "" else baseGps.province,
                    country = if (titleText.isNotBlank()) "" else baseGps.country,
                    streetAddress = addressText,
                    isOfflineAddress = false,
                    locationSource = LocationSource.MANUAL_MAP
                )
                hasPendingLocationChange = true
                renderEditPreview(
                    editedMillis = pendingEditedMillis ?: origDisplayMillis,
                    editedGps = updatedGps
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderEditPreview(editedMillis: Long, editedGps: GpsSnapshot) {
        if (!AuthManager.isAdmin(this)) return
        val appCfg = prefs.loadConfig()

        if (isVideo) {
            val vCtx = editableVideoContext ?: return
            if (binding.vvFullVideo.isPlaying) binding.vvFullVideo.pause()
            isVideoPlaying = false
            binding.vvFullVideo.visibility = View.GONE
            binding.btnPlayPauseVideo.visibility = View.GONE
            binding.ivFullPhoto.visibility = View.VISIBLE

            val previewCfg = vCtx.config.copy(
                timestampMode = TimestampMode.MANUAL,
                manualTimestampMillis = editedMillis,
                is24HourFormat = appCfg.is24HourFormat,
                dateFormatPattern = appCfg.dateFormatPattern,
                showWatermark = true
            )

            isBusy = true
            binding.btnSaveEditedCopy.isEnabled = false
            lifecycleScope.launch(Dispatchers.Default) {
                val logoBmp = WatermarkRenderer.loadLogoBitmap(this@PhotoDetailActivity, previewCfg)
                val rendered = WatermarkRenderer.renderOntoBitmap(
                    source = vCtx.previewFrameBitmap,
                    config = previewCfg,
                    gps = editedGps,
                    logoBitmap = logoBmp,
                    captureMillis = editedMillis,
                    forceOpaqueBackground = false,
                    replaceExistingWatermark = !vCtx.hasCleanOriginal,
                    oldGpsForRemoval = vCtx.gps
                )
                val formattedStamp = previewCfg.formatTimestamp(editedGps, editedMillis)
                val locationLabel = if (editedGps.hasFix) editedGps.buildLocationHeadline() else "Location"
                withContext(Dispatchers.Main) {
                    isBusy = false
                    binding.btnSaveEditedCopy.isEnabled = true
                    pendingEditedMillis = editedMillis
                    pendingEditedGps = editedGps
                    val oldPreview = previewBitmap
                    previewBitmap = rendered
                    binding.ivFullPhoto.setImageBitmap(rendered)
                    if (oldPreview != null && oldPreview != rendered && oldPreview != vCtx.previewFrameBitmap && !oldPreview.isRecycled) {
                        oldPreview.recycle()
                    }
                    binding.tvPreviewBanner.text = "Preview: $locationLabel · $formattedStamp"
                    binding.tvDetailMeta.text = "Previewing GPS watermark copy (original video unchanged)"
                    binding.progressVideoProcessing.visibility = View.GONE
                    binding.layoutPreviewConfirmBar.visibility = View.VISIBLE
                }
            }
            return
        }

        val ctx = editableContext ?: return
        val previewCfg = ctx.config.copy(
            timestampMode = TimestampMode.MANUAL,
            manualTimestampMillis = editedMillis,
            is24HourFormat = appCfg.is24HourFormat,
            dateFormatPattern = appCfg.dateFormatPattern,
            showWatermark = true
        )

        isBusy = true
        binding.btnSaveEditedCopy.isEnabled = false
        lifecycleScope.launch(Dispatchers.Default) {
            val logoBmp = WatermarkRenderer.loadLogoBitmap(this@PhotoDetailActivity, previewCfg)
            val rendered = WatermarkRenderer.renderOntoBitmap(
                source = ctx.sourceBitmap,
                config = previewCfg,
                gps = editedGps,
                logoBitmap = logoBmp,
                captureMillis = editedMillis,
                forceOpaqueBackground = false,
                replaceExistingWatermark = !ctx.hasCleanOriginal,
                oldGpsForRemoval = ctx.gps
            )
            val formattedStamp = previewCfg.formatTimestamp(editedGps, editedMillis)
            val locationLabel = if (editedGps.hasFix) editedGps.buildLocationHeadline() else "Location"
            withContext(Dispatchers.Main) {
                isBusy = false
                binding.btnSaveEditedCopy.isEnabled = true
                pendingEditedMillis = editedMillis
                pendingEditedGps = editedGps
                val oldPreview = previewBitmap
                previewBitmap = rendered
                binding.ivFullPhoto.setImageBitmap(rendered)
                if (oldPreview != null && oldPreview != rendered && oldPreview != ctx.sourceBitmap && !oldPreview.isRecycled) {
                    oldPreview.recycle()
                }
                binding.tvPreviewBanner.text = "Preview: $locationLabel · $formattedStamp"
                binding.tvDetailMeta.text = "Previewing custom watermark copy (original unchanged)"
                binding.progressVideoProcessing.visibility = View.GONE
                binding.layoutPreviewConfirmBar.visibility = View.VISIBLE
            }
        }
    }

    private fun cancelEditPreview() {
        pendingEditedMillis = null
        pendingEditedGps = null
        hasPendingLocationChange = false
        binding.progressVideoProcessing.visibility = View.GONE
        binding.layoutPreviewConfirmBar.visibility = View.GONE
        refreshHeaderAndPhotoView()
        val old = previewBitmap
        previewBitmap = null
        if (old != null && !old.isRecycled) {
            old.recycle()
        }
    }

    private fun savePreviewedCopy() {
        if (!AuthManager.isAdmin(this) || isBusy) return
        val appCfg = prefs.loadConfig()

        if (isVideo) {
            val vCtx = editableVideoContext ?: return
            val editedMillis = pendingEditedMillis ?: vCtx.originalDisplayMillis
            val editedGps = pendingEditedGps ?: vCtx.gps
            val keepMovingTimeline = !hasPendingLocationChange

            isBusy = true
            binding.btnSaveEditedCopy.isEnabled = false
            binding.btnCancelEditPreview.isEnabled = false
            binding.btnEditPhotoDateTime.isEnabled = false
            binding.btnChangePhotoLocation.isEnabled = false
            binding.progressVideoProcessing.progress = 0
            binding.progressVideoProcessing.visibility = View.VISIBLE
            binding.tvPreviewBanner.text = "Rendering GPS watermark on video (0%)..."

            lifecycleScope.launch(Dispatchers.IO) {
                val savedPair = PhotoStorageManager.saveEditedVideoCopy(
                    context = this@PhotoDetailActivity,
                    editable = vCtx,
                    editedMillis = editedMillis,
                    is24HourFormat = appCfg.is24HourFormat,
                    dateFormatPattern = appCfg.dateFormatPattern,
                    editedGps = editedGps,
                    preserveGpsTimeline = keepMovingTimeline
                ) { progress ->
                    val pct = (progress * 100f).toInt().coerceIn(0, 100)
                    runOnUiThread {
                        binding.progressVideoProcessing.progress = pct
                        binding.tvPreviewBanner.text = "Rendering GPS watermark on video ($pct%)..."
                    }
                }
                withContext(Dispatchers.Main) {
                    isBusy = false
                    binding.btnSaveEditedCopy.isEnabled = true
                    binding.btnCancelEditPreview.isEnabled = true
                    val isAdminNow = AuthManager.isAdmin(this@PhotoDetailActivity)
                    binding.btnEditPhotoDateTime.isEnabled = isAdminNow
                    binding.btnChangePhotoLocation.isEnabled = isAdminNow
                    binding.progressVideoProcessing.visibility = View.GONE

                    if (savedPair != null) {
                        val (newUri, newName) = savedPair
                        currentPhotoUri = newUri
                        currentPhotoName = newName
                        currentFilePath = null
                        pendingEditedMillis = null
                        pendingEditedGps = null
                        hasPendingLocationChange = false
                        binding.layoutPreviewConfirmBar.visibility = View.GONE

                        val oldPreview = previewBitmap
                        previewBitmap = null
                        if (oldPreview != null && !oldPreview.isRecycled) oldPreview.recycle()

                        editableVideoContext?.let { oldCtx ->
                            if (!oldCtx.previewFrameBitmap.isRecycled) oldCtx.previewFrameBitmap.recycle()
                        }
                        editableVideoContext = null

                        refreshHeaderAndPhotoView()
                        Toast.makeText(
                            this@PhotoDetailActivity,
                            "Saved video: $newName",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        binding.tvPreviewBanner.text = "Video export failed"
                        Toast.makeText(
                            this@PhotoDetailActivity,
                            "Failed to save new video copy",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            return
        }

        val ctx = editableContext ?: return
        val editedMillis = pendingEditedMillis ?: ctx.originalDisplayMillis
        val editedGps = pendingEditedGps ?: ctx.gps

        val hasTimeChange = abs(editedMillis - ctx.originalDisplayMillis) > 1000L ||
            abs(editedMillis - ctx.originalCaptureMillis) > 60_000L
        val hasLocChange = editedGps.locationSource != LocationSource.LIVE_GPS ||
            editedGps.buildLocationHeadline() != ctx.gps.buildLocationHeadline() ||
            editedGps.streetAddress != ctx.gps.streetAddress ||
            abs(editedGps.latitude - ctx.gps.latitude) > 0.0001 ||
            abs(editedGps.longitude - ctx.gps.longitude) > 0.0001

        isBusy = true
        binding.btnSaveEditedCopy.isEnabled = false
        binding.btnCancelEditPreview.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            val savedPair = PhotoStorageManager.saveEditedDateTimeCopy(
                context = this@PhotoDetailActivity,
                editable = ctx,
                editedMillis = editedMillis,
                is24HourFormat = appCfg.is24HourFormat,
                dateFormatPattern = appCfg.dateFormatPattern,
                editedGps = editedGps
            )
            withContext(Dispatchers.Main) {
                isBusy = false
                binding.btnSaveEditedCopy.isEnabled = true
                binding.btnCancelEditPreview.isEnabled = true

                if (savedPair != null) {
                    val (newUri, newName) = savedPair
                    currentPhotoUri = newUri
                    currentPhotoName = newName
                    currentFilePath = null
                    currentProvenance = when {
                        hasTimeChange && hasLocChange -> "Custom Location & Time"
                        hasLocChange -> "Custom Location Info"
                        else -> "Custom Date & Time"
                    }
                    pendingEditedMillis = null
                    pendingEditedGps = null
                    binding.layoutPreviewConfirmBar.visibility = View.GONE
                    refreshHeaderAndPhotoView()

                    val oldPreview = previewBitmap
                    previewBitmap = null
                    if (oldPreview != null && !oldPreview.isRecycled) {
                        oldPreview.recycle()
                    }
                    editableContext?.let { oldCtx ->
                        if (!oldCtx.sourceBitmap.isRecycled) oldCtx.sourceBitmap.recycle()
                    }
                    editableContext = null

                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Saved separate copy: $newName",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this@PhotoDetailActivity,
                        "Failed to save new photo copy",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun showMetadataAndHistoryDialog() {
        val uriSnapshot = currentPhotoUri
        val nameSnapshot = currentPhotoName
        val pathSnapshot = currentFilePath

        if (isVideo) {
            lifecycleScope.launch(Dispatchers.IO) {
                val vidMeta = PhotoStorageManager.readSourceVideoMetadata(
                    context = this@PhotoDetailActivity,
                    uri = uriSnapshot,
                    filePath = pathSnapshot
                )
                withContext(Dispatchers.Main) {
                    val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US)
                    val totalSec = (vidMeta.durationMillis / 1000L).coerceAtLeast(0L)
                    val sb = StringBuilder()
                    sb.append("File Name: $nameSnapshot\n")
                    sb.append("Location Folder: ${PhotoStorageManager.RELATIVE_MOVIES_PATH}\n")
                    sb.append(String.format(Locale.US, "Duration: %02d:%02d\n", totalSec / 60L, totalSec % 60L))
                    sb.append("Creation Date & Time: ")
                    sb.append(vidMeta.creationTimeMillis?.let { fmt.format(Date(it)) } ?: "Recorded video timestamp")
                    sb.append("\nGPS Coordinates: ")
                    if (vidMeta.latitude != null && vidMeta.longitude != null) {
                        sb.append(String.format(Locale.US, "%.6f°, %.6f°", vidMeta.latitude, vidMeta.longitude))
                    } else {
                        sb.append("Embedded on video watermark")
                    }
                    AlertDialog.Builder(this@PhotoDetailActivity)
                        .setTitle("Video Details")
                        .setMessage(sb.toString().trim())
                        .setPositiveButton("Close", null)
                        .show()
                }
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val record = PhotoStorageManager.loadPhotoMetadataRecord(
                context = this@PhotoDetailActivity,
                photoUri = uriSnapshot,
                displayName = nameSnapshot,
                filePath = pathSnapshot
            )
            withContext(Dispatchers.Main) {
                val fmt = SimpleDateFormat("dd MMM yyyy, hh:mm:ss a", Locale.US)
                val sb = StringBuilder()

                sb.append("ORIGINAL CAPTURE INFO (EXIF PRESERVED)\n")
                sb.append("• Capture Date & Time: ")
                sb.append(record.originalCaptureMillis?.let { fmt.format(Date(it)) } ?: "Not embedded in source")
                sb.append("\n• Original GPS Coordinates: ")
                if (record.originalLatitude != null && record.originalLongitude != null) {
                    sb.append(String.format(Locale.US, "%.6f°, %.6f°", record.originalLatitude, record.originalLongitude))
                } else {
                    sb.append("Not recorded")
                }
                if (!record.originalLocationTitle.isNullOrBlank()) {
                    sb.append("\n• Original Place: ${record.originalLocationTitle}")
                }
                if (!record.cameraInfo.isNullOrBlank()) {
                    sb.append("\n• Camera Device: ${record.cameraInfo}")
                }
                if (!record.sourcePhotoName.isNullOrBlank()) {
                    sb.append("\n• Source Photo: ${record.sourcePhotoName}")
                }

                sb.append("\n\nWATERMARK DISPLAY INFO\n")
                sb.append("• Displayed Time (${if (record.isCustomTimestamp) "Custom Info" else "Original Capture"}): ")
                sb.append(fmt.format(Date(record.watermarkDateTimeMillis)))
                if (record.watermarkLocationTitle.isNotBlank() || record.watermarkLatitude != null) {
                    sb.append("\n• Displayed Location (${if (record.isCustomLocation) "Custom Info" else "Original GPS"}): ")
                    sb.append(record.watermarkLocationTitle.ifBlank { "Coordinates attached" })
                }
                if (record.watermarkAddress.isNotBlank()) {
                    sb.append("\n• Displayed Address: ${record.watermarkAddress}")
                }
                if (record.watermarkLatitude != null && record.watermarkLongitude != null) {
                    sb.append(
                        String.format(
                            Locale.US,
                            "\n• Displayed Coords: %.6f°, %.6f°",
                            record.watermarkLatitude,
                            record.watermarkLongitude
                        )
                    )
                }

                sb.append("\n\nEDIT HISTORY\n")
                if (record.history.isEmpty()) {
                    sb.append("• Original photo · No custom watermark edits recorded.")
                } else {
                    record.history.forEachIndexed { idx, entry ->
                        sb.append("${idx + 1}. ${entry.actionLabel}\n")
                        sb.append("   Recorded: ${fmt.format(Date(entry.recordedAtMillis))}\n")
                        if (!entry.sourcePhotoName.isNullOrBlank()) {
                            sb.append("   Source: ${entry.sourcePhotoName}\n")
                        }
                        if (entry.customDateTimeMillis != null) {
                            sb.append("   Custom Time: ${fmt.format(Date(entry.customDateTimeMillis))}\n")
                        }
                        if (!entry.customLocationTitle.isNullOrBlank()) {
                            sb.append("   Custom Place: ${entry.customLocationTitle}\n")
                        }
                    }
                }

                AlertDialog.Builder(this@PhotoDetailActivity)
                    .setTitle("Photo Info & Edit History")
                    .setMessage(sb.toString().trim())
                    .setPositiveButton("Close", null)
                    .show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isVideo) {
            runCatching { binding.vvFullVideo.stopPlayback() }
        }
        binding.ivFullPhoto.setImageDrawable(null)
        previewBitmap?.let { if (!it.isRecycled) it.recycle() }
        videoThumbBitmap?.let { if (!it.isRecycled) it.recycle() }
        editableContext?.let { if (!it.sourceBitmap.isRecycled) it.sourceBitmap.recycle() }
        editableVideoContext?.let { if (!it.previewFrameBitmap.isRecycled) it.previewFrameBitmap.recycle() }
    }
}
