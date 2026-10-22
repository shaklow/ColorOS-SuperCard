plugins { id("com.android.application") }

android {
    namespace = "dev.local.supercardui"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.local.supercardui"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1-m3e"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
