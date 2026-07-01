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
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.Transition
import com.github.chrisbanes.photoview.PhotoView
import com.shuaib.classmate.R
import java.io.File
import java.io.FileOutputStream

class ImageViewerActivity : AppCompatActivity() {

    private lateinit var photoView: PhotoView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSave: LinearLayout
    private lateinit var btnShare: LinearLayout

    private var imageUrl: String = ""
    private var cachedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive full-screen: extend into status/nav bar areas
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        setContentView(R.layout.activity_image_viewer)

        photoView = findViewById(R.id.photoView)
        progressBar = findViewById(R.id.progressBarImage)
        btnSave = findViewById(R.id.btnSave)
        btnShare = findViewById(R.id.btnShare)

        imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL).orEmpty()

        // Close / back
        findViewById<android.widget.ImageButton>(R.id.btnClose).setOnClickListener { finish() }

        // Load image into PhotoView with loading indicator
        loadImage()

        // Save to gallery
        btnSave.setOnClickListener { saveImageToGallery() }

        // Share image
        btnShare.setOnClickListener { shareImage() }
    }

    private fun loadImage() {
        progressBar.visibility = View.VISIBLE

        Glide.with(this)
            .load(imageUrl)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    progressBar.visibility = View.GONE
                    Toast.makeText(
                        this@ImageViewerActivity,
                        "Failed to load image.",
                        Toast.LENGTH_SHORT
                    ).show()
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

        // Also pre-cache the bitmap for save/share operations
        Glide.with(this)
            .asBitmap()
            .load(imageUrl)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    cachedBitmap = resource
                }
                override fun onLoadCleared(placeholder: Drawable?) {
                    cachedBitmap = null
                }
            })
    }

    // ─── Save to Gallery ───────────────────────────────────────────────────────

    private fun saveImageToGallery() {
        val bmp = cachedBitmap
        if (bmp == null) {
            Toast.makeText(this, "Image is still loading, please wait…", Toast.LENGTH_SHORT).show()
            return
        }

        btnSave.isEnabled = false

        try {
            val fileName = "ClassMate_Notice_${System.currentTimeMillis()}.jpg"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ — use MediaStore (no permission needed)
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
                    Toast.makeText(this, "✅ Image saved to Gallery!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to save image.", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Android 9 and below — write to Pictures directory
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + "/ClassMate")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, fileName)
                FileOutputStream(file).use { stream ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                }
                // Notify MediaScanner so the image appears in Gallery immediately
                android.media.MediaScannerConnection.scanFile(
                    this,
                    arrayOf(file.absolutePath),
                    arrayOf("image/jpeg"),
                    null
                )
                Toast.makeText(this, "✅ Image saved to Gallery!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            btnSave.isEnabled = true
        }
    }

    // ─── Share Image ───────────────────────────────────────────────────────────

    private fun shareImage() {
        val bmp = cachedBitmap
        if (bmp == null) {
            Toast.makeText(this, "Image is still loading, please wait…", Toast.LENGTH_SHORT).show()
            return
        }

        btnShare.isEnabled = false

        try {
            // Write bitmap to app cache directory for sharing
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

    companion object {
        const val EXTRA_IMAGE_URL = "imageUrl"
    }
}
