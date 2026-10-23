from pathlib import Path

# 1. Update ClassMateAcademicScreensSupabase.kt
p_screens = Path("app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt")
content_screens = p_screens.read_text(encoding="utf-8")

target_screens = """        // UTILITIES & STORAGE Section
        fun calcStorageBytes(): Long {
            var total = 0L
            activity.cacheDir.listFiles()?.forEach { total += it.length() }
            return total
        }"""

replacement_screens = """        // UTILITIES & STORAGE Section
        val r2StorageRow = root.findViewById<View>(R.id.layoutR2Storage)
        val r2StorageDivider = root.findViewById<View>(R.id.dividerR2Storage)
        if (r2StorageRow != null) {
            val canViewR2 = isAdmin || canManage
            r2StorageRow.visibility = if (canViewR2) View.VISIBLE else View.GONE
            r2StorageDivider?.visibility = if (canViewR2) View.VISIBLE else View.GONE
            if (canViewR2) {
                r2StorageRow.setOnClickListener {
                    com.shuaib.classmate.ui.R2StorageDialog.show(activity)
                }
            }
        }

        fun calcStorageBytes(): Long {
            var total = 0L
            activity.cacheDir.listFiles()?.forEach { total += it.length() }
            return total
        }"""

if target_screens in content_screens:
    content_screens = content_screens.replace(target_screens, replacement_screens)
elif target_screens.replace('\n', '\r\n') in content_screens:
    content_screens = content_screens.replace(target_screens.replace('\n', '\r\n'), replacement_screens.replace('\n', '\r\n'))
else:
    raise RuntimeError("target_screens not found in ClassMateAcademicScreensSupabase.kt")

p_screens.write_text(content_screens, encoding="utf-8")
print("Updated ClassMateAcademicScreensSupabase.kt")

# 2. Update ClassMatePublishActivity.kt
p_pub = Path("app/src/main/java/com/shuaib/classmate/activities/ClassMatePublishActivity.kt")
content_pub = p_pub.read_text(encoding="utf-8")

target_pub = """                    } else if (uri != null) {
                        try {
                            val mime = contentResolver.getType(uri!!) ?: "application/pdf"
                            val taskId = LibraryUploadManager.enqueueUpload(
                                context = this@ClassMatePublishActivity,
                                uri = uri!!,
                                fileName = selectedFileLabel,
                                batchId = batch,
                                courseId = course.offeringId,
                                courseName = course.code,
                                title = title,
                                mimeType = mime,
                                sizeBytes = selectedFileSize,
                                category = kind
                            )
                            currentUploadTaskId = taskId
                            progressContainer.visibility = View.VISIBLE
                            progressText.text = "Starting background upload…"
                            determinateProgress.isIndeterminate = true
                            determinateProgress.progress = 0
                            status.text = "Upload running in background. You may leave this screen."
                            Toast.makeText(this@ClassMatePublishActivity, "Upload started in background", Toast.LENGTH_SHORT).show()
                            submit.isEnabled = false
                        } catch (e: Exception) {
                            busy = false
                            submit.isEnabled = true
                            status.text = "Failed to start upload: ${e.message}"
                            progressContainer.visibility = View.GONE
                        }
                    }"""

replacement_pub = """                    } else if (uri != null) {
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val stats = runCatching { ClassMateAuthApi.rpc("get_r2_storage_stats", JSONObject()) }.getOrNull()
                                val totalBytes = stats?.optLong("total_bytes", 0L) ?: 0L
                                val limitBytes = stats?.optLong("limit_bytes", 10_630_044_057L) ?: 10_630_044_057L
                                if (totalBytes >= limitBytes || totalBytes + selectedFileSize > limitBytes) {
                                    withContext(Dispatchers.Main) {
                                        busy = false
                                        submit.isEnabled = true
                                        status.text = "Storage limit reached (9.9 GB). File uploads are paused. Please share via Drive Link."
                                        com.google.android.material.dialog.MaterialAlertDialogBuilder(this@ClassMatePublishActivity)
                                            .setTitle("Storage Quota Reached")
                                            .setMessage("Cloudflare R2 free tier storage has reached the 9.9 GB limit. Direct file uploads are paused.\\n\\nYou can still share this resource using Drive Link!")
                                            .setPositiveButton("OK", null)
                                            .show()
                                    }
                                    return@launch
                                }
                                withContext(Dispatchers.Main) {
                                    val mime = contentResolver.getType(uri!!) ?: "application/pdf"
                                    val taskId = LibraryUploadManager.enqueueUpload(
                                        context = this@ClassMatePublishActivity,
                                        uri = uri!!,
                                        fileName = selectedFileLabel,
                                        batchId = batch,
                                        courseId = course.offeringId,
                                        courseName = course.code,
                                        title = title,
                                        mimeType = mime,
                                        sizeBytes = selectedFileSize,
                                        category = kind
                                    )
                                    currentUploadTaskId = taskId
                                    progressContainer.visibility = View.VISIBLE
                                    progressText.text = "Starting background upload…"
                                    determinateProgress.isIndeterminate = true
                                    determinateProgress.progress = 0
                                    status.text = "Upload running in background. You may leave this screen."
                                    Toast.makeText(this@ClassMatePublishActivity, "Upload started in background", Toast.LENGTH_SHORT).show()
                                    submit.isEnabled = false
                                }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) {
                                    busy = false
                                    submit.isEnabled = true
                                    status.text = "Failed to start upload: ${e.message}"
                                    progressContainer.visibility = View.GONE
                                }
                            }
                        }
                    }"""

if target_pub in content_pub:
    content_pub = content_pub.replace(target_pub, replacement_pub)
elif target_pub.replace('\n', '\r\n') in content_pub:
    content_pub = content_pub.replace(target_pub.replace('\n', '\r\n'), replacement_pub.replace('\n', '\r\n'))
else:
    raise RuntimeError("target_pub not found in ClassMatePublishActivity.kt")

p_pub.write_text(content_pub, encoding="utf-8")
print("Updated ClassMatePublishActivity.kt")
