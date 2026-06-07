package com.shuaib.classmate.activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.github.chrisbanes.photoview.PhotoView
import com.shuaib.classmate.R

class ImageViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_viewer)
        val imageUrl = intent.getStringExtra(EXTRA_IMAGE_URL).orEmpty()
        findViewById<android.widget.ImageButton>(R.id.btnClose).setOnClickListener { finish() }
        Glide.with(this)
            .load(imageUrl)
            .into(findViewById<PhotoView>(R.id.photoView))
    }

    companion object {
        const val EXTRA_IMAGE_URL = "imageUrl"
    }
}
