package dev.chrisbanes.simsteward

import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner

/**
 * A real KMP build with real KGP and simsteward applied, wired to fake `xcrun` and `simslim`
 * scripts through the `simsteward.xcrun` and `simsteward.simslim` properties.
 *
 * Every fake invocation is appended to [commands]. [failOn] makes the fakes fail any command
 * that starts with the given words.
 *
 * KGP's own `simctl spawn` uses a hard-coded `/usr/bin/xcrun`, so the fixture stops each
 * simulator test task after simsteward's lease and before KGP's test action.
 */
class FixtureBuild(val root: File) {
  val commandLog: File = root.resolve("commands.log")
  private val failFile = root.resolve("fail-on")

  init {
    root.mkdirs()
    root.resolve("settings.gradle.kts").writeText(
      """
      pluginManagement {
        repositories {
          maven(url = "${File(TEST_REPOSITORY).toURI()}")
          gradlePluginPortal()
          mavenCentral()
        }
      }
      dependencyResolutionManagement {
        repositories {
          mavenCentral()
        }
      }
      rootProject.name = "fixture"
      include(":lib")
      """.trimIndent(),
    )
    root.resolve("gradle.properties").writeText(
      """
      org.gradle.configuration-cache=true
      org.gradle.configuration-cache.problems=fail
      simsteward.xcrun=${fake("xcrun", XCRUN)}
      simsteward.simslim=${fake("simslim", SIMSLIM)}
      simsteward.ios.deviceType=$DEVICE_TYPE
      simsteward.ios.runtime=$RUNTIME
      """.trimIndent(),
    )
    root.resolve("lib").mkdirs()
    // The fake test binary lets KGP's test task run without compiling Kotlin/Native.
    root.resolve("lib/test.kexe").writeText("")
    root.resolve("lib/build.gradle.kts").writeText(
      """
      import java.io.File
      import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

      plugins {
        kotlin("multiplatform") version "$KGP_VERSION"
        id("dev.chrisbanes.simsteward") version "$SIMSTEWARD_VERSION" apply false
      }

      kotlin {
        iosSimulatorArm64()
      }

      // Newer KGP reads these swift-import outputs as task inputs; the fixture drops their real producer.
      val swiftImportStubs = tasks.register("swiftImportStubs") {
        val dir = layout.buildDirectory.dir("kotlin/swiftImportLdDump/iphonesimulator")
        outputs.dir(dir)
        doLast {
          dir.get().asFile.mkdirs()
          listOf("framework", "library").forEach { dir.get().file("arm64_${'$'}{it}_search_paths").asFile.writeText("/tmp\n") }
        }
      }

      // Registered before simsteward so that simsteward's doFirst runs before this one.
      tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        setDependsOn(listOf(swiftImportStubs))
        executable(file("test.kexe"))
        val failTest = project.providers.gradleProperty("fixture.failTest").isPresent
        doFirst {
          println("fixture: ${'$'}path device=${'$'}{device.get()} standalone=${'$'}{standalone.get()}")
          File("${commandLog.absolutePath}").appendText("test ${'$'}path\n")
          if (failTest) throw GradleException("fixture test failure")
          throw StopExecutionException()
        }
      }

      apply(plugin = "dev.chrisbanes.simsteward")
      """.trimIndent(),
    )
  }

  /** Appends Kotlin DSL to the `lib` module's build script. */
  fun appendToLib(script: String) {
    root.resolve("lib/build.gradle.kts").appendText("\n" + script.trimIndent() + "\n")
  }

  /** Makes the fakes fail every command that starts with [command], such as `simctl create`. */
  fun failOn(command: String) = failFile.writeText(command)

  fun commands(): List<String> = if (commandLog.exists()) commandLog.readLines() else emptyList()

  fun clearCommands() = commandLog.delete()

  fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
    .withProjectDir(root)
    .withGradleVersion(GRADLE_VERSION)
    .forwardOutput()
    .withArguments(*arguments, "--no-scan", "--stacktrace")

  fun build(vararg arguments: String): BuildResult = runner(*arguments).build()

  fun buildAndFail(vararg arguments: String): BuildResult = runner(*arguments).buildAndFail()

  private fun fake(name: String, body: String): String {
    val bin = root.resolve("bin").apply { mkdirs() }
    val file = bin.resolve(name)
    file.writeText(
      """
      #!/bin/sh
      echo "$name ${'$'}*" >> '${commandLog.absolutePath}'
      if [ -s '${failFile.absolutePath}' ]; then
        prefix=${'$'}(cat '${failFile.absolutePath}')
        case "${'$'}*" in
          "${'$'}prefix"*)
            echo "line one of $name output" >&2
            echo "$name: injected failure for ${'$'}*" >&2
            exit 3
            ;;
        esac
      fi
      """.trimIndent() + "\n" + body.trimIndent() + "\n",
    )
    file.setExecutable(true)
    return file.absolutePath
  }

  companion object {
    val GRADLE_VERSION: String = System.getProperty("simsteward.test.gradle")
    val KGP_VERSION: String = System.getProperty("simsteward.test.kgp")
    const val DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17"
    const val RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-27-0"
    const val UDID = "23AE16CF-D297-4A91-A615-5BA5B20D700B"

    private val TEST_REPOSITORY = System.getProperty("simsteward.testRepository")
    private val SIMSTEWARD_VERSION = System.getProperty("simsteward.version")

    private val XCRUN = """
      case "${'$'}1 ${'$'}2" in
        "simctl create") echo "$UDID" ;;
      esac
    """

    private const val SIMSLIM = ""
  }
}
