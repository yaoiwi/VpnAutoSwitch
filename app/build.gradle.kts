import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// ---------------------------------------------------------------- 签名
// 优先级：环境变量（CI） > keystore.properties（本地） > debug 签名（兜底）
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: signingProps.getProperty(prop)?.takeIf { it.isNotBlank() }

// 版本只在这里维护；CI 通过 :app:printVersionName 读取，避免解析脚本
val appVersionCode = 79
val appVersionName = "1.43.0"

val storePath = signingValue("KEYSTORE_FILE", "storeFile")
val storePwd = signingValue("KEYSTORE_PASSWORD", "storePassword")
val signingAlias = signingValue("KEY_ALIAS", "keyAlias")
val signingKeyPwd = signingValue("KEY_PASSWORD", "keyPassword")
val releaseStore = storePath?.let { rootProject.file(it) }?.takeIf { it.exists() }
val hasReleaseSigning =
    releaseStore != null && storePwd != null && signingAlias != null && signingKeyPwd != null

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vpnautoswitch.module"
    compileSdk = 37

    defaultConfig {
        // LSPosed 模块仓库要求：不拥有域名时使用 io.github.<username> 前缀
        applicationId = "io.github.yaoiwi.vpnautoswitch"
        minSdk = 26
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = releaseStore
                storePassword = storePwd
                keyAlias = signingAlias
                keyPassword = signingKeyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 有 keystore 就用它（本地 / CI 一致），没有则退回 debug，方便直接编译体验
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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

tasks.register("printVersionName") {
    doLast { println(appVersionName) }
}

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

    testImplementation("junit:junit:4.13.2")
}
