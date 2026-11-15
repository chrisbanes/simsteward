// Copyright 2026, Christopher Banes and the Simsteward project contributors
// SPDX-License-Identifier: Apache-2.0


import org.gradle.caching.http.HttpBuildCache

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

val isCi: Boolean get() = !System.getenv("CI").isNullOrEmpty()

fun gradleProperty(name: String): String? =
  providers.gradleProperty(name).orNull?.takeIf(String::isNotBlank)

fun gradleBooleanProperty(name: String): Boolean =
  providers.gradleProperty(name).map(String::toBoolean).getOrElse(false)

val remoteBuildCacheUrl = gradleProperty("remoteBuildCacheUrl")
val remoteBuildCacheUsername = gradleProperty("remoteBuildCacheUsername")
val remoteBuildCachePassword = gradleProperty("remoteBuildCachePassword")
val isRemoteBuildCachePushEnabled = gradleBooleanProperty("remoteBuildCachePush")
val isRemoteBuildCacheEnabled =
  remoteBuildCacheUrl != null &&
    remoteBuildCacheUsername != null &&
    remoteBuildCachePassword != null

buildCache {
  local {
    isEnabled = !isCi || !isRemoteBuildCacheEnabled
    if (!isEnabled) {
      logger.lifecycle("Local build cache disabled because remote build cache is enabled on CI")
    }
  }

  remote<HttpBuildCache> {
    if (isRemoteBuildCacheEnabled) {
      isEnabled = true
      isPush = isRemoteBuildCachePushEnabled
      url = uri(checkNotNull(remoteBuildCacheUrl))
      credentials {
        username = remoteBuildCacheUsername
        password = remoteBuildCachePassword
      }
      logger.lifecycle("Remote build cache enabled (push: $isRemoteBuildCachePushEnabled)")
    } else {
      isEnabled = false
      logger.lifecycle(
        "Remote build cache disabled " +
          "(url present: ${remoteBuildCacheUrl != null}, " +
          "username present: ${remoteBuildCacheUsername != null}, " +
          "password present: ${remoteBuildCachePassword != null})",
      )
    }
  }
}
