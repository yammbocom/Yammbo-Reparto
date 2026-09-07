import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// La firma NUNCA va escrita aqui ni se sube al repo.
//
// 🚨 El auto-update EXIGE que todas las versiones lleven la MISMA firma: si
// cambia, Android rechaza la actualizacion y hay que desinstalar. Se reutiliza
// el llavero de Yammbo Music, cuyas credenciales viven en su local.properties
// (fuera de este repo). Se puede sobreescribir con un keystore.properties
// propio o con variables de entorno SIGNING_*.
val llavero = File(rootDir, "../Yammbo-Music/local.properties")
val firmaProps = Properties().apply {
    val propio = rootProject.file("keystore.properties")
    when {
        propio.exists() -> propio.inputStream().use { load(it) }
        llavero.exists() -> llavero.inputStream().use { load(it) }
    }
}
fun firma(clave: String): String? =
    System.getenv(clave) ?: firmaProps.getProperty(clave) ?: firmaProps.getProperty(
        when (clave) {
            "SIGNING_STORE_FILE" -> "storeFile"
            "SIGNING_STORE_PASSWORD" -> "storePassword"
            "SIGNING_KEY_ALIAS" -> "keyAlias"
            else -> "keyPassword"
        }
    )
val hayFirma = firma("SIGNING_STORE_FILE") != null && firma("SIGNING_STORE_PASSWORD") != null

android {
    namespace = "com.yammbo.reparto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.yammbo.reparto"
        // El movil de quien reparte puede ser cualquier cosa; 24 cubre de
        // Android 7 en adelante.
        minSdk = 24
        targetSdk = 36
        // Invariante del proyecto: el ULTIMO tramo del nombre ES el
        // versionCode (1.0 <-> 0 seria ambiguo, asi que se empieza en 1.1).
        // El respaldo del actualizador deduce el codigo del tag de GitHub
        // (v1.1 -> 1); si se rompe esa correspondencia, la app compara mal y
        // deja de ver actualizaciones EN SILENCIO.
        versionCode = 3
        versionName = "1.3"
    }

    if (hayFirma) {
        signingConfigs {
            create("yammbo") {
                val ruta = firma("SIGNING_STORE_FILE")!!
                val f = File(ruta)
                storeFile = if (f.isAbsolute && f.exists()) f
                else File(rootDir, "../Yammbo-Music/composeApp/" + ruta).canonicalFile
                storePassword = firma("SIGNING_STORE_PASSWORD")
                keyAlias = firma("SIGNING_KEY_ALIAS")
                keyPassword = firma("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hayFirma) signingConfig = signingConfigs.getByName("yammbo")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.all { it.testLogging { showStandardStreams = true } } }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // 🚨 El org.json que trae android.jar es un STUB: en una prueba JVM cada
    // metodo lanza en vez de parsear, asi que sin esto las pruebas del lector
    // de respuestas fallarian sin que el codigo tenga nada malo. Esta linea
    // pone la implementacion de verdad delante en el classpath de test.
    testImplementation("org.json:json:20240303")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
