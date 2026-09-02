plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- versão ----
// versionCode: vem de -Pautoclick.versionCode (o Dockerfile passa segundos
// desde 2023-11-14). Sem ele, o mesmo cálculo aqui: todo build tem um número
// maior que o anterior, e o Android só instala por cima se for maior.
fun prop(name: String): String = (project.findProperty(name) as String?)?.trim().orEmpty()
val verCode: Int = prop("autoclick.versionCode").toIntOrNull()
    ?: ((System.currentTimeMillis() / 1000L) - 1_700_000_000L).toInt()
val verName: String = prop("autoclick.versionName").ifEmpty { "2.0" }
val serverUrl: String = prop("autoclick.serverUrl").trimEnd('/')
val syncToken: String = prop("autoclick.syncToken")

android {
    namespace = "com.gm.autoclick"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.gm.autoclick"
        minSdk = 26
        targetSdk = 34
        versionCode = verCode
        versionName = verName
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "SYNC_TOKEN", "\"$syncToken\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("../autoclick-keystore.jks")
            storePassword = "autoclick"
            keyAlias = "autoclick"
            keyPassword = "autoclick"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
