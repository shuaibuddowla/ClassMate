// com/shuaib/classmate/activities/ResultViewerActivity.kt
package com.shuaib.classmate.activities

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.shuaib.classmate.adapters.ResultAdapter
import com.shuaib.classmate.databinding.ActivityResultViewerBinding
import com.shuaib.classmate.models.Result

class ResultViewerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultViewerBinding
    private lateinit var firestore: FirebaseFirestore
    private lateinit var resultAdapter: ResultAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultViewerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { 
            finish()
            overridePendingTransition(com.shuaib.classmate.R.anim.slide_in_left, com.shuaib.classmate.R.anim.slide_out_right)
        }

        firestore = FirebaseFirestore.getInstance()

        setupRecyclerView()
        setupSwipeRefresh()
        fetchResults()
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            fetchResults()
        }
    }

    private fun setupRecyclerView() {
        val rootDecorView = window.decorView.findViewById<ViewGroup>(android.R.id.content)
        resultAdapter = ResultAdapter(emptyList(), rootDecorView) { result ->
            openTelegramUrl(result.telegramUrl)
        }
        
        resultAdapter.onItemBound = { holder, position ->
            holder.itemView.translationY = 100f
            holder.itemView.alpha = 0f
            holder.itemView.animate()
                .translationY(0f)
                .alpha(1f)
                .setStartDelay(position * 50L)
                .setDuration(400)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }

        binding.rvResults.apply {
            layoutManager = LinearLayoutManager(this@ResultViewerActivity)
            adapter = resultAdapter
        }
    }

    private fun fetchResults() {
        binding.progressBar.visibility = View.VISIBLE

        firestore.collection("results")
            .orderBy("timestamp", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { querySnapshot ->
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                val results = querySnapshot.toObjects(Result::class.java)
                resultAdapter.updateList(results)
            }
            .addOnFailureListener { e ->
                binding.progressBar.visibility = View.GONE
                binding.swipeRefresh.isRefreshing = false
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun openTelegramUrl(url: String) {
        if (url.isEmpty()) {
            Toast.makeText(this, "Link not available", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            // Try Telegram app first
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage("org.telegram.messenger")
            }
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                // Fallback to browser
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        overridePendingTransition(com.shuaib.classmate.R.anim.slide_in_left, com.shuaib.classmate.R.anim.slide_out_right)
    }
}
