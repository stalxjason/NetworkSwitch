plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.stalxjason.networkswitch"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.stalxjason.networkswitch"
        minSdk = 31
        targetSdk = 36
        // CI / 本地打包时通过环境变量覆盖；不设则用仓库默认值
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0"
    }

    // 三项缺一即不配置 release 签名：AGP 不接受空 storePassword / keyAlias / keyPassword，
    // 且空串会让「已配置签名」的分支被错误点亮
    val keystoreFile = file("networkswitch.keystore")
    val storePw = System.getenv("KEYSTORE_PASSWORD")
    val alias = System.getenv("KEY_ALIAS")
    val keyPw = System.getenv("KEY_PASSWORD")
    val releaseSigningAvailable =
        keystoreFile.exists() && !storePw.isNullOrBlank() && !alias.isNullOrBlank() && !keyPw.isNullOrBlank()

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                // 密钥与口令由 CI 环境变量提供；不配置时 debug 回落默认 debug 签名，
                // release 构建出的 APK 未签名（需另行签名后再分发）
                storeFile = keystoreFile
                storePassword = storePw.orEmpty()
                keyAlias = alias.orEmpty()
                keyPassword = keyPw.orEmpty()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // 中文路径下测试 worker 需显式 UTF-8，否则 ClassLoader 加载不到测试类
            all { test ->
                test.jvmArgs("-Dfile.encoding=UTF-8")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")

    testImplementation("junit:junit:4.13.2")
}
