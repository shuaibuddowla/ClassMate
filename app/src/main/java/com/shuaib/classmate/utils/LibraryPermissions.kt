package com.shuaib.classmate.utils

import com.shuaib.classmate.models.PdfFile

object LibraryPermissions {
    fun canDelete(pdf: PdfFile): Boolean {
        val context = AppContextManager.appContextFlow.value
        val ownerId = if (pdf.provider == "supabase") context.profileId else context.uid
        val authorizedAdmin = context.v2SessionActive && context.isAdmin() &&
            (pdf.provider == "supabase" || context.v2GlobalAdmin)
        return pdf.canManage || (ownerId.isNotBlank() && pdf.uploaderId == ownerId) || authorizedAdmin
    }
}
