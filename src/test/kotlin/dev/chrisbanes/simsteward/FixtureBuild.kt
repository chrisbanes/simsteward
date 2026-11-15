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
      simsteward.xcrun=${fake("xcrun", XCRUN.replace("@ROOT@", root.absolutePath))}
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

      // Registered before simsteward so that simsteward's doFirst runs before this one.
      tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        setDependsOn(emptyList<Any>())
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

  /** Makes the fake `simctl list devices` report these simulators, as `name to udid` pairs. */
  fun listDevices(vararg devices: Pair<String, String>) {
    root.resolve("devices.txt").writeText(
      "== Devices ==\n-- iOS 27.0 --\n" + devices.joinToString("") { (name, udid) -> "    $name ($udid) (Shutdown) \n" },
    )
  }

  fun commands(): List<String> = if (commandLog.exists()) commandLog.readLines() else emptyList()

  fun clearCommands() = commandLog.delete()

  fun runner(vararg arguments: String): GradleRunner = GradleRunner.create()
    .withProjectDir(root)
    .withTestKitDir(TEST_KIT_DIR)
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
    const val GRADLE_VERSION = "8.14"
    const val KGP_VERSION = "2.2.0"
    const val DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17"
    const val RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-27-0"
    const val UDID = "23AE16CF-D297-4A91-A615-5BA5B20D700B"

    private val TEST_REPOSITORY = System.getProperty("simsteward.testRepository")
    private val SIMSTEWARD_VERSION = System.getProperty("simsteward.version")

    /** Simsteward's ownership records live under the Gradle user home, which is this TestKit dir. */
    val TEST_KIT_DIR = File(System.getProperty("java.io.tmpdir"), ".gradle-test-kit-${System.getProperty("user.name")}")
    val RECORDS_DIR = TEST_KIT_DIR.resolve("simsteward/records")

    // The create and bootstatus cases snapshot the ownership record as the build owns it.
    private val XCRUN = """
      case "${'$'}1 ${'$'}2" in
        "simctl create")
          echo "${'$'}3" > '@ROOT@/pool-name'
          cp '$RECORDS_DIR'/"${'$'}3.properties" '@ROOT@/record-at-create.properties' 2>/dev/null || true
          echo "$UDID" ;;
        "simctl bootstatus")
          cp '$RECORDS_DIR'/"${'$'}(cat '@ROOT@/pool-name')".properties '@ROOT@/record-after-create.properties' 2>/dev/null ;;
        "simctl list") cat '@ROOT@/devices.txt' 2>/dev/null || true ;;
      esac
    """

    private const val SIMSLIM = ""
  }
}
