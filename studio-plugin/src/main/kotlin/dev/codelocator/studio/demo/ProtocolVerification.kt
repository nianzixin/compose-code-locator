package dev.codelocator.studio.demo

import dev.codelocator.studio.client.LocatorProtocol
import dev.codelocator.studio.client.LocatorClient
import dev.codelocator.studio.client.FallbackLocatorTransport
import dev.codelocator.studio.client.LocatorTransport
import dev.codelocator.studio.device.AdbCommandRunner
import dev.codelocator.studio.device.AdbPortForwarder
import dev.codelocator.studio.device.DeviceDescriptor
import dev.codelocator.studio.device.DeviceDiscovery
import dev.codelocator.studio.device.ForegroundPackageResolver
import dev.codelocator.studio.source.StudioSourceIndex
import java.nio.file.Files

fun main() {
    verifyHitTestParsing()
    verifyFallbackTransportSwitchesAfterPrimaryFailure()
    verifyStudioSourceIndexRejectsInvalidLocations()
    verifyClientFiltersToTopWindow()
    verifyAdbDeviceParsing()
    verifyForegroundPackageParsing()
    verifyDeviceScopedForwardPorts()
    verifyForwarderClearsStaleMapping()
    println("Studio protocol verification passed")
}

private fun verifyHitTestParsing() {
    val raw = """
        {"x":12,"y":34,"candidates":[
          {"id":1,"bounds":[0,0,200,200],"zIndex":0.0,"sourceId":null,"semanticsTag":"root","text":"Root","role":null,"composableName":"Root","flags":4},
          {"id":2,"bounds":[10,20,80,70],"zIndex":2.5,"sourceId":42,"semanticsTag":"quote\\tag","text":"A \"quote\"\\path\nline","role":"button","composableName":"QuoteBadge","flags":1}
        ]}
    """.trimIndent()

    val response = LocatorProtocol.parseHitTestResponse(raw)
    check(response.x == 12) { "Expected x=12 but was ${response.x}" }
    check(response.y == 34) { "Expected y=34 but was ${response.y}" }
    check(response.candidates.size == 2) { "Expected 2 candidates but was ${response.candidates.size}" }

    val top = response.candidates.first()
    check(top.id == 2L) { "Expected highest z-index candidate first" }
    check(top.bounds.left == 10 && top.bounds.top == 20 && top.bounds.right == 80 && top.bounds.bottom == 70) {
        "Bounds were not decoded correctly: ${top.bounds}"
    }
    check(top.sourceId == 42L) {
        "Source id was not decoded: ${top.sourceId}"
    }
    check(top.semanticsTag == "quote\\tag") {
        "Escaped tag was not decoded: ${top.semanticsTag}"
    }
    check(top.text == "A \"quote\"\\path\nline") {
        "Escaped text was not decoded: ${top.text}"
    }
    check(top.label == "QuoteBadge #2a") {
        "Unexpected label: ${top.label}"
    }
    check(top.flags == 1) {
        "Flags were not decoded: ${top.flags}"
    }

    val sourcePriorityRaw = """
        {"x":12,"y":34,"candidates":[
          {"id":1,"bounds":[10,20,40,40],"zIndex":2.0,"sourceId":null,"semanticsTag":null,"text":"Tiny text","role":null,"composableName":null,"flags":1},
          {"id":2,"bounds":[0,0,200,200],"zIndex":0.0,"sourceId":42,"semanticsTag":"debug_panel","text":null,"role":null,"composableName":null,"flags":3}
        ]}
    """.trimIndent()
    val sourcePriority = LocatorProtocol.parseHitTestResponse(sourcePriorityRaw)
    check(sourcePriority.candidates.first().id == 2L) {
        "Expected source-backed candidate to be preferred over smaller source-less candidate"
    }

    val popupPriorityRaw = """
        {"x":298,"y":176,"candidates":[
          {"id":1,"bounds":[0,122,1228,2700],"zIndex":-2.0,"sourceId":11,"semanticsTag":null,"text":null,"role":null,"composableName":"FillElement","flags":4,"windowId":100,"windowTitle":"MainActivity","windowLayer":1},
          {"id":2,"bounds":[95,109,502,244],"zIndex":1.0,"sourceId":12,"semanticsTag":null,"text":"Popup CTA","role":"button","composableName":"Button","flags":3,"windowId":200,"windowTitle":"Popup","windowLayer":2}
        ]}
    """.trimIndent()
    val popupPriority = LocatorProtocol.parseHitTestResponse(popupPriorityRaw)
    check(popupPriority.candidates.first().id == 2L) {
        "Expected Popup CTA source-backed semantics candidate to be preferred over lower window/layout candidates"
    }
    check(popupPriority.candidates.first().windowLayer == 2 && popupPriority.candidates.first().windowTitle == "Popup") {
        "Expected popup window metadata to be decoded: ${popupPriority.candidates.first()}"
    }

    val popupGuardPriorityRaw = """
        {"x":160,"y":150,"candidates":[
          {"id":1,"bounds":[0,0,1228,2700],"zIndex":1.0,"sourceId":13,"semanticsTag":null,"text":"Underlay CTA","role":"button","composableName":"Button","flags":1,"windowId":100,"windowTitle":"MainActivity","windowLayer":1},
          {"id":2,"bounds":[80,80,520,280],"zIndex":-10000.0,"sourceId":null,"semanticsTag":null,"text":null,"role":null,"composableName":"WindowRoot","flags":8,"windowId":200,"windowTitle":"Popup","windowLayer":2}
        ]}
    """.trimIndent()
    val popupGuardPriority = LocatorProtocol.parseHitTestResponse(popupGuardPriorityRaw)
    check(popupGuardPriority.candidates.first().id == 2L) {
        "Expected popup window guard to block lower-window source candidates"
    }

    val topWindowRealNodePriorityRaw = """
        {"x":320,"y":420,"candidates":[
          {"id":1,"bounds":[0,0,1228,2700],"zIndex":1.0,"sourceId":13,"semanticsTag":null,"text":"Underlay CTA","role":"button","composableName":"Button","flags":1,"windowId":100,"windowTitle":"MainActivity","windowLayer":1},
          {"id":2,"bounds":[200,300,900,780],"zIndex":-10000.0,"sourceId":null,"semanticsTag":null,"text":null,"role":null,"composableName":"WindowRoot","flags":8,"windowId":200,"windowTitle":"Dialog","windowLayer":2},
          {"id":3,"bounds":[280,380,520,470],"zIndex":1.0,"sourceId":14,"semanticsTag":null,"text":"Dialog confirm","role":"button","composableName":"Button","flags":3,"windowId":200,"windowTitle":"Dialog","windowLayer":2}
        ]}
    """.trimIndent()
    val topWindowRealNodePriority = LocatorProtocol.parseHitTestResponse(topWindowRealNodePriorityRaw)
    check(topWindowRealNodePriority.candidates.map { it.id }.take(2) == listOf(3L, 2L)) {
        "Expected real dialog/dropdown node to outrank same-window WindowRoot, got ${topWindowRealNodePriority.candidates}"
    }
}

