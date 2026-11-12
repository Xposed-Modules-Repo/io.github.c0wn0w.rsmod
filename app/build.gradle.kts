plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.cownow.rsmod"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.cownow.rsmod"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // LSPosed 只能安装签名过的 APK；这里沿用本机 debug keystore，避免产出 unsigned 包
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Xposed / LSPosed 编译期 API：运行时由框架提供，不打包进 APK
    compileOnly("de.robv.android.xposed:api:82")

    implementation(libs.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
