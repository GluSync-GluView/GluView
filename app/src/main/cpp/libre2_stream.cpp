// processStream — port FIEL do Juggluco (libre2.cpp processBlue / libre2stream::processTooth), GPLv3.
// Converte o pacote BLE já decifrado (V2 773) em glicemia mg/dL, chamando o `processStream` da
// libDataProcessing.so (2.5.2) através do subenv fabricado (ver sub_base.hpp / jnisub.cpp).
//
// A 2.5.2 exporta `..._processStream` com a assinatura processStream2_7 (HASP1: leva `info`/patchInfo,
// sem `isstreaming`). Estado do algoritmo (COMP/ATTE/MESS) vive num scanstate persistente por sessão.

#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <time.h>
#include <math.h>
#include <stdio.h>
#include <cstring>
#include <mutex>
#include <vector>
#include "sub_base.hpp"

#define LOG_TAG "LIBRE2_CPP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Do bridge (libre2_bridge.cpp): handle da libDataProcessing.so já aberta e inicializada.
extern "C" void* libre2_lib();

// Classes de config falsas (definidas em jnisub.cpp) que o processStream consulta.
extern jclasser AlarmC, NonActiona, GluRange, attconf;

// Constantes do Juggluco (libre2.cpp / libre2.hpp).
static const jint PARSERTYPE = 1095774808;
static const time_t BASESECS = 1262304000L;   // 2010-01-01
static const uint8_t TOKEN[72] = {
    0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x27,0x2B,0xCD,0x3B,0xF3,0xCC,0xA0,0xB8,
    0x56,0xCE,0x96,0x1F,0xDB,0xA5,0x6B,0xB6,0x17,0xB2,0x1B,0xD6,0x57,0x0D,0xF3,0x5F,
    0x00,0x6D,0x4C,0xD4,0x62,0x66,0x26,0x54,0x1F,0xF9,0x5C,0x87,0xAE,0x63,0xD9,0x7F,
    0x53,0x6E,0xB6,0x80,0xE7,0x71,0x2E,0x59,0xCF,0xE4,0x50,0xC0,0xC8,0x53,0x1A,0x33,
    0x61,0xE2,0xDB,0x32,0xF2,0x2A,0xC1,0xB6 };

// Assinaturas dos símbolos exportados usados via subenv (data_t* como jbyteArray).
typedef jobject (*processStream_t)(JNIEnv*, jobject, jint /*parsertype*/,
    jobject /*alarm*/, jobject /*nonaction*/, jobject /*glrange*/, jobject /*atten*/,
    jbyteArray /*ident*/, jbyteArray /*info*/, jbyte /*person*/, jbyteArray /*bluedata*/,
    jint /*startsincebase*/, jint /*nusincebase*/, jint /*warmup*/, jint /*wear*/,
    jbyteArray /*comp*/, jbyteArray /*atte*/, jbyteArray /*mess*/,
    jobject /*outstart*/, jobject /*endtime*/, jobject /*confinsert*/, jobject /*removed*/,
    jobject /*compo*/, jobject /*attenu*/, jobject /*messstate*/, jobject /*algres*/,
    jbyteArray /*token*/);
// libDataProcessing.so 2.5.2 exports the legacy processScan2_7 entry point.
// This is the same direct-symbol branch selected by Juggluco's linklib() when
// the legacy ABI is available (Common/src/main/cpp/libre2.cpp:240-267).
typedef jobject (*processScan_t)(JNIEnv*, jobject, jint /*parsertype*/,
    jobject /*alarm*/, jobject /*nonaction*/, jobject /*glrange*/, jobject /*atten*/,
    jbyteArray /*ident*/, jbyteArray /*info*/, jbyteArray /*scan*/,
    jint /*startsincebase*/, jint /*lastsincebase*/, jint /*warmup*/, jint /*wear*/,
    jint /*timeoffset*/,
    jbyteArray /*comp*/, jbyteArray /*atte*/, jbyteArray /*mess*/,
    jobject /*starttime*/, jobject /*endtime*/, jobject /*confinsert*/, jobject /*removed*/,
    jobject /*compo*/, jobject /*attenu*/, jobject /*messstate*/, jobject /*algres*/,
    jobject /*patchEvents*/, jobject /*person*/, jbyteArray /*token*/);
