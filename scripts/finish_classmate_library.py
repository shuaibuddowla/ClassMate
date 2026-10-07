from pathlib import Path

path = Path('app/src/main/java/com/shuaib/classmate/activities/ClassMateAcademicScreensSupabase.kt')
source = path.read_text(encoding='utf-8-sig')
start = source.index('    private fun loadLibrary(')
end = source.index('    private fun searchLibrary(', start)
replacement = '''    private fun loadLibrary(root: View): Unit = launch(root) {
        val refresh = root.v<SwipeRefreshLayout>(R.id.swipeRefresh)
        val selectedBatch = batchId()
        val selectedFilter = libraryFilter
        val selectedCategory = libraryCategory
        try {
            val semesters = ClassMateAuthApi.rows("semesters",
                "select=id&batch_id=eq.$selectedBatch&status=eq.active&limit=1")
            val semester = semesters.optJSONObject(0)?.optString("id")
            val offerings = if (semester == null) emptyList() else rows(ClassMateAuthApi.rows(
                "semester_courses", "select=id,course_id&semester_id=eq.$semester"))
            val courses = rows(ClassMateAuthApi.rows("courses", "select=id,course_code,course_title,course_type"))
                .associateBy { it.getString("id") }
            val files = rows(ClassMateAuthApi.rows("file_metadata",
                "select=id,title,file_type,category,size_bytes,created_at,semester_course_id" +
                    "&batch_id=eq.$selectedBatch&status=eq.active&order=created_at.desc&limit=100"))
            val favoriteIds = rows(ClassMateAuthApi.rows("file_favorites",
                "select=file_id&profile_id=eq.${profile().getString("id")}"))
                .map { it.getString("file_id") }.toSet()
            if (!active(root) || selectedBatch != batchId() || selectedFilter != libraryFilter ||
                selectedCategory != libraryCategory) return@launch
            val visibleFiles = files.filter { file -> when (selectedFilter) {
                "All" -> true
                "Starred" -> file.optString("id") in favoriteIds
                else -> file.optString("category") == selectedFilter.lowercase()
            } }
            listOf("All" to R.id.chipAll, "Notes" to R.id.chipNotes,
                "Slides" to R.id.chipSlides, "Questions" to R.id.chipQuestions,
                "Starred" to R.id.chipStarred).forEach { (name, id) ->
                root.v<View>(id).alpha = if (selectedFilter == name) 1f else 0.6f
            }
            listOf("theory" to R.id.btnCategoryRegular, "lab" to R.id.btnCategoryLab,
                "other" to R.id.btnCategorySyllabus).forEach { (name, id) ->
                root.v<View>(id).alpha = if (selectedCategory == name) 1f else 0.6f
            }
            text(root, R.id.tvLibrarySubtitle, "${files.size} files this semester")
            root.v<View>(R.id.tvRecentEmpty).visibility = if (visibleFiles.isEmpty()) View.VISIBLE else View.GONE
            recycler(root, R.id.rvRecent, R.layout.item_recent_pdf, visibleFiles.take(3)) { card, file ->
                bindLibraryFile(root, card, file, favoriteIds)
            }
            val selected = offerings.mapNotNull { offering ->
                courses[offering.optString("course_id")]?.let { course -> offering to course }
            }.filter { it.second.optString("course_type") == selectedCategory }
            val items = selected.map { pair -> JSONObject(pair.second.toString())
                .put("offering_id", pair.first.getString("id")) }
            text(root, R.id.tvLibrarySummary, if (selectedCategory == "other")
                "${visibleFiles.count { it.optString("category") == "syllabus" }} files"
                else "${items.size} courses")
            listOf(R.id.rvRegular, R.id.rvLab, R.id.rvOther).forEach { root.v<View>(it).visibility = View.GONE }
            val listId = when (selectedCategory) {
                "lab" -> R.id.rvLab
                "other" -> R.id.rvOther
                else -> R.id.rvRegular
            }
            root.v<View>(listId).visibility = View.VISIBLE
            if (selectedCategory == "other") {
                recycler(root, listId, R.layout.item_recent_pdf,
                    visibleFiles.filter { it.optString("category") == "syllabus" }) { card, file ->
                    bindLibraryFile(root, card, file, favoriteIds)
                }
                return@launch
            }
            recycler(root, listId, R.layout.item_subject_card, items) { card, course ->
                text(card, R.id.tvSubjectName, course.optString("course_title"))
                text(card, R.id.tvSubjectCode, course.optString("course_code"))
                val count = visibleFiles.count { it.optString("semester_course_id") == course.optString("offering_id") }
                text(card, R.id.tvPdfCount, if (count == 0) "No files" else "$count files")
                card.setOnClickListener {
                    val matching = visibleFiles.filter { it.optString("semester_course_id") == course.optString("offering_id") }
                    if (matching.isEmpty()) Toast.makeText(activity, "No files yet", Toast.LENGTH_SHORT).show()
                    else chooseFile(matching)
                }
            }
        } finally { refresh.isRefreshing = false }
    }

    private fun bindLibraryFile(root: View, card: View, file: JSONObject, favorites: Set<String>) {
        val fileId = file.getString("id")
        text(card, R.id.tvTitle, file.optString("title"))
        text(card, R.id.tvSubject, file.optString("category").replaceFirstChar { it.uppercase() })
        text(card, R.id.tvFileMeta, "${file.optLong("size_bytes") / 1024} KB")
        card.v<android.widget.ImageView>(R.id.btnFavorite).apply {
            setImageResource(if (fileId in favorites) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            setOnClickListener {
                launch(root) {
                    if (fileId in favorites) ClassMateAuthApi.delete("file_favorites",
                        "profile_id=eq.${profile().getString("id")}&file_id=eq.$fileId")
                    else ClassMateAuthApi.insert("file_favorites", JSONObject()
                        .put("profile_id", profile().getString("id")).put("file_id", fileId))
                    if (active(root)) loadLibrary(root)
                }
            }
        }
        card.setOnClickListener { openFile(file) }
    }

'''
source = source[:start] + replacement + source[end:]
path.write_text(source, encoding='utf-8')
