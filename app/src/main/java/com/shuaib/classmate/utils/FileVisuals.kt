package com.shuaib.classmate.utils

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.shuaib.classmate.R
import com.shuaib.classmate.models.PdfFile

object FileVisuals {
    data class VisualType(
        val label: String,
        val iconRes: Int,
        val backgroundRes: Int,
        val tint: Int = 0 // 0 means do not apply color filter (keeps multi-color icons authentic)
    )

    fun isGoogleDrive(url: String?, provider: String? = null): Boolean {
        val u = (url ?: "").trim().lowercase()
        if (u.isBlank() || u == "null") return false
        return u.contains("drive.google.com") ||
               u.contains("docs.google.com") ||
               u.contains("google.com/drive") ||
               u.contains("drive.link") ||
               (provider ?: "").equals("drive", ignoreCase = true)
    }

    fun getVisuals(file: PdfFile): VisualType {
        val fileName = (file.githubAssetName.takeIf { it.isNotBlank() } ?: file.title).lowercase()
        val extension = fileName.substringAfterLast('.', "")
        val type = file.fileType.lowercase()
        val mime = file.mimeType.lowercase()
        val rawUrl = file.driveUrl.ifBlank { file.downloadUrl }.trim()
        val url = if (rawUrl.equals("null", ignoreCase = true)) "" else rawUrl.lowercase()
        val material = file.materialType.lowercase()

        // 1. Google Drive Links (pasted Google Drive URL)
        if (isGoogleDrive(url, file.provider) || isGoogleDrive(file.driveUrl, file.provider)) {
            return VisualType("DRIVE", R.drawable.ic_google_drive, R.drawable.bg_library_file_icon_drive, 0)
        }

        // 2. Direct PDF files or direct PDF links
        if (extension == "pdf" || type == "pdf" || mime.contains("pdf") || url.endsWith(".pdf") || url.contains(".pdf?")) {
            return VisualType("PDF", R.drawable.ic_file_pdf, R.drawable.bg_library_file_icon_red, Color.parseColor("#FF6B7A"))
        }

        // 3. Slides & Presentations
        if (material == "slides" || extension in listOf("ppt", "pptx", "key") || type.contains("ppt") || type.contains("slide") || mime.contains("presentation") || fileName.contains("slide")) {
            return VisualType("PPT", R.drawable.ic_file_ppt, R.drawable.bg_library_file_icon_yellow, Color.parseColor("#F59E0B"))
        }

        // 4. Questions & Previous Exams
        if (material == "questions" || fileName.contains("question") || fileName.contains("quiz") || fileName.contains("exam") || fileName.contains("mid term") || fileName.contains("final term")) {
            return VisualType("QUES", R.drawable.ic_file_question, R.drawable.bg_library_file_icon_purple, Color.parseColor("#A855F7"))
        }

        // 5. Syllabus & Curriculum
        if (material == "syllabus" || fileName.contains("syllabus") || fileName.contains("curriculum") || fileName.contains("routine")) {
            return VisualType("SYLLABUS", R.drawable.ic_file_syllabus, R.drawable.bg_library_file_icon_teal, Color.parseColor("#0D9488"))
        }

        // 6. Documents & Notes (Word/Docs/Text)
        if (material == "notes" || extension in listOf("doc", "docx", "txt", "rtf") || type.contains("doc") || mime.contains("word") || mime.contains("document") || fileName.contains("note")) {
            return VisualType("DOC", R.drawable.ic_file_doc, R.drawable.bg_library_file_icon_blue, Color.parseColor("#60A5FA"))
        }

        // 7. Sheets & Spreadsheets (Excel)
        if (extension in listOf("xls", "xlsx", "csv") || type.contains("xls") || mime.contains("sheet") || mime.contains("excel") || fileName.contains("sheet") || fileName.contains("excel")) {
            return VisualType("XLS", R.drawable.ic_file_xls, R.drawable.bg_library_file_icon_green, Color.parseColor("#34D399"))
        }

        // 8. Images
        if (extension in listOf("jpg", "jpeg", "png", "webp", "svg") || mime.startsWith("image/") || type == "image") {
            return VisualType("IMG", R.drawable.ic_file_image, R.drawable.bg_library_file_icon_purple, Color.parseColor("#A855F7"))
        }

        // 9. Archives & Compressed folders
        if (extension in listOf("zip", "rar", "7z", "tar", "gz") || mime.contains("zip") || mime.contains("compressed")) {
            return VisualType("ZIP", R.drawable.ic_folder_outline, R.drawable.bg_library_file_icon_yellow, Color.parseColor("#D97706"))
        }

        // 10. General External Web Links (non-Drive links)
        if (type == "link" || extension == "link" || url.startsWith("http://") || url.startsWith("https://") || mime.contains("link") || mime.contains("uri-list")) {
            return VisualType("LINK", R.drawable.ic_open_external, R.drawable.bg_library_file_icon_cyan, Color.parseColor("#06B6D4"))
        }

        // 11. Fallback Generic File
        return VisualType("FILE", R.drawable.ic_file_generic, R.drawable.bg_library_file_icon_blue, Color.parseColor("#94A3B8"))
    }

    fun applyVisuals(imageView: ImageView?, container: View?, visuals: VisualType) {
        container?.background = container?.context?.let { ContextCompat.getDrawable(it, visuals.backgroundRes) }
        imageView?.apply {
            setImageResource(visuals.iconRes)
            if (visuals.tint != 0) {
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(visuals.tint))
                setColorFilter(visuals.tint)
            } else {
                ImageViewCompat.setImageTintList(this, null)
                imageTintList = null
                colorFilter = null
                clearColorFilter()
            }
        }
    }

    fun isLabResource(file: PdfFile): Boolean {
        val title = file.title.lowercase()
        val subject = file.subject.lowercase()
        val type = file.courseType.lowercase()
        return type == "lab" || title.contains("lab") || title.contains("manual") || subject.endsWith("lab")
    }
}
