plugins { alias(libs.plugins.android.library); alias(libs.plugins.kotlin.android); alias(libs.plugins.maven.publish.base) }
android {
    namespace = "io.github.silascui.sloth.ui"
    compileSdk = libs.versions.compileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
    testOptions { targetSdk = libs.versions.targetSdk.get().toInt() }
    publishing { singleVariant("release") { withSourcesJar() } }
}
kotlin { jvmToolchain(21) }
dependencies {
    api(project(":player"))
    implementation(libs.core)
    api(libs.recyclerview)
    api(libs.viewpager2)
    api(libs.lifecycle.common)
    api(libs.savedstate)
    debugImplementation(libs.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
    androidTestImplementation(libs.test.core)
}
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                artifactId = "sloth-ui"
                from(components["release"])
                pom { name.set("Sloth Video Player UI"); description.set("Lifecycle-aware RecyclerView and ViewPager2 playback bindings") }
            }
        }
        repositories { maven { name = "bundle"; url = uri(rootProject.layout.projectDirectory.dir("player/build/bundle-repository")) } }
    }
}
