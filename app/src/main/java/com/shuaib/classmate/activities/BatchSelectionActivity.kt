package com.shuaib.classmate.activities

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.ActivityBatchSelectionBinding
import com.shuaib.classmate.databinding.ItemBatchSelectionBinding
import com.shuaib.classmate.models.Batch
import com.shuaib.classmate.utils.AppContextManager
import com.shuaib.classmate.utils.SemesterManager
import com.shuaib.classmate.utils.ThemeColors
import com.shuaib.classmate.utils.applyClickAnimation

class BatchSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatchSelectionBinding
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private var batchList = mutableListOf<Batch>()
    private var selectedBatchId: String? = null
    private lateinit var adapter: BatchSelectionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatchSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        loadBatches()

        binding.btnConfirm.setOnClickListener {
            val batchId = selectedBatchId ?: return@setOnClickListener
            saveBatchAndContinue(batchId)
        }
    }

    private fun setupRecyclerView() {
        adapter = BatchSelectionAdapter(batchList) { selectedBatch ->
            selectedBatchId = selectedBatch.id
            binding.btnConfirm.isEnabled = true
        }
        binding.rvBatches.layoutManager = LinearLayoutManager(this)
        binding.rvBatches.adapter = adapter
    }

    private fun loadBatches() {
        binding.progressBar.isVisible = true
        firestore.collection("batches").get()
            .addOnSuccessListener { snapshot ->
                binding.progressBar.isVisible = false
                val loaded = snapshot.documents.mapNotNull { doc ->
                    val id = doc.id
                    val name = doc.getString("name") ?: Batch.formatName(id)
                    val activeSem = doc.getString("activeSemesterId") ?: doc.getString("activeSemester") ?: "1st"
                    Batch(id = id, name = name, activeSemesterId = activeSem)
                }

                batchList.clear()
                if (loaded.isNotEmpty()) {
                    batchList.addAll(loaded.sortedBy { it.id })
                } else {
                    batchList.addAll(Batch.PREDEFINED_BATCHES)
                }
                adapter.notifyDataSetChanged()
            }
            .addOnFailureListener {
                binding.progressBar.isVisible = false
                batchList.clear()
                batchList.addAll(Batch.PREDEFINED_BATCHES)
                adapter.notifyDataSetChanged()
            }
    }

    private fun saveBatchAndContinue(batchId: String) {
        val uid = auth.currentUser?.uid ?: return
        binding.progressBar.isVisible = true
        binding.btnConfirm.isEnabled = false

        val updates = mapOf(
            "batchId" to batchId,
            "updatedAt" to FieldValue.serverTimestamp()
        )

        firestore.collection("users").document(uid)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                AppContextManager.resolveAndInitialize(uid, batchId) { _, _ ->
                    binding.progressBar.isVisible = false
                    startActivity(Intent(this, MainActivity::class.java))
                    finish()
                }
            }
            .addOnFailureListener { e ->
                binding.progressBar.isVisible = false
                binding.btnConfirm.isEnabled = true
                Toast.makeText(this, "Failed to save batch: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    override fun onBackPressed() {
        // Enforce mandatory selection - back button exits the app rather than bypassing batch selection
        finishAffinity()
    }

    private inner class BatchSelectionAdapter(
        private val items: List<Batch>,
        private val onSelect: (Batch) -> Unit
    ) : RecyclerView.Adapter<BatchSelectionAdapter.ViewHolder>() {

        private var selectedPos = -1

        inner class ViewHolder(val itemBinding: ItemBatchSelectionBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = ItemBatchSelectionBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val batch = items[position]
            val isSelected = position == selectedPos

            holder.itemBinding.tvBatchName.text = batch.name
            holder.itemBinding.tvBatchDetails.text = "CSE Department • ${batch.id.uppercase()}"
            holder.itemBinding.tvActiveSemesterBadge.text = SemesterManager.formatDisplay(batch.activeSemesterId)
            holder.itemBinding.radioBatch.isChecked = isSelected

            val strokeColor = if (isSelected) {
                ContextCompat.getColor(this@BatchSelectionActivity, R.color.cm_brand_primary)
            } else {
                ContextCompat.getColor(this@BatchSelectionActivity, R.color.cm_border_default)
            }
            holder.itemBinding.cardBatch.strokeColor = strokeColor
            holder.itemBinding.cardBatch.strokeWidth = if (isSelected) 4 else 2

            holder.itemView.applyClickAnimation {
                val old = selectedPos
                selectedPos = holder.bindingAdapterPosition
                if (old != -1) notifyItemChanged(old)
                notifyItemChanged(selectedPos)
                onSelect(batch)
            }
        }

        override fun getItemCount(): Int = items.size
    }
}