typedef jbyteArray (*activationComplete_t)(JNIEnv*, jobject, jbyteArray /*uid*/,
    jint /*startDaysSince2013*/, jint /*startSinceBase*/, jbyteArray /*mess*/, jbyteArray /*token*/);
typedef jboolean (*getPatchTimeValues_t)(JNIEnv*, jobject, jint /*parsertype*/,
    jbyteArray /*info*/, jobject /*warmup*/, jobject /*wear*/, jbyteArray /*token*/);

#define ABBSYM "Java_com_abbottdiabetescare_flashglucose_sensorabstractionservice_dataprocessing_DataProcessingNative_"

static processStream_t      p_processStream = nullptr;
static processScan_t        p_processScan = nullptr;
static activationComplete_t p_activationComplete = nullptr;
static getPatchTimeValues_t p_getPatchTimeValues = nullptr;
static data_t*              g_token = nullptr;

// ── Estado da sessão (dono aqui; o bridge só carrega a lib). ─────────────────────────
static std::mutex g_mtx;
static bool     g_has_session = false;
static uint8_t  g_uid[16];   static jsize g_uid_len = 0;
static uint8_t  g_info[16];  static jsize g_info_len = 0;    // patchInfo
static uint8_t  g_auth[12];  static jsize g_auth_len = 0;
static char     g_mac[18];   static bool  g_has_mac = false;
static jint     g_start_since_base = 0;                      // sensor start (epoch s) - BASESECS
static scanstate* g_state = nullptr;                        // estado do algoritmo entre pacotes
static int      g_last_id = -1;                             // dedup por id de leitura
static std::vector<jlong> g_history_buf;                    // trend/histórico do último processTooth (consumido 1x)

static bool ensureResolved() {
    if (p_processStream && p_processScan && p_activationComplete && p_getPatchTimeValues && g_token) return true;
    void* lib = libre2_lib();
    if (!lib) { LOGE("processStream: libDataProcessing.so não carregada"); return false; }
    if (!p_processStream)
        p_processStream = reinterpret_cast<processStream_t>(dlsym(lib, ABBSYM "processStream"));
    if (!p_processScan)
        p_processScan = reinterpret_cast<processScan_t>(dlsym(lib, ABBSYM "processScan"));
    if (!p_activationComplete)
        p_activationComplete = reinterpret_cast<activationComplete_t>(dlsym(lib, ABBSYM "activationComplete"));
    if (!p_getPatchTimeValues)
        p_getPatchTimeValues = reinterpret_cast<getPatchTimeValues_t>(dlsym(lib, ABBSYM "getPatchTimeValues"));
    if (!g_token) {
        g_token = data_t::newex(72);
        memcpy(g_token->buf, TOKEN, 72);
    }
    LOGI("resolve processStream=%p processScan=%p activationComplete=%p getPatchTimeValues=%p",
         (void*)p_processStream, (void*)p_processScan, (void*)p_activationComplete,
         (void*)p_getPatchTimeValues);
    return p_processStream != nullptr;
}

// jbyteArray real (do Java) → data_t (para o subenv). Liberar com data_t::deleteex.
static data_t* jToData(JNIEnv* env, jbyteArray arr) {
    if (!arr) return nullptr;
    jsize n = env->GetArrayLength(arr);
    data_t* d = data_t::newex(n);
    env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(d->buf));
    return d;
}
// bytes de sessão → data_t.
static data_t* rawToData(const uint8_t* b, jsize n) {
    if (n <= 0) return nullptr;
    data_t* d = data_t::newex(n);
    memcpy(d->buf, b, n);
    return d;
}

