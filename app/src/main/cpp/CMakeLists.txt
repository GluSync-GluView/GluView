cmake_minimum_required(VERSION 3.22.1)
project(gluviewlibre2)

# Ponte nativa FIEL ao Juggluco (modo DYNLINK) para a libDataProcessing.so da Abbott — versão
# LibreLink 2.5.2, que EXPORTA os símbolos JNI (initialize/P1/P2/getProductFamily/…) e não tem
# JNI_OnLoad. Basta dlopen + dlsym + chamar com o JNIEnv real. Sem fake-VM, sem RegisterNatives.
#
# Fase 1 (aqui): bring-up mínimo — carrega a lib, chama initialize + dpSetMaxAlgorithmVersion e
# expõe getProductFamily para o teste de aceite (deve retornar != 0 para o patchInfo do sensor).

# Aparelho 32 bits (armeabi-v7a, ex.: Moto Z2 Play do seguidor): a ponte depende de ganchos arm64 e
# as bibliotecas da Abbott/Sibionics só existem em arm64 — o app não oferece esses sensores ali
# (NativeSensorSupport). Só uma biblioteca de marcação: garante a pasta lib/armeabi-v7a (e a
# instalação em aparelho 32 bits) mesmo que as dependências androidx deixem de trazer .so ali.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    add_library(gluviewabimarker SHARED abi_marker.c)
    return()
endif()

add_library(gluviewlibre2 SHARED
    libre2_bridge.cpp
    libre2_stream.cpp
    libre2_hooks.cpp
    jnisub.cpp
)

set_target_properties(gluviewlibre2 PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)

find_library(log-lib log)
target_link_libraries(gluviewlibre2 ${log-lib} dl)

# subtrace.c usa designated initializers e retornos "vazios" (trampolins) — silenciar é fiel ao Juggluco.
target_compile_options(gluviewlibre2 PRIVATE -O2 -Wno-return-type -Wno-writable-strings -DLOGSUB)
