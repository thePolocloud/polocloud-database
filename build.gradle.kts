import de.polocloud.gradle.plugin.polocloudRuntime
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0"
    alias(libs.plugins.polocloud.gradle.plugin)
}

group = "de.polocloud"
version = "1.0-SNAPSHOT"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    polocloudRuntime(libs.h2)

    compileOnly(libs.bundles.logging)
    compileOnly(libs.bundles.database)
    compileOnly(libs.bundles.database.drivers)
    compileOnly(libs.bundles.polocloud.common)

    runtimeOnly(libs.bundles.logging)
    runtimeOnly(libs.bundles.database)
    runtimeOnly(libs.bundles.database.drivers)
    runtimeOnly(libs.bundles.polocloud.common)
}


kotlin {
    jvmToolchain(25)
}

val compileTestKotlin: KotlinCompile by tasks
compileTestKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}