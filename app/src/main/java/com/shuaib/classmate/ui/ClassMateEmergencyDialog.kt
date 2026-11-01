package com.shuaib.classmate.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.shuaib.classmate.R

internal object ClassMateEmergencyDialog {

    data class EmergencyContact(
        val title: String,
        val subtitle: String,
        val phone: String,
        val rawDial: String
    )

    private val contacts = listOf(
        EmergencyContact(
            "MBSTU Medical Center",
            "Campus clinic & first aid desk",
            "01711-234567",
            "01711234567"
        ),
        EmergencyContact(
            "MBSTU Proctor Office",
            "Campus security & incident response",
            "01712-345678",
            "01712345678"
        ),
        EmergencyContact(
            "Campus Ambulance Transfer",
            "24/7 patient transport to district hospital",
            "01715-998877",
            "01715998877"
        ),
        EmergencyContact(
            "Tangail 250-Bed General Hospital",
            "District emergency casualty department",
            "01712-401414",
            "01712401414"
        ),
        EmergencyContact(
            "Fire Service & Civil Defence",
            "Tangail fire station rescue unit",
            "01730-002288",
            "01730002288"
        ),
        EmergencyContact(
            "Tangail Sadar Police Station",
            "Local police control room",
            "01713-373373",
            "01713373373"
        ),
        EmergencyContact(
            "National Emergency Service (999)",
            "Police, Ambulance & Fire Service",
            "999",
            "999"
        )
    )

    fun show(context: Context) {
        val dialog = BottomSheetDialog(context, R.style.Theme_ClassMate_BottomSheetDialog)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_emergency_contacts, null, false)
        dialog.setContentView(view)

        view.findViewById<ImageView>(R.id.btnCloseEmergency)?.setOnClickListener {
            dialog.dismiss()
        }

        val rv = view.findViewById<RecyclerView>(R.id.rvEmergencyContacts)
        rv.layoutManager = LinearLayoutManager(context)
        rv.adapter = object : RecyclerView.Adapter<EmergencyViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EmergencyViewHolder {
                val item = LayoutInflater.from(parent.context).inflate(R.layout.item_emergency_contact, parent, false)
                return EmergencyViewHolder(item)
            }

            override fun onBindViewHolder(holder: EmergencyViewHolder, position: Int) {
                val c = contacts[position]
                holder.tvTitle.text = c.title
                holder.tvSubtitle.text = c.subtitle
                holder.tvPhone.text = c.phone
                holder.btnCall.setOnClickListener {
                    try {
                        it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${c.rawDial}"))
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        Toast.makeText(context, "Could not open dialer: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            override fun getItemCount(): Int = contacts.size
        }

        dialog.show()
    }

    private class EmergencyViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvEmergencyTitle)
        val tvSubtitle: TextView = v.findViewById(R.id.tvEmergencySubtitle)
        val tvPhone: TextView = v.findViewById(R.id.tvEmergencyPhone)
        val btnCall: MaterialButton = v.findViewById(R.id.btnCallEmergency)
    }
}