// patchtimevalues(info): {warmup, wear} em minutos (Juggluco libre2.cpp:1177).
//
// `wear` é repassado ao processScan/processTooth da libDataProcessing.so e é o que ela usa para
// marcar a leitura como "sensor expirado" (GlucoseNow.getQuality() != 0 → packReading() descarta).
// Confirmado por log real (aparelho de teste, 18/07/2026): com `p_getPatchTimeValues` resolvendo
// normalmente, a lib devolve wear=21600 (15 dias, o valor OFICIAL do Libre 2 Plus) — ou seja, sem
// este override o app pararia de mostrar glicemia exatamente no dia 15. Requisito do produto (ver
// notas internas do projeto / regra já aplicada ao Sibionics): o GluView NUNCA corta leitura por idade do
// sensor — o aviso de "sensor envelhecendo" é só informativo (SensorWarning.AGING), nunca bloqueia.
// Por isso ignoramos deliberadamente o `owear` real devolvido pela lib e usamos o teto do próprio
// tipo que a lib espera (uint16_t minutos) — maior valor representável, ~45,5 dias, evitando truncar
// dentro da lib caso ela também trate `wear` como 16 bits internamente.
static void patchTimeValues(data_t* info, int* warmup, int* wear) {
    *warmup = 60;
    *wear   = 0xFFFF;   // vida ilimitada (ver comentário acima) — NÃO usar o wear oficial de 14/15 dias
    if (!p_getPatchTimeValues) return;
    outobj owarm, owear;
    if (p_getPatchTimeValues(subenv, nullptr, PARSERTYPE, (jbyteArray)info,
                             owarm, owear, (jbyteArray)g_token)) {
        *warmup = (uint16_t)owarm.toint();
        LOGI("patchtimevalues: lib devolveu wear real=%d (ignorado — vida ilimitada por requisito de produto)",
             (uint16_t)owear.toint());
    }
}

static int timezoneOffsetSeconds(const time_t* t) {
    struct tm local{};
    localtime_r(t, &local);
    return (int)(timegm(&local) - *t);
}

static int framAgeMinutes(data_t* scan) {
    if (!scan || scan->size() < 318) return 0;
    const uint8_t* b = scan->data();
    return (int)b[316] | ((int)b[317] << 8);
}

static jlong packReading(const AlgorithmResults* alg) {
    if (!alg) return 0;
    const GlucoseNow& gl = alg->currentglucose();
    const int gluc = gl.getValue();
    const int qual = gl.getQuality();
    if (qual || !gluc) return 0;
    const uint32_t mgL = (uint32_t)(gluc * 10);
    const int16_t rate16 = (int16_t)lroundf(gl.rate() * 1000.0f);
    return ((jlong)((uint16_t)rate16) << 32) | mgL;
}

// Diagnóstico (21/07/2026 — "o sensor manda mesmo os dados desses buracos?"): sempre grava
// [tamanho bruto de alg->history(), quantos passaram valid(), quantos foram adicionados de fato],
// mesmo quando 0 — assim dá pra responder com dado real (log persistido, não só logcat que gira)
// se o próprio `.so` está devolvendo pontos pros buracos curtos ou não.
static int g_last_history_raw = 0;
static int g_last_history_valid = 0;
static char g_last_history_detail[256] = {0};   // "id:qual:valor id:qual:valor ..." de cada ponto bruto

// Extrai o trend/histórico embutido em `alg->history()` (mesma estrutura que o Juggluco usa em
// addStreamHistory/saveSputnik_PG2, Common/src/main/cpp/share/savehistory.cpp) — o pacote BLE já
// decifrado carrega esses pontos, só nunca eram lidos aqui. Timestamp de cada ponto histórico é
// calculado com a MESMA fórmula do Juggluco: `was = nutime - (nuid - id) * 60` (id = minutos desde
// o início do sensor, 1 unidade = 1 minuto). Grava em `g_history_buf` (consumido 1x por
// nativeConsumeHistory) em vez de retornar direto, pra não mudar a assinatura de nativeProcessTooth
// (zero risco pro caminho já validado da leitura atual).
static void packHistory(const AlgorithmResults* alg, uint32_t nutime, int nuid) {
    g_history_buf.clear();
    g_last_history_raw = 0;
    g_last_history_valid = 0;
    g_last_history_detail[0] = '\0';
    if (!alg) return;
    const jniHistory& hist = alg->history();
    const int len = (int)hist.size();
    g_last_history_raw = len;
    char* wp = g_last_history_detail;
    const char* const wend = g_last_history_detail + sizeof(g_last_history_detail) - 1;
    for (int i = 0; i < len; i++) {
        GlucoseValue rawEntry = hist.get(i);   // cópia — sem filtro, pra diagnostico bruto
        const int rawSpace = (int)(wend - wp);
        if (rawSpace > 0) {
            int n = snprintf(wp, rawSpace, "%s[id=%d,q=%d,v=%d]", (i == 0 ? "" : " "),
                              rawEntry.getId(), rawEntry.getQuality(), rawEntry.getValue());
            if (n > 0) wp += (n < rawSpace ? n : rawSpace - 1);
        }
        GlucoseValue entry = hist.get(i);      // cópia (não referência) — valid() não é const
        if (!entry.valid()) continue;
        g_last_history_valid++;
        const int id = entry.getId();
        if (id == nuid) continue;              // é a leitura atual, já tratada por packReading
        const time_t was = (time_t)nutime - (time_t)(nuid - id) * 60;
        const uint32_t mgL = (uint32_t)(entry.getValue() * 10);
        g_history_buf.push_back(((jlong)(uint32_t)was << 32) | mgL);
    }
    LOGI("packHistory: bruto=%d validos=%d adicionados=%zu", g_last_history_raw, g_last_history_valid, g_history_buf.size());
}

