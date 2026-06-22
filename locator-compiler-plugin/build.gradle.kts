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
