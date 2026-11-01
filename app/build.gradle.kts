import java.time.Instant
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.roborazzi)
}

// S36 — lê local.properties fora do bloco android{} para evitar conflito com extensão java do AGP
val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

// Versão (etapa E1 do plano de atualização OTA, 08_atualizacao_ota_plano.md).
// O Android só aceita atualizar por cima com versionCode MAIOR que o instalado, então ele sobe
// sozinho: minutos desde 2026-01-01 UTC (~423 mil em out/2026, cabe em Int por milênios). Só vale
// para tarefas que geram/instalam APK; testes e sync do Studio usam 1, senão o valor mudaria a cada
// minuto e invalidaria o cache dos testes Robolectric/Roborazzi. -Pgluview.versionCode=N fixa o valor
// (só para testes locais — a publicação usa a fórmula). versionName segue a série v1.0.x das releases
// e precisa crescer junto (o Obtainium compara a string); sobrescrevível com -Pgluview.versionName.
val gluviewVersionName = (findProperty("gluview.versionName") as String?) ?: "1.0.9"
val gluviewVersionCode: Int = (findProperty("gluview.versionCode") as String?)?.toInt() ?: run {
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
    namespace   = "com.gluview.app"
    compileSdk  = 35

    defaultConfig {
        applicationId = "com.gluview.app"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = gluviewVersionCode
        versionName   = gluviewVersionName

        // Servidor GluSync que o app usa. Padrão = instância oficial; quem compila um fork aponta
        // para o próprio servidor com -Pgluview.serverUrl=https://... (sem editar código).
        val serverUrl = ((findProperty("gluview.serverUrl") as String?) ?: "https://glusync-herz.onrender.com").trimEnd('/')
        buildConfigField("String", "GLUSYNC_BASE_URL", "\"$serverUrl\"")

        // Bibliotecas proprietárias (Abbott/Sibionics) NÃO vêm no repositório: quem compila extrai
        // dos APKs oficiais com tools/extract_native_libs.py. Sem elas o app compila e funciona; só o
        // sensor correspondente fica indisponível (NativeSensorSupport).
        fun jniLibsPresent(vararg names: String) =
            names.all { file("src/main/jniLibs/arm64-v8a/$it").isFile }
        buildConfigField("boolean", "HAS_ABBOTT_LIBS", jniLibsPresent(
            "libDataProcessing.so", "libSecureKeyBoxJava.so", "libMathRuntimeChecks.so").toString())
        buildConfigField("boolean", "HAS_SIBIONICS_LIBS", jniLibsPresent(
            "libnative-algorithm-jni-v110.so", "libnative-algorithm-jni-v112.so",
            "libnative-algorithm-v1_1_0.so", "libnative-algorithm-v1_1_2.so",
            "libnative-encrypy-decrypt-v110.so", "libnative-sensitivity-v110.so",
            "libnative-struct2json.so").toString())

        // Módulo nativo da ponte Abbott (fake JavaVM/JNIEnv p/ inicializar o WhiteCryption SKB).
        // arm64-v8a: todas as bibliotecas (sensores Sibionics e Libre 2). armeabi-v7a: só uma
        // biblioteca de marcação (ver CMakeLists.txt) — o bastante para o APK instalar em aparelho
        // 32 bits, como o Moto Z2 Play do seguidor (L28 do redesign visual). Lá os sensores nativos
        // ficam indisponíveis (NativeSensorSupport); aparelhos arm64 continuam usando arm64-v8a.
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "armeabi-v7a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path    = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    ndkVersion = "27.0.12077973"

    signingConfigs {
        create("release") {
            val keystorePath = localProps.getProperty("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile     = file(keystorePath)
                storePassword = localProps.getProperty("KEYSTORE_PASSWORD")
                keyAlias      = localProps.getProperty("KEY_ALIAS")
                keyPassword   = localProps.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // libdata-handle-lib.so passa um ponteiro interno Sibionics (tag "IBIS"/0x49424953)
            // onde o ART espera um jbyteArray válido. Com android:debuggable=true, o CheckJNI
            // ativo mata o processo com SIGABRT ao detectar a referência inválida.
            // isDebuggable=false desativa o CheckJNI do ART; o .so funciona corretamente
            // sem verificação, como em qualquer build de produção.
            // As libs da Abbott agora carregam via dlopen no nosso C++. A 2.5.2 é usada em modo DYNLINK
            // com o JNIEnv real (bridge) + subenv fabricado (processStream) — SEM ptrace/debugclone.
            // debuggable=false: o anti-tamper do processStream aborta (só chama Process.myPid e sai) quando
            // detecta o processo depurável. Sem ptrace, não precisamos mais de debuggable=true.
            isDebuggable    = false
            isJniDebuggable = false
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Aplica signingConfig apenas se local.properties estiver configurado
            if (localProps.containsKey("KEYSTORE_PATH")) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose     = true
        buildConfig = true   // necessário para BuildConfig.DEBUG em AGP 8+
    }

    // Harness visual (Fase 0 do redesign): testes JVM com Robolectric precisam dos resources do
    // app; heap maior porque cada captura de tela inteira aloca bitmaps grandes.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                // -Pgluview.visualaudit.dump=true grava as imagens dos testes do harness em build/visual-audit/
                it.systemProperty("gluview.visualaudit.dump",
                    (project.findProperty("gluview.visualaudit.dump") ?: "false").toString())
                // -Pgluview.visualaudit.updateBaseline=true regrava a linha de base do check-strings
                it.systemProperty("gluview.visualaudit.updateBaseline",
                    (project.findProperty("gluview.visualaudit.updateBaseline") ?: "false").toString())
                // -Pgluview.visualaudit.screens=true liga as capturas de tela (lentas);
                // -Pgluview.visualaudit.run=<nome> escolhe a pasta (ex.: antes, fase3)
                it.systemProperty("gluview.visualaudit.screens",
                    (project.findProperty("gluview.visualaudit.screens") ?: "false").toString())
                it.systemProperty("gluview.visualaudit.run",
                    (project.findProperty("gluview.visualaudit.run") ?: "local").toString())
            }
        }
    }

    // As .so do Sibionics foram compiladas com alinhamento de 4 KB (não 16 KB).
    // useLegacyPackaging=true extrai as .so para disco na instalação em vez de
    // carregá-las direto do APK zip, contornando o check de 16 KB do Android 15+.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libDataProcessing.so"
            keepDebugSymbols += "**/libSecureKeyBoxJava.so"
            keepDebugSymbols += "**/libMathRuntimeChecks.so"
            // NÃO fazer strip da libDataProcessing.so: ela tem o bloco de assinatura da Abbott
            // ANEXADO após o ELF (anti-tamper do processStream, ver libre2_hooks.cpp). O strip
            // reescreveria o ELF e apagaria esse trailer. Idem SecureKeyBox/MathRuntimeChecks.
        }
    }
}

