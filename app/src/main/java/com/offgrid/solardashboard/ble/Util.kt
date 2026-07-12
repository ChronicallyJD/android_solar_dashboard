package com.offgrid.solardashboard.ble

import java.text.SimpleDateFormat
import java.util.Locale

/** ISO-8601 local timestamp to seconds precision, matching the Python format. */
internal fun nowIso(): String =
    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(java.util.Date())
