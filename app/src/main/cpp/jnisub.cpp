// Subenv fabricado — port FIEL do Juggluco jnisub.cpp (GPLv3), adaptado ao GluView (sem SIBIONICS,
// sem a infraestrutura de app). Define as funções JNI "inteligentes" do fake env, as classes/métodos
// falsos que a lib da Abbott procura durante processStream, e a tabela `envbuf`.

#include <algorithm>
#include "sub_base.hpp"

// subLOGGER/subLOGAR ficam mudos (não definimos LOGSUB) — evitam spam no logcat.
#ifdef LOGSUB
#define subLOGGER(...) LOGGER(__VA_ARGS__)
#define subLOGAR(...)  LOGAR(__VA_ARGS__)
#else
#define subLOGGER(...)
#define subLOGAR(...)
#endif

std::unordered_map<string, jclasser*> jclasser::classid(20);

extern "C" jsize subGetArrayLength(JNIEnv*, jarray jar) {
    if (!jar) { __android_log_print(ANDROID_LOG_INFO, "LIBRE2_SUB", "GetArrayLength(null)=0"); return 0; }
    jsize n = reinterpret_cast<data_t*>(jar)->length();
    __android_log_print(ANDROID_LOG_INFO, "LIBRE2_SUB", "GetArrayLength(%p)=%d", jar, (int)n);
    return n;
}
extern "C" void subGetByteArrayRegion(JNIEnv*, jbyteArray jar, jsize start, jsize len, jbyte* out) {
    data_t* ar = reinterpret_cast<data_t*>(jar);
    memcpy(out, ar->data() + start, len);
}
extern "C" void subSetByteArrayRegion(JNIEnv*, jbyteArray jar, jsize start, jsize len, const jbyte* in) {
    data_t* ar = reinterpret_cast<data_t*>(jar);
    memcpy(ar->data() + start, in, len);
}
extern "C" jbyte* subGetByteArrayElements(JNIEnv*, jbyteArray ar, jboolean* copy) {
    if (copy) *copy = JNI_FALSE;
    __android_log_print(ANDROID_LOG_INFO, "LIBRE2_SUB", "GetByteArrayElements(%p)", ar);
    return reinterpret_cast<jbyte*>(reinterpret_cast<data_t*>(ar)->buf);
}
extern "C" void subReleaseByteArrayElements(JNIEnv*, jbyteArray, jbyte*, jint) {}
extern "C" jint* subGetIntArrayElements(JNIEnv*, jintArray ar, jboolean* copy) {
    if (copy) *copy = JNI_FALSE;
    return reinterpret_cast<gegs<jint>*>(ar)->buf;
}
extern "C" void subReleaseIntArrayElements(JNIEnv*, jintArray, jint*, jint) {}
extern "C" void subDeleteLocalRef(JNIEnv*, jobject) {}

extern "C" jbyteArray subNewByteArray(JNIEnv* subenvp, jsize len) {
    jnidata_t* dat = reinterpret_cast<jnidata_t*>(subenvp);
    __android_log_print(ANDROID_LOG_INFO, "LIBRE2_SUB", "NewByteArray %d (map=%p)", (int)len, dat->map);
    if (!dat->map) return nullptr;
    return (jbyteArray)dat->map->alloc(len);
}

typedef jint (*staticintmethod)(va_list);
extern "C" jint subCallStaticIntMethod(JNIEnv*, jclass, jmethodID meth, ...) {
    va_list args; va_start(args, meth);
    jint out = ((staticintmethod)meth)(args);
    va_end(args); return out;
}
typedef jboolean (*staticbooleanmethod)(va_list);
extern "C" jboolean subCallStaticBooleanMethod(JNIEnv*, jclass, jmethodID meth, ...) {
    va_list args; va_start(args, meth);
    jboolean out = ((staticbooleanmethod)meth)(args);
    va_end(args); return out;
}
typedef jobject (*staticObjmethod)(va_list);
extern "C" jobject subCallStaticObjectMethod(JNIEnv*, jclass, jmethodID meth, ...) {
    va_list args; va_start(args, meth);
    jobject out = ((staticObjmethod)meth)(args);
    va_end(args); return out;
}
template <typename T>
T subCallMethod(JNIEnv*, jobject obj, jmethodID meth, ...) {
    va_list args; va_start(args, meth);
    T out = ((T(*)(jobject, va_list))meth)(obj, args);
    va_end(args); return out;
}
constexpr auto subCallBooleanMethod = subCallMethod<jboolean>;
constexpr auto subCallIntMethod = subCallMethod<jint>;
constexpr auto subCallDoubleMethod = subCallMethod<jdouble>;
extern "C" void subCallVoidMethod(JNIEnv*, jobject obj, jmethodID meth, ...) {
    va_list args; va_start(args, meth);
    ((void (*)(jobject, va_list))meth)(obj, args);
    va_end(args);
}

