import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val localConfig = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun configString(name: String): String = "\"" + localConfig.getProperty(name, "")
    .trim().replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
val discordId = providers.environmentVariable("DISCORD_APPLICATION_ID").orNull
    ?: localConfig.getProperty("discord.applicationId", "").trim()
val validDiscordId = discordId.matches(Regex("[1-9][0-9]{0,18}")) && discordId.toLongOrNull() != null
val discordSdkVersion = localConfig.getProperty("discord.sdkVersion", "1.10.19337").trim()
check(discordSdkVersion.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))

android {
    namespace = "com.kadaiyare.izuna"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.kadaiyare.izuna"
        minSdk = 36
        targetSdk = 37
        versionCode = 3
        versionName = "0.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Distribution artifacts must never inherit developer credentials from local.properties.
        buildConfigField("String", "LAST_FM_API_KEY", "\"\"")
        buildConfigField("String", "LAST_FM_SHARED_SECRET", "\"\"")
        buildConfigField("boolean", "DISCORD_ID_VALID", validDiscordId.toString())
        buildConfigField("long", "DISCORD_APPLICATION_ID", "${if (validDiscordId) discordId else "0"}L")
        buildConfigField("String", "DISCORD_EXPECTED_VERSION", "\"$discordSdkVersion\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    val releaseKeyPath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
    if (!releaseKeyPath.isNullOrBlank()) signingConfigs {
        create("distribution") {
            storeFile = file(releaseKeyPath)
            storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
            keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
            keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "LAST_FM_API_KEY", configString("lastfm.apiKey"))
            buildConfigField("String", "LAST_FM_SHARED_SECRET", configString("lastfm.sharedSecret"))
        }
        release {
            isDebuggable = false
            if (!releaseKeyPath.isNullOrBlank()) signingConfig = signingConfigs.getByName("distribution")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

tasks.register("releaseDependencyInventory") {
    val dependencies = configurations.named("releaseRuntimeClasspath")
    val destination = layout.buildDirectory.file("reports/release-dependencies.txt")
    notCompatibleWithConfigurationCache("Reads the resolved dependency graph for a release audit")
    outputs.upToDateWhen { false }
    outputs.file(destination)
    doLast {
        val modules = dependencies.get().incoming.resolutionResult.allComponents.mapNotNull {
            (it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier)?.let { id ->
                "${id.group}:${id.module}:${id.version}"
            }
        }.distinct().sorted()
        destination.get().asFile.apply { parentFile.mkdirs(); writeText(modules.joinToString("\n", postfix = "\n")) }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.work.runtime)
    implementation(project(":discord-runtime"))
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

room {
    schemaDirectory("$projectDir/schemas")
}
