package dev.codelocator.studio.ui

import dev.codelocator.studio.model.HitCandidate

data class LocateResult(
    val imageX: Int,
    val imageY: Int,
    val candidates: List<HitCandidate>,
)
