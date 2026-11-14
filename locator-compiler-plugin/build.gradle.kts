import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    `maven-publish`
    signing
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
}

/**
 * 单个编译器变体的构建参数
 *
 * @property srcDir 薄壳 source 目录（对应一个 Kotlin 编译器大版本）
 * @property compilerEmbeddableVersion 该薄壳对齐的 kotlin-compiler-embeddable 版本
 * @property artifactSuffix 发布产物坐标后缀，gradle-plugin 按宿主 Kotlin 版本映射选择
 */
data class CompilerApiVariant(val srcDir: String, val compilerEmbeddableVersion: String, val artifactSuffix: String)

/**
 * 变体注册表：新增 Kotlin 大版本支持 = 在这里加一行 + 新增对应 src/<dir> 薄壳，
 * 业务逻辑（main）不需要任何改动
 */
val compilerApiVariants = mapOf(
    "2.2" to CompilerApiVariant("compat22", "2.2.10", "kotlin-2.2"),
    "2.4" to CompilerApiVariant("compat24", "2.4.21", "kotlin-2.4"),
)

// -PkotlinCompilerApi 选择本次编译的薄壳变体；IDE 日常开发默认 2.2（当前基线版本）
val compilerApiVariantName = providers.gradleProperty("kotlinCompilerApi").orElse("2.2").get()
val compilerApiVariant = compilerApiVariants[compilerApiVariantName]
    ?: throw GradleException(
        "Unknown kotlinCompilerApi variant '$compilerApiVariantName'. Supported values: ${compilerApiVariants.keys.joinToString()}",
    )

sourceSets {
    main {
        // 把选中变体的薄壳源码与 ServiceLoader 注册文件并入 main source set；
        // 未选中的变体目录天然不参与编译
        kotlin.srcDir("src/${compilerApiVariant.srcDir}/kotlin")
        resources.srcDir("src/${compilerApiVariant.srcDir}/resources")
    }
}

dependencies {
    // 薄壳与 main 共同依赖的编译器内部 API：变体版本决定字节码的目标宿主版本
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:${compilerApiVariant.compilerEmbeddableVersion}")
    implementation(kotlin("stdlib"))
}

// 2.4 变体依赖的 kce 元数据版本高于本仓库的 Kotlin 编译器（2.2.10），需放宽元数据校验才能读取其 API；
// 产物字节码仍由 2.2 编译器生成，属于 2.4 宿主可读的稳定版本
tasks.withType<KotlinCompile>().configureEach {
    if (compilerApiVariantName == "2.4") {
        compilerOptions.freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

// 变体名必须声明为 jar 任务输入：同一模块靠 -PkotlinCompilerApi 切换变体且输出同名 jar，
// 不声明的话切换属性后 jar 会被判 UP-TO-DATE，zip 会打进错误变体
tasks.named<Jar>("jar") {
    inputs.property("kotlinCompilerApi", compilerApiVariantName)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            // 产物坐标带 Kotlin 版本后缀（同一插件版本号），宿主 gradle-plugin 按 Kotlin 版本自动选择
            artifactId = "locator-compiler-plugin-${compilerApiVariant.artifactSuffix}"
            pom {
                name.set("Compose Code Locator Compiler Plugin (${compilerApiVariant.artifactSuffix})")
                description.set("Kotlin compiler plugin that injects debug Compose source identity. Built for Kotlin ${compilerApiVariantName}.x.")
                url.set(rootProject.extra["publicGitHubUrl"] as String)
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("nianzixin")
                        name.set("nianzixin")
                        url.set("https://github.com/nianzixin")
                    }
                }
                scm {
                    val repositoryUrl = rootProject.extra["publicGitHubUrl"] as String
                    connection.set("scm:git:$repositoryUrl.git")
                    developerConnection.set("scm:git:ssh://git@github.com/nianzixin/compose-code-locator.git")
                    url.set(repositoryUrl)
                }
            }
        }
    }
    repositories {
        maven {
            name = "composeLocatorStaging"
            url = rootProject.layout.buildDirectory.dir("composeLocator/release/maven").get().asFile.toURI()
        }
    }
}

signing {
    val signingKey = providers.environmentVariable("SIGNING_KEY")
        .orElse(providers.gradleProperty("signingInMemoryKey"))
        .orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD")
        .orElse(providers.gradleProperty("signingInMemoryKeyPassword"))
        .orNull
    if (!signingKey.isNullOrBlank()) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    isRequired = !signingKey.isNullOrBlank()
    sign(publishing.publications)
}
