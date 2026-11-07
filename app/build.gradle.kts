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

// -Ptarget=windows — сборка под Windows x64 с другой ОС (портативная версия: jar + Java + Melos.exe, см. packaging/windows)
val target: String? = providers.gradleProperty("target").orNull

// Версия приложения: попадает в установщики и в ресурсы (melos/version.txt) — её сверяет обновлялка с релизами GitHub.
// Релиз: packaging/publish.sh <версия> (он передаёт -PappVersion).
val appVersion: String = providers.gradleProperty("appVersion").orNull ?: "1.0.0"

// Окно входа в сервисы (как в Murglar) — WebView из JavaFX: нативные части JavaFX берём под текущую ОС и архитектуру.
val javafxPlatform: String = if (target == "windows") "win" else run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("win") -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    implementation(if (target == "windows") compose.desktop.windows_x64 else compose.desktop.currentOs)
    implementation(libs.murglar.core)
    implementation(libs.vlcj)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.jaudiotagger)
    listOf("base", "graphics", "controls", "media", "web", "swing").forEach {
        implementation("org.openjfx:javafx-$it:${libs.versions.javafx.get()}:$javafxPlatform")
    }
    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "melos.MainKt"
        jvmArgs += listOf("-Dapple.awt.application.appearance=system", "-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb, TargetFormat.AppImage)
            packageName = "Melos"
            packageVersion = appVersion
            description = "Музыкальный плеер на VLC с плагинами Murglar"
            vendor = "Melos"
            modules("java.net.http", "java.sql", "jdk.unsupported", "jdk.jsobject", "java.scripting")
            macOS { bundleID = "app.melos"; iconFile.set(rootProject.file("packaging/icons/melos.icns")) }
            windows {
                menuGroup = "Melos"
                upgradeUuid = "8a3f2c1e-5b7d-4e9a-9c61-2f4b8d0e7a13"
                iconFile.set(rootProject.file("packaging/icons/melos.ico"))   // иконка установщика и ярлыка
                shortcut = true                                               // ярлык на рабочем столе
                dirChooser = true                                             // выбор папки установки
            }
        }
    }
}

tasks.test { useJUnitPlatform() }

tasks.processResources {
    filesMatching("melos/version.txt") { expand("version" to appVersion) }
}
