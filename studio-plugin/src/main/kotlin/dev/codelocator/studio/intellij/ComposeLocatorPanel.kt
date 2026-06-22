package dev.codelocator.studio.intellij

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.CollectionListModel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextArea
import dev.codelocator.studio.device.DeviceDescriptor
import dev.codelocator.studio.model.HitCandidate
import dev.codelocator.studio.ui.LocatorToolWindow
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Image
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JComboBox
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

class ComposeLocatorPanel(
    project: Project,
) : JPanel(BorderLayout(8, 8)) {
    private val statusLabel = JBLabel("Idle")
    private val deviceComboBox = JComboBox<DeviceDescriptor>()
    private val candidateModel = CollectionListModel<CandidateRow>()
    private val candidateList = JBList(candidateModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }
    private val candidateDetails = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }
    private val toolWindow = LocatorToolWindow(
        projectRoot = java.io.File(project.basePath ?: "."),
        navigator = IdeNavigator(project),
    )
    private val screenshotCanvas = ScreenshotCanvas(::handleScreenshotClick)
    private val zoomLabel = JBLabel("Fit").apply {
        horizontalAlignment = SwingConstants.CENTER
        preferredSize = Dimension(56, 28)
    }

    private fun handleScreenshotClick(imageX: Int, imageY: Int) {
        statusLabel.text = "Locating ($imageX, $imageY)..."
        runInBackground {
            val result = toolWindow.locateCandidates(imageX, imageY)
            runOnUi {
                screenshotCanvas.setCandidates(result.candidates)
                candidateModel.removeAll()
                candidateModel.add(result.candidates.map { CandidateRow(it.label, it) })
                when {
                    result.candidates.isEmpty() -> {
                        screenshotCanvas.setSelectedCandidate(null)
                        statusLabel.text = "No hit at ($imageX, $imageY)"
                    }
                    result.candidates.size == 1 -> {
                        val candidate = result.candidates.first()
                        screenshotCanvas.setSelectedCandidate(candidate)
                        updateCandidateDetails(candidate)
                        toolWindow.openCandidate(candidate)
                        statusLabel.text = if (candidate.location?.isNavigable == true) {
                            "Opened 1 candidate at ($imageX, $imageY)"
                        } else {
                            "Found 1 candidate at ($imageX, $imageY), but source was not resolved"
                        }
                    }
                    else -> {
                        val bestCandidate = result.candidates.firstOrNull { it.location?.isNavigable == true }
                            ?: result.candidates.first()
                        val selectedIndex = result.candidates.indexOf(bestCandidate)
                        candidateList.selectedIndex = selectedIndex
                        screenshotCanvas.setSelectedCandidate(bestCandidate)
                        updateCandidateDetails(bestCandidate)
                        if (bestCandidate.location?.isNavigable == true) {
                            toolWindow.openCandidate(bestCandidate)
                            statusLabel.text = "Opened best of ${result.candidates.size} candidates at ($imageX, $imageY)"
                        } else {
                            statusLabel.text = "Found ${result.candidates.size} candidates at ($imageX, $imageY), but source was not resolved"
                        }
                    }
                }
            }
        }.onFailureUi { error ->
            statusLabel.text = "Locate failed: ${error.message}"
        }
    }

    init {
        val controls = JPanel().apply {
            border = BorderFactory.createEmptyBorder(4, 4, 0, 4)
            add(JBLabel("Device"))
            add(deviceComboBox)

            add(JButton("抓取").apply {
                preferredSize = Dimension(88, 30)
                addActionListener {
                    statusLabel.text = "Capturing..."
                    val requestedDevice = deviceComboBox.selectedItem as? DeviceDescriptor
                    runInBackground {
                        val selected = selectCaptureDevice(requestedDevice)
                        runOnUi {
                            if (deviceComboBox.selectedItem != selected) {
                                deviceComboBox.selectedItem = selected
                            }
                        }
                        toolWindow.selectDevice(selected)
                        toolWindow.connect()
                        refreshScreenshot()
                    }.onFailureUi { error ->
                        statusLabel.text = "Capture failed: ${error.message}"
                    }
                }
            })
        }

        candidateList.addListSelectionListener {
            val selected = candidateList.selectedValue?.candidate
            screenshotCanvas.setSelectedCandidate(selected)
            selected?.let(::updateCandidateDetails)
        }

        add(controls, BorderLayout.NORTH)
        add(JPanel(BorderLayout(0, 4)).apply {
            add(JPanel().apply {
                add(JButton("适配").apply {
                    addActionListener {
                        screenshotCanvas.setZoomMode(ZoomMode.FitWidth)
                        updateZoomLabel()
                    }
                })
                add(JButton("-").apply {
                    addActionListener {
                        screenshotCanvas.zoomBy(0.85)
                        updateZoomLabel()
                    }
                })
                add(zoomLabel)
                add(JButton("+").apply {
                    addActionListener {
                        screenshotCanvas.zoomBy(1.18)
                        updateZoomLabel()
                    }
                })
                add(JButton("100%").apply {
                    addActionListener {
                        screenshotCanvas.setZoomScale(1.0)
                        updateZoomLabel()
                    }
                })
            }, BorderLayout.NORTH)
            add(JBScrollPane(screenshotCanvas).apply {
                preferredSize = Dimension(360, 720)
            }, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout(0, 8)).apply {
            preferredSize = Dimension(260, 0)
            add(JBScrollPane(candidateList), BorderLayout.CENTER)
            add(JBScrollPane(candidateDetails).apply {
                preferredSize = Dimension(260, 180)
            }, BorderLayout.SOUTH)
        }, BorderLayout.EAST)
        add(statusLabel, BorderLayout.SOUTH)

        statusLabel.text = "Refreshing devices..."
        runInBackground {
            refreshDevices()
            setStatus("Ready. Click 抓取 to capture the current screen.")
        }.onFailureUi { error ->
            statusLabel.text = "Device refresh failed: ${error.message}"
        }
    }

    private fun refreshScreenshot() {
        val (capture, session) = toolWindow.captureScreenshot()
        val viewModel = ScreenshotDecoder.decode(capture, session)
        runOnUi {
            screenshotCanvas.setScreenshot(viewModel)
            screenshotCanvas.setCandidates(emptyList())
            screenshotCanvas.setSelectedCandidate(null)
            candidateModel.removeAll()
            candidateDetails.text = ""
            updateZoomLabel()
            statusLabel.text = "Screenshot refreshed ${viewModel.captureLabel()}"
        }
    }

    private fun refreshDevices() {
        val devices = toolWindow.listDevices()
        runOnUi {
            val previous = deviceComboBox.selectedItem as? DeviceDescriptor
            deviceComboBox.removeAllItems()
            devices.forEach(deviceComboBox::addItem)
            previous
                ?.takeIf { old -> devices.any { it.serial == old.serial } }
                ?.let { deviceComboBox.selectedItem = it }
        }
    }

    private fun selectCaptureDevice(requestedDevice: DeviceDescriptor?): DeviceDescriptor {
        val devices = toolWindow.listDevices()
        runOnUi {
            deviceComboBox.removeAllItems()
            devices.forEach(deviceComboBox::addItem)
        }
        return requestedDevice
            ?.let { requested -> devices.firstOrNull { it.serial == requested.serial } }
            ?: devices.firstOrNull()
            ?: error("No connected adb device/emulator.")
    }

    private fun updateZoomLabel() {
        runOnUi {
            zoomLabel.text = screenshotCanvas.zoomLabel()
        }
    }

    private fun setStatus(text: String) {
        runOnUi {
            statusLabel.text = text
        }
    }

    private fun ScreenshotViewModel.captureLabel(): String {
        val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(capturedAtMillis))
        return "at $time #${fingerprint.toUInt().toString(16).takeLast(6)}"
    }

    private fun updateCandidateDetails(candidate: HitCandidate) {
        val location = candidate.location
        candidateDetails.text = buildString {
            appendLine("Label: ${candidate.label}")
            appendLine("Composable: ${candidate.composableName ?: "n/a"}")
            appendLine("Tag: ${candidate.semanticsTag ?: "n/a"}")
            appendLine("Text: ${candidate.text ?: "n/a"}")
            appendLine("Role: ${candidate.role ?: "n/a"}")
            appendLine("Kind: ${candidate.kindLabel()}")
            appendLine("Z-Index: ${candidate.zIndex}")
            appendLine("Window: layer=${candidate.windowLayer}, id=${candidate.windowId}, title=${candidate.windowTitle ?: "n/a"}")
            appendLine("Bounds: [${candidate.bounds.left}, ${candidate.bounds.top}, ${candidate.bounds.right}, ${candidate.bounds.bottom}]")
            appendLine("SourceId: ${candidate.sourceId?.toString() ?: "n/a"}")
            appendLine("Source: ${location?.relativePath ?: "n/a"}")
            appendLine("Line: ${location?.takeIf { it.isNavigable }?.let { "${it.line}:${it.column}" } ?: "n/a"}")
            appendLine("Navigation: ${toolWindow.sourceResolutionHint(candidate)}")
            append("Symbol: ${location?.symbol ?: "n/a"}")
        }
    }

    private fun HitCandidate.kindLabel(): String {
        val parts = buildList {
            if (flags and 1 != 0) add("semantics")
            if (flags and 2 != 0) add("merged")
            if (flags and 4 != 0) add("layout")
            if (flags and 8 != 0) add("window")
        }
        return parts.ifEmpty { listOf("manual") }.joinToString("+")
    }

    private fun runInBackground(block: () -> Unit): AsyncUiResult {
        val result = AsyncUiResult()
        ApplicationManager.getApplication().executeOnPooledThread {
            runCatching(block).onFailure { error ->
                result.failure?.invoke(error)
            }
        }
        return result
    }

    private fun runOnUi(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(block)
    }

    private inner class AsyncUiResult {
        var failure: ((Throwable) -> Unit)? = null

        fun onFailureUi(block: (Throwable) -> Unit) {
            failure = { error -> runOnUi { block(error) } }
        }
    }
}

