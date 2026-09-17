package dev.phonerobot.vision

import kotlin.math.sqrt

/** Cosine matching is a convenience feature, never an authentication decision. */
internal class FaceMatcher(
    private val dimensions: Int = 192,
    private val minimumSimilarity: Float = 0.75f,
    private val ambiguityMargin: Float = 0.10f,
) {
    fun normalized(vector: FloatArray): FloatArray? {
        if (vector.size != dimensions || vector.any { !it.isFinite() }) return null
        val norm = sqrt(vector.fold(0.0) { sum, value -> sum + value.toDouble() * value })
        if (!norm.isFinite() || norm < 1e-12) return null
        return FloatArray(dimensions) { (vector[it] / norm).toFloat() }
    }

    fun match(query: FloatArray, enrolled: Map<String, FloatArray>): String? {
        val normalizedQuery = normalized(query) ?: return null
        var bestName: String? = null
        var best = -1f
        var second = -1f
        for ((name, embedding) in enrolled) {
            val candidate = normalized(embedding) ?: continue
            var similarity = 0f
            for (index in normalizedQuery.indices) similarity += normalizedQuery[index] * candidate[index]
            if (similarity > best) {
                second = best
                best = similarity
                bestName = name
            } else if (similarity > second) {
                second = similarity
            }
        }
        return bestName?.takeIf { best >= minimumSimilarity && best - second >= ambiguityMargin }
    }
}
