plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val releaseSigningEnv = listOf(
    "ANDROID_KEYSTORE_FILE",
    "ANDROID_KEYSTORE_PASSWORD",
    "ANDROID_KEY_ALIAS",
    "ANDROID_KEY_PASSWORD",
)

fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.cardvault.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cardvault.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.1.4"
    }

    signingConfigs {
        create("release") {
            val keystoreFile = envOrNull("ANDROID_KEYSTORE_FILE")
            val keystorePassword = envOrNull("ANDROID_KEYSTORE_PASSWORD")
            val alias = envOrNull("ANDROID_KEY_ALIAS")
            val keyPasswordValue = envOrNull("ANDROID_KEY_PASSWORD")

            if (
                keystoreFile != null &&
                keystorePassword != null &&
                alias != null &&
                keyPasswordValue != null
            ) {
                storeFile = rootProject.file(keystoreFile)
                storePassword = keystorePassword
                keyAlias = alias
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        compose = true
        buildConfig = true
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { task ->
        task.name.contains("Release", ignoreCase = true)
    }
    if (buildsRelease) {
        val missing = releaseSigningEnv.filter { envOrNull(it) == null }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release signing is required. Missing environment variables: ${missing.joinToString()}"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")

    // 本地加密数据库：Room + SQLCipher
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("net.zetetic:sqlcipher-android:4.6.1")
    implementation("androidx.sqlite:sqlite-ktx:2.4.0")

    // 设置持久化
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // 本地到期提醒
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // 生物识别
    implementation("androidx.biometric:biometric:1.2.0-alpha05")

    // 网络（发卡行在线验证，支持 SOCKS5 代理）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