private fun verifyFallbackTransportSwitchesAfterPrimaryFailure() {
    var switched = false
    val client = LocatorClient(
        FallbackLocatorTransport(
            primary = object : LocatorTransport {
                override fun hitTest(x: Int, y: Int): String {
                    error("primary unavailable")
                }
            },
            switchToFallback = {
                switched = true
                true
            },
            fallback = object : LocatorTransport {
                override fun hitTest(x: Int, y: Int): String {
                    return """{"x":$x,"y":$y,"candidates":[]}"""
                }
            },
        ),
    )
    check(client.hitTest(1, 2).isEmpty()) {
        "Expected fallback transport to return an empty candidate list"
    }
    check(switched) {
        "Expected fallback transport to switch after primary failure"
    }
}

private fun verifyStudioSourceIndexRejectsInvalidLocations() {
    val root = Files.createTempDirectory("compose-locator-index").toFile()
    try {
        val indexRoot = root.resolve("app/build/intermediates/composeLocator/studio-index/v1")
        val shards = indexRoot.resolve("shards")
        shards.mkdirs()
        indexRoot.resolve("source-id-index.tsv").writeText("42\t2a\n43\t2a\n")
        shards.resolve("2a.jsonl").writeText(
            """
                {"sourceId":42,"relativePath":"app/src/main/kotlin/Invalid.kt","line":0,"column":0,"symbol":"Invalid","kind":"ModifierCallSite"}
                {"sourceId":43,"relativePath":"app/src/main/kotlin/Valid.kt","line":12,"column":4,"symbol":"Valid","kind":"ModifierCallSite"}
            """.trimIndent(),
        )

        val index = StudioSourceIndex(root)
        check(index.resolve(42) == null) {
            "Expected line=0/column=0 locations to be rejected"
        }
        val valid = index.resolve(43)
        check(valid?.line == 12 && valid.column == 4) {
            "Expected valid source location to resolve, got $valid"
        }
        check("no valid source location" in index.resolutionHint(42)) {
            "Expected invalid sourceId diagnostic, got ${index.resolutionHint(42)}"
        }
    } finally {
        root.deleteRecursively()
    }
}

