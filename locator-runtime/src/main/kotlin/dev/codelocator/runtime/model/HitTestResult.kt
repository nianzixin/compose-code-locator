package dev.codelocator.runtime.model

data class HitTestResult(
    val x: Int,
    val y: Int,
    val candidates: List<LocatorNode>,
)
