plugins {
    id("com.android.library")
}

android {
    namespace = "com.manegow.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    api(project(":domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.bluetooth)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(kotlin("test"))
}
