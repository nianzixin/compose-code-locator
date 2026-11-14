// Compose Code Locator 全局 init script（由 Compose Code Locator Studio 插件自动安装到 ~/.gradle/init.d/）。
// 生效条件：当前工程根目录存在 .gradle/compose-locator/enable 标记（由 Studio 插件打开工程时写入）；
// 无标记的工程（含 CI、无关项目）直接跳过，零影响。
// 类加载说明：bootstrap jar 追加到 root buildscript classpath 末尾，与用户 AGP/Kotlin 共存，
// 用户侧同名类优先加载，包内副本仅兜底缺依赖的场景。
import java.io.File

gradle.projectsLoaded {
    val rootProject = gradle.rootProject
    val bootstrapDir = File(rootProject.projectDir, ".gradle/compose-locator")
    if (!File(bootstrapDir, "enable").isFile) return@projectsLoaded

    val bootstrapJar = File(bootstrapDir, "locator-gradle-plugin-bootstrap.jar")
    if (!bootstrapJar.isFile) {
        rootProject.logger.warn("compose-code-locator: 未找到 bootstrap jar（$bootstrapJar），跳过注入；请重新打开工程让 Studio 插件重新释放")
        return@projectsLoaded
    }

    // 供 Gradle 插件读取 bootstrap 目录（runtime AAR / compiler jar 文件依赖来源）
    (gradle as ExtensionAware).extensions.extraProperties["codelocator.bootstrap.dir"] = bootstrapDir

    // 注入 Gradle 插件 classpath：projectsLoaded 阶段配置，buildscript classpath 在工程配置期组装时生效
    rootProject.buildscript {
        dependencies {
            classpath(files(bootstrapJar))
        }
    }

    // 与 README 原手动配置同口径：仅 debug 构建任务注入；taskNames 为空视为 IDE sync，放行以保证 IDE 模型一致
    val taskNames = gradle.startParameter.taskNames.map { it.substringAfterLast(':') }
    val debugBuild = taskNames.isEmpty() || taskNames.any { name ->
        (name.startsWith("assemble") || name.startsWith("install") || name.startsWith("bundle") || name.startsWith("compile")) &&
            (name.endsWith("Debug") || name.startsWith("compileDebug"))
    }

    // 注意：init script 的 Kotlin DSL 对 Action<? super Project> 通配符无法做 lambda SAM 转换，
    // 回调一律用显式 object 表达式
    rootProject.allprojects(object : org.gradle.api.Action<org.gradle.api.Project> {
        override fun execute(project: org.gradle.api.Project) {
            project.pluginManager.withPlugin("com.android.application") {
                if (!debugBuild) return@withPlugin
                // 幂等：工程内手动 apply 或已被注入过时直接跳过
                if (project.pluginManager.hasPlugin("io.github.nianzixin.team-compose-locator")) return@withPlugin
                project.pluginManager.apply("io.github.nianzixin.team-compose-locator")
                project.logger.lifecycle("compose-code-locator: injected into ${project.path} (bootstrap mode)")
            }
        }
    })
}
