import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息放在仓库根目录的 keystore.properties（该文件不入库，已加入 .gitignore）。
// 文件缺失时 release 退回"不签名"构建，方便在没有密钥的机器上只做编译检查。
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile.exists()

android {
    namespace = "com.traveler.miyou"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.traveler.miyou"
        minSdk = 30
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.2"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            isJniDebuggable = false
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
}

/**
 * 打包后把签名 APK 复制到仓库根目录的 apk/：
 *   app/build/outputs/apk/release/app-release.apk → apk/app-release.apk
 * 挂在 assembleRelease 之后自动执行，无需额外命令。
 */
val apkRepoDir = rootProject.layout.projectDirectory.dir("apk")

tasks.register<Copy>("copyReleaseApkToRepo") {
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(apkRepoDir)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    finalizedBy("copyReleaseApkToRepo")
}
