// Copyright (C) 2016 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
import androidx.media3.buildlogic.Media3Modules

pluginManagement {
  includeBuild("build-logic-settings")
  includeBuild("build-logic")
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("gradlebuild.media3-settings-logic") }

rootProject.name = "androidx.media3"

// The core publication/consumer profile avoids configuring optional native decoders.
// All upstream modules remain available in the default full build.
val lmgCoreModules = setOf(
    "lib-common",
    "lib-common-ktx",
    "lib-container",
    "lib-database",
    "lib-datasource",
    "lib-decoder",
    "lib-effect",
    "lib-effect-ndk",
    "lib-exoplayer",
    "lib-exoplayer-dash",
    "lib-exoplayer-hls",
    "lib-extractor",
    "lib-inspector",
    "lib-inspector-frame",
    "lib-muxer",
    "lib-session",
    "lib-transformer",
    "lib-ui",
    "test-data",
    "test-utils",
    "test-utils-robolectric")
val lmgCoreOnly = providers.gradleProperty("lmgCoreOnly").orNull == "true"

Media3Modules.EXTERNAL_MODULES.forEach { (gradleName, moduleInfo) ->
  // LMG: library fork; demos and test applications are intentionally not imported.
  if (moduleInfo.directory.startsWith("libraries/") && moduleInfo.includeInCompositeBuild
      && (!lmgCoreOnly || gradleName in lmgCoreModules)) {
    include(":$gradleName")
    project(":$gradleName").projectDir = file(moduleInfo.directory)
  }
}
