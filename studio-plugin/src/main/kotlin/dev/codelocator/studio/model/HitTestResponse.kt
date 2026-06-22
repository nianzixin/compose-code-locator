package dev.codelocator.studio.model

data class HitTestResponse(
    val x: Int,
    val y: Int,
    val candidates: List<HitCandidate>,
)
