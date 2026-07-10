package org.arkikeskus.launcher.data.smartspace

/** Pure place-name field selection for reverse geocoding (JVM-testable). */
object PlaceNames {
    /** The first non-blank candidate, trimmed; null when none. Callers pass fields in preference
     *  order: locality first (the municipality is the wanted display name), subLocality as the
     *  better-than-nothing district fallback, then the wider admin areas / featureName. */
    fun preferred(candidates: List<String?>): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}
