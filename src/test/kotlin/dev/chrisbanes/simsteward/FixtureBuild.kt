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
 * KGP's own `simctl spawn` uses a hard-coded `/usr/bin/xcrun`, so the fixture replaces KGP's
 * test action with its own: a passing run reports no suite, and a failing run (`fixture.failTest`)
 * reports the root suite finished to the task's test listeners, then throws, as KGP does.
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
    root.resolve("settings.gradle.kts").writeText(settings("fixture") + "\ninclude(\":lib\")\n")
    root.resolve("gradle.properties").writeText(
      """
      org.gradle.configuration-cache=true
      org.gradle.configuration-cache.problems=fail
      simsteward.xcrun=${fake("xcrun", xcrun())}
      simsteward.simslim=${fake("simslim", SIMSLIM)}
      """.trimIndent() + if (pinned) "\nsimsteward.ios.deviceType=$DEVICE_TYPE\nsimsteward.ios.runtime=$RUNTIME\n" else "\n",
    )
    installRuntimes(RUNTIMES_JSON)
    addModule("lib")
  }

  /** Adds a KMP module with simsteward applied and a fake test binary, and includes it in settings. */
  fun addModule(name: String) {
    if (name != "lib") root.resolve("settings.gradle.kts").appendText("\ninclude(\":$name\")\n")
    root.resolve(name).mkdirs()
    // The fake test binary lets KGP's test task run without compiling Kotlin/Native.
    root.resolve("$name/test.kexe").writeText("")
    root.resolve("$name/build.gradle.kts").writeText(
      """
      import java.io.File
      import java.lang.reflect.Proxy
      import org.gradle.api.tasks.testing.AbstractTestTask
      import org.gradle.api.tasks.testing.TestDescriptor
      import org.gradle.api.tasks.testing.TestListener
      import org.gradle.api.tasks.testing.TestResult
      import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

      plugins {
        kotlin("multiplatform") version "$KGP_VERSION"
        id("dev.chrisbanes.simsteward") version "$SIMSTEWARD_VERSION" apply false
      }

      kotlin {
        ${targets.joinToString("\n        ")}
      }

      // Newer KGP reads these swift-import outputs as task inputs; the fixture drops their real producer.
      val swiftImportStubs = tasks.register("swiftImportStubs") {
        val dir = layout.buildDirectory.dir("kotlin/swiftImportLdDump")
        outputs.dir(dir)
        doLast {
          for (sdk in listOf("iphonesimulator", "appletvsimulator", "watchsimulator")) for (kind in listOf("framework", "library")) {
            dir.get().file("${'$'}sdk/arm64_${'$'}{kind}_search_paths").asFile.apply { parentFile.mkdirs(); writeText("/tmp\n") }
          }
        }
      }

      // Registered before simsteward so that simsteward's doFirst runs before this one.
      tasks.withType<KotlinNativeSimulatorTest>().configureEach {
        // Drops KGP's test action; the doFirst below stands in for it.
        actions.clear()
        setDependsOn(listOf(swiftImportStubs))
        executable(file("test.kexe"))
        val failTest = project.providers.gradleProperty("fixture.failTest").isPresent
        // Barrier: the first `fixture.holders` tasks to lease keep their lease until that many tasks
        // hold one and, with `fixture.awaitWaiter`, until another lease request is waiting.
        val holders = project.providers.gradleProperty("fixture.holders").orNull?.toInt() ?: 0
        val awaitWaiter = project.providers.gradleProperty("fixture.awaitWaiter").isPresent
        val arrivals = File("${root.resolve("arrivals").absolutePath}")
        doFirst {
          val deviceName = runCatching { device.get() }.getOrNull()
          println("fixture: ${'$'}path device=${'$'}deviceName standalone=${'$'}{standalone.get()} enabled=${'$'}enabled")
          File("${commandLog.absolutePath}").appendText("test ${'$'}path\n")
          if (holders > 0) {
            arrivals.mkdirs()
            var ordinal = 1
            while (!File(arrivals, ordinal.toString()).mkdir()) ordinal++
            // A lease request is waiting when a thread blocks inside simsteward's code.
            fun leaseIsWaiting() = Thread.getAllStackTraces().any { (thread, frames) ->
              thread.state == Thread.State.WAITING && frames.any { it.className.startsWith("dev.chrisbanes.simsteward.") }
            }
            // Latched: once one holder sees the barrier met, the waiter it frees no longer waits, so others must not re-check.
            val open = File(arrivals, "open")
            fun ready() = open.exists() || (arrivals.list()!!.count { it != "open" } >= holders && (!awaitWaiter || leaseIsWaiting()) && open.mkdir())
            val deadline = System.nanoTime() + 60_000_000_000L
            while (ordinal <= holders && !ready()) {
              check(System.nanoTime() < deadline) { "fixture barrier timed out in ${'$'}path" }
              Thread.sleep(10)
            }
          }
          if (failTest) {
            // Gradle has no public way to fire test events, so reach the listeners the task holds.
            val field = AbstractTestTask::class.java.getDeclaredField("testListenerSubscriptions")
            val subscriptions = field.apply { isAccessible = true }.get(this)
            val listeners = subscriptions.javaClass.getDeclaredField("subscribedListeners").apply { isAccessible = true }.get(subscriptions) as List<*>
            fun <T> stub(type: Class<T>): T = type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ ->
              if (method.name == "toString") "fixture root suite" else null
            })
            val root = stub(TestDescriptor::class.java)
            val result = stub(TestResult::class.java)
            listeners.filterIsInstance<TestListener>().forEach { it.afterSuite(root, result) }
            throw GradleException("fixture test failure")
          }
        }
      }

      apply(plugin = "dev.chrisbanes.simsteward")
      """.trimIndent(),
    )
  }

  /** Appends Kotlin DSL to the `lib` module's build script. */
  fun appendToLib(script: String) = appendTo("lib", script)

  /** Configures the `simsteward {}` extension in [module]; the fixture applies the plugin without type-safe accessors. */
  fun simsteward(module: String, block: String) = appendTo(
    module,
    "extensions.configure<dev.chrisbanes.simsteward.SimstewardExtension>(\"simsteward\") {\n$block\n}",
  )

  /** Appends Kotlin DSL to [module]'s build script. */
  fun appendTo(module: String, script: String) {
    root.resolve("$module/build.gradle.kts").appendText("\n" + script.trimIndent() + "\n")
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

  /** The `simctl create` commands the fakes saw. */
  fun creates(): List<String> = commands().filter { it.startsWith("xcrun simctl create ") }

  /** The name of the pool simulator most recently created, from the fake `simctl create`. */
  fun poolName(): String = root.resolve("pool-name").readText().trim()

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
    val GRADLE_VERSION: String = System.getProperty("simsteward.test.gradle")
    val KGP_VERSION: String = System.getProperty("simsteward.test.kgp")
    const val DEVICE_TYPE = "com.apple.CoreSimulator.SimDeviceType.iPhone-17"
    const val RUNTIME = "com.apple.CoreSimulator.SimRuntime.iOS-27-0"
    const val UDID = "23AE16CF-D297-4A91-A615-5BA5B20D700B"

    private val TEST_REPOSITORY = System.getProperty("simsteward.testRepository")

    /** A settings script that resolves the plugin from the test repository, without any `include`. */
    fun settings(rootName: String) = """
      pluginManagement {
        repositories {
          maven(url = "${File(TEST_REPOSITORY).toURI()}")
          gradlePluginPortal()
          mavenCentral()
        }
      }
      dependencyResolutionManagement { repositories { mavenCentral() } }
      rootProject.name = "$rootName"
    """.trimIndent()
    private val SIMSTEWARD_VERSION = System.getProperty("simsteward.version")

    /** Simsteward's ownership records live under the Gradle user home, which is this TestKit dir. */
    val TEST_KIT_DIR = File(System.getProperty("simsteward.testKitDir"))
    val RECORDS_DIR = TEST_KIT_DIR.resolve("simsteward/records")

    // The create and bootstatus cases snapshot the ownership record as the build owns it.
    private fun FixtureBuild.xcrun() = """
      case "${'$'}1 ${'$'}2" in
        "simctl create")
          echo "${'$'}3" > '${root.resolve("pool-name").absolutePath}'
          cp '$RECORDS_DIR'/"${'$'}3.properties" '${root.resolve("record-at-create.properties").absolutePath}' 2>/dev/null || true
          # Each create gets its own UDID, the first one being UDID; mkdir claims a number atomically.
          mkdir -p '${root.resolve("udids").absolutePath}'
          n=1
          while ! mkdir '${root.resolve("udids").absolutePath}'/"${'$'}n" 2>/dev/null; do n=${'$'}((n + 1)); done
          if [ "${'$'}n" = 1 ]; then echo "$UDID"; else printf '%s%02d\n' "${UDID.dropLast(2)}" "${'$'}n"; fi ;;
        "simctl bootstatus")
          cp '$RECORDS_DIR'/"${'$'}(cat '${root.resolve("pool-name").absolutePath}')".properties '${root.resolve("record-after-create.properties").absolutePath}' 2>/dev/null || true ;;
        "simctl list")
          case "${'$'}3 ${'$'}4" in
            "devices "*) cat '${root.resolve("devices.txt").absolutePath}' 2>/dev/null || true ;;
            "-j runtimes") cat '${root.resolve("bin/runtimes.json").absolutePath}' ;;
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
