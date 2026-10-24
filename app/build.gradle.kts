import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
}

kotlin {
    jvmToolchain(21)
}

// -PdevOut=<папка> — отдельная папка сборки: тестовые сборки не подменяют классы у уже запущенного Melos
providers.gradleProperty("devOut").orNull?.let { layout.buildDirectory.set(file(it)) }

// Окно входа в сервисы (как в Murglar) — WebView из JavaFX: нативные части JavaFX берём под текущую ОС и архитектуру.
val javafxPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("win") -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(libs.murglar.core)
    implementation(libs.vlcj)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.jaudiotagger)
    listOf("base", "graphics", "controls", "media", "web", "swing").forEach {
        implementation("org.openjfx:javafx-$it:${libs.versions.javafx.get()}:$javafxPlatform")
    }
    // окно входа на Chromium (CEF), как в Murglar: Google, Apple, SoundCloud не пускают во встроенный WebKit из JavaFX.
    // Сам Chromium (~150–200 МБ) KCEF скачивает при первом входе в папку данных Melos.
    implementation("dev.datlag:kcef:2025.03.23")
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "melos.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.appearance=system", "-Dfile.encoding=UTF-8",
            // CEF встраивает окно браузера в AWT — ему нужен доступ к внутренним пакетам AWT
            "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED", "--add-opens", "java.desktop/sun.lwawt=ALL-UNNAMED",
            "--add-opens", "java.desktop/sun.lwawt.macosx=ALL-UNNAMED", "--add-opens", "java.desktop/java.awt.peer=ALL-UNNAMED")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Melos"
            packageVersion = "1.0.0"
            description = "Музыкальный плеер на VLC с плагинами Murglar"
            vendor = "Melos"
            modules("java.net.http", "java.sql", "jdk.unsupported", "jdk.jsobject", "java.scripting")
            macOS { bundleID = "app.melos" }
            windows { menuGroup = "Melos"; upgradeUuid = "8a3f2c1e-5b7d-4e9a-9c61-2f4b8d0e7a13" }
        }
    }
}

tasks.test { useJUnitPlatform() }
