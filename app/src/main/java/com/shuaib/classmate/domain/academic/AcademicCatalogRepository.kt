package com.shuaib.classmate.domain.academic

interface AcademicCatalogRepository {
    suspend fun loadAccessibleCatalog(): Result<AcademicCatalog>
}