extern "C" jclass subFindClass(JNIEnv*, const char* clname) {
    const char* end = strrchr(clname, '/') + 1;
    jclass cl = (jclass)jclasser::classid[end];
    __android_log_print(ANDROID_LOG_INFO, "LIBRE2_SUB", "FindClass %s -> %p", end, cl);
    return cl;
}

class thrower : public _jthrowable {
    string name;
public:
    thrower(const char* name) : name(name) {}
    const string& getname() const { return name; }
};
jint subThrow(JNIEnv*, jthrowable) { return 0; }
jboolean except = 0;
extern "C" jboolean subExceptionCheck(JNIEnv*) { return 0; }
extern "C" void subExceptionClear(JNIEnv*) { except = 0; }
extern "C" jthrowable subExceptionOccurred(JNIEnv*) {
    static thrower athrow("Hier");
    return &athrow;
}

extern "C" jobject nothingproc(va_list) { return nullptr; }
static jmethodID nothing = (jmethodID)nothingproc;
extern "C" jmethodID subGetStaticMethodID(JNIEnv*, jclass cl, const char* name, const char* sig) {
    jclasser* cla = (jclasser*)cl;
    auto& funcs = cla->funcs;
    auto it = std::find_if(funcs.begin(), funcs.end(), [name, sig](Method& me) {
        return !(strcmp(me.name.data(), name) || strcmp(me.pat.data(), sig));
    });
    __android_log_print(ANDROID_LOG_INFO, "LIBRE2_SUB", "MethodID %s %s -> %s",
                        name, sig, it != funcs.end() ? "ok" : "MISS");
    if (it != funcs.end()) return (jmethodID)it->func;
    return nothing;
}

#define mkfieldID(cl, memb) offsetof(cl, memb) + 8
template <typename T>
T* fieldvalue(jobject obj, jfieldID fieldid) {
    return reinterpret_cast<T*>(reinterpret_cast<uint8_t*>(obj) + reinterpret_cast<intptr_t>(fieldid) - 8);
}
void subSetLongField(JNIEnv*, jobject o, jfieldID f, jlong v) { *fieldvalue<jlong>(o, f) = v; }
void subSetDoubleField(JNIEnv*, jobject o, jfieldID f, jdouble v) { *fieldvalue<jdouble>(o, f) = v; }
void subSetIntField(JNIEnv*, jobject o, jfieldID f, jint v) { *fieldvalue<jint>(o, f) = v; }
jlong subGetLongField(JNIEnv*, jobject o, jfieldID f) { return *fieldvalue<jlong>(o, f); }

const char* subGetStringUTFChars(JNIEnv*, jstring s, jboolean*) { return reinterpret_cast<const char*>(s); }
void subReleaseStringUTFChars(JNIEnv*, jstring, const char*) {}

jobject subNewObjectV(JNIEnv*, jclass, jmethodID meth, va_list args) {
    typedef jobject (*new_t)(va_list);
    return reinterpret_cast<new_t>(meth)(args);
}
jfieldID subGetFieldID(JNIEnv* env, jclass cl, const char* name, const char* sig) {
    return reinterpret_cast<jfieldID>(subGetStaticMethodID(env, cl, name, sig));
}
extern "C" jclass subGetObjectClass(JNIEnv*, jobject ob) {
    return (jclass)((algobj*)ob)->cl;
}
extern "C" jobjectArray subNewObjectArray(JNIEnv*, jsize n, jclass, jobject) {
    return (jobjectArray)gegs<jobject>::newex(n);
}
extern "C" jobject subGetObjectArrayElement(JNIEnv*, jobjectArray ar, jsize el) {
    return reinterpret_cast<gegs<jobject>*>(ar)->data()[el];
}
extern "C" void subSetObjectArrayElement(JNIEnv*, jobjectArray ar, jsize el, jobject v) {
    reinterpret_cast<gegs<jobject>*>(ar)->data()[el] = v;
}

