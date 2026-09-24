plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
}

// Release signing is driven entirely by environment variables so that no key material ever
// lives in the repository. CI decodes the keystore from a secret into JARVIS_KEYSTORE_PATH.
val releaseKeystorePath: String? = System.getenv("JARVIS_KEYSTORE_PATH")
val hasReleaseKeystore = releaseKeystorePath != null && file(releaseKeystorePath).exists()

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    // Kept stable so existing "Kun Tartibi" installs upgrade in place and keep their data.
    applicationId = "com.aistudio.kuntartibi.xqpzly"
    minSdk = 26
    targetSdk = 36
    versionCode = 110
    versionName = "1.1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
  }

  signingConfigs {
    if (hasReleaseKeystore) {
      create("release") {
        storeFile = file(releaseKeystorePath!!)
        storePassword = System.getenv("JARVIS_STORE_PASSWORD")
        keyAlias = System.getenv("JARVIS_KEY_ALIAS") ?: "jarvis"
        keyPassword = System.getenv("JARVIS_KEY_PASSWORD")
        enableV1Signing = true
        enableV2Signing = true
        enableV3Signing = true
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      isCrunchPngs = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseKeystore) signingConfig = signingConfigs.getByName("release")
    }
    debug {
      applicationIdSuffix = ".debug"
      versionNameSuffix = "-debug"
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  packaging {
    resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
    jniLibs { useLegacyPackaging = false }
  }
  androidResources { noCompress += listOf("onnx") }
  testOptions {
    unitTests {
      isIncludeAndroidResources = true
      isReturnDefaultValues = true
    }
  }
  lint {
    abortOnError = true
    checkReleaseBuilds = false
    textReport = true
    warningsAsErrors = false
    // Wake-word models are large binary assets by design.
    disable += listOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
  }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

// Secrets are read from .env (git-ignored) and fall back to .env.example placeholders.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.fragment.ktx)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.service)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  implementation(libs.androidx.sqlite)
  implementation(libs.sqlcipher.android)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.documentfile)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.okhttp)
  implementation(libs.play.services.auth)
  implementation(libs.onnxruntime.android)
  implementation(libs.porcupine.android)
  // Vosk ships JNA bindings; the @aar classifiers pull the Android flavours and skip the desktop jar.
  implementation("com.alphacephei:vosk-android:${libs.versions.vosk.get()}@aar")
  implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")

  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.test.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.okhttp.mockwebserver)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  testImplementation(libs.androidx.room.testing)

  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  androidTestImplementation(libs.androidx.rules)
  androidTestImplementation(libs.androidx.uiautomator)
  androidTestImplementation(libs.kotlinx.coroutines.test)
  androidTestImplementation(libs.androidx.room.testing)

  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
}
