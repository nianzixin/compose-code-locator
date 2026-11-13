package dev.codelocator.studio.intellij

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.security.MessageDigest

/**
 * Compose Locator Gradle 侧环境安装器。
 *
 * 职责：
 * 1. 识别 Gradle 工程（存在 settings.gradle(.kts)），写 `.gradle/compose-locator/enable` marker；
 *    非 Gradle 工程不写，全局 init script 对其零影响
 * 2. 把随插件分发的三个构建期产物（Gradle 插件 bootstrap jar、compiler 插件 jar、runtime AAR）
 *    释放到 `.gradle/compose-locator/`，Gradle 插件以文件依赖消费，断网可用
 * 3. 把全局 init script 写入 `~/.gradle/init.d/`，使任意方式发起的 Gradle 构建都能自动注入
 *
 * 版本策略：`.gradle/compose-locator/version.txt` 记录安装时的 Studio 插件版本，
 * 与当前插件版本或产物哈希不一致时覆盖重写，保证 Gradle 侧产物始终与 AS 插件对齐
 */
object ComposeLocatorBootstrapInstaller {
    /** 插件内 bootstrap 产物所在目录：安装后的插件目录 lib/，由本类自身 jar 位置推导 */
    private const val PLUGIN_LIB_DIR_NAME = "lib"

    /** 工程内 marker 目录（相对工程根） */
    private const val MARKER_DIR = ".gradle/compose-locator"

    /** 用户级全局 init script 安装目录 */
    private const val USER_INIT_SCRIPT_DIR = ".gradle/init.d"

    /** 全局 init script 文件名（写入 ~/.gradle/init.d/） */
    private const val INIT_SCRIPT_NAME = "compose-code-locator.init.gradle.kts"

    /** init script 模板资源路径（打包进插件 jar） */
    private const val INIT_SCRIPT_RESOURCE = "/locator/compose-locator.init.gradle.kts"

    /** Studio 插件版本戳，写入 marker 目录 version.txt，升级时触发产物覆盖重写；从 plugin.xml 读取避免多处维护 */
    private val PLUGIN_VERSION: String by lazy {
        // 解析打包进插件 jar 的 META-INF/plugin.xml 的 <version> 节点
        ComposeLocatorBootstrapInstaller::class.java.getResourceAsStream("/META-INF/plugin.xml")
            ?.bufferedReader()?.readText()
            ?.let { Regex("<version>([^<]+)</version>").find(it)?.groupValues?.get(1) }
            ?: "unknown"
    }

    /** 随插件分发的构建期产物文件名（在插件 lib/ 目录与工程 marker 目录中同名） */
    private val ARTIFACT_NAMES = listOf(
        "locator-gradle-plugin-bootstrap.jar",
        "locator-compiler-plugin.jar",
        "locator-runtime-android.aar",
    )

    private val LOG = Logger.getInstance(ComposeLocatorBootstrapInstaller::class.java)

    /**
     * 安装 Gradle 侧 bootstrap 环境（marker、构建期产物、全局 init script）。
     * 非 Gradle 工程或开发环境（无插件安装目录）静默跳过；
     * 版本戳/产物哈希与上次一致且产物齐全时只补写 init script，不做重复复制。
     */
    fun install(projectRoot: File) {
        if (!isGradleProject(projectRoot)) return

        val bootstrapDir = File(projectRoot, MARKER_DIR)
        val pluginLibDir = locatePluginLibDir()
        if (pluginLibDir == null) {
            // 开发环境（stub 编译/单测运行）下没有插件安装目录，跳过而不报错
            LOG.info("Compose Locator bootstrap skipped: plugin lib dir not found (dev/stub environment)")
            return
        }

        val versionStamp = PLUGIN_VERSION + ":" + artifactFingerprint(pluginLibDir)
        val stampFile = File(bootstrapDir, "version.txt")
        val enableFile = File(bootstrapDir, "enable")
        val upToDate = stampFile.isFile && stampFile.readText() == versionStamp &&
            enableFile.isFile && ARTIFACT_NAMES.all { File(bootstrapDir, it).isFile }

        if (upToDate) {
            writeUserInitScript()
            return
        }

        bootstrapDir.mkdirs()
        ARTIFACT_NAMES.forEach { name ->
            File(pluginLibDir, name).copyTo(File(bootstrapDir, name), overwrite = true)
        }
        enableFile.writeText("enabled by compose-code-locator studio plugin $PLUGIN_VERSION\n")
        stampFile.writeText(versionStamp)

        writeUserInitScript()
        LOG.info("Compose Locator bootstrap installed at ${bootstrapDir.absolutePath}")
    }

    /** 判断是否 Gradle 工程：仅凭 settings 文件存在，Android 与否交给 init script 的 withPlugin 判断 */
    private fun isGradleProject(projectRoot: File): Boolean =
        listOf("settings.gradle.kts", "settings.gradle").any { File(projectRoot, it).isFile }

    /**
     * 定位插件安装目录 lib/：本类自身 jar 的上级目录。
     * 真实安装后类来自 compose-code-locator/lib/studio-plugin-*.jar，三个构建期产物与其同级；
     * 找不到产物时返回 null（开发环境无此结构）
     */
    private fun locatePluginLibDir(): File? {
        val codeSource = ComposeLocatorBootstrapInstaller::class.java.protectionDomain.codeSource ?: return null
        val codeFile = runCatching { File(codeSource.location.toURI()) }.getOrNull() ?: return null
        val libDir = if (codeFile.isFile) codeFile.parentFile else codeFile
        if (!ARTIFACT_NAMES.all { File(libDir, it).isFile }) return null
        return libDir
    }

    /** 产物指纹：对三个 jar/aar 的内容做摘要拼接，Studio 插件升级（产物变化）时触发重释放 */
    private fun artifactFingerprint(pluginLibDir: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        ARTIFACT_NAMES.forEach { name ->
            File(pluginLibDir, name).inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /** 写全局 init script：内容与模板资源一致时跳过，避免重复改动用户文件 */
    private fun writeUserInitScript() {
        val template = ComposeLocatorBootstrapInstaller::class.java.getResourceAsStream(INIT_SCRIPT_RESOURCE)
            ?.bufferedReader()?.readText()
        if (template.isNullOrEmpty()) {
            LOG.warn(
                "Compose Locator init script template missing from plugin resources: $INIT_SCRIPT_RESOURCE",
                IllegalStateException("missing init script template"),
            )
            return
        }
        val initDir = File(System.getProperty("user.home"), USER_INIT_SCRIPT_DIR)
        val target = File(initDir, INIT_SCRIPT_NAME)
        target.parentFile?.mkdirs()
        if (!target.isFile || target.readText() != template) {
            target.writeText(template)
            LOG.info("Compose Locator global init script written to ${target.absolutePath}")
        }    }
}
