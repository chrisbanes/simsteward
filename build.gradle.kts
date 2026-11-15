import org.gradle.api.attributes.plugin.GradlePluginApiVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
  `java-gradle-plugin`
  `maven-publish`
  kotlin("jvm") version "2.3.21"
}

group = "dev.chrisbanes.simsteward"
version = "0.1.0-SNAPSHOT"

java {
  sourceCompatibility = JavaVersion.VERSION_17
  targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
  compilerOptions {
    jvmTarget = JvmTarget.JVM_17
    // Gradle 8.14 embeds Kotlin 2.0, so its build scripts must be able to read our metadata.
    apiVersion = KotlinVersion.KOTLIN_2_0
    languageVersion = KotlinVersion.KOTLIN_2_0
  }
}

dependencies {
  compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.0")

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
publishing {
  repositories {
    maven {
      name = "test"
      url = uri(testRepository)
    }
  }
}

// Minimum supported versions, declared in the published metadata and exercised by the CI matrix.
val minGradle = "8.14"
val minKgp = "2.2.0"

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
  publications.withType<MavenPublication>().configureEach {
    pom {
      properties.put("simsteward.minGradle", minGradle)
      properties.put("simsteward.minKgp", minKgp)
    }
  }
}

tasks.test {
  // `-Psimsteward.test.gradle=<version> -Psimsteward.test.kgp=<version>` select the fixture versions.
  systemProperty("simsteward.test.gradle", providers.gradleProperty("simsteward.test.gradle").getOrElse(minGradle))
  systemProperty("simsteward.test.kgp", providers.gradleProperty("simsteward.test.kgp").getOrElse(minKgp))
}

val smokeTest by tasks.registering(Test::class) {
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
  systemProperty("simsteward.version", version.toString())
  // Each checkout, and each test task, gets its own Gradle user home, so concurrent runs never share records.
  systemProperty("simsteward.testKitDir", layout.buildDirectory.dir("test-kit/$name").get().asFile.absolutePath)
  inputs.dir(testRepository)
}

tasks.test {
  useJUnitPlatform { excludeTags("smoke") }
}
