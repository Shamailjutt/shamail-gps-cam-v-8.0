package com.shamail.gpscam

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SoundEffectConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.ZoomState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.shamail.gpscam.data.AppPreferences
import com.shamail.gpscam.data.AuthManager
import com.shamail.gpscam.data.GpsSnapshot
import com.shamail.gpscam.data.TimestampMode
import com.shamail.gpscam.data.VideoGpsKeyframe
import com.shamail.gpscam.data.WatermarkConfig
import com.shamail.gpscam.databinding.ActivityMainBinding
import com.shamail.gpscam.location.LocationHelper
import com.shamail.gpscam.metadata.PhotoMetadataEngine
import com.shamail.gpscam.storage.PhotoStorageManager
import com.shamail.gpscam.ui.DateTimeEditorSheet
import com.shamail.gpscam.watermark.WatermarkRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPreferences
    private lateinit var locationHelper: LocationHelper

    private var watermarkConfig = WatermarkConfig()
    private var gpsSnapshot = GpsSnapshot()
    private var cachedLogoBitmap: Bitmap? = null

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var activeCamera: Camera? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var flashMode: Int = ImageCapture.FLASH_MODE_AUTO
    private var isVideoMode: Boolean = false
    private var isTorchEnabled: Boolean = false
    private var isCapturingPhoto: Boolean = false
    private var isRecordingVideo: Boolean = false
    private var isProcessingVideo: Boolean = false
    private var recordingStartElapsedMs: Long = 0L
    private var recordedVideoGpsTimeline = mutableListOf<VideoGpsKeyframe>()
    private var activeVideoCaptureGpsSeed: GpsSnapshot? = null

    private var observedZoomLiveData: LiveData<ZoomState>? = null
    private var currentZoomRatio: Float = 1.0f
    private var minZoomRatio: Float = 1.0f
    private var maxZoomRatio: Float = 1.0f
    private var isUpdatingZoomUiFromCamera: Boolean = false
    private var isDraggingZoomSlider: Boolean = false
    private var supportedZoomPresets: List<Float> = emptyList()
    private val zoomPresetViews = mutableListOf<Pair<Float, TextView>>()
    private var isPinchingZoom: Boolean = false
    private var pinchOccurredInGesture: Boolean = false
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private val zoomStateObserver = Observer<ZoomState> { state ->
        onCameraZoomStateChanged(state)
    }

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            refreshWatermarkAndHeader()
            clockHandler.postDelayed(this, 1000L)
        }
    }

    private val recTimerRunnable = object : Runnable {
        override fun run() {
            if (isRecordingVideo) {
                val elapsedSec = ((SystemClock.elapsedRealtime() - recordingStartElapsedMs) / 1000L).coerceAtLeast(0L)
                val mins = elapsedSec / 60L
                val secs = elapsedSec % 60L
                binding.tvRecTimer.text = String.format(Locale.US, "%02d:%02d", mins, secs)
                clockHandler.postDelayed(this, 500L)
            }
        }
    }

    private val postCaptureEditLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshGalleryBadge()
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                this,
                "Microphone permission denied · Video will record without audio",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasCam = grants[Manifest.permission.CAMERA] == true || hasCameraPermission()
        if (hasCam) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
            if (hasLocationPermission()) {
                locationHelper.startUpdates()
            } else {
                binding.tvGpsStatus.text = "GPS permission off · Camera ready"
            }
            refreshGalleryBadge()
        } else {
            showPermissionOverlay(permanentlyDenied = !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!AuthManager.hasActiveSession(this)) {
            startActivity(Intent(this, AppLoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = AppPreferences(this)
        val initialCfg = effectiveConfigForRole(prefs.loadConfig())
        watermarkConfig = initialCfg
        lensFacing = if (initialCfg.defaultLensFacing == 0) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        flashMode = when (initialCfg.defaultFlashMode) {
            1 -> ImageCapture.FLASH_MODE_ON
            2 -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }

        locationHelper = LocationHelper(this, lifecycleScope) { updatedGps ->
            gpsSnapshot = updatedGps
            if (isRecordingVideo && updatedGps.hasFix) {
                val offsetMs = (SystemClock.elapsedRealtime() - recordingStartElapsedMs).coerceAtLeast(0L)
                appendRecordingGpsKeyframe(offsetMs, updatedGps.copy())
            }
            refreshWatermarkAndHeader()
        }

        setupInteractiveControls()
        reloadSettingsAndAssets()
        checkAndRequestRuntimePermissions()
    }

    override fun onResume() {
        super.onResume()
        if (!AuthManager.hasActiveSession(this)) {
            startActivity(Intent(this, AppLoginActivity::class.java))
            finish()
            return
        }
        reloadSettingsAndAssets()
        clockHandler.post(clockTicker)
        if (hasCameraPermission()) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
        }
        if (hasLocationPermission() && watermarkConfig.autoLocationEnabled) {
            locationHelper.startUpdates()
        } else {
            locationHelper.stopUpdates()
        }
        refreshGalleryBadge()
    }

    override fun onPause() {
        super.onPause()
        clockHandler.removeCallbacks(clockTicker)
        clockHandler.removeCallbacks(recTimerRunnable)
        locationHelper.stopUpdates()
        if (isRecordingVideo) {
            activeRecording?.stop()
        }
    }

    private fun setupInteractiveControls() {
        binding.btnFlash.setOnClickListener {
            if (isVideoMode) {
                toggleVideoTorch()
            } else {
                cycleFlashMode()
            }
        }

        binding.tvModePhoto.setOnClickListener {
            if (isVideoMode) switchCaptureMode(video = false)
        }

        binding.tvModeVideo.setOnClickListener {
            if (!isVideoMode) switchCaptureMode(video = true)
        }

        binding.btnGalleryGps.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            val galleryGpsIntent = Intent(this, GalleryGpsEditorActivity::class.java).apply {
                putExtra("gps_has_fix", gpsSnapshot.hasFix)
                putExtra("gps_lat", gpsSnapshot.latitude)
                putExtra("gps_lon", gpsSnapshot.longitude)
                putExtra("gps_city", gpsSnapshot.city)
                putExtra("gps_district", gpsSnapshot.district)
                putExtra("gps_province", gpsSnapshot.province)
                putExtra("gps_country", gpsSnapshot.country)
                putExtra("gps_street", gpsSnapshot.streetAddress)
            }
            postCaptureEditLauncher.launch(galleryGpsIntent)
        }

        binding.btnTimestampMode.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            openDateTimeEditor()
        }

        binding.watermarkPreview.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            openDateTimeEditor()
        }

        binding.btnSettings.setOnClickListener {
            if (!AuthManager.isAdmin(this)) return@setOnClickListener
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnGallery.setOnClickListener {
            if (isRecordingVideo) return@setOnClickListener
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        binding.btnSwitchCamera.setOnClickListener {
            if (isRecordingVideo) return@setOnClickListener
            switchFrontRearCamera()
        }

        binding.btnCapture.setOnClickListener {
            it.playSoundEffect(SoundEffectConstants.CLICK)
            if (isVideoMode) {
                toggleVideoRecording()
            } else {
                captureAndOpenPostCaptureEditor()
            }
        }

        setupZoomControls()

        scaleGestureDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    isPinchingZoom = true
                    pinchOccurredInGesture = true
                    return maxZoomRatio > minZoomRatio + 0.05f
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (maxZoomRatio <= minZoomRatio + 0.05f) return false
                    val factor = detector.scaleFactor
                    if (!factor.isFinite() || factor <= 0f) return false
                    val nextZoom = (currentZoomRatio * factor).coerceIn(minZoomRatio, maxZoomRatio)
                    applyCameraZoomRatio(nextZoom)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    isPinchingZoom = false
                }
            }
        )

        binding.viewFinder.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                pinchOccurredInGesture = false
            }
            if (event.pointerCount > 1 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
                pinchOccurredInGesture = true
            }

            scaleGestureDetector.onTouchEvent(event)

            if (event.actionMasked == MotionEvent.ACTION_UP &&
                !isPinchingZoom &&
                !pinchOccurredInGesture &&
                binding.viewFinder.width > 0 &&
                binding.viewFinder.height > 0
            ) {
                view.performClick()
                val factory = binding.viewFinder.meteringPointFactory
                val point = factory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(
                    point,
                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                )
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
                runCatching {
                    activeCamera?.cameraControl?.startFocusAndMetering(action)
                }
            }
            true
        }

        binding.btnGrantPermissions.setOnClickListener {
            if (!hasCameraPermission() && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            } else {
                requestRequiredPermissions()
            }
        }

        updateModeToggleUi()
    }

    private fun effectiveConfigForRole(rawConfig: WatermarkConfig): WatermarkConfig {
        return if (AuthManager.isAdmin(this)) {
            rawConfig
        } else {
            rawConfig.copy(
                timestampMode = TimestampMode.AUTO_DEVICE,
                openEditorAfterCapture = false,
                autoLocationEnabled = true
            )
        }
    }

    private fun applyRoleRestrictions() {
        val isAdmin = AuthManager.isAdmin(this)
        binding.btnGalleryGps.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnGalleryGps.isEnabled = isAdmin
        binding.btnTimestampMode.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnTimestampMode.isEnabled = isAdmin
        binding.btnSettings.visibility = if (isAdmin) View.VISIBLE else View.GONE
        binding.btnSettings.isEnabled = isAdmin
        binding.watermarkPreview.isClickable = isAdmin
    }

    private fun reloadSettingsAndAssets() {
        watermarkConfig = effectiveConfigForRole(prefs.loadConfig())
        cachedLogoBitmap = WatermarkRenderer.loadLogoBitmap(this, watermarkConfig)
        if (cachedLogoBitmap != null) {
            binding.ivHeaderLogo.setImageBitmap(cachedLogoBitmap)
        }
        applyRoleRestrictions()
        updateTimestampButtonBadge()
        refreshWatermarkAndHeader()
    }

    private fun updateTimestampButtonBadge() {
        binding.btnTimestampMode.text = "Date & Time"
    }

    private fun refreshWatermarkAndHeader() {
        binding.watermarkPreview.updateState(
            newConfig = watermarkConfig,
            newGps = gpsSnapshot,
            newLogo = cachedLogoBitmap,
            currentMillis = System.currentTimeMillis()
        )

        binding.tvGpsStatus.text = when {
            !watermarkConfig.autoLocationEnabled -> "Auto GPS Paused · Custom Location Ready"
            !hasLocationPermission() -> "Location permission off · Offline camera ready"
            gpsSnapshot.hasFix -> {
                val acc = String.format(Locale.US, "±%.1f m", gpsSnapshot.accuracyMeters)
                val modeTag = if (gpsSnapshot.isOfflineAddress) "Offline GPS" else "GPS Locked"
                "$modeTag ($acc) · ${gpsSnapshot.buildLocationHeadline()}"
            }
            else -> "Searching GPS satellites... (Camera ready)"
        }
    }

    private fun openDateTimeEditor() {
        if (!AuthManager.isAdmin(this)) return
        val sheet = DateTimeEditorSheet(gpsSnapshot) { updated ->
            watermarkConfig = effectiveConfigForRole(updated)
            updateTimestampButtonBadge()
            refreshWatermarkAndHeader()
        }
        sheet.show(supportFragmentManager, "DateTimeEditorSheet")
    }

    private fun checkAndRequestRuntimePermissions() {
        if (hasCameraPermission() && hasLocationPermission()) {
            binding.permissionOverlay.visibility = View.GONE
            startCamera()
            locationHelper.startUpdates()
            refreshGalleryBadge()
        } else {
            requestRequiredPermissions()
        }
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            required.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            required.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            required.add(Manifest.permission.READ_MEDIA_IMAGES)
            required.add(Manifest.permission.READ_MEDIA_VIDEO)
        }
        permissionLauncher.launch(required.toTypedArray())
    }

    private fun showPermissionOverlay(permanentlyDenied: Boolean) {
        binding.permissionOverlay.visibility = View.VISIBLE
        binding.btnGrantPermissions.text = if (permanentlyDenied) {
            "Open App Settings"
        } else {
            "Grant Camera & GPS Permissions"
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun switchCaptureMode(video: Boolean) {
        if (isRecordingVideo || isCapturingPhoto || isProcessingVideo) return
        if (isVideoMode == video) return
        isVideoMode = video
        isTorchEnabled = false
        updateModeToggleUi()
        if (video && !hasAudioPermission()) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
        cameraProvider?.let { bindCameraUseCases(it) }
    }

    private fun updateModeToggleUi() {
        if (isVideoMode) {
            binding.tvModePhoto.background = null
            binding.tvModePhoto.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvModeVideo.setBackgroundResource(R.drawable.bg_mode_tab_active)
            binding.tvModeVideo.setTextColor(ContextCompat.getColor(this, R.color.gold_on_primary))
            binding.btnCapture.contentDescription = if (isRecordingVideo) "Stop Video Recording" else "Record GPS Video"
            binding.viewShutterInner.setBackgroundResource(
                if (isRecordingVideo) R.drawable.bg_shutter_inner_video_recording
                else R.drawable.bg_shutter_inner_video_idle
            )
        } else {
            binding.tvModeVideo.background = null
            binding.tvModeVideo.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.tvModePhoto.setBackgroundResource(R.drawable.bg_mode_tab_active)
            binding.tvModePhoto.setTextColor(ContextCompat.getColor(this, R.color.gold_on_primary))
            binding.btnCapture.contentDescription = "Capture GPS Photo"
            binding.viewShutterInner.setBackgroundResource(R.drawable.bg_shutter_inner)
        }
    }

    private fun startCamera() {
        if (isRecordingVideo) return
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                cameraProvider = provider
                bindCameraUseCases(provider)
            } catch (e: Exception) {
                Toast.makeText(
                    this,
                    "Camera initialization error: ${e.localizedMessage ?: "Unavailable"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        if (isRecordingVideo) return
        val preview = Preview.Builder()
            .build()
            .also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

        val requestedSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        val selectorToUse = if (runCatching { provider.hasCamera(requestedSelector) }.getOrDefault(false)) {
            requestedSelector
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            if (isVideoMode) {
                imageCapture = null
                val qualitySelector = QualitySelector.fromOrderedList(
                    listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD)
                )
                val recorder = Recorder.Builder()
                    .setQualitySelector(qualitySelector)
                    .build()

                val videoUseCase = VideoCapture.withOutput(recorder)
                videoCapture = videoUseCase
                activeCamera = provider.bindToLifecycle(
                    this,
                    selectorToUse,
                    preview,
                    videoUseCase
                )
                activeCamera?.cameraControl?.enableTorch(isTorchEnabled)
            } else {
                videoCapture = null
                isTorchEnabled = false
                val captureBuilder = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setFlashMode(flashMode)
                    .setJpegQuality(watermarkConfig.jpegQuality.coerceIn(70, 100))

                imageCapture = captureBuilder.build()
                activeCamera = provider.bindToLifecycle(
                    this,
                    selectorToUse,
                    preview,
                    imageCapture
                )
            }
            syncFlashUiWithHardware()
            observeActiveCameraZoomState()
        } catch (e: Exception) {
            observedZoomLiveData?.removeObserver(zoomStateObserver)
            observedZoomLiveData = null
            binding.llZoomControls.visibility = View.GONE
            Toast.makeText(
                this,
                "Unable to bind camera sensor: ${e.localizedMessage ?: "Error"}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupZoomControls() {
        binding.btnZoomOut.setOnClickListener {
            if (maxZoomRatio <= minZoomRatio + 0.05f) return@setOnClickListener
            val nextZoom = when {
                currentZoomRatio > 3.05f -> currentZoomRatio - 1.0f
                currentZoomRatio > 1.05f -> maxOf(1.0f, currentZoomRatio - 0.5f)
                else -> currentZoomRatio - 0.1f
            }.coerceIn(minZoomRatio, maxZoomRatio)
            applyCameraZoomRatio(nextZoom)
        }

        binding.btnZoomIn.setOnClickListener {
            if (maxZoomRatio <= minZoomRatio + 0.05f) return@setOnClickListener
            val nextZoom = when {
                currentZoomRatio < 0.95f -> minOf(1.0f, currentZoomRatio + 0.1f)
                currentZoomRatio < 2.95f -> currentZoomRatio + 0.5f
                else -> currentZoomRatio + 1.0f
            }.coerceIn(minZoomRatio, maxZoomRatio)
            applyCameraZoomRatio(nextZoom)
        }

        binding.btnZoomReset.setOnClickListener {
            if (maxZoomRatio <= minZoomRatio + 0.05f) return@setOnClickListener
            val defaultZoom = 1.0f.coerceIn(minZoomRatio, maxZoomRatio)
            applyCameraZoomRatio(defaultZoom)
        }

        binding.sliderZoom.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                isDraggingZoomSlider = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                isDraggingZoomSlider = false
            }
        })

        binding.sliderZoom.addOnChangeListener { _, value, fromUser ->
            if (fromUser && !isUpdatingZoomUiFromCamera) {
                applyCameraZoomRatio(value, fromSlider = true)
            }
        }
    }

    private fun observeActiveCameraZoomState() {
        observedZoomLiveData?.removeObserver(zoomStateObserver)
        val zoomLiveData = activeCamera?.cameraInfo?.zoomState
        observedZoomLiveData = zoomLiveData
        if (zoomLiveData == null) {
            binding.llZoomControls.visibility = View.GONE
            return
        }
        zoomLiveData.observe(this, zoomStateObserver)
    }

    private fun onCameraZoomStateChanged(state: ZoomState) {
        val rawMin = if (state.minZoomRatio.isFinite() && state.minZoomRatio > 0f) state.minZoomRatio else 1.0f
        val rawMax = if (state.maxZoomRatio.isFinite() && state.maxZoomRatio >= rawMin) state.maxZoomRatio else rawMin
        minZoomRatio = rawMin
        maxZoomRatio = rawMax

        if (maxZoomRatio <= minZoomRatio + 0.05f) {
            currentZoomRatio = minZoomRatio
            binding.llZoomControls.visibility = View.GONE
            return
        }

        binding.llZoomControls.visibility = View.VISIBLE
        val clampedZoom = (if (state.zoomRatio.isFinite()) state.zoomRatio else 1.0f)
            .coerceIn(minZoomRatio, maxZoomRatio)
        currentZoomRatio = clampedZoom

        val presets = computeSupportedZoomPresets(minZoomRatio, maxZoomRatio)
        if (presets != supportedZoomPresets) {
            rebuildZoomPresetButtons(presets)
        }
        updateZoomUiVisuals(clampedZoom, updateSlider = !isDraggingZoomSlider)
    }

    private fun computeSupportedZoomPresets(minZoom: Float, maxZoom: Float): List<Float> {
        val candidates = mutableListOf<Float>()
        if (minZoom < 0.92f) {
            if (minZoom <= 0.52f) {
                candidates.add(0.5f)
            } else {
                val roundedMin = kotlin.math.round(minZoom * 10f) / 10f
                if (roundedMin < 0.95f) {
                    candidates.add(roundedMin)
                }
            }
        }
        listOf(1.0f, 2.0f, 5.0f).forEach { level ->
            if (level >= minZoom - 0.02f && level <= maxZoom + 0.02f) {
                candidates.add(level)
            }
        }
        if (maxZoom in 2.95f..4.90f && candidates.none { kotlin.math.abs(it - 3.0f) < 0.1f }) {
            candidates.add(3.0f)
        }
        if (candidates.isEmpty()) {
            candidates.add(1.0f.coerceIn(minZoom, maxZoom))
        }
        return candidates
            .distinctBy { kotlin.math.round(it * 10f).toInt() }
            .sorted()
    }

    private fun rebuildZoomPresetButtons(presets: List<Float>) {
        supportedZoomPresets = presets
        zoomPresetViews.clear()
        binding.llZoomPresets.removeAllViews()

        val density = resources.displayMetrics.density
        val marginPx = (3 * density).toInt()
        val padH = (9 * density).toInt()

        presets.forEach { preset ->
            val label = if (kotlin.math.abs(preset - preset.toInt()) < 0.05f) {
                "${preset.toInt()}x"
            } else {
                String.format(Locale.US, "%.1fx", preset)
            }
            val chip = TextView(this).apply {
                text = label
                textSize = 11f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(padH, 0, padH, 0)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    marginStart = marginPx
                    marginEnd = marginPx
                }
                setOnClickListener {
                    val target = preset.coerceIn(minZoomRatio, maxZoomRatio)
                    applyCameraZoomRatio(target)
                }
            }
            binding.llZoomPresets.addView(chip)
            zoomPresetViews.add(preset to chip)
        }
    }

    private fun applyCameraZoomRatio(targetRatio: Float, fromSlider: Boolean = false) {
        val camera = activeCamera ?: return
        if (maxZoomRatio <= minZoomRatio + 0.05f) return
        val clamped = targetRatio.coerceIn(minZoomRatio, maxZoomRatio)
        currentZoomRatio = clamped
        updateZoomUiVisuals(clamped, updateSlider = !fromSlider)
        try {
            camera.cameraControl.setZoomRatio(clamped)
        } catch (_: Exception) {
            // Keep preview/capture/recording resilient if sensor rejects a transient zoom request
        }
    }

    private fun updateZoomUiVisuals(zoomRatio: Float, updateSlider: Boolean) {
        binding.tvZoomLevel.text = String.format(Locale.US, "%.1fx", zoomRatio)

        val defaultZoom = 1.0f.coerceIn(minZoomRatio, maxZoomRatio)
        val isAtDefault = kotlin.math.abs(zoomRatio - defaultZoom) <= 0.03f
        binding.btnZoomReset.alpha = if (isAtDefault) 0.55f else 1.0f
        binding.btnZoomOut.alpha = if (zoomRatio <= minZoomRatio + 0.02f) 0.45f else 1.0f
        binding.btnZoomIn.alpha = if (zoomRatio >= maxZoomRatio - 0.02f) 0.45f else 1.0f

        val closest = zoomPresetViews.minByOrNull { kotlin.math.abs(it.first - zoomRatio) }
        val activeColor = ContextCompat.getColor(this, R.color.gold_on_primary)
        val inactiveColor = ContextCompat.getColor(this, R.color.text_secondary)
        zoomPresetViews.forEach { (preset, view) ->
            val isSelected = closest != null &&
                kotlin.math.abs(closest.first - preset) < 0.01f &&
                kotlin.math.abs(preset - zoomRatio) <= 0.12f
            if (isSelected) {
                view.setBackgroundResource(R.drawable.bg_mode_tab_active)
                view.setTextColor(activeColor)
            } else {
                view.background = null
                view.setTextColor(inactiveColor)
            }
        }

        if (updateSlider && maxZoomRatio > minZoomRatio + 0.05f) {
            isUpdatingZoomUiFromCamera = true
            try {
                val slider = binding.sliderZoom
                val safeFrom = minZoomRatio
                val safeTo = maxZoomRatio
                val safeVal = zoomRatio.coerceIn(safeFrom, safeTo)
                // Keep Material Slider invariants (valueFrom < valueTo, value in range) valid at every step
                slider.value = slider.value.coerceIn(slider.valueFrom, slider.valueTo)
                if (slider.valueFrom > safeFrom) slider.valueFrom = safeFrom
                if (slider.valueTo < safeTo) slider.valueTo = safeTo
                slider.value = safeVal
                slider.valueFrom = safeFrom
                slider.valueTo = safeTo
            } catch (_: Exception) {
                // Guard against transient Material Slider state rounding issues
            } finally {
                isUpdatingZoomUiFromCamera = false
            }
        }
    }

    private fun switchFrontRearCamera() {
        if (isRecordingVideo) return
        val provider = cameraProvider ?: return
        val nextFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val testSelector = CameraSelector.Builder().requireLensFacing(nextFacing).build()
        if (runCatching { provider.hasCamera(testSelector) }.getOrDefault(false)) {
            lensFacing = nextFacing
            isTorchEnabled = false
            bindCameraUseCases(provider)
        } else {
            Toast.makeText(this, "Requested camera lens not available on this device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleVideoTorch() {
        val hasFlash = activeCamera?.cameraInfo?.hasFlashUnit() == true
        if (!hasFlash) {
            isTorchEnabled = false
            syncFlashUiWithHardware()
            Toast.makeText(this, "Torch not supported on active camera lens", Toast.LENGTH_SHORT).show()
            return
        }
        isTorchEnabled = !isTorchEnabled
        activeCamera?.cameraControl?.enableTorch(isTorchEnabled)
        syncFlashUiWithHardware()
    }

    private fun cycleFlashMode() {
        val hasFlash = activeCamera?.cameraInfo?.hasFlashUnit() == true
        if (!hasFlash) {
            flashMode = ImageCapture.FLASH_MODE_OFF
            syncFlashUiWithHardware()
            Toast.makeText(this, "Flash not supported on active camera lens", Toast.LENGTH_SHORT).show()
            return
        }

        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        imageCapture?.flashMode = flashMode
        syncFlashUiWithHardware()
    }

    private fun syncFlashUiWithHardware() {
        val hasFlash = activeCamera?.cameraInfo?.hasFlashUnit() == true
        if (!hasFlash) {
            binding.btnFlash.text = "OFF"
            binding.btnFlash.setIconResource(R.drawable.ic_flash_off)
            binding.btnFlash.alpha = 0.6f
            return
        }
        binding.btnFlash.alpha = 1.0f
        if (isVideoMode) {
            if (isTorchEnabled) {
                binding.btnFlash.text = "ON"
                binding.btnFlash.setIconResource(R.drawable.ic_flash_on)
            } else {
                binding.btnFlash.text = "OFF"
                binding.btnFlash.setIconResource(R.drawable.ic_flash_off)
            }
            return
        }
        when (flashMode) {
            ImageCapture.FLASH_MODE_ON -> {
                binding.btnFlash.text = "ON"
                binding.btnFlash.setIconResource(R.drawable.ic_flash_on)
            }
            ImageCapture.FLASH_MODE_OFF -> {
                binding.btnFlash.text = "OFF"
                binding.btnFlash.setIconResource(R.drawable.ic_flash_off)
            }
            else -> {
                binding.btnFlash.text = "AUTO"
                binding.btnFlash.setIconResource(R.drawable.ic_flash_auto)
            }
        }
    }

    private fun captureAndOpenPostCaptureEditor() {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }
        if (isCapturingPhoto) return
        isCapturingPhoto = true
        binding.btnCapture.isEnabled = false

        binding.shutterFlashOverlay.alpha = 0.85f
        binding.shutterFlashOverlay.animate()
            .alpha(0f)
            .setDuration(180L)
            .start()

        val captureMomentMillis = PhotoMetadataEngine.normalizeToSecondMillis(System.currentTimeMillis())
        val capturedConfig = effectiveConfigForRole(watermarkConfig.copy())
        val capturedGps = gpsSnapshot.copy()
        val isFront = lensFacing == CameraSelector.LENS_FACING_FRONT

        val rawTempFile = File(cacheDir, "raw_capture_$captureMomentMillis.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(rawTempFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        var orientedReadyFile: File? = null
                        var directSavedUri: Uri? = null
                        try {
                            val rawSourceExif = PhotoStorageManager.readSourcePhotoExif(
                                context = this@MainActivity,
                                uri = Uri.fromFile(rawTempFile),
                                filePath = rawTempFile.absolutePath
                            )
                            val oriented = PhotoStorageManager.decodeAndOrientTempCapture(
                                tempFile = rawTempFile,
                                isFrontCamera = isFront,
                                preset = capturedConfig.resolutionPreset
                            )
                            if (oriented != null) {
                                if (capturedConfig.openEditorAfterCapture) {
                                    val postFile = File(cacheDir, "post_edit_$captureMomentMillis.jpg")
                                    FileOutputStream(postFile).use { out ->
                                        oriented.compress(
                                            Bitmap.CompressFormat.JPEG,
                                            capturedConfig.jpegQuality.coerceIn(85, 100),
                                            out
                                        )
                                    }
                                    orientedReadyFile = postFile
                                } else {
                                    directSavedUri = PhotoStorageManager.processAndSaveWatermarkedPhoto(
                                        context = this@MainActivity,
                                        sourceBitmap = oriented,
                                        config = capturedConfig,
                                        gps = capturedGps,
                                        captureDeviceMillis = captureMomentMillis,
                                        originalSourceExif = rawSourceExif
                                    )
                                }
                                oriented.recycle()
                            }
                        } finally {
                            if (rawTempFile.exists()) rawTempFile.delete()
                        }

                        withContext(Dispatchers.Main) {
                            isCapturingPhoto = false
                            binding.btnCapture.isEnabled = true

                            if (capturedConfig.openEditorAfterCapture && orientedReadyFile != null && orientedReadyFile.exists()) {
                                val editIntent = Intent(this@MainActivity, PostCaptureEditActivity::class.java).apply {
                                    putExtra("temp_photo_path", orientedReadyFile.absolutePath)
                                    putExtra("capture_millis", captureMomentMillis)
                                    putExtra("gps_has_fix", capturedGps.hasFix)
                                    putExtra("gps_lat", capturedGps.latitude)
                                    putExtra("gps_lon", capturedGps.longitude)
                                    putExtra("gps_acc", capturedGps.accuracyMeters)
                                    putExtra("gps_time", capturedGps.gpsTimestampMillis)
                                    putExtra("gps_city", capturedGps.city)
                                    putExtra("gps_district", capturedGps.district)
                                    putExtra("gps_province", capturedGps.province)
                                    putExtra("gps_country", capturedGps.country)
                                    putExtra("gps_street", capturedGps.streetAddress)
                                    putExtra("gps_offline", capturedGps.isOfflineAddress)
                                }
                                postCaptureEditLauncher.launch(editIntent)
                            } else if (!capturedConfig.openEditorAfterCapture && directSavedUri != null) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Saved GPS photo to ${PhotoStorageManager.RELATIVE_PICTURES_PATH}",
                                    Toast.LENGTH_SHORT
                                ).show()
                                refreshGalleryBadge()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Failed to process captured image",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    if (rawTempFile.exists()) rawTempFile.delete()
                    isCapturingPhoto = false
                    binding.btnCapture.isEnabled = true
                    Toast.makeText(
                        this@MainActivity,
                        "Capture error: ${exception.localizedMessage ?: "Camera failure"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    @SuppressLint("MissingPermission")
    private fun toggleVideoRecording() {
        if (isProcessingVideo) return
        val currentRecording = activeRecording
        if (isRecordingVideo && currentRecording != null) {
            binding.btnCapture.isEnabled = false
            currentRecording.stop()
            return
        }

        val capture = videoCapture ?: run {
            Toast.makeText(this, "Video camera is not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        val captureMomentMillis = System.currentTimeMillis()
        val capturedConfig = effectiveConfigForRole(watermarkConfig.copy())
        val capturedGps = gpsSnapshot.copy()
        val rawTempVid = File(cacheDir, "raw_video_$captureMomentMillis.mp4")

        val outputOptions = FileOutputOptions.Builder(rawTempVid).build()
        var pending = capture.output.prepareRecording(this, outputOptions)
        if (hasAudioPermission()) {
            pending = pending.withAudioEnabled()
        }

        activeRecording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
            when (event) {
                is VideoRecordEvent.Start -> {
                    isRecordingVideo = true
                    recordingStartElapsedMs = SystemClock.elapsedRealtime()
                    recordedVideoGpsTimeline.clear()
                    val seedGps = gpsSnapshot.copy()
                    activeVideoCaptureGpsSeed = seedGps
                    if (seedGps.hasFix) {
                        recordedVideoGpsTimeline.add(VideoGpsKeyframe(offsetMs = 0L, gps = seedGps))
                    }
                    binding.tvRecTimer.text = "00:00"
                    binding.llRecTimer.visibility = View.VISIBLE
                    binding.tvModePhoto.isEnabled = false
                    binding.tvModeVideo.isEnabled = false
                    binding.btnSwitchCamera.isEnabled = false
                    binding.btnGallery.isEnabled = false
                    binding.btnCapture.isEnabled = true
                    updateModeToggleUi()
                    clockHandler.post(recTimerRunnable)
                }

                is VideoRecordEvent.Finalize -> {
                    isRecordingVideo = false
                    activeRecording = null
                    clockHandler.removeCallbacks(recTimerRunnable)
                    binding.llRecTimer.visibility = View.GONE
                    binding.tvModePhoto.isEnabled = true
                    binding.tvModeVideo.isEnabled = true
                    binding.btnSwitchCamera.isEnabled = true
                    binding.btnGallery.isEnabled = true
                    binding.btnCapture.isEnabled = true
                    updateModeToggleUi()

                    val timelineSnapshot = recordedVideoGpsTimeline.toList()
                    val startGps = timelineSnapshot.firstOrNull()?.gps ?: activeVideoCaptureGpsSeed ?: capturedGps
                    recordedVideoGpsTimeline.clear()
                    activeVideoCaptureGpsSeed = null

                    val usableFile = rawTempVid.exists() && rawTempVid.length() > 1024L
                    if (event.hasError() && !usableFile) {
                        runCatching { if (rawTempVid.exists()) rawTempVid.delete() }
                        Toast.makeText(
                            this,
                            "Video recording error (${event.error})",
                            Toast.LENGTH_LONG
                        ).show()
                        return@start
                    }

                    if (capturedConfig.openEditorAfterCapture && AuthManager.isAdmin(this)) {
                        val editIntent = Intent(this@MainActivity, PostCaptureEditActivity::class.java).apply {
                            putExtra("temp_video_path", rawTempVid.absolutePath)
                            putExtra("capture_millis", captureMomentMillis)
                            putExtra("gps_has_fix", startGps.hasFix)
                            putExtra("gps_lat", startGps.latitude)
                            putExtra("gps_lon", startGps.longitude)
                            putExtra("gps_acc", startGps.accuracyMeters)
                            putExtra("gps_time", startGps.gpsTimestampMillis)
                            putExtra("gps_city", startGps.city)
                            putExtra("gps_district", startGps.district)
                            putExtra("gps_province", startGps.province)
                            putExtra("gps_country", startGps.country)
                            putExtra("gps_street", startGps.streetAddress)
                            putExtra("gps_offline", startGps.isOfflineAddress)
                            putExtra("gps_timeline_json", PhotoStorageManager.gpsTimelineToJsonString(timelineSnapshot))
                        }
                        postCaptureEditLauncher.launch(editIntent)
                    } else {
                        isProcessingVideo = true
                        binding.btnCapture.isEnabled = false
                        binding.tvVideoProcessingStatus.text = "Applying GPS watermark to video..."
                        binding.llVideoProcessing.visibility = View.VISIBLE

                        lifecycleScope.launch(Dispatchers.IO) {
                            val savedPair = try {
                                PhotoStorageManager.processAndSaveWatermarkedVideo(
                                    context = this@MainActivity,
                                    sourceFile = rawTempVid,
                                    config = capturedConfig,
                                    gps = startGps,
                                    gpsTimeline = timelineSnapshot,
                                    captureDeviceMillis = captureMomentMillis,
                                    replaceExistingWatermark = false
                                ) { progress ->
                                    val pct = (progress * 100f).toInt().coerceIn(0, 100)
                                    runOnUiThread {
                                        binding.tvVideoProcessingStatus.text = "Rendering GPS watermark ($pct%)..."
                                    }
                                }
                            } finally {
                                runCatching { if (rawTempVid.exists()) rawTempVid.delete() }
                            }

                            withContext(Dispatchers.Main) {
                                isProcessingVideo = false
                                binding.btnCapture.isEnabled = true
                                binding.llVideoProcessing.visibility = View.GONE
                                if (savedPair != null) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Saved GPS video to ${PhotoStorageManager.RELATIVE_MOVIES_PATH}",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    refreshGalleryBadge()
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Failed to process recorded video",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun appendRecordingGpsKeyframe(offsetMs: Long, snap: GpsSnapshot) {
        val last = recordedVideoGpsTimeline.lastOrNull()
        if (last != null && kotlin.math.abs(offsetMs - last.offsetMs) < 350L) {
            recordedVideoGpsTimeline[recordedVideoGpsTimeline.lastIndex] =
                VideoGpsKeyframe(offsetMs = last.offsetMs, gps = snap)
        } else {
            recordedVideoGpsTimeline.add(VideoGpsKeyframe(offsetMs = offsetMs, gps = snap))
        }
    }

    private fun refreshGalleryBadge() {
        lifecycleScope.launch(Dispatchers.IO) {
            val savedList = PhotoStorageManager.listSavedPhotos(this@MainActivity)
            val latest = savedList.firstOrNull()
            val thumb = latest?.let {
                PhotoStorageManager.loadMediaThumbnail(
                    context = this@MainActivity,
                    uri = it.uri,
                    isVideo = it.isVideo,
                    filePath = it.filePath
                )
            }
            withContext(Dispatchers.Main) {
                binding.tvPhotoCount.text = savedList.size.toString()
                if (thumb != null) {
                    binding.ivGalleryThumb.setImageBitmap(thumb)
                } else if (latest != null && !latest.isVideo) {
                    binding.ivGalleryThumb.setImageURI(latest.uri)
                } else {
                    binding.ivGalleryThumb.setImageDrawable(null)
                }
            }
        }
    }
}
