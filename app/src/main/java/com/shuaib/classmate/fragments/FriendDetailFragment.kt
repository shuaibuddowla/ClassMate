// com/shuaib/classmate/fragments/FriendDetailFragment.kt
package com.shuaib.classmate.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.google.firebase.firestore.FirebaseFirestore
import com.shuaib.classmate.R
import com.shuaib.classmate.databinding.FragmentFriendDetailBinding
import com.shuaib.classmate.models.User

class FriendDetailFragment : Fragment() {

    private var _binding: FragmentFriendDetailBinding? = null
    private val binding get() = _binding!!
    private lateinit var db: FirebaseFirestore

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFriendDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        db = FirebaseFirestore.getInstance()

        val userId = arguments?.getString("userId") ?: return
        fetchFriendDetails(userId)
    }

    private fun fetchFriendDetails(userId: String) {
        db.collection("users").document(userId).get()
            .addOnSuccessListener { document ->
                val user = document.toObject(User::class.java)
                user?.let { updateUI(it) }
            }
    }

    private fun updateUI(user: User) {
        binding.tvFriendDetailName.text = user.name
        binding.tvFriendDetailRole.text = user.role.uppercase()

        Glide.with(this)
            .load(user.photoUrl.ifEmpty { null })
            .placeholder(R.drawable.ic_default_avatar)
            .into(binding.ivFriendDetailProfile)

        binding.detailFriendId.tvLabel.text = "Student ID"
        binding.detailFriendId.tvValue.text = user.studentId

        binding.detailFriendEmail.tvLabel.text = "Email"
        binding.detailFriendEmail.tvValue.text = user.email

        binding.detailFriendPhone.tvLabel.text = "Phone"
        binding.detailFriendPhone.tvValue.text = user.phone

        binding.detailFriendBlood.tvLabel.text = "Blood Group"
        binding.detailFriendBlood.tvValue.text = user.bloodGroup

        binding.detailFriendDistrict.tvLabel.text = "Home District"
        binding.detailFriendDistrict.tvValue.text = user.homeDistrict

        binding.detailFriendAddress.tvLabel.text = "Address"
        binding.detailFriendAddress.tvValue.text = user.address

        binding.btnDetailCall.setOnClickListener {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${user.phone}")
            }
            startActivity(intent)
        }

        binding.btnDetailWhatsapp.setOnClickListener {
            val url = "https://api.whatsapp.com/send?phone=${user.phone}"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "WhatsApp not installed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
