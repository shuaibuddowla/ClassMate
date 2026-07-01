package com.shuaib.classmate.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.gms.tasks.Tasks
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.SetOptions
import com.shuaib.classmate.adapters.PollAdapter
import com.shuaib.classmate.databinding.FragmentPollBinding
import com.shuaib.classmate.models.Poll

class PollFragment : Fragment() {

    private var _binding: FragmentPollBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: FirebaseFirestore
    private lateinit var auth: FirebaseAuth
    private var currentUserId = ""
    private var isAdmin = false
    private var pollListener: com.google.firebase.firestore.ListenerRegistration? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPollBinding.inflate(
            inflater, container, false
        )
        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()
        auth = FirebaseAuth.getInstance()
        currentUserId = auth.currentUser?.uid ?: ""

        checkAdminAndLoadPolls()
    }

    private fun checkAdminAndLoadPolls() {
        db.collection("users")
            .document(currentUserId)
            .get()
            .addOnSuccessListener { doc ->
                if (_binding == null) return@addOnSuccessListener
                val role = doc.getString("role") ?: "student"
                isAdmin = role == "superadmin" || role == "admin"
                loadPolls()
            }
    }

    private fun loadPolls() {
        pollListener?.remove()
        pollListener = db.collection("polls")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (_binding == null) return@addSnapshotListener
                if (error != null || snapshot == null) return@addSnapshotListener

                val pollDocs = snapshot.documents
                if (pollDocs.isEmpty()) {
                    binding.emptyState.isVisible = true
                    binding.rvPolls.isVisible = false
                } else {
                    fetchVotesAndRender(pollDocs)
                }
            }
    }

    private fun refreshPollsOnce() {
        db.collection("polls")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { snapshot ->
                if (_binding == null) return@addOnSuccessListener
                if (!isAdded) return@addOnSuccessListener
                val pollDocs = snapshot.documents
                if (pollDocs.isEmpty()) {
                    binding.emptyState.isVisible = true
                    binding.rvPolls.isVisible = false
                } else {
                    fetchVotesAndRender(pollDocs)
                }
            }
    }

    private fun fetchVotesAndRender(pollDocs: List<DocumentSnapshot>) {
        val voteTasks = pollDocs.map { doc ->
            doc.reference.collection("votes").get()
        }
        Tasks.whenAllSuccess<QuerySnapshot>(voteTasks)
            .addOnSuccessListener { voteSnapshots ->
                if (_binding == null) return@addOnSuccessListener
                if (!isAdded) return@addOnSuccessListener
                val polls = pollDocs.mapIndexed { index, doc ->
                    buildPoll(doc, voteSnapshots[index])
                }
                binding.emptyState.isVisible = false
                binding.rvPolls.isVisible = true
                binding.rvPolls.layoutManager = LinearLayoutManager(requireContext())
                binding.rvPolls.adapter = PollAdapter(
                    polls = polls,
                    currentUserId = currentUserId,
                    isAdmin = isAdmin,
                    onVote = { pollId, option ->
                        castVote(pollId, option)
                    },
                    onMultiVote = { poll, option ->
                        toggleMultiVote(poll, option)
                    },
                    onDelete = { pollId ->
                        deletePoll(pollId)
                    }
                )
            }
    }

    private fun castVote(pollId: String, option: String) {
        db.collection("polls")
            .document(pollId)
            .collection("votes")
            .document(currentUserId)
            .set(
                mapOf(
                    "option" to option,
                    "selectedOptions" to listOf(option),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener { refreshPollsOnce() }
            .addOnFailureListener { e ->
                Toast.makeText(context,
                    "Vote failed: ${e.message}",
                    Toast.LENGTH_SHORT).show()
            }
    }

    private fun toggleMultiVote(poll: Poll, option: String) {
        val selectedOptions = poll.selectedOptionsFor(currentUserId).toMutableList()
        if (selectedOptions.contains(option)) {
            selectedOptions.remove(option)
        } else {
            selectedOptions.add(option)
        }

        db.collection("polls")
            .document(poll.id)
            .collection("votes")
            .document(currentUserId)
            .set(
                mapOf(
                    "option" to Poll.encodeMultiVote(selectedOptions),
                    "selectedOptions" to selectedOptions,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            )
            .addOnSuccessListener { refreshPollsOnce() }
            .addOnFailureListener { e ->
                Toast.makeText(
                    context,
                    "Vote failed: ${e.message}",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    private fun buildPoll(doc: DocumentSnapshot, voteSnapshot: QuerySnapshot): Poll {
        return Poll(
            id = doc.id,
            question = doc.getString("question") ?: "",
            options = doc.get("options") as? List<String> ?: emptyList(),
            createdBy = doc.getString("createdBy") ?: "",
            createdAt = doc.getTimestamp("createdAt"),
            expiresAt = doc.getTimestamp("expiresAt"),
            isActive = doc.getBoolean("isActive") ?: true,
            votes = readVoteDocs(voteSnapshot),
            allowMultipleAnswers = doc.getBoolean("allowMultipleAnswers") ?: false,
            multiVotes = readMultiVoteDocs(voteSnapshot)
        )
    }

    private fun readVoteDocs(snapshot: QuerySnapshot): Map<String, String> {
        return snapshot.documents.associate { doc ->
            doc.id to (doc.getString("option") ?: "")
        }.filterValues { it.isNotBlank() }
    }

    private fun readMultiVoteDocs(snapshot: QuerySnapshot): Map<String, List<String>> {
        return snapshot.documents.associate { doc ->
            val selectedOptions = (doc.get("selectedOptions") as? List<*>)
                ?.mapNotNull { it as? String }
                .orEmpty()
            doc.id to selectedOptions
        }.filterValues { it.isNotEmpty() }
    }

    private fun readMultiVotes(value: Any?): Map<String, List<String>> {
        val rawMap = value as? Map<*, *> ?: return emptyMap()
        return rawMap.mapNotNull { (key, selected) ->
            val userId = key as? String ?: return@mapNotNull null
            val options = (selected as? List<*>)?.mapNotNull { it as? String }.orEmpty()
            userId to options
        }.toMap()
    }

    private fun deletePoll(pollId: String) {
        db.collection("polls").document(pollId).delete()
    }

    override fun onDestroyView() {
        pollListener?.remove()
        pollListener = null
        _binding = null
        super.onDestroyView()
    }
}
