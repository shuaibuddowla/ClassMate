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

                runOnUiThread {
                    binding.progress.isVisible = false
                    binding.tvPageCount.text = "$pageCount ${if (pageCount == 1) "page" else "pages"}"
                    
                    val displayWidth = resources.displayMetrics.widthPixels
                    val density = resources.displayMetrics.density
                    
                    val adapter = PdfPagesAdapter(pdfRenderer, displayWidth, density)
                    binding.rvPages.adapter = adapter
                }
            } catch (e: Exception) {
                Log.e(TAG, "Unable to render offline PDF", e)
                runOnUiThread { showError("Unable to open offline PDF") }
            }
        }.start()
    }

    private class PdfPagesAdapter(
        private val renderer: PdfRenderer,
        private val displayWidth: Int,
        private val density: Float
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<PdfPagesAdapter.PageViewHolder>() {

        private val margin = (12 * density).toInt()

        class PageViewHolder(val imageView: ImageView) : androidx.recyclerview.widget.RecyclerView.ViewHolder(imageView)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
            val imageView = ImageView(parent.context).apply {
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
            return PageViewHolder(imageView)
        }

        override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
            val imageView = holder.imageView
            imageView.setImageBitmap(null)
            imageView.tag = position

            val pageIndex = position
            Thread {
                try {
                    val bitmap = renderPage(pageIndex)
                    imageView.post {
                        if (imageView.tag == pageIndex) {
                            imageView.setImageBitmap(bitmap)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PdfPagesAdapter", "Error rendering page $pageIndex", e)
                }
            }.start()
        }

        override fun getItemCount(): Int = renderer.pageCount

        private fun renderPage(index: Int): Bitmap {
            synchronized(renderer) {
                renderer.openPage(index).use { page ->
                    val scale = displayWidth.toFloat() / page.width.toFloat()
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(displayWidth, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
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