static AlgorithmResults* callProcessScan(data_t* ident, data_t* info, data_t* scan,
        scanstate* oldstate, scanstate* newstate, jint startsincebase, uint32_t nutime,
        int warmup, int wear, outobj* starttime, outobj* endtime, outobj* person) {
    if (!p_processScan) return nullptr;

    const jint lastsincebase = (jint)(nutime - BASESECS);
    time_t nutime_t = (time_t)nutime;
    const jint timeoffset = 1000 * timezoneOffsetSeconds(&nutime_t);
    algobj alarm{&AlarmC}, nonAction{&NonActiona}, range{&GluRange}, attenconf{&attconf};
    outobj confinsert, removed, compo, attenu, messstate, outalgres, patchEvents;

    jnidata_t sub = {&envbuf, newstate};
    JNIEnv* subenvp = (JNIEnv*)&sub;

    jobject result = p_processScan(
        subenvp, nullptr, PARSERTYPE,
        alarm, nonAction, range, attenconf,
        (jbyteArray)ident, (jbyteArray)info, (jbyteArray)scan,
        startsincebase, lastsincebase, warmup, wear, timeoffset,
        (jbyteArray)oldstate->get(COMP), (jbyteArray)oldstate->get(ATTE), (jbyteArray)oldstate->get(MESS),
        (jobject)starttime, (jobject)endtime, confinsert, removed,
        compo, attenu, messstate, outalgres,
        patchEvents, (jobject)*person, (jbyteArray)g_token);

    if (auto* patch = (std::vector<PatchEvent*>*)patchEvents.ptr) {
        LOGI("processScan PatchEvent count=%zu", patch->size());
        for (PatchEvent* p : *patch) delete p;
        delete patch;
    }

    AlgorithmResults* alg = (AlgorithmResults*)outalgres.ptr;
    const intptr_t res = reinterpret_cast<intptr_t>(result);
    const int isinserted = reinterpret_cast<intptr_t>(confinsert.ptr);
    const int isremoved = reinterpret_cast<intptr_t>(removed.ptr);
    LOGI("processScan res=%ld inserted=%d removed=%d alg=%p", (long)res, isinserted, isremoved, alg);

    if (alg) {
        alg->setinserted(isinserted);
        alg->setremoved(isremoved);
    }
    if (res != 0) {
        delete alg;
        return nullptr;
    }

    newstate->datpos(FAKE) = oldstate->datpos(FAKE);
    if (compo.ptr)     newstate->setpos(COMP, static_cast<data_t*>(compo.ptr));     else newstate->makenull(COMP);
    if (attenu.ptr)    newstate->setpos(ATTE, static_cast<data_t*>(attenu.ptr));    else newstate->makenull(ATTE);
    if (messstate.ptr) newstate->setpos(MESS, static_cast<data_t*>(messstate.ptr)); else newstate->makenull(MESS);
    return alg;
}

