import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    // El esquema de cada versión de la base queda en git: sirve para revisar las migraciones.
    arg("room.schemaLocation", "$projectDir/schemas")
}

// La firma de release vive en `keystore.properties` (ignorado por git, con el keystore fuera del
// repo). Sin ese archivo el proyecto compila igual: el release queda sin firmar.
val keystoreFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystoreFile.exists()) {
        keystoreFile.inputStream().use { stream -> this.load(stream) }
    }
}

android {
    namespace = "com.noted.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.noted.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        resourceConfigurations += listOf("en", "es")
    }

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmado con la clave propia si está `keystore.properties`; si no, queda sin firma.
            signingConfig = signingConfigs.findByName("release")
            // Solo para migrar datos entre firmas: un release debuggable deja usar `run-as`
            // para restaurar la copia. Sin `-PdebugRelease` el APK sale como corresponde.
            isDebuggable = providers.gradleProperty("debugRelease").isPresent
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Igual que release pero firmada con la clave de desarrollo, para medir
        // rendimiento real en el aparato sin perder los datos de la versión debug.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // El benchmark de escala no corre con la suite: se queda afuera salvo que se
        // pida con `-Descala=1`. Así la suite sigue en 768 tests, sin saltados.
        unitTests.all { test ->
            val escala = System.getProperty("escala") == "1"
            if (!escala) test.exclude("**/EscalaBenchmark*")
            test.systemProperty("escala", if (escala) "1" else "")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.documentfile:documentfile:1.0.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jsoup:jsoup:1.18.3")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
