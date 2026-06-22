plugins {
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
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.21")
    implementation(kotlin("stdlib"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "locator-compiler-plugin"
            pom {
                name.set("Compose Code Locator Compiler Plugin")
                description.set("Kotlin compiler plugin that injects debug Compose source identity.")
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