static bool initializeScanState(data_t* ident, data_t* info, data_t* scan, uint32_t nutime,
        int warmup, int wear, jlong* packed, jlong* startEpochSec) {
    scanstate empty;
    scanstate firstState(defaultscanstate);
    outobj person, starttime, endtime;
    AlgorithmResults* alg = callProcessScan(
        ident, info, scan, &empty, &firstState, 0, nutime, warmup, wear, &starttime, &endtime, &person);
    if (!alg) return false;

    *packed = packReading(alg);
    delete alg;

    const jint startminbase = starttime.toint();
    g_start_since_base = startminbase;
    if (startEpochSec) *startEpochSec = (jlong)BASESECS + startminbase;

    scanstate* activated = new scanstate(defaultscanstate);
    jnidata_t sub = {&envbuf, activated};
    JNIEnv* subenvp = (JNIEnv*)&sub;
    constexpr int THREE_YEAR_SECS = 94694400;
    constexpr int SEC_PER_DAY = 60 * 60 * 24;
    data_t* messout = p_activationComplete
        ? reinterpret_cast<data_t*>(p_activationComplete(
            subenvp, nullptr, (jbyteArray)ident,
            (startminbase > THREE_YEAR_SECS ? startminbase - THREE_YEAR_SECS : 0) / SEC_PER_DAY,
            startminbase, (jbyteArray)firstState.get(MESS), (jbyteArray)g_token))
        : nullptr;

    if (messout && messout->length() > 0) {
        activated->makenull(COMP);
        activated->makenull(ATTE);
        activated->setpos(MESS, messout);
    } else {
        activated->reset();
        data_t* messwas = firstState.get(MESS);
        if (!messwas) {
            delete activated;
            return false;
        }
        data_t* copy = activated->alloc(messwas->length());
        memcpy(copy->data(), messwas->data(), messwas->length());
        activated->setpos(MESS, copy);
    }

    delete g_state;
    g_state = activated;
    LOGI("processScan initialized state startSinceBase=%d packed=%lld", g_start_since_base, (long long)*packed);
    return true;
}

// ── USenabledStreaming: grava a sessão do sensor ativo (uid + patchInfo + auth + MAC). ──
extern "C" JNIEXPORT void JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeUsEnabledStreaming(
        JNIEnv* env, jobject, jbyteArray uid, jbyteArray patchInfo, jbyteArray auth, jbyteArray address) {
    std::lock_guard<std::mutex> lock(g_mtx);
    const jsize incoming_uid_len = uid ? env->GetArrayLength(uid) : 0;
    const jsize bounded_uid_len = incoming_uid_len > (jsize)sizeof(g_uid)
        ? (jsize)sizeof(g_uid) : incoming_uid_len;
    bool uid_matches = false;
    if (uid && bounded_uid_len > 0 && g_uid_len == bounded_uid_len) {
        uint8_t incoming_uid[sizeof(g_uid)]{};
        env->GetByteArrayRegion(uid, 0, bounded_uid_len,
                                reinterpret_cast<jbyte*>(incoming_uid));
        uid_matches = memcmp(g_uid, incoming_uid, bounded_uid_len) == 0;
    }
    g_uid_len  = uid       ? env->GetArrayLength(uid)       : 0;
    g_info_len = patchInfo ? env->GetArrayLength(patchInfo) : 0;
    g_auth_len = auth      ? env->GetArrayLength(auth)      : 0;
    if (g_uid_len  > (jsize)sizeof(g_uid))  g_uid_len  = sizeof(g_uid);
    if (g_info_len > (jsize)sizeof(g_info)) g_info_len = sizeof(g_info);
    if (g_auth_len > (jsize)sizeof(g_auth)) g_auth_len = sizeof(g_auth);
    if (uid       && g_uid_len  > 0) env->GetByteArrayRegion(uid,       0, g_uid_len,  reinterpret_cast<jbyte*>(g_uid));
    if (patchInfo && g_info_len > 0) env->GetByteArrayRegion(patchInfo, 0, g_info_len, reinterpret_cast<jbyte*>(g_info));
    if (auth      && g_auth_len > 0) env->GetByteArrayRegion(auth,      0, g_auth_len, reinterpret_cast<jbyte*>(g_auth));

    jsize addr_len = address ? env->GetArrayLength(address) : 0;
    g_has_mac = false;
    if (address && addr_len == 6) {
        uint8_t a[6];
        env->GetByteArrayRegion(address, 0, 6, reinterpret_cast<jbyte*>(a));
        static const char* H = "0123456789ABCDEF";
        char* p = g_mac;
        for (int i = 5; i >= 0; --i) {
            *p++ = H[(a[i] >> 4) & 0xF]; *p++ = H[a[i] & 0xF];
            if (i != 0) *p++ = ':';
        }
        *p = '\0';
        g_has_mac = true;
    }
    // Nova sessão → zera o estado do algoritmo.
    if (!uid_matches) {
        delete g_state; g_state = nullptr;
        g_start_since_base = 0;
        g_last_id = -1;
    }
    g_has_session = g_uid_len > 0;
    LOGI("USenabledStreaming: uid=%dB info=%dB auth=%dB mac=%s", (int)g_uid_len, (int)g_info_len,
         (int)g_auth_len, g_has_mac ? g_mac : "(none)");
}

