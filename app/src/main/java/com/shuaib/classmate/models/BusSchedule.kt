package com.shuaib.classmate.models

data class BusSchedule(
    val id: String = "",
    val time: String = "",          // e.g., "08:30 AM"
    val departureFrom: String = "",  // "Campus" or "City"
    val busName: String = "",       // e.g., "Double Decker", "Staff Bus"
    val route: String = "",         // e.g., "Via Town Hall, Bypass"
    val scheduleType: String = ""    // "class_day" or "off_day"
)
