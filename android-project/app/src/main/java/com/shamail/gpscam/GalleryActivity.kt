package com.shamail.gpscam

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.shamail.gpscam.data.SavedPhotoItem
import com.shamail.gpscam.databinding.ActivityGalleryBinding
import com.shamail.gpscam.databinding.ItemGalleryPhotoBinding
import com.shamail.gpscam.storage.PhotoStorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class GalleryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGalleryBinding
    private val photos = mutableListOf<SavedPhotoItem>()
    private lateinit var adapter: PhotoGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        adapter = PhotoGridAdapter(photos, lifecycleScope) { clicked ->
            val intent = Intent(this, PhotoDetailActivity::class.java).apply {
                putExtra("photo_uri", clicked.uri.toString())
                putExtra("photo_name", clicked.displayName)
                putExtra("photo_path", clicked.filePath)
                putExtra("photo_provenance", clicked.timestampProvenance)
                putExtra("is_video", clicked.isVideo)
            }
            startActivity(intent)
        }

        binding.rvPhotos.layoutManager = GridLayoutManager(this, 2)
        binding.rvPhotos.adapter = adapter
    }

    override fun onResume() {
        super.onResume()
        loadGalleryItems()
    }

    private fun loadGalleryItems() {
        lifecycleScope.launch(Dispatchers.IO) {
            val loaded = PhotoStorageManager.listSavedPhotos(this@GalleryActivity)
            withContext(Dispatchers.Main) {
                photos.clear()
                photos.addAll(loaded)
                adapter.notifyDataSetChanged()
                val videoCount = loaded.count { it.isVideo }
                val photoCount = loaded.size - videoCount
                binding.tvGallerySubtitle.text = if (videoCount > 0) {
                    "GpsCamByShamail · $photoCount photo(s) · $videoCount video(s)"
                } else {
                    "${PhotoStorageManager.RELATIVE_PICTURES_PATH} · $photoCount photo(s)"
                }
                binding.emptyGalleryView.visibility = if (loaded.isEmpty()) View.VISIBLE else View.GONE
                binding.rvPhotos.visibility = if (loaded.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private class PhotoGridAdapter(
        private val items: List<SavedPhotoItem>,
        private val scope: CoroutineScope,
        private val onClick: (SavedPhotoItem) -> Unit
    ) : RecyclerView.Adapter<PhotoGridAdapter.PhotoVH>() {

        private val thumbCache = LruCache<String, Bitmap>(64)

        class PhotoVH(val binding: ItemGalleryPhotoBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoVH {
            val b = ItemGalleryPhotoBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return PhotoVH(b)
        }

        override fun onBindViewHolder(holder: PhotoVH, position: Int) {
            val item = items[position]
            val key = item.uri.toString()
            holder.binding.root.tag = key
            holder.binding.tvPhotoName.text = item.displayName
            holder.binding.tvTimestampBadge.visibility = View.GONE

            if (item.isVideo) {
                val totalSec = (item.durationMillis / 1000L).coerceAtLeast(0L)
                holder.binding.tvVideoDuration.text = String.format(
                    Locale.US,
                    "%02d:%02d",
                    totalSec / 60L,
                    totalSec % 60L
                )
                holder.binding.llVideoBadge.visibility = View.VISIBLE
            } else {
                holder.binding.llVideoBadge.visibility = View.GONE
            }

            val cached = thumbCache.get(key)
            if (cached != null && !cached.isRecycled) {
                holder.binding.ivPhoto.setImageBitmap(cached)
            } else {
                holder.binding.ivPhoto.setImageDrawable(null)
                val ctx = holder.binding.root.context.applicationContext
                scope.launch(Dispatchers.IO) {
                    val loaded = PhotoStorageManager.loadMediaThumbnail(
                        context = ctx,
                        uri = item.uri,
                        isVideo = item.isVideo,
                        filePath = item.filePath
                    )
                    if (loaded != null) {
                        thumbCache.put(key, loaded)
                    }
                    withContext(Dispatchers.Main) {
                        if (holder.binding.root.tag == key) {
                            if (loaded != null) {
                                holder.binding.ivPhoto.setImageBitmap(loaded)
                            } else if (!item.isVideo) {
                                holder.binding.ivPhoto.setImageURI(item.uri)
                            }
                        }
                    }
                }
            }

            holder.binding.root.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size
    }
}