// Início do sensor (epoch s) → startsincebase. Setado pelo Libre2Source a partir das prefs.
extern "C" JNIEXPORT void JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeSetSensorStart(JNIEnv*, jobject, jlong epochSec) {
    std::lock_guard<std::mutex> lock(g_mtx);
    g_start_since_base = epochSec > 0 ? (jint)(epochSec - BASESECS) : 0;
    LOGI("setSensorStart: startSinceBase=%d", g_start_since_base);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeSensorIdent(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_has_session || g_uid_len == 0) return nullptr;
    jbyteArray r = env->NewByteArray(g_uid_len);
    env->SetByteArrayRegion(r, 0, g_uid_len, reinterpret_cast<const jbyte*>(g_uid));
    return r;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeStreamingAuthData(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_has_session || g_auth_len == 0) return nullptr;
    jbyteArray r = env->NewByteArray(g_auth_len);
    env->SetByteArrayRegion(r, 0, g_auth_len, reinterpret_cast<const jbyte*>(g_auth));
    return r;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeDeviceAddress(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_has_session || !g_has_mac) return nullptr;
    return env->NewStringUTF(g_mac);
}
extern "C" JNIEXPORT void JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeResetBluetooth(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    delete g_state; g_state = nullptr;
    g_last_id = -1;
}

// processScan: scan NFC/FRAM (344B) -> inicializa/atualiza o scanstate como no Juggluco.
// Retorna long[3] = {glicemia empacotada ou 0, sensorStartEpochSec, status}
// status: 0=scan processado, 1=estado inicializado, -1=falha.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeProcessScan(
        JNIEnv* env, jobject, jbyteArray uid, jbyteArray patchInfo, jbyteArray fram, jlong scanEpochSec) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!ensureResolved() || !p_processScan || !p_activationComplete) {
        LOGE("processScan: simbolos ausentes processScan=%p activationComplete=%p",
             (void*)p_processScan, (void*)p_activationComplete);
        return nullptr;
    }

    data_t* ident = jToData(env, uid);
    data_t* info = jToData(env, patchInfo);
    data_t* scan = jToData(env, fram);
    if (!ident || !info || !scan || scan->size() < 344) {
        LOGE("processScan: buffers invalidos uid=%p info=%p scan=%p scanLen=%d",
             ident, info, scan, scan ? scan->size() : -1);
        data_t::deleteex(ident);
        data_t::deleteex(info);
        data_t::deleteex(scan);
        return nullptr;
    }

    const jsize scan_uid_len = ident->size() > (int)sizeof(g_uid)
        ? (jsize)sizeof(g_uid) : (jsize)ident->size();
    const bool uid_changed = g_uid_len != scan_uid_len ||
        memcmp(g_uid, ident->data(), scan_uid_len) != 0;
    if (uid_changed) {
        delete g_state; g_state = nullptr;
        g_start_since_base = 0;
        g_last_id = -1;
    }
    // Remember the identity before the later 0x1b/0x1e branch. This lets
    // USenabledStreaming preserve the state initialized by this processScan.
    g_uid_len = scan_uid_len;
    memcpy(g_uid, ident->data(), g_uid_len);

    int warmup, wear;
    patchTimeValues(info, &warmup, &wear);
    uint32_t nutime = (uint32_t)(scanEpochSec > 0 ? scanEpochSec : time(NULL));
    jlong packed = 0;
    jlong startEpoch = 0;
    jlong status = 0;

    if (!g_state || !g_state->good()) {
        if (!initializeScanState(ident, info, scan, nutime, warmup, wear, &packed, &startEpoch)) {
            status = -1;
        } else {
            status = 1;
        }
    } else {
        jint startsincebase = g_start_since_base;
        if (!startsincebase) {
            const int age = framAgeMinutes(scan);
            startsincebase = (jint)(nutime - (uint32_t)(age * 60) - BASESECS);
            g_start_since_base = startsincebase;
        }
        startEpoch = (jlong)BASESECS + startsincebase;
        scanstate* newstate = new scanstate(defaultscanstate);
        outobj person, starttime, endtime;
        AlgorithmResults* alg = callProcessScan(
            ident, info, scan, g_state, newstate, startsincebase, nutime, warmup, wear,
            &starttime, &endtime, &person);
        if (alg) {
            packed = packReading(alg);
            delete alg;
            delete g_state;
            g_state = newstate;
            newstate = nullptr;
        } else {
            status = -1;
        }
        delete newstate;
    }

    jlong out[3] = { packed, startEpoch, status };
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, out);
    data_t::deleteex(ident);
    data_t::deleteex(info);
    data_t::deleteex(scan);
    LOGI("processScan out packed=%lld start=%lld status=%lld",
         (long long)packed, (long long)startEpoch, (long long)status);
    return arr;
}

