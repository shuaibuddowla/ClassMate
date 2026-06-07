package com.shuaib.classmate.utils

import android.graphics.Color
import com.shuaib.classmate.R
import com.shuaib.classmate.models.PdfFile

object FileVisuals {
    data class VisualType(
        val label: String,
        val iconRes: Int,
        val backgroundRes: Int,
        val tint: Int
    )

    fun getVisuals(file: PdfFile): VisualType {
        val fileName = (file.githubAssetName.takeIf { it.isNotBlank() } ?: file.title).lowercase()
        val extension = fileName.substringAfterLast('.', "")
        val type = file.fileType.lowercase()
        val mime = file.mimeType.lowercase()

        return when {
            // PDF
            extension == "pdf" || type == "pdf" || mime.contains("pdf") ->
                VisualType("PDF", R.drawable.ic_file_pdf, R.drawable.bg_library_file_icon_red, Color.parseColor("#FF6B7A"))

            // Documents (DOC/DOCX)
            extension in listOf("doc", "docx") || type.contains("doc") || mime.contains("word") ->
                VisualType("DOC", R.drawable.ic_file_doc, R.drawable.bg_library_file_icon_blue, Color.parseColor("#60A5FA"))

            // Slides (PPT/PPTX)
            extension in listOf("ppt", "pptx") || type.contains("ppt") || type.contains("slide") || mime.contains("presentation") ->
                VisualType("PPT", R.drawable.ic_file_ppt, R.drawable.bg_library_file_icon_yellow, Color.parseColor("#F59E0B"))

            // Sheets (XLS/XLSX)
            extension in listOf("xls", "xlsx") || type.contains("xls") || mime.contains("sheet") || mime.contains("excel") ->
                VisualType("XLS", R.drawable.ic_file_xls, R.drawable.bg_library_file_icon_green, Color.parseColor("#34D399"))

            // Images
            extension in listOf("jpg", "jpeg", "png", "webp") || mime.startsWith("image/") ->
                VisualType("IMG", R.drawable.ic_file_image, R.drawable.bg_library_file_icon_purple, Color.parseColor("#A855F7"))

            // Archives
            extension in listOf("zip", "rar", "7z") || mime.contains("zip") || mime.contains("compressed") ->
                VisualType("ZIP", R.drawable.ic_folder_outline, R.drawable.bg_library_file_icon_yellow, Color.parseColor("#D97706"))

            // Default
            else ->
                VisualType("FILE", R.drawable.ic_file_generic, R.drawable.bg_library_file_icon_blue, Color.parseColor("#94A3B8"))
        }
    }

    fun isLabResource(file: PdfFile): Boolean {
        val title = file.title.lowercase()
        val subject = file.subject.lowercase()
        val type = file.courseType.lowercase()
        return type == "lab" || title.contains("lab") || title.contains("manual") || subject.endsWith("lab")
    }
}