// E8 do plano OTA: o Room exporta o esquema do banco para app/schemas (versionado), base para
// testar migrações futuras com MigrationTestHelper.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Lifecycle
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // Security — EncryptedSharedPreferences pros tokens de sessão (achado #3, auditoria 2026-07-09)
    implementation(libs.androidx.security.crypto)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)


    // SignalR
    implementation(libs.signalr)

    // S31 — Glance widget
    implementation(libs.androidx.glance.appwidget)

    // WorkManager — watchdog para reiniciar o serviço se morto pelo SO
    implementation(libs.androidx.work.runtime.ktx)

    // S33 — Biometric + AppCompat
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.appcompat)

    // S27 — Android Auto (projeção no carro)
    implementation(libs.androidx.car.app)

    // F8 Mi Band — RxAndroidBle real (transporte BLE do MiBandUploader)
    implementation(libs.rxandroidble)
    implementation(libs.rxjava2)
    // RxJava 3: só para instalar o tratador global de erros (o SignalR usa RxJava 3 — L29).
    implementation(libs.rxjava3)
    implementation(libs.kotlinx.coroutines.rx2)

    // T85 — Passkeys/WebAuthn (atrás da flag passkeysEnabled)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)

    // Scanner DataMatrix/QR para embalagem do sensor Sibionics.
    // Google Code Scanner (ML Kit): detecção rápida e robusta de DataMatrix com
    // auto-zoom/auto-foco e UI do sistema. Muito superior ao ZXing para códigos 2D densos.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // Testes unitários (JVM puro) — cobre a cripto determinística do Libre 2.
    testImplementation("junit:junit:4.13.2")
    // org.json real (não o stub do android.jar, que lança RuntimeException em teste JVM puro sem
    // Robolectric) — necessário pra testar WatchFaceJson.encode/decodeComponents (Fase 5 do plano
    // 06_watchfaces_v2_arquitetura.md: round-trip de compatibleWearableIds via WatchFaceEntity).
    testImplementation("org.json:json:20240303")

    // Harness visual (Fase 0 do redesign) — só teste/debug, nada entra no APK de release.
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
