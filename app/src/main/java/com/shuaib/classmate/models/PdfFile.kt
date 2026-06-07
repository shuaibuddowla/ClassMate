// com/shuaib/classmate/models/PdfFile.kt
package com.shuaib.classmate.models

data class PdfFile(
    val id: String = "",
    val title: String = "",
    val subject: String = "",
    val description: String = "",
    val uploadedBy: String = "",
    val telegramUrl: String = "",
    val driveUrl: String = "",
    val fileId: String = "",
    val timestamp: com.google.firebase.Timestamp? = null,
    val courseCode: String = "",
    val courseType: String = "",
    val fileType: String = "pdf",
    val mimeType: String = "application/pdf",
    val sizeBytes: Long = 0L,
    val provider: String = "",
    val downloadUrl: String = "",
    val githubAssetId: Long = 0L,
    val githubAssetName: String = "",
    val createdAt: com.google.firebase.Timestamp? = null,
    val updatedAt: com.google.firebase.Timestamp? = null,
    val downloadCount: Long = 0L,
    val isDeleted: Boolean = false
)
