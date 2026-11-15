// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0


rootProject.name = "simsteward"

dependencyResolutionManagement {
  repositories {
    mavenCentral()
    // Metalava and its com.android.tools.* dependencies resolve only from Google Maven.
    google {
      mavenContent {
        includeGroupAndSubgroups("com.android")
      }
    }
  }
}
