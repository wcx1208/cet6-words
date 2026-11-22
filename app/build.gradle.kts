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
        versionCode = 30
        versionName = "3.8.1"
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

// 构建前自动把 docs/ 的网页同步进 assets/web，杜绝「改了网页或词库忘同步安卓」。
// 由 Gradle Copy 任务原生增量快照比对 index.html 与 data.js 两者内容，不再使用脆弱的 mtime 护栏。
val syncWebDocs = tasks.register<Copy>("syncWebDocs") {
    val docsDir = rootProject.projectDir.resolve("docs")
    onlyIf { docsDir.resolve("index.html").exists() && docsDir.resolve("data.js").exists() }
    from(docsDir) { include("index.html", "data.js") }
    into(layout.projectDirectory.dir("src/main/assets/web"))
}
tasks.named("preBuild") { dependsOn(syncWebDocs) }

dependencies {
}
