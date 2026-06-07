package com.shuaib.classmate.utils

import android.content.Context
import android.os.Environment
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.itextpdf.text.BaseColor
import com.itextpdf.text.Document
import com.itextpdf.text.Element
import com.itextpdf.text.FontFactory
import com.itextpdf.text.Paragraph
import com.itextpdf.text.Phrase
import com.itextpdf.text.pdf.PdfPCell
import com.itextpdf.text.pdf.PdfPTable
import com.itextpdf.text.pdf.PdfWriter
import com.shuaib.classmate.models.AttendanceRecord
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object AttendancePdfGenerator {

    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val dateFormat = SimpleDateFormat("EEE, dd MMM yyyy", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault())

    fun generateSessionReport(context: Context, sessionId: String): Task<File> {
        val sessionTask = db.collection("attendance_sessions").document(sessionId).get()
        val recordsTask = db.collection("attendance_records").whereEqualTo("sessionId", sessionId).get()

        return Tasks.whenAllSuccess<Any>(sessionTask, recordsTask).continueWith { task ->
            val session = task.result[0] as DocumentSnapshot
            val records = (task.result[1] as com.google.firebase.firestore.QuerySnapshot).documents
            val subject = session.getString("subject") ?: "Attendance"
            val file = outputFile(context, "Attendance_${subject.safeName()}_${fileDateFormat.format(Date())}.pdf")

            Document().usePdf(file) { document ->
                title(document, "ClassMate - Attendance Report")
                subtitle(document, "$subject | ${formatTimestamp(session.getTimestamp("startTime"))}")

                val table = PdfPTable(floatArrayOf(0.6f, 3f, 1.4f, 1.8f, 1.3f)).apply {
                    widthPercentage = 100f
                }
                listOf("No.", "Name", "Status", "Time", "Source").forEach { table.header(it) }

                records.sortedBy { it.getString("studentName") ?: "" }.forEachIndexed { index, record ->
                    table.cell("${index + 1}")
                    table.cell(record.getString("studentName") ?: "Student")
                    table.cell(record.getString("status") ?: "-")
                    table.cell(formatTimestamp(record.getTimestamp("detectedAt")))
                    table.cell(record.getString("source") ?: "-")
                }
                document.add(table)

                val present = records.count { it.getString("status") == "present" }
                document.add(Paragraph("\nPresent: $present / ${records.size} | Generated: ${dateTimeFormat.format(Date())}", smallFont()))
            }

            file
        }
    }

    fun generateMonthlyReport(context: Context): Task<File> {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_MONTH, 1)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val start = Timestamp(calendar.time)
        calendar.add(Calendar.MONTH, 1)
        val end = Timestamp(calendar.time)

        val sessionsTask = db.collection("attendance_sessions")
            .whereEqualTo("status", "closed")
            .whereGreaterThanOrEqualTo("startTime", start)
            .whereLessThan("startTime", end)
            .get()
        val studentsTask = db.collection("users").whereEqualTo("role", "student").get()

        return Tasks.whenAllSuccess<Any>(sessionsTask, studentsTask).continueWithTask { first ->
            val sessions = (first.result[0] as com.google.firebase.firestore.QuerySnapshot).documents
            val students = (first.result[1] as com.google.firebase.firestore.QuerySnapshot).documents
            val recordTasks = sessions.map { session ->
                db.collection("attendance_records").whereEqualTo("sessionId", session.id).get()
            }

            Tasks.whenAllSuccess<Any>(recordTasks).continueWith { recordsResult ->
                val monthName = SimpleDateFormat("MMMM_yyyy", Locale.getDefault()).format(Date())
                val file = outputFile(context, "Attendance_Report_$monthName.pdf")
                val recordsBySession = sessions.mapIndexed { index, session ->
                    session to (recordsResult.result[index] as com.google.firebase.firestore.QuerySnapshot).documents
                }

                Document().usePdf(file) { document ->
                    title(document, "ClassMate - Monthly Attendance Report")
                    subtitle(document, SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date()))

                    recordsBySession.sortedBy { it.first.getTimestamp("startTime")?.toDate() }.forEach { (session, records) ->
                        document.add(Paragraph(session.getString("subject") ?: "Attendance", headingFont()))
                        document.add(Paragraph(formatTimestamp(session.getTimestamp("startTime")), smallFont()))
                        val table = PdfPTable(floatArrayOf(0.6f, 3f, 1.4f, 1.8f)).apply { widthPercentage = 100f }
                        listOf("No.", "Name", "Status", "Time").forEach { table.header(it) }
                        records.sortedBy { it.getString("studentName") ?: "" }.forEachIndexed { i, record ->
                            table.cell("${i + 1}")
                            table.cell(record.getString("studentName") ?: "Student")
                            table.cell(record.getString("status") ?: "-")
                            table.cell(formatTimestamp(record.getTimestamp("detectedAt")))
                        }
                        document.add(table)
                        document.add(Paragraph("\n"))
                    }

                    document.add(Paragraph("Summary", headingFont()))
                    val summary = PdfPTable(floatArrayOf(3f, 1.2f, 1.2f, 1.2f, 1.2f)).apply { widthPercentage = 100f }
                    listOf("Student Name", "Sessions", "Present", "Absent", "%").forEach { summary.header(it) }
                    students.sortedBy { it.getString("name") ?: "" }.forEach { student ->
                        val uid = student.id
                        val allRecords = recordsBySession.mapNotNull { (_, records) -> records.firstOrNull { it.getString("studentUid") == uid } }
                        val present = allRecords.count { it.getString("status") == "present" }
                        val total = sessions.size
                        val absent = total - present
                        val percent = if (total == 0) 0 else (present * 100 / total)
                        summary.cell(student.getString("name") ?: "Student")
                        summary.cell("$total")
                        summary.cell("$present")
                        summary.cell("$absent")
                        summary.cell("$percent%", if (percent < 75) BaseColor.RED else BaseColor(52, 211, 153))
                    }
                    document.add(summary)
                }
                file
            }
        }
    }

    fun generateFormalSheet(
        sessionId: String,
        sessionDate: String,
        records: List<AttendanceRecord>,
        subject: String = "Attendance"
    ): File {
        val context = FirebaseFirestore.getInstance().app.applicationContext
        val file = outputFile(context, "Attendance_Sheet_$sessionId.pdf")

        Document().usePdf(file) { document ->
            // University Header
            val universityFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16f)
            val headerParagraph = Paragraph("Mawlana Bhashani Science and Technology University", universityFont).apply {
                alignment = Element.ALIGN_CENTER
                spacingAfter = 4f
            }
            document.add(headerParagraph)

            val deptFont = FontFactory.getFont(FontFactory.HELVETICA, 12f)
            val deptParagraph = Paragraph("Department of Computer Science & Engineering", deptFont).apply {
                alignment = Element.ALIGN_CENTER
                spacingAfter = 20f
            }
            document.add(deptParagraph)

            // Title
            val titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18f)
            val titleParagraph = Paragraph("ATTENDANCE SHEET", titleFont).apply {
                alignment = Element.ALIGN_CENTER
                spacingAfter = 20f
            }
            document.add(titleParagraph)

            // Course Details
            val detailFont = FontFactory.getFont(FontFactory.HELVETICA, 11f)
            document.add(Paragraph("Course  : $subject", detailFont))
            document.add(Paragraph("Semester: 1st Year, 2nd Semester", detailFont))
            document.add(Paragraph("Date    : $sessionDate", detailFont))
            document.add(Paragraph("Session : ${sessionId.take(8)}", detailFont))
            document.add(Paragraph("\n", detailFont))

            // Table
            val table = PdfPTable(floatArrayOf(0.8f, 3f, 1.5f, 1.2f)).apply {
                widthPercentage = 100f
                setWidths(floatArrayOf(0.8f, 3f, 1.5f, 1.2f))
            }

            // Table Headers
            val headerFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10f)
            val headerBg = BaseColor(240, 240, 240)
            listOf("No.", "Student Name", "Status", "Time").forEach { header ->
                val cell = PdfPCell(Phrase(header, headerFont)).apply {
                    backgroundColor = headerBg
                    horizontalAlignment = Element.ALIGN_CENTER
                    verticalAlignment = Element.ALIGN_MIDDLE
                    setPadding(8f)
                }
                table.addCell(cell)
            }

            // Table Rows
            val rowFont = FontFactory.getFont(FontFactory.HELVETICA, 9f)
            val absentBg = BaseColor(255, 240, 240) // Light red
            records.sortedBy { it.studentName }.forEachIndexed { index, record ->
                // No.
                val noCell = PdfPCell(Phrase("${index + 1}", rowFont)).apply {
                    horizontalAlignment = Element.ALIGN_CENTER
                    setPadding(6f)
                    if (record.status == "absent") backgroundColor = absentBg
                }
                table.addCell(noCell)

                // Name
                val nameCell = PdfPCell(Phrase(record.studentName, rowFont)).apply {
                    setPadding(6f)
                    if (record.status == "absent") backgroundColor = absentBg
                }
                table.addCell(nameCell)

                // Status
                val statusCell = PdfPCell(Phrase(record.status.capitalize(), rowFont)).apply {
                    horizontalAlignment = Element.ALIGN_CENTER
                    setPadding(6f)
                    if (record.status == "absent") backgroundColor = absentBg
                }
                table.addCell(statusCell)

                // Time
                val timeStr = record.detectedAt?.toDate()?.let { SimpleDateFormat("HH:mm", Locale.getDefault()).format(it) } ?: "—"
                val timeCell = PdfPCell(Phrase(timeStr, rowFont)).apply {
                    horizontalAlignment = Element.ALIGN_CENTER
                    setPadding(6f)
                    if (record.status == "absent") backgroundColor = absentBg
                }
                table.addCell(timeCell)
            }

            document.add(table)

            // Summary
            val presentCount = records.count { it.status == "present" }
            val totalCount = records.size
            val percentage = if (totalCount > 0) (presentCount * 100 / totalCount) else 0
            document.add(Paragraph("\nTotal Present: $presentCount / $totalCount", detailFont))
            document.add(Paragraph("Attendance %: $percentage%", detailFont))

            // Signatures
            document.add(Paragraph("\n\n", detailFont))
            document.add(Paragraph("Teacher Signature: ____________________", detailFont))
            document.add(Paragraph("\n", detailFont))
            document.add(Paragraph("CR Signature: _________________________", detailFont))
            document.add(Paragraph("Class Representative", detailFont))

            // Footer
            val footerFont = FontFactory.getFont(FontFactory.HELVETICA, 8f)
            val footerParagraph = Paragraph("\n\nGenerated by ClassMate App", footerFont).apply {
                alignment = Element.ALIGN_CENTER
            }
            document.add(footerParagraph)
        }

        return file
    }

    private fun outputFile(context: Context, name: String): File {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloads.exists()) downloads.mkdirs()
        return File(downloads, name)
    }

    private fun title(document: Document, text: String) {
        document.add(Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18f)).apply {
            alignment = Element.ALIGN_CENTER
            spacingAfter = 8f
        })
    }

    private fun subtitle(document: Document, text: String) {
        document.add(Paragraph(text, FontFactory.getFont(FontFactory.HELVETICA, 12f)).apply {
            alignment = Element.ALIGN_CENTER
            spacingAfter = 16f
        })
    }

    private fun headingFont() = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14f)
    private fun smallFont() = FontFactory.getFont(FontFactory.HELVETICA, 10f)
    private fun formatTimestamp(timestamp: Timestamp?): String = timestamp?.toDate()?.let { dateTimeFormat.format(it) } ?: "-"
    private fun String.safeName(): String = replace(Regex("[^A-Za-z0-9_]+"), "_").trim('_')

    private fun Document.usePdf(file: File, block: (Document) -> Unit) {
        PdfWriter.getInstance(this, FileOutputStream(file))
        open()
        try {
            block(this)
        } finally {
            close()
        }
    }

    private fun PdfPTable.header(text: String) {
        addCell(PdfPCell(Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10f))).apply {
            backgroundColor = BaseColor(77, 159, 255)
            horizontalAlignment = Element.ALIGN_CENTER
            setPadding(6f)
        })
    }

    private fun PdfPTable.cell(text: String, color: BaseColor? = null) {
        addCell(PdfPCell(Phrase(text, FontFactory.getFont(FontFactory.HELVETICA, 9f, color ?: BaseColor.BLACK))).apply {
            setPadding(5f)
        })
    }
}
