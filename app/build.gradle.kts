import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式签名：密钥配置在 keystore.properties（已 gitignore，文件在仓库外 keystore/ 目录）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.wcx.mywords"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.wcx.mywords"
        minSdk = 26
        targetSdk = 34
        versionCode = 28
        versionName = "3.7"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // 音频以未压缩形式打入 APK，才能用 AssetManager.openFd 直接播放
    androidResources { noCompress += listOf("mp3") }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        getByName("release") {
            // WebView + JS 桥架构不引入混淆，避免反射裁剪风险；体积已足够小
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
}

// 构建前自动把 docs/ 的网页同步进 assets/web，杜绝「改了网页忘同步安卓」。
// 命令行构建副本（android-build/MyWords）由 cet6-apk 构建脚本第 0 步负责同步；
// 若副本里残留了过期的 docs/ 目录，时间戳护栏保证旧文件不会反向覆盖新 assets。
val syncWebDocs = tasks.register<Copy>("syncWebDocs") {
    val docsDir = rootProject.projectDir.resolve("docs")
    val webDir = layout.projectDirectory.dir("src/main/assets/web")
    onlyIf {
        val docsHtml = docsDir.resolve("index.html")
        val curHtml = webDir.file("index.html").asFile
        docsHtml.exists() && (!curHtml.exists() || docsHtml.lastModified() > curHtml.lastModified())
    }
    from(docsDir) { include("index.html", "data.js") }
    into(webDir)
    inputs.files(docsDir.resolve("index.html"), docsDir.resolve("data.js"))
}
tasks.named("preBuild") { dependsOn(syncWebDocs) }

dependencies {
}
