package dev.onlookermonitor.app.protectedapps

/** One launcher app shown in the Protected Apps picker. Icons are loaded lazily by the adapter. */
data class AppRow(
    val pkg: String,
    val label: String,
)
