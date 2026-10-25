package com.fivegmag.a5gmsmediasessionhandler.controller

/**
 * urlFilters of a metrics reporting configuration (TS 26.510 V19.2.0 tables 8.7.3.1-1 and 9.5.3.1-1): a non-empty list of Media Entry
 * Point URL patterns, regular expressions, for which metrics are reported; without the list metrics are reported for all media
 * delivery sessions. A pattern that is not a valid regular expression matches nothing.
 */
fun matchesUrlFilters(urlFilters: List<String>?, mediaEntryPointUrl: String?): Boolean {
    if (urlFilters.isNullOrEmpty()) return true
    if (mediaEntryPointUrl == null) return false
    return urlFilters.any {
        try {
            Regex(it).containsMatchIn(mediaEntryPointUrl)
        } catch (_: Exception) {
            false
        }
    }
}
