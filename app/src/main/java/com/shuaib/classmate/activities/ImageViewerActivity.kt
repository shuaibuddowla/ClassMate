package com.shuaib.classmate.activities

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.github.chrisbanes.photoview.PhotoView
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ItemFullscreenImageBinding
import java.io.File
import java.io.FileOutputStream

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var photoView: PhotoView
    private lateinit var viewPagerImages: ViewPager2
    private lateinit var progressBar: ProgressBar
    private lateinit var tvImageTitle: TextView
    private lateinit var btnSave: LinearLayout
    private lateinit var btnShare: LinearLayout

    private var imageUrls: ArrayList<String> = arrayListOf()
    private var currentPosition: Int = 0
    private val cachedBitmaps = mutableMapOf<Int, Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive full-screen
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_image_viewer)

        photoView = findViewById(R.id.photoView)
        viewPagerImages = findViewById(R.id.viewPagerImages)
        progressBar = findViewById(R.id.progressBarImage)
        tvImageTitle = findViewById(R.id.tvImageTitle)
        btnSave = findViewById(R.id.btnSave)
        btnShare = findViewById(R.id.btnShare)

        val listExtra = intent.getStringArrayListExtra(EXTRA_IMAGE_URLS)
        val singleExtra = intent.getStringExtra(EXTRA_IMAGE_URL).orEmpty()
        currentPosition = intent.getIntExtra(EXTRA_INITIAL_POSITION, 0)

        if (!listExtra.isNullOrEmpty()) {
            imageUrls.addAll(listExtra)
        } else if (singleExtra.isNotBlank()) {
            imageUrls.add(singleExtra)
        }

        // Close / back
        findViewById<android.widget.ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        if (imageUrls.size > 1) {
            setupMultiImageViewer()
        } else {
            setupSingleImageViewer(imageUrls.firstOrNull() ?: "")
        }

        // Save to gallery
        btnSave.setOnClickListener { saveImageToGallery() }

        // Share image
        btnShare.setOnClickListener { shareImage() }
    }

    private fun setupSingleImageViewer(url: String) {
        photoView.visibility = View.VISIBLE
        viewPagerImages.visibility = View.GONE
        tvImageTitle.text = "Notice Image"
        loadSingleImage(url)
    }

    private fun setupMultiImageViewer() {
        photoView.visibility = View.GONE
        viewPagerImages.visibility = View.VISIBLE
        progressBar.visibility = View.GONE

        updateTitle(currentPosition)

        val adapter = FullscreenImagePagerAdapter(imageUrls) { pos, bmp ->
            cachedBitmaps[pos] = bmp
        }
        viewPagerImages.adapter = adapter
        viewPagerImages.setCurrentItem(currentPosition.coerceIn(0, imageUrls.size - 1), false)

        viewPagerImages.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                currentPosition = position
                updateTitle(position)
                preloadBitmapForPosition(position)
            }
        })

        preloadBitmapForPosition(currentPosition)
    }

    private fun updateTitle(position: Int) {
        tvImageTitle.text = "Notice Image (${position + 1}/${imageUrls.size})"
    }

    private fun loadSingleImage(url: String) {
        if (url.isBlank()) return
        progressBar.visibility = View.VISIBLE

        Glide.with(this)
            .load(url)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    progressBar.visibility = View.GONE
                    Toast.makeText(this@ImageViewerActivity, "Failed to load image.", Toast.LENGTH_SHORT).show()
                    return false
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    progressBar.visibility = View.GONE
                    return false
                }
            })
            .into(photoView)

        preloadBitmapForPosition(0)
    }

    private fun preloadBitmapForPosition(position: Int) {
        if (position !in imageUrls.indices) return
        val url = imageUrls[position]
        Glide.with(this)
            .asBitmap()
            .load(url)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    cachedBitmaps[position] = resource
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    cachedBitmaps.remove(position)
                }
            })
    }

    private fun getActiveBitmap(): Bitmap? {
        return cachedBitmaps[currentPosition]
    }

    private fun saveImageToGallery() {
        val bmp = getActiveBitmap()
        if (bmp == null) {
            Toast.makeText(this, "Image is still loading, please wait…", Toast.LENGTH_SHORT).show()
            return
        }

        btnSave.isEnabled = false

        try {
            val fileName = "ClassMate_Notice_${System.currentTimeMillis()}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ClassMate")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri: Uri? = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { stream ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    Toast.makeText(this, "✓ Image saved to Gallery!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to save image.", Toast.LENGTH_SHORT).show()
                }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + "/ClassMate")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { stream ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
                Toast.makeText(this, "✓ Image saved to Gallery!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            btnSave.isEnabled = true
        }
    }

    private fun shareImage() {
        val bmp = getActiveBitmap()
        if (bmp == null) {
            Toast.makeText(this, "Image is still loading, please wait…", Toast.LENGTH_SHORT).show()
            return
        }

        btnShare.isEnabled = false

        try {
            val cacheDir = File(cacheDir, "shared_images").apply { mkdirs() }
            val shareFile = File(cacheDir, "classmate_notice_share.jpg")
            FileOutputStream(shareFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            val shareUri: Uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                shareFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_TEXT, "Shared from ClassMate")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Share notice image via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to share: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            btnShare.isEnabled = true
        }
    }

    private inner class FullscreenImagePagerAdapter(
        private val urls: List<String>,
        private val onBitmapReady: (Int, Bitmap) -> Unit
    ) : RecyclerView.Adapter<FullscreenImagePagerAdapter.ViewHolder>() {

        inner class ViewHolder(val binding: ItemFullscreenImageBinding) :
            RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemFullscreenImageBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val url = urls[position]
            val pv = holder.binding.fullscreenPhotoView
            val pBar = holder.binding.fullscreenProgressBar
            pBar.visibility = View.VISIBLE

            Glide.with(holder.itemView.context)
                .load(url)
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        pBar.visibility = View.GONE
                        return false
                    }

                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>?,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        pBar.visibility = View.GONE
                        return false
                    }
                })
                .into(pv)

            Glide.with(holder.itemView.context)
                .asBitmap()
                .load(url)
                .into(object : CustomTarget<Bitmap>() {
                    override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                        onBitmapReady(position, resource)
                    }
                    override fun onLoadCleared(placeholder: Drawable?) {}
                })

            // Allow zooming inside PhotoView without triggering ViewPager swipe while zoomed
            pv.setOnScaleChangeListener { _, _, _ ->
                viewPagerImages.isUserInputEnabled = (pv.scale <= 1.05f)
            }
        }

        override fun getItemCount(): Int = urls.size
    }

    companion object {
        const val EXTRA_IMAGE_URL = "imageUrl"
        const val EXTRA_IMAGE_URLS = "imageUrls"
        const val EXTRA_INITIAL_POSITION = "initialPosition"
    }
}
