plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.android) }
android {
    namespace = "io.github.silascui.sloth.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.silascui.sloth"
        minSdk = libs.versions.minSdk.get().toInt(); targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1; versionName = project.version.toString()
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}
kotlin { jvmToolchain(21) }
dependencies { implementation(project(":player-ui")); implementation(libs.appcompat); implementation(libs.viewpager2) }
