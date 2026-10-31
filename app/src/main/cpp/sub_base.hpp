// Fundação do subenv fabricado (port FIEL do Juggluco, GPLv3) para chamar processStream da
// libDataProcessing.so da Abbott. Consolida o mínimo de inout.hpp / scanstate.hpp / glucose.hpp /
// jnisub.hpp / jnisubin.hpp que o subenv precisa — sem a infraestrutura de app do Juggluco.
//
// A ideia (Juggluco): o processStream é uma função JNI; ela recebe um JNIEnv* que na verdade é um
// `jnidata_t{&envbuf, scanstate}` cast para JNIEnv*. A tabela `envbuf` intercepta as chamadas JNI
// que a lib faz. Arrays (jbyteArray) são `data_t*` (buffer com prefixo de comprimento). Classes/
// métodos Java que a lib procura são `jclasser`/`Method` falsos que devolvem funções C++.
#pragma once

#include <jni.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <cstddef>
#include <new>
#include <string>
#include <vector>
#include <unordered_map>
#include <android/log.h>

using std::string;

// ── Logging (Juggluco LOGGER/LOGAR/LOGSTRING) → logcat. subLOGGER fica mudo (LOGSUB indefinido). ──
#define SUBTAG "LIBRE2_SUB"
#ifndef LOGGER
#define LOGGER(...)   __android_log_print(ANDROID_LOG_INFO, SUBTAG, __VA_ARGS__)
#endif
#define LOGAR(s)      __android_log_print(ANDROID_LOG_INFO, SUBTAG, "%s", s)
#define LOGSTRING(s)  __android_log_print(ANDROID_LOG_INFO, SUBTAG, "%s", s)
#define LOGGERN(s,n)  __android_log_print(ANDROID_LOG_INFO, SUBTAG, "%.*s", (int)(n), (const char*)(s))

// ── gegs<T> = array com prefixo de comprimento (inout.hpp). data_t = gegs<unsigned char>. ──
template <typename T, int NRel = 0>
struct gegs {
    typedef T type;
    int len = NRel;
    T buf[NRel];

    int length() const { return len; }
    int size() const { return length(); }
    T* data() { return buf; }
    const T* data() const { return buf; }
    T* end() { return buf + size(); }
    const T* end() const { return buf + size(); }

    static gegs* newex(int len) {
        gegs* ar = reinterpret_cast<gegs*>(
            new (std::align_val_t(alignof(struct gegs))) unsigned char[sizeof(gegs) + len * sizeof(T)]);
        ar->len = len;
        return ar;
    }
    static gegs* newex(const gegs* in) {
        gegs* ptr = newex(in->length());
        memcpy(ptr->buf, in->buf, in->length() * sizeof(T));
        return ptr;
    }
    static void deleteex(const gegs* geg) {
        operator delete[](reinterpret_cast<unsigned char*>(const_cast<gegs*>(geg)),
                          std::align_val_t(alignof(struct gegs)));
    }
    void operator delete(void* p) {
        operator delete[](reinterpret_cast<unsigned char*>(p), std::align_val_t(alignof(struct gegs)));
    }
};
typedef gegs<unsigned char> data_t;

// ── Mmap<T> (anônimo, private) — base do estado (scanstate.hpp). ──
template <class T>
class Mmap {
protected:
    size_t len;
    void* buf;
public:
    typedef T unittype;
    Mmap(size_t count)
        : len(count * sizeof(T)),
          buf(mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_ANONYMOUS | MAP_PRIVATE, -1, 0)) {
        if (MAP_FAILED == buf) buf = nullptr;
    }
    Mmap() : len(0), buf(nullptr) {}
    Mmap(Mmap&& o) : len(o.len), buf(o.buf) { o.buf = nullptr; }
    T* data() { return (T*)buf; }
    const T* data() const { return (const T*)buf; }
    auto count() const { return len / sizeof(T); }
    auto size() const { return count(); }
    virtual ~Mmap() {
        if (buf && buf != MAP_FAILED) { munmap(buf, len); buf = nullptr; }
    }
};

// ── estado do algoritmo entre pacotes (scanstate.hpp: enum + multimmap + scanstate) ──
enum state : int { MESS = 0, COMP = 1, ATTE = 2, FAKE = 3 };

// Contorna a suposição do compilador de que "this" nunca é null (usado por multimmap::get).
inline bool isnull(void* ptr) {
    typedef char type;
    type* other = (type*)ptr + 1;
    return other == (decltype(other))sizeof(*other);
}

template <typename T>
constexpr int alignstart(int len) {
    constexpr int size = alignof(T);
    return ((len + size - 1) / size) * size;
}

