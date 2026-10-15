import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localConfig = Properties().apply {
    val configFile = rootProject.file("local.properties")
    if (configFile.exists()) configFile.inputStream().use { load(it) }
}
val discordId = localConfig.getProperty("discord.applicationId", "").trim()
val validDiscordId = discordId.matches(Regex("[1-9][0-9]{0,18}")) && discordId.toLongOrNull() != null
val discordSdkVersion = localConfig.getProperty("discord.sdkVersion", "1.10.19337").trim()
check(discordSdkVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Set discord.sdkVersion to the official download's numeric version" }
// Explicit diagnostic build only; the ordinary probe still refuses the unverified SDK on 16 KB.
val diagnose16kb = providers.gradleProperty("diagnoseDiscord16kb").orNull == "true"
val discordAar = rootProject.file("vendor/discord/discord_partner_sdk.aar")
check(discordAar.isFile) { "Place the official Android AAR in vendor/discord/discord_partner_sdk.aar; see docs/discord-setup.md" }

val verifyDiscordSdk16kb = tasks.register<Exec>("verifyDiscordSdk16kb") {
    group = "verification"
    description = "Check both packaged and Prefab SDK libraries before any release build."
    workingDir(rootProject.projectDir)
    commandLine(providers.gradleProperty("discordPython").orElse("python").get(),
        rootProject.file("tools/check_discord_sdk.py").absolutePath,
        discordAar.absolutePath, "--sdk-version", discordSdkVersion)
}
// Diagnostic debug builds remain available. Neither a diagnostic flag nor an incremental
// release build can bypass this check; a corrected official AAR is required for release.
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(verifyDiscordSdk16kb) }

android {
    namespace = "com.kadaiyare.izuna.discordprobe"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.kadaiyare.izuna.discordprobe"
        minSdk = 36
        targetSdk = 37
        versionCode = 1
        versionName = "0.2-rpc-probe"
        buildConfigField("boolean", "DISCORD_ID_SET", discordId.isNotEmpty().toString())
        buildConfigField("boolean", "DISCORD_ID_VALID", validDiscordId.toString())
        buildConfigField("long", "DISCORD_APPLICATION_ID", "${if (validDiscordId) discordId else "0"}L")
        buildConfigField("String", "DISCORD_EXPECTED_VERSION", "\"$discordSdkVersion\"")
        buildConfigField("boolean", "DIAGNOSE_16KB", diagnose16kb.toString())
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildTypes {
        release {
            // This switch is never enabled in a release artifact, even when passed on the CLI.
            buildConfigField("boolean", "DIAGNOSE_16KB", "false")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(project(":discord-runtime"))
    // The AAR's unused AuthenticationActivity references these types during D8/R8.
    implementation(libs.androidx.browser)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
