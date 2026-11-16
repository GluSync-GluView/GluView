# Avisos e créditos

GluView — Copyright (C) 2026 os autores do GluView.

Este programa é software livre: você pode redistribuí-lo e/ou modificá-lo sob os termos da GNU
General Public License, publicada pela Free Software Foundation, versão 3 da licença ou (a seu
critério) qualquer versão posterior. Ele é distribuído na esperança de ser útil, mas **SEM
NENHUMA GARANTIA**, nem mesmo a garantia implícita de COMERCIALIZAÇÃO ou ADEQUAÇÃO A UM
PROPÓSITO ESPECÍFICO. Veja o arquivo [`LICENSE`](LICENSE).

## Código derivado de outros projetos (GPLv3)

| Projeto | O que veio dele | Onde está no GluView |
|---|---|---|
| [Juggluco](https://github.com/j-kaltes/Juggluco) — Jaap Korthals Altes | Ponte nativa para a biblioteca da Abbott (Libre 2 Plus), subenv JNI, processamento do streaming, montagem da biblioteca assinada; protocolo Sibionics GS3 V2 (RC4) e V3 (AES-OFB) e reset GS2 (commit `Common/src/main/cpp/sibionics3/`, `handleData.cpp`) | `app/src/main/cpp/` (`jnisub.cpp`, `libre2_stream.cpp`, `libre2_bridge.cpp`, `libre2_hooks.cpp`), `tools/extract_native_libs.py`, `app/src/main/java/com/gluview/app/data/sibionics/` (`SibionicsGS3Protocol.kt`, `SibionicsV112Protocol.kt`) |
| [xDrip+](https://github.com/NightscoutFoundation/xDrip) — Nightscout Foundation e colaboradores | Protocolo e handshake do Dexcom G6; fluxo do Dexcom G7 (autenticação, registro 0x4E, histórico 0x59, estados do sensor) portado de `g5model/Ob1G5StateMachine`, `EGlucoseRxMessage2`, `BackFillTxMessage2`, `BackFillStream`, `CalibrationState` (commit `7c14016d5f1c05e4b1687e955c644431a496e9a2`) | `app/src/main/java/com/gluview/app/data/dexcom/` |
| [libkeks do xDrip+](https://github.com/NightscoutFoundation/xDrip/tree/master/libkeks) — JamOrHam | Autenticação EC-JPAKE do Dexcom G7 (cópia sem alterações de lógica; tirados só o `Umbilical` e o `@Keep`), mesmo commit acima; interface `IPluginDA` do módulo `ipluginda` | `keks/` |
| [WatchDrip](https://github.com/bigdigital/watchdrip) — bigdigital | Gerador e codificador de visor da Mi Band 5/6 | `app/src/main/java/com/gluview/app/watchface/miband/`, `compiler-cli/` |

Projetos usados como referência de protocolo, sem cópia de código: Gadgetbridge (Mi Band),
DiaBLE (identificação de sensores Libre).

## Outros componentes

- Fontes Roboto (`app/src/main/assets/fonts/`): Apache License 2.0.
- Bibliotecas de terceiros do build (AndroidX, Retrofit, OkHttp, SignalR, RxAndroidBle etc.): cada
  uma sob a própria licença, listadas em `gradle/libs.versions.toml`.

## Bibliotecas proprietárias e marcas

As bibliotecas nativas da Abbott e da Sibionics **não fazem parte deste repositório nem desta
licença**: pertencem aos respectivos fabricantes. Quem compila as extrai dos aplicativos oficiais
(ver `docs/build-native-libs.md`). Os pacotes `com.abbottdiabetescare…`, `com.algorithm…` e
`com.no.sisense…` existem só porque a interface JNI dessas bibliotecas exige esses nomes.

FreeStyle Libre e LibreLink são marcas da Abbott. SIBIONICS é marca da Sibionics. Dexcom, Accu-Chek,
Mi Band e demais nomes pertencem aos seus donos. O GluView não é afiliado, patrocinado nem aprovado
por nenhum desses fabricantes.

## Uso do nome GluView

A licença cobre o código, não o nome nem o ícone. Versões modificadas distribuídas por terceiros
devem usar **outro nome, outro ícone e outro nome de pacote** (o `com.gluview.app` é do GluView
oficial e está sendo registrado na verificação de desenvolvedor do Google; no build, por exemplo,
`-Pgluview.applicationIdSuffix=.meufork`) e deixar claro que não são o GluView oficial. O GluView é um
projeto sem fins lucrativos; o nome não pode ser usado para vender o app ou sugerir uma versão
oficial paga.
