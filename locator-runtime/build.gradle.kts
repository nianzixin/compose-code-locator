plugins {
    application
    kotlin("jvm")
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    implementation(kotlin("stdlib"))
}

application {
    mainClass.set("dev.codelocator.runtime.demo.RuntimeDemoKt")
}

tasks.register<JavaExec>("verifyRuntimeProtocol") {
    group = "verification"
    description = "Verifies runtime hit-testing, source stack handling, and JSON protocol escaping."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.codelocator.runtime.demo.RuntimeProtocolVerificationKt")
}

tasks.register<JavaExec>("verifyRuntimePerformance") {
    group = "verification"
    description = "Verifies runtime registry, hit-test, and JSON encoding performance with synthetic nodes."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("dev.codelocator.runtime.demo.RuntimePerformanceVerificationKt")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "locator-runtime"
            pom {
                name.set("Compose Code Locator Runtime")
                description.set("Shared runtime protocol and hit-test model for Compose Code Locator.")
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
