import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// MyGuitarPlayer for Windows 11: the Android app's shared code (app/src/main/java) compiled for the JVM with
// Compose Desktop, plus the Windows-only pieces in src/main/kotlin (sound via javax.sound, storage, window).
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
}

val appVersion = "1.0.0"

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Bundled course + sample songs come straight from the Android assets, under "assets/" on the classpath,
// plus the version for the crash report / about line.
val desktopAssets = layout.buildDirectory.dir("generated/desktopAssets")
val syncAssets by tasks.registering(Sync::class) {
    from("../app/src/main/assets") { into("assets") }
    into(desktopAssets)
    val version = appVersion
    inputs.property("version", version)
    doLast { desktopAssets.get().file("app-version.txt").asFile.writeText(version) }
}

sourceSets {
    main {
        kotlin.srcDirs("../app/src/main/java", "src/main/kotlin")
        resources.srcDir(syncAssets)
    }
}

ksp {
    // Same schemas as Android (needed for the auto-migrations in AppDb).
    arg("room.schemaLocation", rootProject.file("app/schemas").path)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.cmp.material3)
    implementation(libs.cmp.material.icons.extended)
    implementation(libs.jb.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
}

compose.desktop {
    application {
        mainClass = "com.myguitarplayer.MainKt"
        // Packaging needs jpackage, which Android Studio's JBR lacks: use a Temurin 21 toolchain (auto-downloaded).
        javaHome = javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
            vendor.set(JvmVendorSpec.ADOPTIUM)
        }.get().metadata.installationPath.asFile.absolutePath
        // The bundled SQLite driver loads a native library.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "MyGuitarPlayer"
            packageVersion = appVersion
            // ASCII only: the MSI database uses code page 1252 (WiX error 311 otherwise).
            description = "Learn to play and sing with the guitar"
            vendor = "MyGuitarPlayer"
            // javax.sound + SQLite (bundled driver loads a native lib) + clipboard (java.desktop).
            modules("java.desktop", "java.sql", "jdk.unsupported")
            windows {
                iconFile.set(project.file("icons/MyGuitarPlayer.ico"))
                menuGroup = "MyGuitarPlayer"
                shortcut = true
                menu = true
                dirChooser = true
                perUserInstall = true
                // Keep this fixed forever: it lets a newer installer upgrade the old one in place.
                upgradeUuid = "6f1d1b8e-3a52-4c55-9a8e-6c0b6a3f4e21"
            }
        }
        buildTypes.release.proguard { isEnabled.set(false) }
    }
}