class multimmap {
    const int maxdats;
public:
    int freepos;
    Mmap<unsigned char> map;
    int& datpos(int datnr) { return reinterpret_cast<int*>(map.data())[datnr]; }
    template <typename... A>
    multimmap(int maxdats, A... args) : maxdats(maxdats), freepos(datastart()), map(args...) {}
    virtual ~multimmap() {}
    int datastart() const { return maxdats * sizeof(int); }
    data_t* get(int datnr) {
        if (isnull(this)) return nullptr;
        if (!map.data()) return nullptr;
        int pos = datpos(datnr);
        if (pos < datastart() || (size_t)pos >= map.size()) return nullptr;  // rede de segurança
        data_t* dat = reinterpret_cast<data_t*>(map.data() + pos);
        if (dat->len > 0) return dat;
        return nullptr;
    }
    bool good() const { return const_cast<multimmap*>(this)->get(MESS); }
    data_t* alloc(int len) {
        int was = freepos;
        data_t* dat = reinterpret_cast<data_t*>(map.data() + was);
        dat->len = len;
        freepos += alignstart<int64_t>(sizeof(data_t) + len);
        return dat;
    }
    void reset() {
        freepos = datastart();
        memset(map.data(), '\0', freepos);
    }
    void makenull(int datnr) { datpos(datnr) = 0; }
    void setpos(int datnr, data_t* dat) {
        int pos = reinterpret_cast<unsigned char*>(dat) - map.data();
        datpos(datnr) = pos;
    }
};

constexpr const int defaultscanstate = 4;
// scanstate do GluView: só a variante em memória (anônima). O Juggluco também persiste em arquivo;
// aqui o estado vive só na sessão de streaming (reiniciado a cada nova sessão BLE).
class scanstate : public multimmap {
public:
    scanstate(int block = 1) : multimmap(4, (size_t)(block * 4096)) {}
    virtual ~scanstate() override {}
};

// ── tipos de glicose (glucose.hpp) ──
struct GlucoseValue {
    const int id;
    const int dataQuality;
    const int value;
    int getId() const { return id; }
    int getQuality() const { return dataQuality; }
    int getValue() const { return value; }
    bool valid() { return !(getId() % 15) && !getQuality() && getValue() > 38 && getValue() < 502; }
};
struct GlucoseNow : GlucoseValue {
    const int trendArrow;
    const float rateOfChange;
    const float rate() const { return rateOfChange; }
    const int trend() const { return trendArrow; }
    GlucoseNow(GlucoseValue* val, const int trend, const float rate)
        : GlucoseValue(*val), trendArrow(trend), rateOfChange(rate) {}
};

// ── resultado do algoritmo (jnisub.hpp) ──
struct scandata;
struct jniHistory : std::vector<GlucoseValue*> {
    const GlucoseValue& get(int i) const { return *operator[](i); }
};
struct AlgorithmResults {
    const jniHistory* hist;
    const GlucoseNow* glu;
    const int alarm;
    const jboolean isActionable;
    const int esaMinutesToWait;
    const jboolean lsaDetected;
    bool inserted, removed;
    void setremoved(int rem) { removed = rem; }
    void setinserted(int ins) { inserted = ins; }
    const jniHistory& history() const { return *hist; }
    const GlucoseNow& currentglucose() const { return *glu; }
    const jboolean getlsaDetected() const { return lsaDetected; }
    int trend() const { return glu->trendArrow; }
    float RateOfChange() const { return glu->rateOfChange; }
    ~AlgorithmResults();
};

// ── classes/métodos falsos + objetos de config/saída (jnisubin.hpp) ──
struct Method {
    string name;
    string pat;
    void* func;
};
struct jclasser {
    std::vector<Method> funcs;
    static std::unordered_map<string, jclasser*> classid;
    jclasser(const char name[], std::vector<Method>&& funcs) : funcs(std::move(funcs)) {
        classid.emplace(std::make_pair(name, this));
    }
};
struct algobj {
    jclasser* cl;
    void* ptr = nullptr;
    algobj(jclasser* cl) : cl(cl) {}
    operator jobject() { return (jobject)this; }
    intptr_t toint() { return reinterpret_cast<intptr_t>(ptr); }
};
extern jclasser out;
struct outobj : public algobj {
    outobj() : algobj(&out) {}
};
struct jnidata_t {
    const struct JNINativeInterface* functions;
    multimmap* map;
};
class PatchEvent {
    const int id;
    const int errorCode;
public:
    PatchEvent(int id, int code) : id(id), errorCode(code) {}
    int getId() const { return id; }
    int getErrorCode() const { return errorCode; }
};
extern JNIEnv* subenv;
extern bool javaAttached;
extern struct JNINativeInterface envbuf;
JavaVM* getnewvm();
