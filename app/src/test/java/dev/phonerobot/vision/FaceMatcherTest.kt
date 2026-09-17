package dev.phonerobot.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceMatcherTest {
    private val matcher = FaceMatcher(dimensions = 2)

    @Test fun recognizesCloseEmbeddingRegardlessOfMagnitude() {
        assertEquals("Ada", matcher.match(floatArrayOf(3f, 0.1f), mapOf("Ada" to floatArrayOf(2f, 0f))))
    }

    @Test fun unrelatedFaceRemainsUnknown() {
        assertNull(matcher.match(floatArrayOf(0f, 1f), mapOf("Ada" to floatArrayOf(1f, 0f))))
    }

    @Test fun similarCandidatesAreAmbiguousRegardlessOfOrder() {
        val known = mapOf("Ada" to floatArrayOf(1f, 0f), "Bea" to floatArrayOf(1f, 0.05f))
        assertNull(matcher.match(floatArrayOf(1f, 0.02f), known))
        assertNull(matcher.match(floatArrayOf(1f, 0.02f), known.entries.reversed().associate { it.toPair() }))
    }

    @Test fun rejectsZeroNonFiniteAndWrongSizedQueries() {
        val known = mapOf("Ada" to floatArrayOf(1f, 0f))
        for (invalid in listOf(floatArrayOf(0f, 0f), floatArrayOf(Float.NaN, 1f),
            floatArrayOf(Float.POSITIVE_INFINITY, 1f), floatArrayOf(1f))) {
            assertNull(matcher.match(invalid, known))
        }
    }

    @Test fun ignoresInvalidStoredEmbeddingsWithoutInventingIdentity() {
        val invalid = mapOf("Zero" to floatArrayOf(0f, 0f), "NaN" to floatArrayOf(Float.NaN, 0f),
            "Short" to floatArrayOf(1f))
        assertNull(matcher.match(floatArrayOf(1f, 0f), invalid))
        assertEquals("Ada", matcher.match(floatArrayOf(1f, 0f), invalid + ("Ada" to floatArrayOf(1f, 0f))))
    }
}
