// Ponte nativa para a libDataProcessing.so da Abbott (LibreLink 2.5.2), na forma FIEL ao Juggluco
// (modo DYNLINK: dlopen + dlsym dos símbolos JNI exportados, chamados com o JNIEnv real).
//
// A 2.5.2 EXPORTA todas as funções (initialize, P1, P2, getProductFamily, getEnableStreamingPayload,
// getStreamingUnlockPayload, processScan, processStream…) e NÃO tem JNI_OnLoad — então não é preciso
// fake-VM nem derrotar RegisterNatives. Depende só de libc/libm/libdl/liblog (nenhuma SKB no NEEDED).
//
// FASE 1 (bring-up): carrega a lib, roda a sequência de init legada (initialize →
// dpSetMaxAlgorithmVersion) e expõe getProductFamily para o teste de aceite. As demais operações
// (V1/V2, processTooth, etc.) entram depois que o getProductFamily validar a abordagem.

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>

#define LOG_TAG "LIBRE2_CPP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Constantes do Juggluco (libre2.cpp): parsertype fixo e token de 72 bytes validado pela lib.
static const jint PARSERTYPE = 1095774808;
static const uint8_t TOKEN[72] = {
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x27,0x2B,0xCD,0x3B,0xF3,0xCC,0xA0,0xB8,
    0x56,0xCE,0x96,0x1F,0xDB,0xA5,0x6B,0xB6,0x17,0xB2,0x1B,0xD6,0x57,0x0D,0xF3,0x5F,
    0x00,0x6D,0x4C,0xD4,0x62,0x66,0x26,0x54,0x1F,0xF9,0x5C,0x87,0xAE,0x63,0xD9,0x7F,
    0x53,0x6E,0xB6,0x80,0xE7,0x71,0x2E,0x59,0xCF,0xE4,0x50,0xC0,0xC8,0x53,0x1A,0x33,
    0x61,0xE2,0xDB,0x32,0xF2,0x2A,0xC1,0xB6 };

// Assinaturas das funções exportadas (Juggluco libre2.cpp/g.cpp, caminho DYNLINK).
typedef void       (*initialize_t)(JNIEnv*, jobject, jobject);
typedef void       (*dpSetMax_t)(JNIEnv*, jobject, jint, jbyteArray);
typedef jint       (*getProductFamily_t)(JNIEnv*, jobject, jint, jbyteArray, jbyteArray);
typedef jint       (*P1_t)(JNIEnv*, jobject, jint, jint, jbyteArray, jbyteArray, jbyteArray);
typedef jbyteArray (*P2_t)(JNIEnv*, jobject, jint, jint, jbyteArray, jbyteArray, jbyteArray);

static void* g_lib = nullptr;
static initialize_t       p_initialize = nullptr;
static dpSetMax_t         p_dpSetMax = nullptr;
static getProductFamily_t p_getProductFamily = nullptr;
static P1_t               p_P1 = nullptr;
static P2_t               p_P2 = nullptr;
static bool g_inited = false;

// Exposto ao libre2_stream.cpp (processStream resolve seus símbolos deste mesmo handle).
extern "C" void* libre2_lib() { return g_lib; }

#define ABBSYM "Java_com_abbottdiabetescare_flashglucose_sensorabstractionservice_dataprocessing_DataProcessingNative_"

static jbyteArray makeToken(JNIEnv* env) {
    jbyteArray a = env->NewByteArray(72);
    env->SetByteArrayRegion(a, 0, 72, reinterpret_cast<const jbyte*>(TOKEN));
    return a;
}