private data class CandidateRow(
    val label: String,
    val candidate: dev.codelocator.studio.model.HitCandidate,
) {
    override fun toString(): String = label
}

private enum class ZoomMode {
    FitWidth,
    Manual,
}

private class ScreenshotCanvas(
    private val onClick: (x: Int, y: Int) -> Unit,
) : JComponent() {
    private var model: ScreenshotViewModel? = null
    private var candidates: List<HitCandidate> = emptyList()
    private var selectedCandidate: HitCandidate? = null
    private var zoomMode: ZoomMode = ZoomMode.FitWidth
    private var manualScale: Double = 1.0

    init {
        preferredSize = Dimension(360, 720)
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                val current = model ?: return
                val placement = placementFor(current.image)
                val imageX = ((event.x - placement.x) / placement.scale).toInt()
                val imageY = ((event.y - placement.y) / placement.scale).toInt()
                if (imageX !in 0 until current.image.width || imageY !in 0 until current.image.height) return
                onClick(imageX, imageY)
            }
        })
    }

    fun setScreenshot(model: ScreenshotViewModel?) {
        this.model = model
        revalidate()
        repaint()
    }

    fun setZoomMode(mode: ZoomMode) {
        zoomMode = mode
        revalidate()
        repaint()
    }

    fun setZoomScale(scale: Double) {
        zoomMode = ZoomMode.Manual
        manualScale = scale.coerceIn(0.1, 4.0)
        revalidate()
        repaint()
    }

    fun zoomBy(factor: Double) {
        val current = model
        val base = if (zoomMode == ZoomMode.FitWidth && current != null) {
            fitScaleFor(current.image)
        } else {
            manualScale
        }
        setZoomScale(base * factor)
    }

    fun zoomLabel(): String {
        val current = model
        if (zoomMode == ZoomMode.FitWidth) return "Fit"
        return "${(effectiveScale(current?.image) * 100).toInt()}%"
    }

    fun setCandidates(candidates: List<HitCandidate>) {
        this.candidates = candidates
        repaint()
    }

    fun setSelectedCandidate(candidate: HitCandidate?) {
        selectedCandidate = candidate
        repaint()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val current = model ?: return
        val placement = placementFor(current.image)
        graphics.drawImage(
            current.image.getScaledInstance(placement.width, placement.height, Image.SCALE_SMOOTH),
            placement.x,
            placement.y,
            null,
        )
        paintCandidateOverlays(graphics as Graphics2D, placement)
    }

    override fun getPreferredSize(): Dimension {
        val current = model ?: return preferredSize
        val scale = effectiveScale(current.image)
        return Dimension(
            (current.image.width * scale).toInt().coerceAtLeast(DEFAULT_WIDTH),
            (current.image.height * scale).toInt().coerceAtLeast(DEFAULT_HEIGHT),
        )
    }

    private fun placementFor(image: BufferedImage): ImagePlacement {
        val scale = effectiveScale(image)
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val x = ((this.width - width) / 2).coerceAtLeast(0)
        val y = ((this.height - height) / 2).coerceAtLeast(0)
        return ImagePlacement(x, y, width, height, scale)
    }

    private fun effectiveScale(image: BufferedImage?): Double {
        return if (zoomMode == ZoomMode.FitWidth && image != null) {
            fitScaleFor(image)
        } else {
            manualScale
        }
    }

    private fun fitScaleFor(image: BufferedImage): Double {
        return minOf(1.0, DEFAULT_WIDTH / image.width.toDouble())
    }

    private fun paintCandidateOverlays(graphics: Graphics2D, placement: ImagePlacement) {
        if (candidates.isEmpty()) return
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        candidates.forEachIndexed { index, candidate ->
            val bounds = candidate.bounds
            val x = placement.x + (bounds.left * placement.scale).toInt()
            val y = placement.y + (bounds.top * placement.scale).toInt()
            val width = ((bounds.right - bounds.left) * placement.scale).toInt().coerceAtLeast(1)
            val height = ((bounds.bottom - bounds.top) * placement.scale).toInt().coerceAtLeast(1)
            val selected = candidate == selectedCandidate || (selectedCandidate == null && index == 0)
            graphics.color = if (selected) Color(0xE2, 0x58, 0x22, 96) else Color(0x28, 0x7D, 0x8E, 72)
            graphics.fillRoundRect(x, y, width, height, 12, 12)
            graphics.color = if (selected) Color(0x8C, 0x2D, 0x04) else Color(0x0D, 0x47, 0x56)
            graphics.drawRoundRect(x, y, width, height, 12, 12)
        }
    }

    private data class ImagePlacement(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val scale: Double,
    )

    private companion object {
        const val DEFAULT_WIDTH = 360
        const val DEFAULT_HEIGHT = 720
    }
}
