plugins {
    id("com.android.application")
}

android {
    namespace = "com.eta.hidequickshare"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.eta.hidequickshare"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "2.2"
    }

    buildTypes {
        release {
            // Hook 类绝不能被混淆
            isMinifyEnabled = false
            isShrinkResources = false
            // 用 debug 签名，保证 CI 产物可直接安装；正式分发请换自己的 key
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    // 模块没有 Activity/布局，关掉无用构建步骤
    buildFeatures {
        buildConfig = false
        resValues = false
        shaders = false
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