private fun verifyClientFiltersToTopWindow() {
    val client = LocatorClient(
        object : LocatorTransport {
            override fun hitTest(x: Int, y: Int): String {
                return """
                    {"x":$x,"y":$y,"candidates":[
                      {"id":1,"bounds":[0,0,1228,2700],"zIndex":1.0,"sourceId":13,"semanticsTag":null,"text":"Underlay CTA","role":"button","composableName":"Button","flags":1,"windowId":100,"windowTitle":"MainActivity","windowLayer":1},
                      {"id":2,"bounds":[80,80,520,280],"zIndex":-10000.0,"sourceId":null,"semanticsTag":null,"text":null,"role":null,"composableName":"WindowRoot","flags":8,"windowId":200,"windowTitle":"Popup","windowLayer":2}
                    ]}
                """.trimIndent()
            }
        },
    )
    val candidates = client.hitTest(160, 150)
    check(candidates.map { it.id } == listOf(2L)) {
        "Expected Studio client to expose only the top-window candidates, got $candidates"
    }
}

private fun verifyAdbDeviceParsing() {
    val raw = """
        List of devices attached
        983d2183 device usb:336592896X product:mondrian model:2304FPN6DC device:mondrian transport_id:3
        emulator-5554 device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64 device:emu64a transport_id:1
        offline-1 offline transport_id:2
    """.trimIndent()

    val devices = DeviceDiscovery.parseDevices(raw)
    check(devices.size == 2) { "Expected 2 active devices, got $devices" }

    val phone = devices.first()
    check(phone.serial == "983d2183") { "Unexpected serial: $phone" }
    check(phone.product == "mondrian") { "Unexpected product: ${phone.product}" }
    check(phone.model == "2304FPN6DC") { "Unexpected model: ${phone.model}" }
    check(phone.device == "mondrian") { "Unexpected device code: ${phone.device}" }
    check(phone.transportId == "3") { "Unexpected transport id: ${phone.transportId}" }
    check(phone.toString() == "2304FPN6DC (983d2183) - mondrian") {
        "Unexpected display name: $phone"
    }

    val emulator = devices[1]
    check(emulator.displayName == "sdk gphone64 arm64 (emulator-5554) - sdk_gphone64_arm64") {
        "Unexpected emulator display name: ${emulator.displayName}"
    }

    val uniquePortDevices = listOf(
        DeviceDescriptor(serial = "phone", localPort = 49391),
        DeviceDescriptor(serial = "pad", localPort = 49391),
    ).let(DeviceDiscovery::withUniqueLocalPorts)
    check(uniquePortDevices.map { it.localPort } == listOf(49391, 49392)) {
        "Expected colliding local ports to be made unique, got $uniquePortDevices"
    }
}

private fun verifyForegroundPackageParsing() {
    val raw = """
        topResumedActivity=ActivityRecord{9b6a3af u0 dev.codelocator.demo/.MainActivity} t3779}
        mCurrentFocus=Window{b23fbc6 u0 dev.codelocator.demo/dev.codelocator.demo.MainActivity}
    """.trimIndent()
    check(ForegroundPackageResolver.parse(raw) == "dev.codelocator.demo") {
        "Expected foreground package to be parsed from activity dump"
    }

    val windowOnly = """
        mCurrentFocus=Window{61c136d u0 com.soyoung.clinic.pad/com.soyoung.clinic.pad.ui.TriageActivity}
    """.trimIndent()
    check(ForegroundPackageResolver.parse(windowOnly) == "com.soyoung.clinic.pad") {
        "Expected foreground package to be parsed from focused window"
    }
}

private fun verifyDeviceScopedForwardPorts() {
    val phone = DeviceDescriptor(serial = "L2E0222127015908")
    val pad = DeviceDescriptor(serial = "PAD1234567890")
    check(phone.localPort != pad.localPort) {
        "Expected different devices to use different local adb forward ports, got ${phone.localPort}"
    }
    check(phone.remotePort == 49391 && pad.remotePort == 49391) {
        "Expected the app debug server remote port to stay fixed at 49391"
    }
}

private fun verifyForwarderClearsStaleMapping() {
    val runner = RecordingAdbRunner()
    val device = DeviceDescriptor(serial = "PAD1234567890", localPort = 49444)
    AdbPortForwarder(runner).forwardLocalAbstract(device, "com.soyoung.clinic.pad")
    AdbPortForwarder(runner).forwardTcp(device)
    check(
        runner.commands == listOf(
            listOf("-s", "PAD1234567890", "forward", "--remove", "tcp:49444"),
            listOf("-s", "PAD1234567890", "forward", "tcp:49444", "localabstract:codelocator.com.soyoung.clinic.pad"),
            listOf("-s", "PAD1234567890", "forward", "--remove", "tcp:49444"),
            listOf("-s", "PAD1234567890", "forward", "tcp:49444", "tcp:49391"),
        ),
    ) {
        "Expected forwarder to clear stale local port before forwarding, got ${runner.commands}"
    }
}

private class RecordingAdbRunner : AdbCommandRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(vararg args: String): String {
        commands += args.toList()
        return ""
    }
}
