import java.io.File
import java.util.Locale

plugins {
    application
}

val javafxVersion = "20"
val osName = System.getProperty("os.name").lowercase(Locale.getDefault())
val osArch = System.getProperty("os.arch").lowercase(Locale.getDefault())
val javafxPlatform = when {
    osName.contains("mac") && osArch.contains("aarch64") -> "mac-aarch64"
    osName.contains("mac") -> "mac"
    osName.contains("win") -> "win"
    osName.contains("linux") && osArch.contains("aarch64") -> "linux-aarch64"
    osName.contains("linux") -> "linux"
    else -> throw IllegalStateException("Unsupported JavaFX platform: $osName ($osArch)")
}

dependencies {
    implementation(project(":honeycomb-core"))
    implementation("org.openjfx:javafx-base:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-graphics:$javafxVersion:$javafxPlatform")
    implementation("org.openjfx:javafx-controls:$javafxVersion:$javafxPlatform")
}

application {
    mainClass.set("com.honeycomb.visualizer.VisualizerApp")
}

tasks.withType<JavaExec>().configureEach {
    val javafxJars = configurations.runtimeClasspath.get().filter { it.name.contains("javafx") }
    if (!javafxJars.isEmpty) {
        val modulePath = javafxJars.files.joinToString(File.pathSeparator) { it.absolutePath }
        jvmArgs = (jvmArgs ?: emptyList()) + listOf("--module-path", modulePath, "--add-modules", "javafx.controls")
    }
}
