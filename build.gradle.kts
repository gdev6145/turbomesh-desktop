import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose)
}

repositories {
    mavenCentral()
    google()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.coroutines.swing)
    implementation(libs.coroutines.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.jdbc)
    implementation(libs.sqlite.jdbc)
    implementation(libs.okhttp)
    implementation(libs.bluez.dbus)
    implementation(libs.dbus.transport.unix)
    implementation(libs.zxing.core)
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "com.turbomesh.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            modules("java.sql", "java.naming", "java.security.jgss")
            packageName = "turbomesh"
            packageVersion = "1.2.0"
            description = "TurboMesh — Bluetooth Mesh networking for desktop"
            vendor = "TurboMesh"
            linux { }
        }
    }
}

kotlin {
    jvmToolchain(17)
}