jobject newpatchevent(va_list args) {
    jint id = va_arg(args, jint);
    jint code = va_arg(args, jint);
    return (jobject)new PatchEvent{id, code};
}
jobject newglucosevalue(va_list args) {
    jint id = va_arg(args, jint);
    jint qual = va_arg(args, jint);
    jint val = va_arg(args, jint);
    return (jobject)new GlucoseValue{id, qual, val};
}
class MemoryRegion {
    const int start;
    const int len;
public:
    MemoryRegion(int start, int len) : start(start), len(len) {}
    int getStart() const { return start; }
    int getLength() const { return len; }
};
jobject newmemoryregion(va_list args) {
    jint start = va_arg(args, jint);
    jint len = va_arg(args, jint);
    return (jobject)new MemoryRegion{start, len};
}
jobject newarraylist(va_list) {
    jniHistory* vec = new jniHistory();
    vec->reserve(33);
    return reinterpret_cast<jobject>(vec);
}
jboolean addtohist(jobject obj, va_list args) {
    reinterpret_cast<jniHistory*>(obj)->push_back(va_arg(args, GlucoseValue*));
    return 1;
}
jobject returnint(va_list args) { return reinterpret_cast<jobject>(va_arg(args, jint)); }

AlgorithmResults::~AlgorithmResults() {
    for (GlucoseValue* g : *hist) delete g;
    delete hist;
    delete glu;
}

inline float trend2rate(const int trend) {
    return trend ? (trend - 3.0f) * 1.3f : NAN;
}
template <int version>
jobject newalgorithmresults2_4(va_list args) {
    jniHistory* hist = va_arg(args, jniHistory*);
    GlucoseValue* val = va_arg(args, GlucoseValue*);
    jfloat rate;
    if constexpr (version == 3) rate = (va_arg(args, double));
    int trend = va_arg(args, intptr_t);
    if constexpr (version != 3) rate = trend2rate(trend);
    GlucoseNow* glu = new GlucoseNow(val, trend, rate);
    delete val;
    int alarm = va_arg(args, intptr_t);
    jboolean actionable = va_arg(args, int);
    jint waitminutes = va_arg(args, jint);
    jboolean lsa = va_arg(args, int);
    return (jobject)new AlgorithmResults{.hist = hist, .glu = glu, .alarm = alarm,
        .isActionable = actionable, .esaMinutesToWait = waitminutes, .lsaDetected = lsa};
}

jint getLowGlucoseAlarmThreshold(jobject, va_list) { return 70; }
jint getHighGlucoseAlarmThreshold(jobject, va_list) { return 240; }
jboolean isDebuggerConnected(va_list) { return 0; }
jboolean getIsEnabled(jobject, va_list) { return 0; }
jboolean getIsProjectedLowGlucoseCheckEnabled(jobject, va_list) { return 0; }
jboolean getIsVelocityCheckEnabled(jobject, va_list) { return 0; }
jint getMinimumActionableValue(jobject, va_list) { return 70; }
jint getMaximumActionableValue(jobject, va_list) { return 500; }
jint getMinimumActionableId(jobject, va_list) { return 0; }
jdouble getMinimumNegativeActionableVelocity(jobject, va_list) { return -2.0; }
jdouble getMaximumPositiveActionableVelocity(jobject, va_list) { return -2.0; }
jint getGmax(jobject, va_list) { return 500; }
jint getGmin(jobject, va_list) { return 40; }
int myPid(va_list) { return getpid(); }
#define boolfun(name, res) jboolean name(jobject, va_list) { return res; }
boolfun(isEsaDetectionEnabled, JNI_FALSE)
boolfun(isLsaDetectionEnabled, JNI_TRUE)
boolfun(isEsaCorrectionEnabled, JNI_TRUE)
boolfun(isLsaCorrectionEnabled, JNI_TRUE)

// Não usamos RegisterNatives (modo DYNLINK resolve via dlsym) — stub para a tabela.
extern "C" jint subRegisterNatives(JNIEnv*, jclass, const JNINativeMethod*, jint) { return 0; }

#include "subtrace.c"
struct JNINativeInterface envbuf = {
#include "subtrace.h"
};
jnidata_t jnidata = {&envbuf, nullptr};
JNIEnv* subenv = (JNIEnv*)&jnidata;

#define function(x) (void*)&x
#define valueptr(x) (void*)x

jclasser AlgorithR{"AlgorithmResults", {
{"<init>", "(Ljava/util/List;Lcom/abbottdiabetescare/flashglucose/sensorabstractionservice/dataprocessing/GlucoseValue;Lcom/abbottdiabetescare/flashglucose/sensorabstractionservice/TrendArrow;Lcom/abbottdiabetescare/flashglucose/sensorabstractionservice/Alarm;ZIZ)V", function(newalgorithmresults2_4<0>)},
{"<init>", "(Ljava/util/List;Lcom/abbottdiabetescare/flashglucose/sensorabstractionservice/dataprocessing/GlucoseValue;FLcom/abbottdiabetescare/flashglucose/sensorabstractionservice/TrendArrow;Lcom/abbottdiabetescare/flashglucose/sensorabstractionservice/Alarm;ZIZ)V", function(newalgorithmresults2_4<3>)}}};

