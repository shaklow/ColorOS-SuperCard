import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins { id("com.android.application") }
android {
    namespace = "dev.local.supercardhost"
    compileSdk = 36
    // Release CI also uses assembleDebug, so DEBUG alone must never enable probes.
    sourceSets.getByName("main").java.srcDir(
        if (providers.gradleProperty("payProbe").orNull == "true")
            "src/payProbe/java" else "src/noPayProbe/java"
    )
    defaultConfig {
        applicationId = "dev.local.supercardhost"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1-host-probe"
        testInstrumentationRunner = "dev.local.supercardhost.SettingsStartupTest"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // State/lifecycle tests need framework resources only. Real archive loading
    // is tested against the installed unified APK by SettingsStartupTest.
}
dependencies {
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("org.luckypray:dexkit:2.0.7")
    // Supplied by Vector in scoped processes; do not bundle framework API classes.
    compileOnly("io.github.libxposed:api:102.0.0")
    // Runtime region tests load the adapter methods; production still receives this API from Vector.
    testRuntimeOnly("io.github.libxposed:api:102.0.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}

// Keep MDC/AndroidX classes and their 0x7f resources inside one private archive.
// The unified host must continue to preserve the vendor's own 0x7f resource table.
abstract class PrepareDhuUiAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun copyArchive() {
        val target = outputDirectory.file("dhu-ui.apk").get().asFile
        target.parentFile.mkdirs()
        archive.get().asFile.copyTo(target, overwrite = true)
    }
}

val dhuUiAssets = tasks.register<PrepareDhuUiAssets>("prepareDhuUiAssets") {
    dependsOn(":dhu-ui:assembleDebug")
    archive.set(project(":dhu-ui").layout.buildDirectory.file("outputs/apk/debug/dhu-ui-debug.apk"))
    outputDirectory.set(layout.buildDirectory.dir("generated/dhuUiAssets"))
}
androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(dhuUiAssets, PrepareDhuUiAssets::outputDirectory)
    }
}

// Preserve assertion details in CI logs when a remote test fails.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
