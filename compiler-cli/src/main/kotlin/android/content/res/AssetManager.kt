package android.content.res

import java.io.InputStream

/**
 * Shim de [android.content.res.AssetManager]. No app real os assets vêm do APK; aqui vêm do
 * classpath do compiler-cli (`compiler-cli/src/main/resources/`, mesma árvore de caminhos que
 * `assets/` no app — os arquivos .bin/.json/.ttf são copiados pra lá, ver Fase 6 do plano
 * 06_watchfaces_v2_arquitetura.md).
 */
class AssetManager {
    fun open(path: String): InputStream =
        javaClass.classLoader.getResourceAsStream(path)
            ?: throw java.io.FileNotFoundException("Asset não encontrado no classpath do compiler-cli: $path")
}