// ── processTooth: pacote decifrado → glicemia empacotada (Libre2Reading.decode). ──────
// Retorno: 0 = sem glicemia válida / warm-up / falha; 1 = duplicado; senão o encoding do glucoselong
// (bits 0-31 = mg/dL×10, bits 32-47 = rate×1000 int16, bits 48+ = alarme; alarme fica 0, GluView alerta).
extern "C" JNIEXPORT jlong JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeProcessTooth(JNIEnv* env, jobject, jbyteArray packet) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (!g_has_session) { LOGE("processTooth: sem sessão"); return 0; }
    if (!ensureResolved()) return 0;

    data_t* bluedata = jToData(env, packet);
    data_t* ident = rawToData(g_uid, g_uid_len);
    data_t* info  = rawToData(g_info, g_info_len);
    if (!bluedata || !ident || !info) {
        data_t::deleteex(bluedata); data_t::deleteex(ident); data_t::deleteex(info);
        LOGE("processTooth: buffers de sessão ausentes"); return 0;
    }

    int warmup, wear;
    patchTimeValues(info, &warmup, &wear);

    uint32_t nutime = (uint32_t)time(NULL);
    jint nusincebase = (jint)(nutime - BASESECS);
    jint startsincebase = g_start_since_base != 0 ? g_start_since_base : nusincebase;

    // Diagnóstico: tempos + pacote decifrado (para comparar com o esperado pela lib).
    {
        char hex[3 * 32 + 1]; int n = bluedata->size() < 32 ? bluedata->size() : 32; char* p = hex;
        for (int i = 0; i < n; i++) p += sprintf(p, "%02X ", bluedata->buf[i]);
        LOGI("processTooth in: blue=%dB [%s] warmup=%d wear=%d startSB=%d nuSB=%d ageMin=%d",
             bluedata->size(), hex, warmup, wear, startsincebase, nusincebase, (nusincebase - startsincebase) / 60);
    }

    if (!g_state) g_state = new scanstate(defaultscanstate);
    scanstate* newstate = new scanstate(defaultscanstate);

    jnidata_t sub = {&envbuf, newstate};
    JNIEnv* subenvp = (JNIEnv*)&sub;

    algobj alarm{&AlarmC}, nonAction{&NonActiona}, range{&GluRange}, attenconf{&attconf};
    outobj outstart, endtime, confinsert, removed, compo, attenu, messstate, outalgres;

    intptr_t res = reinterpret_cast<intptr_t>(p_processStream(
        subenvp, nullptr, PARSERTYPE,
        (jobject)alarm, (jobject)nonAction, (jobject)range, (jobject)attenconf,
        (jbyteArray)ident, (jbyteArray)info, (jbyte)0, (jbyteArray)bluedata,
        startsincebase, nusincebase, warmup, wear,
        (jbyteArray)g_state->get(COMP), (jbyteArray)g_state->get(ATTE), (jbyteArray)g_state->get(MESS),
        (jobject)outstart, (jobject)endtime, (jobject)confinsert, (jobject)removed,
        (jobject)compo, (jobject)attenu, (jobject)messstate, (jobject)outalgres,
        (jbyteArray)g_token));

    AlgorithmResults* alg = (AlgorithmResults*)outalgres.ptr;
    jlong out = 0;

    LOGI("processStream res=%ld compo=%p atte=%p mess=%p alg=%p",
         (long)res, compo.ptr, attenu.ptr, messstate.ptr, outalgres.ptr);

    if (res) {
        LOGE("processTooth: processStream falhou (res=%ld)", (long)res);
    } else {
        // Persiste o novo estado (COMP/ATTE/MESS) em newstate → vira o estado atual.
        // Guarda contra saída null (senão setpos grava offset lixo e o get() do próximo pacote
        // lê num endereço inválido → SIGSEGV). Slot vazio = makenull (datpos=0 → get()==null).
        newstate->datpos(FAKE) = g_state->datpos(FAKE);
        if (compo.ptr)     newstate->setpos(COMP, static_cast<data_t*>(compo.ptr));     else newstate->makenull(COMP);
        if (attenu.ptr)    newstate->setpos(ATTE, static_cast<data_t*>(attenu.ptr));    else newstate->makenull(ATTE);
        if (messstate.ptr) newstate->setpos(MESS, static_cast<data_t*>(messstate.ptr)); else newstate->makenull(MESS);
        delete g_state; g_state = newstate; newstate = nullptr;

        if (alg) {
            const GlucoseNow& gl = alg->currentglucose();
            const int gluc = gl.getValue();      // mg/dL
            const int qual = gl.getQuality();    // 0 = boa
            const int gid  = gl.getId();
            const float drate = gl.rate();
            LOGI("processStream id=%d qual=%d mgdl=%d rate=%.2f", gid, qual, gluc, drate);
            packHistory(alg, nutime, gid);   // trend/histórico do pacote (21/07/2026) — sempre, mesmo se duplicado
            if (!qual && gluc) {
                if (gid == g_last_id) {
                    out = 1;                     // duplicado
                } else {
                    g_last_id = gid;
                    const uint32_t mgL = (uint32_t)(gluc * 10);
                    const int16_t rate16 = (int16_t)lroundf(drate * 1000.0f);
                    out = ((jlong)((uint16_t)rate16) << 32) | mgL;
                }
            }
        }
    }

    if (alg) delete alg;
    delete newstate;   // nullptr se virou g_state
    data_t::deleteex(bluedata);
    data_t::deleteex(ident);
    data_t::deleteex(info);
    return out;
}

