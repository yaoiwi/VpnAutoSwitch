import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vpnautoswitch.module"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vpnautoswitch.module"
        minSdk = 26
        targetSdk = 37
        versionCode = 65
        versionName = "1.32.20"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 用 debug 签名，方便直接安装体验；正式发布请换成自己的签名
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
}

// Compose 1.12 的 AAR 元数据里声明需要 AGP 9.1+，但本工程在 AGP 8.13 上同样能正常编译，
// 这里直接关掉该校验任务，避免被这条兼容性提示卡住。
// 如果你把 AGP 升到 9.x，可以删掉下面这段（AGP 9 内置 Kotlin，也不需要再声明 KGP）。
tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }
    .configureEach { enabled = false }

dependencies {
    // 只编译期可见，运行时由 LSPosed 提供
    compileOnly("de.robv.android.xposed:api:82")

    // Miuix（HyperOS 风格组件库）+ Compose 运行时
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    implementation("org.jetbrains.compose.runtime:runtime-android:1.12.0")
    implementation("org.jetbrains.compose.foundation:foundation-android:1.12.0")
    implementation("org.jetbrains.compose.ui:ui-android:1.12.0")
    implementation("androidx.activity:activity-compose:1.13.0")
}
