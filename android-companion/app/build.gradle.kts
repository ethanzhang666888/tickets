plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val usbSharedSources = tasks.register<Sync>("syncUsbSharedSources") {
    // Share the checked decision/observation primitives, not normal-app activities or services.
    from("src/main/java") {
        include("local/tickets/companion/pages/**", "local/tickets/companion/policy/**", "local/tickets/companion/usb/**")
        include("local/tickets/companion/Safety.kt", "local/tickets/companion/NodeScanBudget.kt",
            "local/tickets/companion/PickerCaptureEvidence.kt", "local/tickets/companion/DamaiLaunchIntent.kt")
        include(listOf("LocalExecution", "NativeOrderReview", "NativeFrame", "NativeReadFailure", "LocalDispatchGuard",
            "ScopedTouchTarget", "ExecutionVisualSelection", "VisualDispatchEvidence").map {
            "local/tickets/companion/execution/$it.kt"
        })
    }
    into(layout.buildDirectory.dir("generated/usb-shared"))
}

android {
    namespace = "local.tickets.companion"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "local.tickets.companion"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-local-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // Connected tests may uninstall their target during cleanup. Never target the
    // normal application: its private submission evidence must survive test runs.
    buildTypes {
        create("verification") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".verification"
            matchingFallbacks += "debug"
        }
        create("usbExecution") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".usb"
            versionNameSuffix = "-usb"
            isDebuggable = false
            matchingFallbacks += "debug"
        }
    }
    testBuildType = "verification"
    sourceSets {
        getByName("main").java.setSrcDirs(emptyList<String>())
        getByName("main").manifest.srcFile("src/common/AndroidManifest.xml")
        getByName("debug").java.srcDir("src/main/java")
        getByName("debug").manifest.srcFile("src/main/AndroidManifest.xml")
        getByName("release").java.srcDir("src/main/java")
        getByName("release").manifest.srcFile("src/main/AndroidManifest.xml")
        getByName("verification").java.srcDir("src/main/java")
        getByName("usbExecution").java.srcDir(usbSharedSources.map { it.destinationDir })
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

tasks.configureEach {
    if (name == "preUsbExecutionBuild" || (name.startsWith("lint") && name.contains("UsbExecution"))) dependsOn(usbSharedSources)
}

dependencies {
    // The model is bundled in the APK: no Play services or first-use download required.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("junit:junit:4.13.2")
}
