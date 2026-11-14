package dev.codelocator.studio.ui

import dev.codelocator.studio.client.LocatorClient
import dev.codelocator.studio.navigation.NavigationRequest
import dev.codelocator.studio.navigation.Navigator
import dev.codelocator.studio.navigation.SourceNavigator
import dev.codelocator.studio.model.HitCandidate
import dev.codelocator.studio.model.HitCandidateOrdering
import dev.codelocator.studio.service.LocatorStudioService
import dev.codelocator.studio.source.StudioSourceIndex
import java.io.File

class CaptureAndLocateAction(
    private val studioService: LocatorStudioService? = null,
    private val projectRoot: File? = null,
    private val navigator: Navigator = SourceNavigator(),
    private val fallbackClient: LocatorClient = LocatorClient(),
) {
    private val sourceIndex by lazy {
        projectRoot?.let(::StudioSourceIndex)
    }

    fun onImageClick(x: Int, y: Int, session: ScreenshotSession): LocateResult {
        val candidates = studioService?.locate(x, y, session)
            ?: fallbackClient.hitTest(session.toDeviceX(x), session.toDeviceY(y))
                .map { candidate -> sourceIndex?.resolve(candidate) ?: candidate }
        val resolvedCandidates = candidates.sortedForLocation().distinctForDisplay()
        return LocateResult(imageX = x, imageY = y, candidates = resolvedCandidates)
    }

    fun open(candidate: HitCandidate) {
        candidate.location
            ?.takeIf { it.isNavigable }
            ?.let { navigator.open(NavigationRequest(it, projectRoot = projectRoot)) }
    }

    private fun List<HitCandidate>.sortedForLocation(): List<HitCandidate> {
        return sortedWith(HitCandidateOrdering.byLocationPriority)
    }

    /**
     * 去掉展示上完全重复的候选：同一节点的语义层与布局层可能注册了相同 sourceId 且 bounds 一致，
     * 列表里会出现两行一模一样的条目（调用点条目还会被夹在中间）；按 sourceId + bounds 去重，
     * sourceId 为空的节点用固定占位符参与去重，因此同 bounds 的匿名节点也会被合并
     */
    private fun List<HitCandidate>.distinctForDisplay(): List<HitCandidate> {
        return distinctBy { candidate ->
            "${candidate.sourceId ?: "no-source"}#${candidate.bounds.left},${candidate.bounds.top},${candidate.bounds.right},${candidate.bounds.bottom}"
        }
    }
}
