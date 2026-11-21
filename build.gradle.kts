// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0


import com.diffplug.spotless.LineEnding
import org.gradle.api.attributes.plugin.GradlePluginApiVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  `java-gradle-plugin`
  kotlin("jvm") version "2.4.21"
  id("com.vanniktech.maven.publish") version "0.37.0"
  id("org.jetbrains.dokka") version "2.3.0"
  id("me.tylerbwong.gradle.metalava") version "0.5.1"
  id("com.diffplug.spotless") version "8.12.0"
}

// Minimum supported versions, declared in the published metadata and exercised by the CI matrix.
// Java 17 is our choice, not a dependency's: Gradle 8.14 and KGP still run on Java 8.
val minJava = 17
val minGradle = "8.14"
val minKgp = "2.2.0"

java {
  sourceCompatibility = JavaVersion.toVersion(minJava)
  targetCompatibility = JavaVersion.toVersion(minJava)
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.fromTarget(minJava.toString())
    // Gradle 8.14 embeds Kotlin 2.0, so its build scripts must be able to read our metadata.
    apiVersion = KotlinVersion.KOTLIN_2_0
    languageVersion = KotlinVersion.KOTLIN_2_0
    freeCompilerArgs.addAll(
      // 2.0 is deprecated but deliberate (see above), so silence the warning.
      "-Xsuppress-version-warnings",
      // Compile against Java 17's API even on a newer build JDK, so newer JDK methods can't leak in.
      "-Xjdk-release=$minJava",
    )
  }
}

val metalavaVersion = "1.0.0-alpha16"

metalava {
  filename.set("api/api.txt")
  excludedSourceSets.setFrom(sourceSets.test.get().kotlin.sourceDirectories)
  version.set(metalavaVersion)
}

// The plugin resolves its default version while applying, before the extension above is configured.
configurations.named("metalava") {
  resolutionStrategy.force("com.android.tools.metalava:metalava:$metalavaVersion")
}

// Metalava can write an extra blank line at EOF; keep the checked-in signature stable.
tasks.named("metalavaGenerateSignature").configure {
  val apiFile = layout.projectDirectory.file("api/api.txt").asFile
  doLast {
    val contents = apiFile.readText()
    val normalized = contents.trimEnd() + "\n"
    if (contents != normalized) apiFile.writeText(normalized)
  }
}

dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")

  testImplementation(kotlin("test"))
  testImplementation(gradleTestKit())
}

gradlePlugin {
  plugins {
    register("simsteward") {
      id = "dev.chrisbanes.simsteward"
      implementationClass = "dev.chrisbanes.simsteward.SimstewardPlugin"
    }
  }
}

// Fixtures resolve the plugin from this repository next to real KGP, as consumers do (ADR 0002).
val testRepository = layout.buildDirectory.dir("test-repository")
// Gradle's plugin resolution picks the variant by this attribute, so 8.14 is the declared minimum.
listOf("apiElements", "runtimeElements").forEach { name ->
  configurations.named(name) {
    attributes.attribute(
      GradlePluginApiVersion.GRADLE_PLUGIN_API_VERSION_ATTRIBUTE,
      objects.named(minGradle),
    )
  }
}

publishing {
  repositories {
    maven {
      name = "test"
      url = uri(testRepository)
    }
  }
  publications.withType<MavenPublication>().configureEach {
    pom {
      properties.put("simsteward.minJava", minJava.toString())
      properties.put("simsteward.minGradle", minGradle)
      properties.put("simsteward.minKgp", minKgp)
    }
  }
}

tasks.register<Test>("smokeTest") {
  group = "verification"
  description = "Runs the opt-in smoke test against a real slim simulator (needs Xcode and SimSlim)."
  testClassesDirs = sourceSets.test.get().output.classesDirs
  classpath = sourceSets.test.get().runtimeClasspath
  useJUnitPlatform { includeTags("smoke") }
  testLogging.showStandardStreams = true
}

tasks.withType<Test>().configureEach {
  dependsOn("publishAllPublicationsToTestRepository")
  systemProperty("simsteward.testRepository", testRepository.get().asFile.absolutePath)
  systemProperty("simsteward.version", providers.gradleProperty("VERSION_NAME").get())
  // Each checkout, and each test task, gets its own Gradle user home, so concurrent runs never share records.
  systemProperty("simsteward.testKitDir", layout.buildDirectory.dir("test-kit/$name").get().asFile.absolutePath)
  // `-Psimsteward.test.gradle=<version> -Psimsteward.test.kgp=<version>` select the fixture versions.
  systemProperty("simsteward.test.gradle", providers.gradleProperty("simsteward.test.gradle").getOrElse(minGradle))
  systemProperty("simsteward.test.kgp", providers.gradleProperty("simsteward.test.kgp").getOrElse(minKgp))
  inputs.dir(testRepository)
}

tasks.test {
  useJUnitPlatform { excludeTags("smoke") }
}

spotless {
  // Workaround for https://github.com/diffplug/spotless/issues/1644
  lineEndings = LineEnding.PLATFORM_NATIVE

  kotlin {
    target("src/**/*.kt")
    ktlint("1.0.1")
    licenseHeaderFile(file("spotless/copyright.txt"))
  }

  kotlinGradle {
    target("*.kts")
    ktlint("1.0.1")
    licenseHeaderFile(file("spotless/copyright.txt"), "(^(?![\\/ ]\\**).*$)")
  }
}
