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
  private val devicesFile = root.resolve("devices.json")

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
      simsteward.ios.deviceType=$DEVICE_TYPE
      simsteward.ios.runtime=$RUNTIME
      """.trimIndent(),
    )
    writeModule("lib")
  }

  /** Adds another KMP module, [name], with the same fixture test task as `lib`. */
  fun addModule(name: String) {
    root.resolve("settings.gradle.kts").appendText("\ninclude(\":$name\")\n")
    writeModule(name)
  }

  /** Plants a simulator that simsteward did not create, as `simctl list devices -j` reports it. */
  fun addOtherSimulator(name: String, udid: String) =
    devicesFile.writeText("""{"devices":{"$RUNTIME":[{"name":"$name","udid":"$udid","state":"Shutdown"}]}}""")

  private fun writeModule(name: String) {
    val module = root.resolve(name).apply { mkdirs() }
    // The fake test binary lets KGP's test task run without compiling Kotlin/Native.
    module.resolve("test.kexe").writeText("")
    module.resolve("build.gradle.kts").writeText(
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
        // Barrier: the first `fixture.holders` tasks to lease keep their lease until that many tasks
        // hold one and, with `fixture.awaitWaiter`, until another lease request is waiting.
        val holders = project.providers.gradleProperty("fixture.holders").orNull?.toInt() ?: 0
        val awaitWaiter = project.providers.gradleProperty("fixture.awaitWaiter").isPresent
        val arrivals = File("${root.resolve("arrivals").absolutePath}")
        doFirst {
          println("fixture: ${'$'}path device=${'$'}{device.get()} standalone=${'$'}{standalone.get()}")
          File("${commandLog.absolutePath}").appendText("test ${'$'}path\n")
          if (holders > 0) {
            arrivals.mkdirs()
            var ordinal = 1
            while (!File(arrivals, ordinal.toString()).mkdir()) ordinal++
            // A lease request is waiting when a thread blocks inside simsteward's code.
            fun leaseIsWaiting() = Thread.getAllStackTraces().any { (thread, frames) ->
              thread.state == Thread.State.WAITING && frames.any { it.className.startsWith("dev.chrisbanes.simsteward.") }
            }
            fun ready() = arrivals.list()!!.size >= holders && (!awaitWaiter || leaseIsWaiting())
            val deadline = System.nanoTime() + 60_000_000_000L
            while (ordinal <= holders && !ready()) {
              check(System.nanoTime() < deadline) { "fixture barrier timed out in ${'$'}path" }
              Thread.sleep(10)
            }
          }
          if (failTest) throw GradleException("fixture test failure")
          throw StopExecutionException()
        }
      }

      apply(plugin = "dev.chrisbanes.simsteward")
      """.trimIndent(),
    )
  }

  /** Appends Kotlin DSL to the `lib` module's build script. */
  fun appendToLib(script: String) = appendTo("lib", script)

  /** Appends Kotlin DSL to [module]'s build script. */
  fun appendTo(module: String, script: String) {
    root.resolve("$module/build.gradle.kts").appendText("\n" + script.trimIndent() + "\n")
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

  // Each create gets its own UDID, the first one being [UDID]; mkdir claims a number atomically.
  private fun xcrun(): String {
    val udids = root.resolve("udids").absolutePath
    return """
      case "${'$'}1 ${'$'}2" in
        "simctl create")
          mkdir -p '$udids'
          n=1
          while ! mkdir "$udids/${'$'}n" 2>/dev/null; do n=${'$'}((n + 1)); done
          if [ "${'$'}n" = 1 ]; then echo "$UDID"; else echo "$UDID-${'$'}n"; fi
          ;;
        "simctl list") cat '${devicesFile.absolutePath}' 2>/dev/null || echo '{"devices":{}}' ;;
      esac
    """
  }

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

    private const val SIMSLIM = ""
  }
}
