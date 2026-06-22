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
        val resolvedCandidates = candidates.sortedForLocation()
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
}
