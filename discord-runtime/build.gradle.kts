plugins { alias(libs.plugins.android.library) }

val discordAar = rootProject.file("vendor/discord/discord_partner_sdk.aar")
check(discordAar.isFile) { "Place the official SDK in vendor/discord/discord_partner_sdk.aar; see docs/discord-setup.md" }

android {
    namespace = "com.kadaiyare.izuna.discordruntime"
    compileSdk { version = release(37) }
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 36
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++20"; arguments += "-DANDROID_STL=c++_shared" } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { prefab = true }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}

dependencies {
    api(files(discordAar))
    implementation(libs.androidx.browser)
}
