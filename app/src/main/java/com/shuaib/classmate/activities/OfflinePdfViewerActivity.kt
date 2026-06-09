package com.shuaib.classmate.activities

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.shuaib.classmate.databinding.ActivityOfflinePdfViewerBinding
import java.io.File

class OfflinePdfViewerActivity : AppCompatActivity() {
    private lateinit var binding: ActivityOfflinePdfViewerBinding
    private var renderer: PdfRenderer? = null
    private var descriptor: ParcelFileDescriptor? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOfflinePdfViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.tvTitle.text = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Offline PDF" }

        val path = intent.getStringExtra(EXTRA_FILE_PATH).orEmpty()
        if (path.isBlank()) {
            showError("Offline file missing")
            return
        }

        renderPdf(File(path))
    }

    private fun renderPdf(file: File) {
        if (!file.exists() || file.length() == 0L) {
            showError("Offline copy not found")
            return
        }

        Thread {
            try {
                descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val pdfRenderer = PdfRenderer(descriptor!!)
                renderer = pdfRenderer
                val pageCount = pdfRenderer.pageCount
                val pages = mutableListOf<Bitmap>()

                for (index in 0 until pageCount) {
                    pdfRenderer.openPage(index).use { page ->
                        val width = resources.displayMetrics.widthPixels
                        val scale = width.toFloat() / page.width.toFloat()
                        val height = (page.height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, Rect(0, 0, width, height), null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages.add(bitmap)
                    }
                }

                runOnUiThread {
                    binding.progress.isVisible = false
                    binding.tvPageCount.text = "$pageCount ${if (pageCount == 1) "page" else "pages"}"
                    pages.forEach { addPageView(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to render offline PDF", e)
                runOnUiThread { showError("Unable to open offline PDF") }
            }
        }.start()
    }

    private fun addPageView(bitmap: Bitmap) {
        val margin = (12 * resources.displayMetrics.density).toInt()
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = margin
            }
        }
        binding.pageContainer.addView(imageView)
    }

    private fun showError(message: String) {
        binding.progress.isVisible = false
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onDestroy() {
        renderer?.close()
        descriptor?.close()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_FILE_PATH = "extra_file_path"
        const val EXTRA_TITLE = "extra_title"
        private const val TAG = "OfflinePdfViewer"
    }
}
