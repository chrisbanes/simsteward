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
