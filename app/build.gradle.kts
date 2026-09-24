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
        versionCode = 14
        versionName = "1.1.12"
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
        // Shizuku 用户服务需要 AIDL 生成 Stub（AGP 8 默认关闭）
        aidl = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version")
        }
    }
}

dependencies {
    // 小米穿戴互联 SDK（手机侧）：AAR 来自官方 interconnect 开发测试 demo（libs/xms-wearable-lib）
    implementation(fileTree("libs") { include("*.jar", "*.aar") })
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.5")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    // Shizuku：看门狗以 shell 权限拉起保活服务
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

/**
 * 打包后把**已签名**的 APK 复制到仓库根目录的 apk/，并再**复制一份重命名**成带版本号的发布包：
 *   app/build/outputs/apk/release/app-release.apk → apk/app-release.apk
 *                                                → apk/TravelerNote-v<版本号>.apk
 *
 * 只匹配 app-release.apk：没有签名配置时 AGP 产出的是 app-release-unsigned.apk，
 * 会被这里排除，避免把未签名包当成发布包（此时带版本号的那份也不会生成）。
 *
 * 两份都留：`app-release.apk` 文件名固定，方便本地覆盖安装；
 * `TravelerNote-v<版本号>.apk` 与 GitHub Release 资产同名，发版直接上传它。
 * 版本号取自 defaultConfig.versionName，改版本号后文件名自动跟着变。
 *
 * 注意：这里**只新增/覆盖同名文件，绝不删除 apk/ 里的任何东西** ——
 * 历史版本包（1.1.5、1.1.4…）要一直留着，方便随时回退安装，不要加清理逻辑。
 */
val apkRepoDir = rootProject.layout.projectDirectory.dir("apk")

/** 版本号（改 defaultConfig.versionName 后，带版本号的文件名会自动跟着变）。 */
val appVersionName: String = android.defaultConfig.versionName ?: "unknown"

tasks.register<Copy>("copyReleaseApkToRepo") {
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("app-release.apk")
    }
    into(apkRepoDir)
    doLast {
        val src = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        if (!src.exists()) {
            logger.warn("未找到已签名的 app-release.apk，跳过带版本号的复制")
            return@doLast
        }
        val dest = apkRepoDir.file("TravelerNote-v$appVersionName.apk").asFile
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        logger.lifecycle("带版本号的安装包：apk/TravelerNote-v$appVersionName.apk（${dest.length()} 字节）")
    }
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    doFirst {
        if (!hasReleaseSigning) {
            logger.warn("注意：未找到 keystore.properties，release 不会签名；产物为 app-release-unsigned.apk，也不会复制到 apk/")
        }
    }
    finalizedBy("copyReleaseApkToRepo")
}
