import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Plano 21 (Projeto DM1/GluView/21_plano_gluview_completo.md), F0.2: núcleo de terapia em Kotlin
// puro, sem Android — perfil, curvas de insulina, IOB/COB, calculadora de bolus, previsão,
// calibração e suavização. Fica isolado para ser testado número a número (oráculo) e reaproveitado
// pela calculadora para caneta (F4) e, no futuro, pela bomba (F10).
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
