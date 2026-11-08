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
        // Só para o leitor de AXML exigido pelo validador do Watch Face Push (plano 16) — o
        // filtro impede que qualquer outra dependência venha do JitPack.
        maven("https://jitpack.io") { content { includeGroup("com.github.xgouchet") } }
    }
}

rootProject.name = "GluView"
include(":app")
include(":compiler-cli")
include(":wear-contract")
include(":wear")
