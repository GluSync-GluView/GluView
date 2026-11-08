import java.time.Instant

// App GluView do relógio Wear OS (plano Projeto DM1/GluView/14_wearos_plano_execucao_emulador.md,
// Fases 5-7). MESMO applicationId do celular (com.gluview.app) e mesma chave de assinatura: o Data
// Layer só entrega dados entre apps com mesmo pacote e mesma assinatura. Recebe a leitura do celular
// e mostra em complication, tile e numa tela própria; sem o celular, busca no GluSync (plano B).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Mesma regra do :app: versionCode = minutos desde 2026-01-01 UTC só quando gera APK.
val wearVersionCode: Int = (findProperty("gluview.versionCode") as String?)?.toInt() ?: run {
    val buildsApk = gradle.startParameter.taskNames.any {
        it.contains("assemble", ignoreCase = true) || it.contains("install", ignoreCase = true) ||
            it.contains("bundle", ignoreCase = true)
    }
    if (buildsApk) {
        val epoch = Instant.parse("2026-01-01T00:00:00Z").toEpochMilli()
        ((System.currentTimeMillis() - epoch) / 60_000L).toInt()
    } else 1
}

android {
    namespace  = "com.gluview.wear"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gluview.app"
        minSdk        = 30   // Wear OS 3 (Galaxy Watch 4 em diante)
        targetSdk     = 35
        versionCode   = wearVersionCode
        versionName   = (findProperty("gluview.versionName") as String?) ?: "1.0.12"
        // Ferramentas de teste (receiver de depuração p/ apontar o plano B a um GluSync local).
        buildConfigField("boolean", "DEBUG_TOOLS", "false")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEBUG_TOOLS", "true")
        }
        release {
            isMinifyEnabled = false
        }
        // Par do build "demo" do :app (com.gluview.app.demo): mesmo sufixo, senão o Data Layer não
        // liga os dois. Usado para testar no emulador com a glicemia sintética do modo demonstração.
        create("demo") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".demo"
            versionNameSuffix   = "-demo"
            matchingFallbacks  += listOf("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose     = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":wear-contract"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)

    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)

    implementation(libs.wear.complications.data.source.ktx)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.material)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.androidx.concurrent.futures.ktx)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.security.crypto)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // org.json real nos testes JVM (o android.jar só tem stubs)
}
