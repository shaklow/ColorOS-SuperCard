import java.util.Properties

plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
// Match ordinary Android Studio/AGP projects: local.properties works without SDK environment variables.
val androidHome = providers.provider {
    val local = Properties()
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { local.load(it) }
    local.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        ?: error("Set sdk.dir in local.properties or provide ANDROID_HOME")
}
dependencies { compileOnly(files(androidHome.map { file("$it/platforms/android-36/android.jar") })) }
