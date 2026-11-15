package dev.chrisbanes.simsteward

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.io.TempDir

/**
 * Opt-in (`./gradlew smokeTest`): real KGP, real `xcrun`, real SimSlim and a real slim simulator.
 * It covers what the fakes can't: KGP's hard-coded `/usr/bin/xcrun simctl spawn` finding the leased device by name.
 */
@Tag("smoke")
class RealSimulatorSmokeTest {
  @TempDir
  lateinit var directory: File

  private val task = ":lib:iosSimulatorArm64Test"

  @Test
  fun realKmpTestRunsOnSlimManagedSimulatorAndLeavesNothingBehind() {
    assertEquals(emptyList(), managedSimulators(), "managed simulators exist before the run")
    writeFixture()

    val first = runner().build()
    assertEquals(TaskOutcome.SUCCESS, first.task(task)?.outcome, first.output)
    assertTrue(LEASE_LINE in first.output, first.output)
    val report = directory.resolve("lib/build/test-results/iosSimulatorArm64Test").listFiles { f -> f.extension == "xml" }.orEmpty()
      .joinToString { it.readText() }
    assertTrue(Regex("""tests="[1-9]\d*"""").containsMatchIn(report) && """failures="0"""" in report && """errors="0"""" in report, report)
    assertEquals(emptyList(), managedSimulators(), "managed simulators remain after the run")

    val second = runner().build()
    assertEquals(TaskOutcome.UP_TO_DATE, second.task(task)?.outcome, second.output)
    // A lease is the only way simsteward creates a simulator, so no lease line means none was created.
    assertTrue(LEASE_LINE !in second.output, second.output)
    assertEquals(emptyList(), managedSimulators(), "managed simulators remain after the rerun")
  }

  private fun runner() = GradleRunner.create()
    .withProjectDir(directory)
    .withTestKitDir(File(System.getProperty("simsteward.testKitDir")))
    .withGradleVersion("8.14")
    .forwardOutput()
    .withArguments(task, "--no-scan", "--stacktrace")

  private fun managedSimulators(): List<String> {
    val process = ProcessBuilder("xcrun", "simctl", "list", "devices").redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { output }
    return output.lines().filter { NAME_PREFIX in it }
  }

  private fun writeFixture() {
    directory.resolve("settings.gradle.kts").writeText(
      """
      pluginManagement {
        repositories {
          maven(url = "${File(System.getProperty("simsteward.testRepository")).toURI()}")
          gradlePluginPortal()
          mavenCentral()
        }
      }
      dependencyResolutionManagement { repositories { mavenCentral() } }
      rootProject.name = "smoke"
      include(":lib")
      """.trimIndent(),
    )
    directory.resolve("gradle.properties").writeText("org.gradle.configuration-cache=true\norg.gradle.jvmargs=-Xmx2g\n")
    directory.resolve("lib/src/commonTest/kotlin").mkdirs()
    directory.resolve("lib/src/commonTest/kotlin/SmokeTest.kt").writeText(
      """
      import kotlin.test.Test
      import kotlin.test.assertEquals

      class SmokeTest {
        @Test fun adds() = assertEquals(4, 2 + 2)
      }
      """.trimIndent(),
    )
    directory.resolve("lib/build.gradle.kts").writeText(
      """
      plugins {
        kotlin("multiplatform") version "$KGP_VERSION"
        id("dev.chrisbanes.simsteward") version "${System.getProperty("simsteward.version")}"
      }
      kotlin {
        iosSimulatorArm64()
        sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
      }
      """.trimIndent(),
    )
  }

  private companion object {
    // Matches a Kotlin/Native toolchain that CI and dev machines already cache.
    const val KGP_VERSION = "2.3.21"
    const val LEASE_LINE = "leased managed simulator"
  }
}
