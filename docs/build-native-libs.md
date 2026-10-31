# Bibliotecas nativas dos sensores

O FreeStyle Libre 2 Plus e o Sibionics só funcionam com bibliotecas proprietárias dos fabricantes.
Elas **não estão no repositório** (não podem ser redistribuídas). Sem elas o GluView compila e
funciona, mas esses dois sensores aparecem como indisponíveis.

Para incluí-las no seu build, baixe você mesmo os APKs oficiais abaixo (versão exata, arquitetura
arm64-v8a) e rode o script de extração.

| Sensor | APK necessário | Bibliotecas |
|---|---|---|
| FreeStyle Libre 2 Plus | FreeStyle LibreLink **2.5.2** (pacote `com.freestylelibre.app.cn`, versionCode 6349) | `libDataProcessing.so`, `libSecureKeyBoxJava.so`, `libMathRuntimeChecks.so` |
| Sibionics | SIBIONICS / SiJoy **01.01.00.00** (pacote `com.sisensing.sijoy`) | 7 × `libnative-*.so` |

```bash
python tools/extract_native_libs.py --librelink LibreLink-2.5.2.apk --sibionics SiJoy-01.01.00.00.apk
```

O script grava em `app/src/main/jniLibs/arm64-v8a/` (pasta ignorada pelo Git) e confere o SHA-256
de cada arquivo. Com outra versão do APK ele para e não grava nada daquele grupo: uma biblioteca
diferente quebraria a leitura do sensor sem nenhum aviso.

## Por que a `libDataProcessing.so` não é copiada como está

A biblioteca da Abbott confere, em tempo de execução, a assinatura do APK em que está. Como o
Juggluco faz, o script grava a biblioteca original seguida do bloco de assinatura do próprio APK
oficial e de um final fixo de 101 bytes. Por isso o build **não** pode remover símbolos dessas
bibliotecas (`keepDebugSymbols` em `app/build.gradle.kts`).

## Limitações

- Só arm64-v8a: em aparelhos 32 bits os sensores nativos ficam indisponíveis.
- A licença GPLv3 do GluView não se aplica a essas bibliotecas, que continuam pertencendo aos
  fabricantes. Use-as só com os seus próprios sensores.
