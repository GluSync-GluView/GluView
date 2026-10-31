plugins {
    alias(libs.plugins.kotlin.jvm)
    application
    id("com.gradleup.shadow") version "8.3.5"
}

// Fase 6 do plano 06_watchfaces_v2_arquitetura.md — compilador "oficial" da loja, roda como
// processo Java companheiro do GluSync (.NET). NÃO usa o Android Gradle Plugin: reaproveita as
// MESMAS fontes do :app (WatchFaceModels/ComponentKind/MiBandCanvasCompiler/MiBandPackageBuilder/
// firmware/GlucoseGraphRenderer — ver includes abaixo) compiladas contra um shim leve de
// android.graphics/android.content/android.util/android.os sobre java.awt (src/main/kotlin/android/*
// neste módulo), em vez do android.jar real. Zero duplicação de lógica de negócio; só a camada de
// desenho de baixo nível troca de motor gráfico (decisão registrada — Robolectric/Canvas real
// exigiria libs nativas por SO, inviável num container simples do Render).
sourceSets {
    main {
        kotlin {
            // include() é um filtro GLOBAL sobre todo srcDir deste source set (inclusive o default
            // src/main/kotlin, onde moram o shim android.*/os módulo próprio deste módulo) — por
            // isso os caminhos do shim entram na mesma lista dos arquivos compartilhados do :app.
            srcDir("../app/src/main/java")
            include(
                "android/**",
                "com/gluview/app/compilercli/**",
                "com/gluview/app/ui/screen/watchface/WatchFaceRuntimeData.kt",
                "com/gluview/app/watchface/WatchFaceModels.kt",
                "com/gluview/app/watchface/ComponentKind.kt",
                "com/gluview/app/watchface/WearableCatalog.kt",
                "com/gluview/app/watchface/WearableDefinition.kt",
                "com/gluview/app/watchface/compiler/WatchfaceCompiler.kt",
                "com/gluview/app/watchface/miband/WatchFaceDesignGenerator.kt",
                "com/gluview/app/watchface/miband/MiBandCanvasCompiler.kt",
                "com/gluview/app/watchface/miband/MiBandPackageBuilder.kt",
                "com/gluview/app/util/GlucoseGraphRenderer.kt",
                "com/gluview/app/util/GlucoseUnit.kt",
                "com/gluview/app/util/TimeFormat.kt",
                "com/gluview/app/domain/model/GlucoseReading.kt",
                "com/gluview/app/domain/model/GlucoseZone.kt",
                "com/gluview/app/data/sibionics/SibionicsSensorStatus.kt"
            )
        }
        java {
            srcDir("../app/src/main/java")
            include("com/gluview/app/watchface/miband/firmware/**")
        }
        resources {
            // Templates .bin/config.json/canvas.png + fontes — cópia dos assets do :app (Fase 6),
            // ver README.md deste módulo pro porquê de não compartilhar a pasta assets/ direto.
            srcDir("src/main/assets-copy")
        }
    }
}

dependencies {
    implementation("org.json:json:20240303")
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.gluview.app.compilercli.MainKt")
}

tasks.test {
    useJUnitPlatform()
}
