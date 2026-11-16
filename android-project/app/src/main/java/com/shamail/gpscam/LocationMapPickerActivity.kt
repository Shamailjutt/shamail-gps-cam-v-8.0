package com.shamail.gpscam

import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsApiSettings
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shamail.gpscam.data.AuthManager
import com.shamail.gpscam.data.SavedLocationDatabase
import com.shamail.gpscam.data.SavedLocationEntity
import com.shamail.gpscam.databinding.ActivityLocationMapPickerBinding
import com.shamail.gpscam.location.LocationInputResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LocationMapPickerActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var binding: ActivityLocationMapPickerBinding
    private var googleMap: GoogleMap? = null
    private var activeMarker: Marker? = null

    private var selectedLat: Double = 31.520370
    private var selectedLon: Double = 74.358749
    private var savedAsBookmark: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AuthManager.isAdmin(this)) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        runCatching {
            MapsApiSettings.addInternalUsageAttributionId(
                applicationContext,
                "gmp_mcp_codeassist_v1_aistudio"
            )
        }

        binding = ActivityLocationMapPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        selectedLat = intent.getDoubleExtra("initial_lat", 31.520370).takeIf { it != 0.0 } ?: 31.520370
        selectedLon = intent.getDoubleExtra("initial_lon", 74.358749).takeIf { it != 0.0 } ?: 74.358749
        val initTitle = intent.getStringExtra("initial_title").orEmpty()
        val initAddress = intent.getStringExtra("initial_address").orEmpty()

        binding.etSelectedLat.setText(String.format(Locale.US, "%.6f", selectedLat))
        binding.etSelectedLon.setText(String.format(Locale.US, "%.6f", selectedLon))
        binding.etSelectedLocationTitle.setText(initTitle)
        binding.etSelectedFullAddress.setText(initAddress)

        val hasMapsApiKey = BuildConfig.MAPS_API_KEY.isNotBlank()
        if (hasMapsApiKey) {
            binding.cardMapWrapper.visibility = View.VISIBLE
            binding.cardMapsKeyNotice.visibility = View.GONE
            val existingFragment = supportFragmentManager.findFragmentById(R.id.mapContainerFrame) as? SupportMapFragment
            val mapFragment = existingFragment ?: SupportMapFragment.newInstance().also { frag ->
                supportFragmentManager.beginTransaction()
                    .replace(R.id.mapContainerFrame, frag)
                    .commitNow()
            }
            mapFragment.getMapAsync(this)
        } else {
            binding.cardMapWrapper.visibility = View.GONE
            binding.cardMapsKeyNotice.visibility = View.VISIBLE
            binding.tvMapTapHint.text = "Search any place above or enter GPS coordinates below:"
        }

        if (initTitle.isBlank() && initAddress.isBlank()) {
            reverseGeocodePoint(selectedLat, selectedLon)
        }

        bindControls()
    }

    override fun onResume() {
        super.onResume()
        if (!AuthManager.isAdmin(this)) {
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        map.uiSettings.apply {
            isZoomControlsEnabled = true
            isCompassEnabled = true
            isMapToolbarEnabled = false
        }

        val startPoint = LatLng(selectedLat, selectedLon)
        placeOrMovePin(startPoint, animateCamera = false, zoom = 15f)

        map.setOnMapClickListener { clickedLatLng ->
            selectedLat = clickedLatLng.latitude
            selectedLon = clickedLatLng.longitude
            binding.etSelectedLat.setText(String.format(Locale.US, "%.6f", selectedLat))
            binding.etSelectedLon.setText(String.format(Locale.US, "%.6f", selectedLon))
            placeOrMovePin(clickedLatLng, animateCamera = true, zoom = map.cameraPosition.zoom.coerceAtLeast(14f))
            reverseGeocodePoint(selectedLat, selectedLon)
        }
    }

    private fun placeOrMovePin(point: LatLng, animateCamera: Boolean, zoom: Float) {
        val map = googleMap ?: return
        if (activeMarker == null) {
            activeMarker = map.addMarker(
                MarkerOptions()
                    .position(point)
                    .title("Selected GPS Pin")
                    .icon(BitmapDescriptorFactory.defaultMarker(42f))
            )
        } else {
            activeMarker?.position = point
        }

        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(point, zoom)
        if (animateCamera) {
            map.animateCamera(cameraUpdate)
        } else {
            map.moveCamera(cameraUpdate)
        }
    }

    private fun bindControls() {
        binding.btnBackMapPicker.setOnClickListener {
            setResult(RESULT_CANCELED)
            finish()
        }

        binding.btnMapSearch.setOnClickListener {
            performPlaceSearch()
        }

        binding.etMapSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performPlaceSearch()
                true
            } else {
                false
            }
        }

        binding.btnLookupTypedCoords.setOnClickListener {
            val latRaw = binding.etSelectedLat.text?.toString()?.trim().orEmpty()
            val lonRaw = binding.etSelectedLon.text?.toString()?.trim().orEmpty()
            val combinedParsed = LocationInputResolver.parseCoordinates("$latRaw $lonRaw")
            val lat = latRaw.toDoubleOrNull() ?: combinedParsed?.first
            val lon = lonRaw.toDoubleOrNull() ?: combinedParsed?.second
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                Toast.makeText(this, "Enter valid Latitude (-90..90) and Longitude (-180..180)", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            selectedLat = lat
            selectedLon = lon
            binding.etSelectedLat.setText(String.format(Locale.US, "%.6f", lat))
            binding.etSelectedLon.setText(String.format(Locale.US, "%.6f", lon))
            placeOrMovePin(LatLng(lat, lon), animateCamera = true, zoom = 15f)
            reverseGeocodePoint(lat, lon)
        }

        binding.btnSaveBookmarkFromMap.setOnClickListener {
            promptSaveLocationToDb()
        }

        binding.btnConfirmMapLocation.setOnClickListener {
            val lat = binding.etSelectedLat.text?.toString()?.trim()?.toDoubleOrNull() ?: selectedLat
            val lon = binding.etSelectedLon.text?.toString()?.trim()?.toDoubleOrNull() ?: selectedLon
            val title = binding.etSelectedLocationTitle.text?.toString()?.trim().orEmpty()
                .ifBlank { String.format(Locale.US, "GPS %.5f°, %.5f°", lat, lon) }
            val address = binding.etSelectedFullAddress.text?.toString()?.trim().orEmpty()
                .ifBlank { String.format(Locale.US, "%.6f°, %.6f°", lat, lon) }

            val result = Intent().apply {
                putExtra("picked_title", title)
                putExtra("picked_address", address)
                putExtra("picked_lat", lat.coerceIn(-90.0, 90.0))
                putExtra("picked_lon", lon.coerceIn(-180.0, 180.0))
                putExtra("picked_was_saved_bookmark", savedAsBookmark)
            }
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun performPlaceSearch() {
        val query = binding.etMapSearch.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            Toast.makeText(this, "Enter a Plus Code, coordinates, or place name", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val resolved = LocationInputResolver.resolveInput(
                context = this@LocationMapPickerActivity,
                rawInput = query,
                referenceLat = selectedLat,
                referenceLon = selectedLon
            )
            if (resolved != null) {
                selectedLat = resolved.latitude
                selectedLon = resolved.longitude
                binding.etSelectedLat.setText(String.format(Locale.US, "%.6f", selectedLat))
                binding.etSelectedLon.setText(String.format(Locale.US, "%.6f", selectedLon))
                binding.etSelectedLocationTitle.setText(resolved.locationTitle)
                binding.etSelectedFullAddress.setText(resolved.fullAddress)
                placeOrMovePin(LatLng(selectedLat, selectedLon), animateCamera = true, zoom = 15.5f)
            } else {
                Toast.makeText(
                    this@LocationMapPickerActivity,
                    "Location not found. Try a full Plus Code, city name, or Lat, Lon.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun reverseGeocodePoint(lat: Double, lon: Double) {
        if (!Geocoder.isPresent()) return
        lifecycleScope.launch {
            val resolved = LocationInputResolver.reverseGeocodeToResult(
                context = this@LocationMapPickerActivity,
                latitude = lat,
                longitude = lon
            )
            binding.etSelectedLocationTitle.setText(resolved.locationTitle)
            binding.etSelectedFullAddress.setText(resolved.fullAddress)
        }
    }

    private fun promptSaveLocationToDb() {
        val lat = binding.etSelectedLat.text?.toString()?.trim()?.toDoubleOrNull() ?: selectedLat
        val lon = binding.etSelectedLon.text?.toString()?.trim()?.toDoubleOrNull() ?: selectedLon
        val title = binding.etSelectedLocationTitle.text?.toString()?.trim().orEmpty()
            .ifBlank { String.format(Locale.US, "GPS %.5f°, %.5f°", lat, lon) }
        val address = binding.etSelectedFullAddress.text?.toString()?.trim().orEmpty()
            .ifBlank { title }

        val input = EditText(this).apply {
            setText(title)
            hint = "Saved Location Name (e.g. Site Office)"
            setPadding(42, 32, 42, 32)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Save GPS Location")
            .setMessage("Enter a bookmark name to save this location for reuse:")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val label = input.text?.toString()?.trim().orEmpty().ifBlank { title }
                lifecycleScope.launch(Dispatchers.IO) {
                    val dao = SavedLocationDatabase.getInstance(this@LocationMapPickerActivity).savedLocationDao()
                    dao.insert(
                        SavedLocationEntity(
                            label = label,
                            locationTitle = title,
                            fullAddress = address,
                            latitude = lat.coerceIn(-90.0, 90.0),
                            longitude = lon.coerceIn(-180.0, 180.0)
                        )
                    )
                    withContext(Dispatchers.Main) {
                        savedAsBookmark = true
                        Toast.makeText(
                            this@LocationMapPickerActivity,
                            "Saved \"$label\" to Saved Locations",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
