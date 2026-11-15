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
class FixtureBuild(
  val root: File,
  private val pinned: Boolean = true,
  private val targets: List<String> = listOf("iosSimulatorArm64()"),
) {
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
      simsteward.xcrun=${fake("xcrun", xcrun())}
      simsteward.simslim=${fake("simslim", SIMSLIM)}
      """.trimIndent() + if (pinned) "\nsimsteward.ios.deviceType=$DEVICE_TYPE\nsimsteward.ios.runtime=$RUNTIME\n" else "\n",
    )
    installRuntimes(RUNTIMES_JSON)
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
        ${targets.joinToString("\n        ")}
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

  /** Sets what the fake `xcrun simctl list -j runtimes` prints. */
  fun installRuntimes(json: String) {
    root.resolve("bin").apply { mkdirs() }.resolve("runtimes.json").writeText(json)
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
    private fun FixtureBuild.xcrun() = """
      case "${'$'}1 ${'$'}2" in
        "simctl create")
          echo "${'$'}3" > '${root.resolve("pool-name").absolutePath}'
          cp '$RECORDS_DIR'/"${'$'}3.properties" '${root.resolve("record-at-create.properties").absolutePath}' 2>/dev/null || true
          echo "$UDID" ;;
        "simctl bootstatus")
          cp '$RECORDS_DIR'/"${'$'}(cat '${root.resolve("pool-name").absolutePath}')".properties '${root.resolve("record-after-create.properties").absolutePath}' 2>/dev/null || true ;;
        "simctl list")
          case "${'$'}3 ${'$'}4" in
            "devices "*) cat '${root.resolve("devices.txt").absolutePath}' 2>/dev/null || true ;;
            "-j runtimes") cat '${root.resolve("bin/runtimes.json").absolutePath}' ;;
            "-j devicetypes") cat '${root.resolve("bin/devicetypes.json").absolutePath}' ;;
          esac ;;
      esac
    """

    private const val SIMSLIM = ""

    /** Shaped like real `simctl list -j runtimes`, trimmed to the fields simsteward could read. */
    fun runtime(platform: String, version: String, family: String, vararg deviceTypes: String, available: Boolean = true) =
      """
      {"isAvailable": $available, "version": "$version", "platform": "$platform",
       "identifier": "com.apple.CoreSimulator.SimRuntime.$platform-${version.replace('.', '-')}",
       "name": "$platform $version",
       "supportedDeviceTypes": [${deviceTypes.joinToString { """{"name": "$it", "productFamily": "$family", "identifier": "com.apple.CoreSimulator.SimDeviceType.${it.replace(' ', '-')}"}""" }}]}
      """.trimIndent()

    fun runtimesJson(vararg runtimes: String) = """{"runtimes": [${runtimes.joinToString()}]}"""

    val RUNTIMES_JSON = runtimesJson(
      runtime("iOS", "26.4", "iPhone", "iPhone 16"),
      runtime("iOS", "27.0", "iPhone", "iPhone 17", "iPhone 17 Pro"),
      runtime("iOS", "28.0", "iPhone", "iPhone 18", available = false),
      runtime("tvOS", "27.0", "Apple TV", "Apple TV 4K"),
      runtime("watchOS", "27.0", "Apple Watch", "Apple Watch Series 11"),
    )
  }
}
