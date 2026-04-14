import de.polocloud.gradle.plugin.polocloudRuntime
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

apply(from = rootProject.file("gradle/version.gradle.kts"))

plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    `maven-publish`
    signing

    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.nexus.publish)
    alias(libs.plugins.polocloud.gradle.plugin)
}

group = "de.polocloud"
// version is now set by gradle/version.gradle.kts — do NOT set it here

repositories {
    mavenCentral()
    maven { url = uri("https://repo1.maven.org/maven2") }

    maven {
        name = "polocloud-snapshots"
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
}

dependencies {
    polocloudRuntime(libs.bundles.logging)
    polocloudRuntime(libs.bundles.database.drivers)
    polocloudRuntime(libs.bundles.polocloud.common)
}

java {
    withSourcesJar()
    withJavadocJar()
}

kotlin {
    jvmToolchain(25)
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])

            pom {
                name.set("polocloud-database")
                description.set("A flexible database provider module for the PoloCloud ecosystem, supporting multiple backends.")
                url.set("https://github.com/thePolocloud/polocloud-database")

                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("httpmarco")
                        name.set("Mirco Lindenau")
                        email.set("mirco.lindenau@gmx.de")
                    }
                }

                scm {
                    url.set("https://github.com/thePolocloud/polocloud-database")
                    connection.set("scm:git:https://github.com/thePolocloud/polocloud-database.git")
                    developerConnection.set("scm:git:https://github.com/thePolocloud/polocloud-database.git")
                }
            }
        }
    }
}

nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            username.set(System.getenv("OSSRH_USERNAME"))
            password.set(System.getenv("OSSRH_PASSWORD"))
        }
    }
}

signing {
    val signingKey = System.getenv("GPG_PRIVATE_KEY")
    val signingPassphrase = System.getenv("GPG_PASSPHRASE")

    if (signingKey != null && signingPassphrase != null) {
        useInMemoryPgpKeys(signingKey, signingPassphrase)
        sign(publishing.publications)
    }
}