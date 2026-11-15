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

tasks.test {
  useJUnitPlatform()
  dependsOn("publishAllPublicationsToTestRepository")
  systemProperty("simsteward.testRepository", testRepository.get().asFile.absolutePath)
  systemProperty("simsteward.version", version.toString())
  inputs.dir(testRepository)
}
