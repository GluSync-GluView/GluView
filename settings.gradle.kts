pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // SÃ³ para o leitor de AXML exigido pelo validador do Watch Face Push (plano 16) â€” o
        // filtro impede que qualquer outra dependÃªncia venha do JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.xgouchet") } }
    }
}

rootProject.name = "GluView"
include(":app")
include(":compiler-cli")
include(":wear-contract")
include(":wear")
include(":keks")
include(":therapy-core")
include(":mirror-core")
include(":wearables-xiaomi")
project(":wearables-xiaomi").projectDir = file("wearables-xiaomi")