jclasser classen[]{
{"ArrayList", {{"add", "(Ljava/lang/Object;)Z", function(addtohist)}, {"<init>", "()V", function(newarraylist)}}},
{"GlucoseValue", {{"<init>", "(III)V", function(newglucosevalue)}}},
{"PatchEvent", {{"<init>", "(II)V", function(newpatchevent)}}},
{"MemoryRegion", {{"<init>", "(II)V", function(newmemoryregion)}}},
{"DataProcessingResult", {{"fromValue", "(I)Lcom/abbottdiabetescare/flashglucose/sensorabstractionservice/dataprocessing/DataProcessingResult;", function(returnint)}}},
{"Alarm", {{"fromValue", "(I)Lcom/abbottdiabetescare/flashglucose/sensorabstractionservice/Alarm;", function(returnint)}}},
{"TrendArrow", {{"fromValue", "(I)Lcom/abbottdiabetescare/flashglucose/sensorabstractionservice/TrendArrow;", function(returnint)}}},
{"Process", {{"myPid", "()I", function(myPid)}}},
{"Byte", {{"valueOf", "(B)Ljava/lang/Byte;", function(returnint)}}},
{"Integer", {{"valueOf", "(I)Ljava/lang/Integer;", function(returnint)}}},
{"Boolean", {{"valueOf", "(Z)Ljava/lang/Boolean;", function(returnint)}}}};

jclasser Debug{"Debug", {{"isDebuggerConnected", "()Z", function(isDebuggerConnected)}}};
jclasser AlarmC{"AlarmConfiguration", {{"getLowGlucoseAlarmThreshold", "()I", function(getLowGlucoseAlarmThreshold)}, {"getHighGlucoseAlarmThreshold", "()I", function(getHighGlucoseAlarmThreshold)}}};
jclasser NonActiona{"NonActionableConfiguration", {
{"getIsEnabled", "()Z", function(getIsEnabled)},
{"getIsProjectedLowGlucoseCheckEnabled", "()Z", function(getIsProjectedLowGlucoseCheckEnabled)},
{"getIsVelocityCheckEnabled", "()Z", function(getIsVelocityCheckEnabled)},
{"getMaximumActionableValue", "()I", function(getMaximumActionableValue)},
{"getMaximumPositiveActionableVelocity", "()D", function(getMaximumPositiveActionableVelocity)},
{"getMinimumActionableId", "()I", function(getMinimumActionableId)},
{"getMinimumActionableValue", "()I", function(getMinimumActionableValue)},
{"getMinimumNegativeActionableVelocity", "()D", function(getMinimumNegativeActionableVelocity)}}};
jclasser GluRange{"GlucoseValueRange", {{"getGmax", "()I", function(getGmax)}, {"getGmin", "()I", function(getGmin)}}};
jclasser attconf{"AttenuationConfiguration", {
{"isEsaCorrectionEnabled", "()Z", function(isEsaCorrectionEnabled)},
{"isEsaDetectionEnabled", "()Z", function(isEsaDetectionEnabled)},
{"isLsaCorrectionEnabled", "()Z", function(isLsaCorrectionEnabled)},
{"isLsaDetectionEnabled", "()Z", function(isLsaDetectionEnabled)}}};
jclasser DataProcessingNative{"DataProcessingNative", {}};

void setout(jobject obj, va_list args) {
    void* v = va_arg(args, void*);
    ((algobj*)obj)->ptr = v;
    __android_log_print(ANDROID_LOG_INFO, "LIBRE2_SUB", "Out.value(%p)", v);
}
jclasser out{"Out", {{"value", "(Ljava/lang/Object;)V", function(setout)}}};

static jint libre2getenv(JavaVM*, void** envptr, jint) {
    *reinterpret_cast<JNIEnv**>(envptr) = subenv;
    return 0;
}
bool javaAttached = false;
static JNIInvokeInterface libre2vm{
    .DestroyJavaVM = [](JavaVM*) -> jint { return 0; },
    .AttachCurrentThread = [](JavaVM*, JNIEnv** p_env, void*) -> jint {
        *p_env = subenv;
        if (!javaAttached) {
            javaAttached = true;
            while (true) syscall(SYS_exit, 0);
        }
        return 0;
    },
    .DetachCurrentThread = [](JavaVM*) -> jint { return 0; },
    .GetEnv = libre2getenv,
    .AttachCurrentThreadAsDaemon = [](JavaVM*, JNIEnv**, void*) -> jint { return 0; }};
static JavaVM hiervmptr{.functions = &libre2vm};
JavaVM* getnewvm() { return &hiervmptr; }
