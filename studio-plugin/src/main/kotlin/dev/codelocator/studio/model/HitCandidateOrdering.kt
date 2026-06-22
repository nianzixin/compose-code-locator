package dev.codelocator.studio.model

object HitCandidateOrdering {
    private const val FLAG_AUTO_SEMANTICS = 1
    private const val FLAG_AUTO_LAYOUT = 1 shl 2
    private const val FLAG_WINDOW_ROOT = 1 shl 3

    val byLocationPriority: Comparator<HitCandidate> =
        compareByDescending<HitCandidate> { it.windowLayer }
            .thenBy { it.isWindowRoot() }
            .thenByDescending { it.location?.isNavigable == true && it.isInteractiveSemanticsCandidate() }
            .thenByDescending { it.location?.isNavigable == true }
            .thenByDescending { it.sourceId != null && it.isInteractiveSemanticsCandidate() }
            .thenByDescending { it.sourceId != null }
            .thenByDescending { it.isInteractiveSemanticsCandidate() }
            .thenByDescending { it.isSemantics() }
            .thenBy { it.isLayoutFallback() }
            .thenBy { it.bounds.area() }
            .thenByDescending { it.zIndex }
            .thenByDescending { it.semanticsTag != null }
            .thenBy { it.id }

    fun HitCandidate.isInteractiveSemanticsCandidate(): Boolean {
        return isSemantics() &&
            !isLayoutFallback() &&
            !isWindowRoot() &&
            (role != null || text != null || semanticsTag != null)
    }

    private fun HitCandidate.isSemantics(): Boolean {
        return flags and FLAG_AUTO_SEMANTICS != 0
    }

    private fun HitCandidate.isLayoutFallback(): Boolean {
        return flags and FLAG_AUTO_LAYOUT != 0
    }

    private fun HitCandidate.isWindowRoot(): Boolean {
        return flags and FLAG_WINDOW_ROOT != 0
    }
}
