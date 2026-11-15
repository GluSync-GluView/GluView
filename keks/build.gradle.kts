// PT-3 (Dexcom G7): pareamento EC-JPAKE do G7 — cópia fiel da libkeks do xDrip
// (NightscoutFoundation/xDrip, pasta libkeks, autor JamOrHam, GPLv3; commit de referência
// 7c14016d5f1c05e4b1687e955c644431a496e9a2). Só tirado o Umbilical (harness de cobertura do xDrip,
// dependia do androidx) e o @Keep da interface. Java puro, testável na JVM. Ver NOTICE.md.
plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // A curva P-256 e a aritmética de pontos vêm do BouncyCastle (o xDrip usa bcpkix 1.68).
    implementation("org.bouncycastle:bcprov-jdk18on:1.80.2")
    // O código original usa Lombok (val, @Getter...): só em tempo de compilação, não vai no APK.
    compileOnly("org.projectlombok:lombok:1.18.42")
    annotationProcessor("org.projectlombok:lombok:1.18.42")
    testCompileOnly("org.projectlombok:lombok:1.18.42")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.42")
    testImplementation("junit:junit:4.13.2")
}

// O lombok.config muda o bytecode (sem @lombok.Generated, que o R8 recusaria): é entrada da compilação.
tasks.withType<JavaCompile>().configureEach { inputs.file("lombok.config") }
