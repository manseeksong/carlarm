import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 서명 정보는 저장소 밖에 둡니다. 파일이 없으면 서명 없이 빌드됩니다.
// 찾는 곳: 환경 변수 CARLARM_KEYSTORE_PROPERTIES, 없으면 ~/Library/Android/carlarm-signing/keystore.properties
val signingFile = File(
    System.getenv("CARLARM_KEYSTORE_PROPERTIES")
        ?: (System.getProperty("user.home") + "/Library/Android/carlarm-signing/keystore.properties")
)
val signingProps = Properties().apply { if (signingFile.isFile) signingFile.inputStream().use { load(it) } }
val hasSigning = signingFile.isFile && signingProps.getProperty("storeFile") != null

android {
    namespace = "kr.carlarm.app"
    // Pleos Connect Emulator v2.1.0 = API 34
    compileSdk = 34

    // AAOS 기어 읽기(android.car.Car, CarPropertyManager)
    useLibrary("android.car")

    defaultConfig {
        applicationId = "kr.carlarm.app"
        minSdk = 28 // Pleos 안내: minSdk 28 이상
        targetSdk = 34
        versionCode = 6
        versionName = "0.4.1"
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = File(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Pleos SDK가 권한 확인에 리플렉션을 쓰므로 축소와 난독화를 끕니다.
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*", "/META-INF/NOTICE*")
        }
    }

    lint {
        // 차량용 앱 기본 설정(launcher 아이콘 등)으로 release 빌드가 멈추지 않게 합니다.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:vehicle-data:1.1.11")
    implementation("ai.pleos.playground:TextToSpeech:3.3.1")
    // 0.4: 길 안내 정보(앞쪽 쉼터까지의 거리)
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
}
