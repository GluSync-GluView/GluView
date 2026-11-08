import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fase 4 do plano Projeto DM1/GluView/14_wearos_plano_execucao_emulador.md (versão enxuta decidida
// pelo Felipe em 08/11/2026): o CONTRATO entre o GluView do celular e o do relógio Wear OS — o
// formato da leitura enviada pelo Data Layer e os nomes dos caminhos. Kotlin puro, sem Android nem
// Google Play Services: o :app e o futuro :wear convertem para DataMap cada um do seu lado, e as
// regras de saúde (dado velho nunca aparece como atual) ficam testadas aqui, num lugar só.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