static void clearPending(JNIEnv* env, const char* where) {
    if (env->ExceptionCheck()) {
        LOGE("exceção Java pendente após %s — limpando", where);
        env->ExceptionClear();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeAbbottInit(JNIEnv* env, jobject thiz) {
    if (g_inited) return JNI_TRUE;
    if (!g_lib) {
        g_lib = dlopen("libDataProcessing.so", RTLD_NOW);
        if (!g_lib) { LOGE("dlopen libDataProcessing.so FALHOU: %s", dlerror()); return JNI_FALSE; }
        LOGI("dlopen libDataProcessing.so OK (LibreLink 2.5.2)");
        // Interpõe os hooks (fork/pipe/waitpid/execv/…) na lib, como o Juggluco faz via SONAME liblog→libg.
        // Necessário para o anti-tamper do processStream (ver libre2_hooks.cpp).
        extern void libre2_install_hooks();
        libre2_install_hooks();
    }
    p_initialize       = reinterpret_cast<initialize_t>(dlsym(g_lib, ABBSYM "initialize"));
    p_dpSetMax         = reinterpret_cast<dpSetMax_t>(dlsym(g_lib, ABBSYM "dpSetMaxAlgorithmVersion"));
    p_getProductFamily = reinterpret_cast<getProductFamily_t>(dlsym(g_lib, ABBSYM "getProductFamily"));
    p_P1               = reinterpret_cast<P1_t>(dlsym(g_lib, ABBSYM "P1"));
    p_P2               = reinterpret_cast<P2_t>(dlsym(g_lib, ABBSYM "P2"));
    LOGI("dlsym initialize=%p dpSetMax=%p getProductFamily=%p P1=%p P2=%p",
         (void*)p_initialize, (void*)p_dpSetMax, (void*)p_getProductFamily, (void*)p_P1, (void*)p_P2);
    if (!p_initialize || !p_getProductFamily) { LOGE("símbolos essenciais ausentes"); return JNI_FALSE; }

    // Sequência de init legada (Juggluco doabbottinit): initialize(env, thiz, thiz) →
    // dpSetMaxAlgorithmVersion(env, thiz, 3, token). Passamos o thiz do Libre2Native (jobject válido).
    p_initialize(env, thiz, thiz);
    clearPending(env, "initialize");
    LOGI("initialize() OK");
    if (p_dpSetMax) {
        p_dpSetMax(env, thiz, 3, makeToken(env));
        clearPending(env, "dpSetMaxAlgorithmVersion");
        LOGI("dpSetMaxAlgorithmVersion(3) OK");
    }
    // Init do subsistema P1/rootcheck (Juggluco doabbottinit, libre2.cpp:946-953): quando P1 existe,
    // chama P1(0,0,null,null,token) uma vez. Esta chamada ARMA/inicializa a checagem de tamper interna;
    // SEM ela, o processStream chama Process.myPid() e aborta (res=0, sem produzir resultado).
    if (p_P1) {
        jint r = p_P1(env, thiz, 0, 0, nullptr, nullptr, makeToken(env));
        clearPending(env, "P1(0,0,null,null)");
        LOGI("P1(0,0,null,null) = %d (init rootcheck)", r);
    }
    g_inited = true;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeProductFamily(JNIEnv* env, jobject thiz, jbyteArray patchInfo) {
    if (!p_getProductFamily) return -1;
    jint fam = p_getProductFamily(env, thiz, PARSERTYPE, patchInfo, makeToken(env));
    clearPending(env, "getProductFamily");
    LOGI("getProductFamily() = %d", fam);
    return fam;
}

// V1(op,i2,a,b) → P1(env, thiz, op, i2, a, b, token). Primitiva de cripto (auth BLE, v1check).
extern "C" JNIEXPORT jint JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeV1(JNIEnv* env, jobject thiz, jint op, jint i2, jbyteArray a, jbyteArray b) {
    if (!p_P1) return -1;
    jint r = p_P1(env, thiz, op, i2, a, b, makeToken(env));
    clearPending(env, "P1");
    return r;
}

// V2(op,i2,a,b) → P2(...). Primitiva de cripto (auth BLE, decrypt do pacote V2(773)).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeV2(JNIEnv* env, jobject thiz, jint op, jint i2, jbyteArray a, jbyteArray b) {
    if (!p_P2) return nullptr;
    jbyteArray r = p_P2(env, thiz, op, i2, a, b, makeToken(env));
    clearPending(env, "P2");
    return r;
}

// Estado de sessão (USenabledStreaming/sensorIdent/streamingAuthData/deviceAddress) e processTooth
// vivem em libre2_stream.cpp (junto do subenv/processStream).
