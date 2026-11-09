// Android-порт Melos (этап 1): тот же общий код, что и на десктопе, но движок — libVLC,
// сеть — OkHttp, а десктопные файлы (окна, vlcj, jar-плагины) исключены и заменены своими.
plugins {
    alias(libs.plugins.agp)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "melos.droid"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.melos.droid"
        minSdk = 26                    // java.util.Base64 из murglar core
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }
}

// Общий код живёт в app/src/main/kotlin: копируем его в generated/sharedSrc минус десктоп-файлы
// (они перезаписаны своими реализациями тех же пакетов в android/src/main/kotlin) и включаем как исходники.
// Фильтровать сам java.srcDir бесполезно — Kotlin-компиляция фильтры java-каталога не видит, поэтому Sync.
val sharedExcludes = listOf(
    "melos/Main.kt",
    // каркас окна — своя мобильная оболочка (DroidRoot):
    "melos/ui/App.kt", "melos/ui/Images.kt", "melos/ui/Rows.kt", "melos/ui/Brain.kt", "melos/ui/PlayerBar.kt", "melos/ui/ScrollList.kt", "melos/ui/Stage.kt",
    "melos/ui/Fonts.kt",
    // свои реализации тех же пакетов:
    "melos/core/Paths.kt",
    "melos/host/Chromium.kt", "melos/host/EmbeddedWebView.kt", "melos/host/HttpNetwork.kt",
    "melos/host/Plugins.kt", "melos/host/Updater.kt", "melos/host/MacMediaHub.kt",
    "melos/player/VlcEngine.kt", "melos/player/TrackAnalysis.kt",
    "melos/player/Analyzer.kt", "melos/player/EqProbe.kt", "melos/player/AudioBench.kt", "melos/player/Limiter.kt",
    // десктоп-фичи: загрузки — заглушка в android/src, локальная библиотека — позже
    "melos/download/Downloads.kt", "melos/builtin/local/**",
)
val syncShared = tasks.register<Sync>("syncSharedSources") {
    from("../app/src/main/kotlin") { exclude(sharedExcludes) }
    into(layout.buildDirectory.dir("generated/sharedSrc"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(syncShared) }
(compatibleAndroid().sourceSets.getByName("main").java).srcDir(layout.buildDirectory.dir("generated/sharedSrc"))

fun Project.compatibleAndroid() = extensions.getByName("android") as com.android.build.gradle.BaseExtension

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.libvlc.android)
    implementation(libs.okhttp)
    implementation("com.squareup.okhttp3:okhttp-urlconnection:${libs.versions.okhttp.get()}")
    implementation(libs.murglar.core)
    implementation(libs.serialization.json)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${libs.versions.coroutines.get()}")
}
