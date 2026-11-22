import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plano 21, F0.2/F2: formato das mensagens, cifra e regras do espelhamento celular↔celular sem
// servidor (LAN, internet P2P, Bluetooth). Kotlin puro: as regras de saúde (leitura velha nunca
// vira atual, sem duplicar, ordem) ficam testadas aqui, num lugar só, igual ao :wear-contract.
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

// Teste do STUN contra os servidores públicos só quando pedido: gradlew :mirror-core:test -Dstun.live=true
tasks.test { systemProperty("stun.live", System.getProperty("stun.live") ?: "false") }
