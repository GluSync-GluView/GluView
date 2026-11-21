cmake_minimum_required(VERSION 3.22.1)
project(gluviewlibre3)

# Ponte nativa FIEL ao Juggluco (modo DYNLINK) para a libDataProcessing.so da Abbott — versão
# LibreLink 2.5.2, que EXPORTA os símbolos JNI (initialize/P1/P2/getProductFamily/…) e não tem
# JNI_OnLoad. Basta dlopen + dlsym + chamar com o JNIEnv real. Sem fake-VM, sem RegisterNatives.
#
# Libre 3 adiciona:
# - loadlibs.cpp: JNI entry points (libre3BeginSecurityHandshake, libre3SelectAppKeyAndSavedAuthorization,
#   libre3AcceptPatchCertificate, libre3CreateEphemeralPublicKey, libre3DeriveAuthorizationRoot,
#   libre3EncryptChallengeReply, libre3DecryptChallengeResponse, libre3ExportChallengeContext,
#   libre3ExportSavedAuthorization, libre3EmulatorDecryptReply, libre3EmulatorEncryptResponse)
# - nfc.cpp: NFC activation (lingoPatchSecurityVersion, getLibre3secs, interpret3NFC2, startTimeIDsum)
# - bluetooth.cpp: BLE communication (getLingoSecurityVersion, setLibre3kAuth, getLibre3kAuth,
#   getLibre3Sensorptr, saveLibre3MinuteL, saveLingoMinuteL, saveLibre3fastData, saveLibre3History,
#   saveLingoHistory, saveLingoFastData, libre3processpatchstatus, libre3EventLog, libre3ControlHistory,
#   libre3ClinicalControl, lingoControlHistory, lingoClinicalControl, libre3EventLogControl)
# - process/: whitebox crypto, authorization (fresh/saved auth, kAuth 149B)
# (21/11/2026: só loadlibs.cpp + process/ entram; ver o comentário em add_library abaixo)

# Aparelho 32 bits (armeabi-v7a): a ponte depende de ganchos arm64 e as bibliotecas da Abbott
# só existem em arm64 — o app não oferece esses sensores ali (NativeSensorSupport).
# Só uma biblioteca de marcação: garante a pasta lib/armeabi-v7a mesmo que as dependências
# androidx deixem de trazer .so ali.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    add_library(gluviewabimarker SHARED abi_marker.c)
    return()
endif()

# Collect all process/ C files
file(GLOB L3_PROCESS_SOURCES "process/*.c")

# Auditoria 21/11/2026: bluetooth.cpp e nfc.cpp do Juggluco ficam FORA. Os dois estavam inteiros
# dentro de `#ifdef LIBRE3` (nunca definido aqui), ou seja, compilavam para nada, e dependem do
# banco de sensores do Juggluco (Sensoren/SensorGlucoseData/sendKAuth), que o GluView não tem.
# A leitura dos pacotes, os comandos e a ativação NFC foram portados para Kotlin
# (data/libre3/Libre3Protocol.kt, com testes); aqui fica só a criptografia da autorização.
add_library(gluviewlibre3 SHARED
    loadlibs.cpp
    ${L3_PROCESS_SOURCES}
)

set_target_properties(gluviewlibre3 PROPERTIES CXX_STANDARD 20 CXX_STANDARD_REQUIRED ON)
set_target_properties(gluviewlibre3 PROPERTIES C_STANDARD 11 C_STANDARD_REQUIRED ON)

find_library(log-lib log)
target_link_libraries(gluviewlibre3 ${log-lib} dl)

# Process/ files use designated initializers and "empty" returns (trampolines) — silence warnings to match Juggluco.
# Define L3_EXTERNAL_ENTROPY_ONLY to avoid OpenSSL header dependency (entropy provided at runtime via dlsym).
target_compile_options(gluviewlibre3 PRIVATE -O2 -Wno-return-type -Wno-writable-strings -DLOGSUB -DNOLOG -DL3_EXTERNAL_ENTROPY_ONLY)

# Include directories for shared headers
# Add parent directory (cpp/) so that #include "share/logs.hpp" resolves to cpp/share/logs.hpp
# Also add current directory (libre3/) for config.h
# Add share directory for fromjava.h, hexstr.hpp, etc.
target_include_directories(gluviewlibre3 PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/..
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../share
)