// ── nativeConsumeHistory: trend/histórico do último processTooth (21/07/2026). ─────────────
// Cada elemento: bits 32-63 = timestamp epoch (segundos), bits 0-31 = mg/dL×10. Consome (limpa) o
// buffer ao ler — chamar sempre logo após nativeProcessTooth, na mesma thread do GATT callback.
// Kotlin decide o que fazer com cada ponto (Libre2BleManager/Libre2Source) — aqui só extrai o que a
// própria `.so` da Abbott já calculou, nunca inventa nada.
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeConsumeHistory(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    if (g_history_buf.empty()) return nullptr;
    jlongArray arr = env->NewLongArray((jsize)g_history_buf.size());
    env->SetLongArrayRegion(arr, 0, (jsize)g_history_buf.size(), g_history_buf.data());
    g_history_buf.clear();
    return arr;
}

// ── nativeHistoryDebug: diagnóstico do ÚLTIMO processTooth, mesmo quando 0 (21/07/2026). ────
// [0]=tamanho bruto de alg->history() (quantos pontos a .so devolveu no total, antes de filtrar),
// [1]=quantos passaram valid() (id%15==0, qualidade boa, faixa plausível). NÃO consome (pode ler
// depois de nativeConsumeHistory sem afetar nada) — só pra registrar em log persistido se a `.so`
// está mesmo entregando pontos de histórico pros buracos de conexão, com evidência real.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeHistoryDebug(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    jint out[2] = { g_last_history_raw, g_last_history_valid };
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, out);
    return arr;
}

// ── nativeHistoryRawDetail: id/qualidade/valor de CADA ponto bruto do último processTooth ──────
// (21/07/2026 — "o que tem nesses 3 pontos, de verdade?"). String tipo
// "[id=8531,q=0,v=145] [id=8532,q=1,v=0] ...". Só diagnóstico, nunca usado pra decidir nada.
extern "C" JNIEXPORT jstring JNICALL
Java_com_gluview_app_data_libre2_Libre2Native_nativeHistoryRawDetail(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lock(g_mtx);
    return env->NewStringUTF(g_last_history_detail);
}
