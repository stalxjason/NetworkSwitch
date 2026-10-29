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
        versionCode = 1
        versionName = "1.0"
    }

    // 自用项目，只构建 debug 包：不配置 signingConfigs / release，
    // AGP 直接用默认 debug 签名，不需要 keystore 与环境变量。

    buildFeatures {
        viewBinding = true
        // AGP 8 起默认不生成 BuildConfig；日志门禁要用 BuildConfig.DEBUG
        buildConfig = true
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
            // android.util.Log 等框架方法在 JVM 单测里没有实现，返回默认值而不是抛异常
            isReturnDefaultValues = true

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
    // JVM 单测里 android.util.Xml 是桩，解析器测试注入 kxml2 的实现
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
