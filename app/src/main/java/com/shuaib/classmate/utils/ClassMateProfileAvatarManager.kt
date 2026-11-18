package com.shuaib.classmate.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shuaib.classmate.BuildConfig
import com.shuaib.classmate.R
import com.shuaib.classmate.data.remote.supabase.ClassMateAuthApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

object ClassMateProfileAvatarManager {

    private const val PREFS_NAME = "classmate_avatar_prompt_prefs"
    private const val KEY_LAST_PROMPT = "last_prompt_timestamp"
    private const val KEY_NEVER_PROMPT = "never_prompt_again"
    private const val PROMPT_INTERVAL_MS = 5 * 24 * 60 * 60 * 1000L // 5 days

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    fun shouldPrompt(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NEVER_PROMPT, false)) return false
        val last = prefs.getLong(KEY_LAST_PROMPT, 0L)
        return (System.currentTimeMillis() - last) > PROMPT_INTERVAL_MS
    }

    fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_PROMPT, System.currentTimeMillis())
            .apply()
    }

    /**
     * Shows Material 3 dialog prompting user to add profile picture.
     */
    fun promptAddProfilePhoto(activity: AppCompatActivity, onChoosePhoto: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        markPrompted(activity)

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_prompt_profile_photo, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setCancelable(true)
            .create()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        view.findViewById<View>(R.id.btnPromptUpload)?.setOnClickListener {
            dialog.dismiss()
            onChoosePhoto()
        }

        view.findViewById<View>(R.id.btnPromptLater)?.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    /**
     * Shows modal bottom sheet for changing or removing avatar.
     */
    fun showAvatarOptionsSheet(
        activity: AppCompatActivity,
        hasExistingAvatar: Boolean,
        onChooseGallery: () -> Unit,
        onRemoveAvatar: (() -> Unit)? = null
    ) {
        if (activity.isFinishing || activity.isDestroyed) return

        val dialog = BottomSheetDialog(activity)
        val view = LayoutInflater.from(activity).inflate(R.layout.bottom_sheet_avatar_options, null)

        view.findViewById<View>(R.id.btnOptionGallery)?.setOnClickListener {
            dialog.dismiss()
            onChooseGallery()
        }

        val btnRemove = view.findViewById<View>(R.id.btnOptionRemove)
        if (hasExistingAvatar && onRemoveAvatar != null) {
            btnRemove?.visibility = View.VISIBLE
            btnRemove?.setOnClickListener {
                dialog.dismiss()
                onRemoveAvatar()
            }
        } else {
            btnRemove?.visibility = View.GONE
        }

        dialog.setContentView(view)
        dialog.show()
    }

    /**
     * Decodes, corrects EXIF orientation, center-crops to square, and scales to max 512x512 JPEG.
     */
    fun processAvatarBitmap(context: Context, uri: Uri): ByteArray {
        val resolver = context.contentResolver

        var rotation = 0
        try {
            resolver.openInputStream(uri)?.use { stream ->
                val exif = android.media.ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
                rotation = when (orientation) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        } catch (_: Exception) {}

        val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOptions)
        }

        var sampleSize = 1
        val maxTargetDim = 720
        while (boundsOptions.outWidth / sampleSize > maxTargetDim || boundsOptions.outHeight / sampleSize > maxTargetDim) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val original = resolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        } ?: throw IOException("Could not decode selected image file.")

        val rotated = if (rotation != 0) {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rot = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (rot != original) original.recycle()
            rot
        } else {
            original
        }

        val width = rotated.width
        val height = rotated.height
        val squareDim = minOf(width, height)
        val xOffset = (width - squareDim) / 2
        val yOffset = (height - squareDim) / 2
        val squareBitmap = Bitmap.createBitmap(rotated, xOffset, yOffset, squareDim, squareDim)
        if (squareBitmap != rotated) rotated.recycle()

        val finalBitmap = if (squareDim > 512) {
            val scaled = Bitmap.createScaledBitmap(squareBitmap, 512, 512, true)
            if (scaled != squareBitmap) squareBitmap.recycle()
            scaled
        } else {
            squareBitmap
        }

        val output = ByteArrayOutputStream()
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
        finalBitmap.recycle()

        return output.toByteArray()
    }

    /**
     * Uploads avatar directly to Supabase storage 'avatars' bucket,
     * then calls update_profile_avatar RPC to update user metadata and profile.
     */
    suspend fun uploadAvatar(context: Context, uri: Uri, userId: String): Result<Pair<String, JSONObject>> = withContext(Dispatchers.IO) {
        try {
            val bytes = processAvatarBitmap(context, uri)
            val fileName = "${userId}/avatar_${System.currentTimeMillis()}.jpg"
            val token = ClassMateAuthApi.accessToken?.takeIf { it.isNotBlank() }
                ?: BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim()
            val uploadUrl = "${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/storage/v1/object/avatars/$fileName"

            val body = bytes.toRequestBody("image/jpeg".toMediaType())
            val request = Request.Builder()
                .url(uploadUrl)
                .header("Authorization", "Bearer $token")
                .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY.trim())
                .header("Content-Type", "image/jpeg")
                .header("x-upsert", "true")
                .post(body)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val err = response.body?.string().orEmpty()
                    throw IOException("Upload failed (HTTP ${response.code}): $err")
                }
            }

            val publicUrl = "${BuildConfig.SUPABASE_URL.trim().trimEnd('/')}/storage/v1/object/public/avatars/$fileName"
            val updated = ClassMateAuthApi.rpc("update_profile_avatar", JSONObject().put("target_avatar_url", publicUrl))
            Result.success(Pair(publicUrl, updated))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Clears profile avatar in database.
     */
    suspend fun removeAvatar(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val updated = ClassMateAuthApi.rpc("update_profile_avatar", JSONObject().put("target_avatar_url", ""))
            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
