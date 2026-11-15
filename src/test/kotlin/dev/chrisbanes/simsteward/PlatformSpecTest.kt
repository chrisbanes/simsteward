// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0

package dev.chrisbanes.simsteward

import groovy.json.JsonOutput
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class PlatformSpecTest {
  @TempDir
  lateinit var directory: File

  private val dt = "com.apple.CoreSimulator.SimDeviceType"
  private val rt = "com.apple.CoreSimulator.SimRuntime"

  private fun creates(build: FixtureBuild) =
    build.creates().map { it.split(" ").drop(4) }

  @Test
  fun eachPlatformLeasesASimulatorOfItsOwnPlatform() {
    val build = FixtureBuild(
      directory,
      pinned = false,
      targets = listOf("iosSimulatorArm64()", "tvosSimulatorArm64()", "watchosSimulatorArm64()"),
    )

    build.build(":lib:iosSimulatorArm64Test", ":lib:tvosSimulatorArm64Test", ":lib:watchosSimulatorArm64Test")

    assertEquals(
      setOf(
        listOf("$dt.iPhone-17", "$rt.iOS-27-0"),
        listOf("$dt.Apple-TV-4K", "$rt.tvOS-27-0"),
        listOf("$dt.Apple-Watch-Series-11", "$rt.watchOS-27-0"),
      ),
      creates(build).toSet(),
    )
  }

  @Test
  fun renamedTargetStillMapsToItsPlatform() {
    val build = FixtureBuild(directory, pinned = false, targets = listOf("tvosSimulatorArm64(\"foo\")"))

    build.build(":lib:fooTest")

    assertEquals(listOf(listOf("$dt.Apple-TV-4K", "$rt.tvOS-27-0")), creates(build))
  }

  @Test
  fun pinnedValueOverridesEitherPick() {
    val build = FixtureBuild(directory, pinned = false)
    build.root.resolve("gradle.properties").appendText("simsteward.ios.runtime=$rt.iOS-26-4\n")

    build.build(":lib:iosSimulatorArm64Test")

    assertEquals(listOf(listOf("$dt.iPhone-16", "$rt.iOS-26-4")), creates(build))
  }

  @Test
  fun newestRuntimeComparesEachVersionComponent() {
    val build = FixtureBuild(directory, pinned = false)
    build.installRuntimes(
      FixtureBuild.runtimesJson(
        FixtureBuild.runtime("iOS", "18.3.1", "iPhone", "iPhone 16"),
        FixtureBuild.runtime("iOS", "18.4", "iPhone", "iPhone 16"),
        FixtureBuild.runtime("iOS", "17.6.1", "iPhone", "iPhone 16"),
      ),
    )

    build.build(":lib:iosSimulatorArm64Test")

    assertEquals(listOf(listOf("$dt.iPhone-16", "$rt.iOS-18-4")), creates(build))
  }

  private fun row(
    id: String,
    version: Any?,
    build: Any? = null,
    platform: String = "iOS",
    family: String = "iPhone",
    available: Boolean = true,
  ): Map<String, Any?> = mapOf(
    "identifier" to id,
    "version" to version,
    "buildversion" to build,
    "platform" to platform,
    "isAvailable" to available,
    "supportedDeviceTypes" to listOf(mapOf("identifier" to "$dt.Device", "productFamily" to family)),
  )

  private fun fixture(name: String, rows: List<Map<String, Any?>>, target: String = "iosSimulatorArm64()"): FixtureBuild =
    FixtureBuild(directory.resolve(name), pinned = false, targets = listOf(target)).also {
      it.installRuntimes(JsonOutput.toJson(mapOf("runtimes" to rows)))
    }

  @Test
  fun numericVersionsAndObservedBuildMetadata() {
    val pairs = listOf("18.3" to "18.3.1", "18.3" to "18.3.0", "18.9" to "18.10")
    for ((index, pair) in pairs.withIndex()) for (reverse in listOf(false, true)) {
      val rows = listOf(row("older", pair.first), row("newer", pair.second)).let { if (reverse) it.reversed() else it }
      val build = fixture("numeric-$index-$reverse", rows)
      build.build(":lib:iosSimulatorArm64Test")
      assertEquals(listOf(listOf("$dt.Device", "newer")), creates(build))
    }
    val observed = listOf(
      "26.2" to "23C54",
      "18.3.1" to "22D8075",
      "18.0" to "22A5282m",
      "27.1" to "24A94401",
      "27.1" to "24A94232",
      "27.1" to "24A94425",
      "26.4.1" to "23E254a",
      "27.2" to "24B91016s",
    )
    for ((index, value) in observed.withIndex()) for (withOlder in listOf(false, true)) {
      val rows = listOf(row("observed", value.first, value.second)) + if (withOlder) listOf(row("older", "1.0")) else emptyList()
      val build = fixture("observed-$index-$withOlder", rows)
      build.build(":lib:iosSimulatorArm64Test")
      assertEquals(listOf(listOf("$dt.Device", "observed")), creates(build))
    }
  }

  @Test
  fun malformedEligibleVersionsFailEagerly() {
    val invalid = listOf<Any?>("18.0beta", "18..0", "", "+18.0", "18. 0", null, 123, "2147483648.0")
    for ((index, value) in invalid.withIndex()) {
      val bad = row("bad", value).let { if (index == 5) it - "version" else it }
      val build = fixture("invalid-$index", listOf(bad))
      val output = build.buildAndFail(":lib:iosSimulatorArm64Test").output
      assertTrue("Invalid runtime version" in output && "bad" in output && "dotted numeric" in output, output)
      assertEquals(emptyList(), creates(build))
    }
    val build = fixture("invalid-lower", listOf(row("bad", "1.beta"), row("good", "27.0")))
    assertTrue("Invalid runtime version" in build.buildAndFail(":lib:iosSimulatorArm64Test").output)
    assertEquals(emptyList(), creates(build))
  }

  @Test
  fun eligibilityAndPinsPrecedeVersionParsing() {
    val rows = listOf(
      row("unavailable", "bad", available = false),
      row("wrong", "bad", platform = "tvOS"),
      row("incompatible", "bad", family = "other"),
      row("selected", "18.0"),
      row("newer", "27.0"),
    )
    val automatic = fixture("filtered", rows)
    automatic.build(":lib:iosSimulatorArm64Test")
    assertEquals(listOf(listOf("$dt.Device", "newer")), creates(automatic))
    val pinned = fixture("pinned", rows + row("bad-selected", "bad"))
    pinned.root.resolve("gradle.properties").appendText("simsteward.ios.runtime=bad-selected\n")
    pinned.build(":lib:iosSimulatorArm64Test")
    assertEquals(listOf(listOf("$dt.Device", "bad-selected")), creates(pinned))
    val older = fixture("older-pin", rows)
    older.root.resolve("gradle.properties").appendText("simsteward.ios.runtime=selected\n")
    older.build(":lib:iosSimulatorArm64Test")
    assertEquals(listOf(listOf("$dt.Device", "selected")), creates(older))
    for (id in listOf("unavailable", "incompatible", "missing")) {
      val failed = fixture("pin-$id", rows)
      failed.root.resolve("gradle.properties").appendText("simsteward.ios.runtime=$id\n")
      assertTrue("No installed iOS simulator runtime" in failed.buildAndFail(":lib:iosSimulatorArm64Test").output)
      assertEquals(emptyList(), creates(failed))
    }
    val devicePin = fixture(
      "device-pin",
      listOf(
        row("invalid", "bad", family = "other") +
          ("supportedDeviceTypes" to listOf(mapOf("identifier" to "other", "productFamily" to "other"))),
        row("good", "27.0"),
      ),
    )
    devicePin.root.resolve("gradle.properties").appendText("simsteward.ios.deviceType=$dt.Device\n")
    devicePin.build(":lib:iosSimulatorArm64Test")
    assertEquals(listOf(listOf("$dt.Device", "good")), creates(devicePin))
    val unsupported = fixture("unsupported-device", rows)
    unsupported.root.resolve("gradle.properties").appendText("simsteward.ios.deviceType=missing\n")
    assertTrue("No installed iOS simulator runtime" in unsupported.buildAndFail(":lib:iosSimulatorArm64Test").output)
    assertEquals(emptyList(), creates(unsupported))
    val both = FixtureBuild(directory.resolve("both"))
    both.installRuntimes("not json")
    both.build(":lib:iosSimulatorArm64Test")
    assertTrue(both.commands().none { "simctl list -j runtimes" in it })
  }

  @Test
  fun greatestVersionAmbiguityAndDuplicateIdentifiers() {
    val rows = listOf(row("ordinary", "27.2", "24B8117"), row("duo", "27.2", "24B91016s"))
    val messages = mutableListOf<String>()
    for (reverse in listOf(true, false)) {
      val build = fixture("ambiguous-$reverse", if (reverse) rows.reversed() else rows)
      val output = build.buildAndFail(":lib:iosSimulatorArm64Test").output
      val message = output.lineSequence().first { "Multiple iOS runtime identifiers share" in it }.trim()
      messages += message
      for (text in listOf("27.2", "ordinary", "duo", "24B8117", "24B91016s", "simsteward.ios.runtime")) assertTrue(text in message, message)
      assertEquals(emptyList(), creates(build))
    }
    assertEquals(messages.first(), messages.last())
    val combinations = listOf(
      "27.1" to listOf("same", "same"),
      "18.0" to listOf("22A5346a", "22A3351"),
      "27.1" to listOf("24A94401", "24A94232", "24A94425"),
      "26.2" to listOf("23C52", "23C54"),
    )
    for ((index, combination) in combinations.withIndex()) for (reverse in listOf(false, true)) {
      val duplicate = combination.second.map { row("same-id", combination.first, it) }.let { if (reverse) it.reversed() else it }
      val build = fixture("duplicates-$index-$reverse", duplicate)
      build.build(":lib:iosSimulatorArm64Test")
      assertEquals(listOf(listOf("$dt.Device", "same-id")), creates(build))
    }
    for (reverse in listOf(false, true)) {
      val lower = listOf(row("a", "27.1"), row("b", "27.1"), row("top", "27.2"))
      val build = fixture("lower-$reverse", if (reverse) lower.reversed() else lower)
      build.build(":lib:iosSimulatorArm64Test")
      assertEquals(listOf(listOf("$dt.Device", "top")), creates(build))
      val pin = fixture("tie-pin-$reverse", if (reverse) rows.reversed() else rows)
      pin.root.resolve("gradle.properties").appendText("simsteward.ios.runtime=duo\n")
      pin.build(":lib:iosSimulatorArm64Test")
      assertEquals(listOf(listOf("$dt.Device", "duo")), creates(pin))
    }
  }

  @Test
  fun ambiguityGuidanceIsPlatformSpecificAndBounded() {
    val dangerous = "x".repeat(170) + "\nline"
    val boundedRows = listOf(row("a", "27.2", dangerous), row("a", "27.2", dangerous), row("b", "27.2", "control\nvalue"), row("low", "1.0"))
    val boundedMessages = listOf(false, true).map { reverse ->
      val build = fixture("unsafe-$reverse", if (reverse) boundedRows.reversed() else boundedRows)
      val output = build.buildAndFail(":lib:iosSimulatorArm64Test").output
      assertEquals(emptyList(), creates(build))
      output.lineSequence().first { "Multiple iOS runtime identifiers share" in it }.trim()
    }
    assertEquals(boundedMessages.first(), boundedMessages.last())
    assertTrue("control\\u000avalue" in boundedMessages.first())
    assertTrue("x".repeat(161) !in boundedMessages.first())
    assertTrue("builds omitted" !in boundedMessages.first())
    val missing = fixture("missing-build", listOf(row("a", "27.2"), row("b", "27.2", 42)))
    assertTrue("not reported" in missing.buildAndFail(":lib:iosSimulatorArm64Test").output)
    for ((platform, family, property) in listOf(Triple("iOS", "iPhone", "ios"), Triple("tvOS", "Apple TV", "tvos"), Triple("watchOS", "Apple Watch", "watchos"))) {
      val rows = (0..5).flatMap { id -> (0..3).map { build -> row("id$id", "27.2", if (build == 0) null else "build$build", platform, family) } }
      val messages = mutableListOf<String>()
      for (reverse in listOf(false, true)) {
        val fixture = fixture("bounded-$property-$reverse", if (reverse) rows.reversed() else rows, "${property}SimulatorArm64()")
        val output = fixture.buildAndFail(":lib:${property}SimulatorArm64Test").output
        val message = output.lineSequence().first { "Multiple $platform runtime identifiers share" in it }.trim()
        for (text in listOf("simsteward.$property.runtime", "+1 identifiers omitted", "+1 builds omitted")) assertTrue(text in message, message)
        assertEquals(emptyList(), creates(fixture))
        messages += message
      }
      assertEquals(messages.first(), messages.last())
    }
  }

  @Test
  fun missingRuntimeFailsNamingPlatformAndWhatToInstall() {
    val build = FixtureBuild(directory, pinned = false, targets = listOf("watchosSimulatorArm64()"))
    build.installRuntimes(FixtureBuild.runtimesJson(FixtureBuild.runtime("iOS", "27.0", "iPhone", "iPhone 17")))

    val result = build.buildAndFail(":lib:watchosSimulatorArm64Test")

    assertTrue("No installed watchOS simulator runtime" in result.output, result.output)
    assertTrue("Install a watchOS simulator runtime" in result.output, result.output)
    assertEquals(emptyList(), creates(build))
  }

  @Test
  fun noSimctlLookupsDuringConfiguration() {
    val build = FixtureBuild(directory, pinned = false)

    build.build("help")

    assertEquals(emptyList(), build.commands())
  }
}
