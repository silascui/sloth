plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.maven.publish.base) }
android {
    namespace = "io.github.silascui.sloth"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.minSdk.get().toInt(); testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    testOptions { targetSdk = libs.versions.targetSdk.get().toInt() }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { jvmToolchain(21) }
dependencies {
    api(libs.media3.exoplayer) { version { strictly(libs.versions.media3.get()) } }
    api(libs.media3.ui) { version { strictly(libs.versions.media3.get()) } }
    implementation(libs.annotation)
    implementation(libs.media3.hls) { version { strictly(libs.versions.media3.get()) } }
    implementation(libs.media3.dash) { version { strictly(libs.versions.media3.get()) } }
    testImplementation(libs.junit)
    debugImplementation(libs.appcompat)
    androidTestImplementation(libs.test.runner); androidTestImplementation(libs.test.ext); androidTestImplementation(libs.test.core)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "sloth"
                from(components["release"])
                pom { name.set("Sloth Video Player"); description.set("Process-scoped Media3 feed and detail playback framework") }
            }
        }
        repositories { maven { name = "bundle"; url = uri(layout.buildDirectory.dir("bundle-repository")) } }
    }
}
