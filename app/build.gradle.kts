plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.example.llmcheck"
    compileSdk = 34
    defaultConfig { applicationId = "com.example.llmcheck"; minSdk = 26; targetSdk = 34; versionCode = 1; versionName = "1.0" }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // для личного использования: подпись стандартным ключом, чтобы APK ставился без своего keystore
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
