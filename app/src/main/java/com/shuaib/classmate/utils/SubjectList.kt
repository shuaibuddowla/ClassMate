package com.shuaib.classmate.utils

object SubjectList {
    val subjects = listOf(
        Subject("Electronic Devices and Circuits", "CSE1201"),
        Subject("Electronic Devices and Circuits Lab", "CSE1202"),
        Subject("Structured Programming", "CSE1203"),
        Subject("Structured Programming Lab", "CSE1204"),
        Subject("Digital Electronics", "CSE1205"),
        Subject("Digital Electronics Lab", "CSE1206"),
        Subject("Physics", "CSE1207"),
        Subject("Physics Lab", "CSE1208"),
        Subject("Statistics", "CSE1209"),
        Subject("Integral Calculus", "CSE1211"),
        Subject("Engineering Drawing", "CSE1214"),
        Subject("Viva-Voce", "CSE1215"),
        Subject("Bhashani Studies", "BHS1201"),
        Subject("Other Document", "LIB0000"),
    ).distinctBy { it.name }

    fun codeFor(subjectName: String): String {
        return subjects.firstOrNull { it.name.equals(subjectName, ignoreCase = true) }?.code.orEmpty()
    }
}

data class Subject(
    val name: String,
    val code: String = ""
)
