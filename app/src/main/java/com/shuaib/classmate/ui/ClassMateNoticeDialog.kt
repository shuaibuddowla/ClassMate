package com.shuaib.classmate.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.activities.ClassMateAcademicCache

internal object ClassMateNoticeDialog {

    fun showUnconfiguredDialog(
        context: Context,
        title: String = "Notice",
        onDismiss: (() -> Unit)? = null
    ) {
        val fullText = "Bus schedule has not been configured or academic calendar has not been updated. Contact developer."
        val targetWord = "developer"
        val startIndex = fullText.indexOf(targetWord)

        val spannable = SpannableString(fullText)
        if (startIndex != -1) {
            val endIndex = startIndex + targetWord.length
            val clickableSpan = object : ClickableSpan() {
                override fun onClick(widget: View) {
                    openDeveloperFacebook(context)
                }

                override fun updateDrawState(ds: TextPaint) {
                    super.updateDrawState(ds)
                    ds.color = Color.parseColor("#2563EB")
                    ds.isUnderlineText = true
                }
            }
            spannable.setSpan(clickableSpan, startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(Color.parseColor("#2563EB")), startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(UnderlineSpan(), startIndex, endIndex, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(spannable)
            .setPositiveButton("OK") { d, _ ->
                d.dismiss()
                onDismiss?.invoke()
            }
            .setOnDismissListener {
                onDismiss?.invoke()
            }
            .show()

        // Enable clickable links in the message body
        dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun openDeveloperFacebook(context: Context) {
        val supportUrl = ClassMateAcademicCache.supportContactUrl(context)
        try {
            val fbIntent = Intent(Intent.ACTION_VIEW, Uri.parse("fb://facewebmodal/f?href=$supportUrl")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(fbIntent)
        } catch (_: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(supportUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(webIntent)
        }
    }
}
