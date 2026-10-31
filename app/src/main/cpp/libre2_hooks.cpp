// Hooks PLT/GOT na libDataProcessing.so (2.5.2) — replica o mecanismo do Juggluco share/hooks.cpp.
//
// EVIDÊNCIA (readelf --dyn-syms da 2.5.2): a lib importa da libc exatamente
//   fork, pipe, waitpid, execv, fopen, stat, pthread_create, __android_log_print
// — o mesmo conjunto que o hooks.cpp do Juggluco intercepta. O anti-tamper do processStream:
//   pipe() → fork() → [filho: execv("pm path <pkg>") escreve "package:/…/base.apk" no pipe] →
//   waitpid() → [pai lê o pipe, fopen() no APK, verifica a assinatura]. Se a assinatura não for a da
//   Abbott, a lib aborta (processStream devolve 0 sem produzir glicemia).
//
// O Juggluco derrota via patch de SONAME (liblog→libg) que interpõe os hooks do próprio libg sobre a
// libc. Como o GluView carrega a .so via dlopen (símbolos exportados/DYNLINK), replicamos a MESMA
// interposição por GOT-hook em runtime (dl_iterate_phdr + reescrita do GOT). Infra reaproveitada do
// abbott_bridge.cpp (comprovada). ESTA VERSÃO É LOG-ONLY (evidência): chama o original e loga, para
// confirmar a sequência exata antes de forjar a resposta.

#include <link.h>
#include <elf.h>
#include <dlfcn.h>
#include <sys/mman.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <ctype.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#define LOG_TAG "LIBRE2_HOOK"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef pid_t (*fork_t)(void);
typedef int   (*pipe_t)(int[2]);
typedef pid_t (*waitpid_t)(pid_t, int*, int);
typedef int   (*execv_t)(const char*, char* const[]);
typedef FILE* (*fopen_t)(const char*, const char*);
typedef int   (*stat_t)(const char*, struct stat*);
typedef int   (*open_t)(const char*, int, ...);
typedef int   (*openat_t)(int, const char*, int, ...);
typedef ssize_t (*read_t)(int, void*, size_t);
typedef ssize_t (*readlink_t)(const char*, char*, size_t);
typedef size_t (*fread_t)(void*, size_t, size_t, FILE*);
typedef int    (*strcmp_t)(const char*, const char*);

static fork_t    real_fork = nullptr;
static pipe_t    real_pipe = nullptr;
static waitpid_t real_waitpid = nullptr;
static execv_t   real_execv = nullptr;
static fopen_t   real_fopen = nullptr;
static stat_t    real_stat = nullptr;
static open_t    real_open = nullptr;
static openat_t  real_openat = nullptr;
static read_t    real_read = nullptr;
static readlink_t real_readlink = nullptr;
static fread_t   real_fread = nullptr;
static strcmp_t  real_strcmp = nullptr;

// ── FORGE (fiel ao Juggluco share/hooks.cpp: Fork/Pipe/Waitpid) ─────────────────────
// A lib faz pipe()→fork()→[filho: pm path <pkg>]→waitpid()→[pai lê o pipe e verifica a assinatura].
// Forjamos: fork NÃO forka (retorna um pid falso), pipe injeta o `package` forjado no pipe (a resposta
// do "pm path"), waitpid confirma sucesso. O `package` aponta p/ a própria .so — que tem o bloco de
// assinatura da Abbott anexado (libDataProcessing.so assinada, ver craft_signed_so.py).
static const pid_t FAKE_CHILD = 9815123;   // Juggluco: childpid=9815123
static char g_package[512];                // "package:<caminho-da-so>\n"
static int  g_package_len = 0;

static pid_t my_fork(void) {
    LOGI("fork() → FORJADO (pid=%d, sem pm path)", FAKE_CHILD);
    return FAKE_CHILD;                      // não forka; o "filho" nunca roda
}
static int my_pipe(int fds[2]) {
    int r = real_pipe ? real_pipe(fds) : pipe(fds);
    if (r == 0 && g_package_len > 0) {
        ssize_t w = write(fds[1], g_package, g_package_len);   // write da libc (não hookado)
        LOGI("pipe({%d,%d})=%d → injetado package (%zd/%d): %s", fds[0], fds[1], r, w, g_package_len, g_package);
    }
    return r;
}
static pid_t my_waitpid(pid_t pid, int* st, int opt) {
    if (pid == FAKE_CHILD) {
        if (st) *st = 0;
        LOGI("waitpid(%d) → FORJADO status=0", pid);
        return FAKE_CHILD;
    }
    return real_waitpid ? real_waitpid(pid, st, opt) : waitpid(pid, st, opt);
}
static int my_execv(const char* path, char* const argv[]) {
    char buf[512]; int n = 0;
    for (int i = 0; argv && argv[i] && n < 480; i++) n += snprintf(buf + n, sizeof(buf) - n, "%s ", argv[i]);
    LOGI("execv(%s) argv=[%s]", path ? path : "?", buf);
    return real_execv ? real_execv(path, argv) : execv(path, argv);
}
static FILE* my_fopen(const char* path, const char* mode) {
    LOGI("fopen(%s, %s)", path ? path : "?", mode ? mode : "?");
    return real_fopen ? real_fopen(path, mode) : fopen(path, mode);
}
static int my_stat(const char* path, struct stat* st) {
    LOGI("stat(%s)", path ? path : "?");
    return real_stat ? real_stat(path, st) : stat(path, st);
}
static int my_open(const char* path, int flags, ...) {
    LOGI("open(%s, 0x%x)", path ? path : "?", flags);
    return real_open ? real_open(path, flags) : open(path, flags);
}
static int my_openat(int dirfd, const char* path, int flags, ...) {
    LOGI("openat(%s, 0x%x)", path ? path : "?", flags);
    return real_openat ? real_openat(dirfd, path, flags) : openat(dirfd, path, flags);
}
static ssize_t my_read(int fd, void* buf, size_t n) {
    ssize_t r = real_read ? real_read(fd, buf, n) : read(fd, buf, n);
    // Loga leituras pequenas com conteúdo "package:" ou texto (resultado do pipe do pm path).
    if (r > 0 && r < 300 && buf) {
        char t[301]; int m = (int)r; memcpy(t, buf, m); t[m] = '\0';
        bool printable = true;
        for (int i = 0; i < m && i < 40; i++) if (t[i] && !isprint((unsigned char)t[i]) && t[i] != '\n') { printable = false; break; }
        if (printable) LOGI("read(fd=%d, %d) = \"%s\"", fd, m, t);
    }
    return r;
}
static ssize_t my_readlink(const char* path, char* buf, size_t n) {
    ssize_t r = real_readlink ? real_readlink(path, buf, n) : readlink(path, buf, n);
    LOGI("readlink(%s) = \"%.*s\"", path ? path : "?", r > 0 ? (int)r : 0, buf);
    return r;
}
static size_t my_fread(void* ptr, size_t sz, size_t nm, FILE* f) {
    size_t r = real_fread ? real_fread(ptr, sz, nm, f) : fread(ptr, sz, nm, f);
    size_t bytes = r * sz;
    if (bytes > 0 && bytes < 300 && ptr) {
        char t[301]; int m = (int)bytes; memcpy(t, ptr, m); t[m] = '\0';
        bool printable = true;
        for (int i = 0; i < m && i < 60; i++) if (t[i] && !isprint((unsigned char)t[i]) && t[i] != '\n') { printable = false; break; }
        if (printable) LOGI("fread(%d) = \"%s\"", m, t);
    }
    return r;
}
static int my_strcmp(const char* a, const char* b) {
    int r = real_strcmp ? real_strcmp(a, b) : strcmp(a, b);
    // Só loga comparações "interessantes" (pacote/caminho) para não inundar.
    if (a && b && (strstr(a, "package") || strstr(a, "com.") || strstr(a, "/data/") ||
                   strstr(b, "package") || strstr(b, "com.") || strstr(b, "librelink") || strstr(b, "freestyle")))
        LOGI("strcmp(\"%.60s\", \"%.60s\") = %d", a, b, r);
    return r;
}

static bool g_installed = false;
struct HookReq { const char* libpart; };

static int hookCb(struct dl_phdr_info* info, size_t, void* data) {
    auto* req = (HookReq*)data;
    if (!info->dlpi_name || !strstr(info->dlpi_name, req->libpart)) return 0;

    // Monta o `package` forjado = "package:<caminho-real-da-so>\n" (a .so tem a assinatura anexada).
    // É o que o `pm path` do app "deveria" devolver p/ o anti-tamper (Juggluco: <filesdir>/libcalibrate.so).
    g_package_len = snprintf(g_package, sizeof(g_package), "package:%s\n", info->dlpi_name);
    LOGI("lib path = %s", info->dlpi_name);

    ElfW(Dyn)* dyn = nullptr;
    for (int i = 0; i < info->dlpi_phnum; i++)
        if (info->dlpi_phdr[i].p_type == PT_DYNAMIC)
            dyn = (ElfW(Dyn)*)(info->dlpi_addr + info->dlpi_phdr[i].p_vaddr);
    if (!dyn) return 1;

    ElfW(Rela)* jmprel = nullptr; size_t pltsz = 0;
    ElfW(Rela)* rela = nullptr;   size_t relasz = 0;
    ElfW(Sym)*  symtab = nullptr; const char* strtab = nullptr;
    for (ElfW(Dyn)* d = dyn; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_JMPREL:   jmprel = (ElfW(Rela)*)(info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_PLTRELSZ: pltsz  = d->d_un.d_val; break;
            case DT_RELA:     rela   = (ElfW(Rela)*)(info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_RELASZ:   relasz = d->d_un.d_val; break;
            case DT_SYMTAB:   symtab = (ElfW(Sym)*)(info->dlpi_addr + d->d_un.d_ptr); break;
            case DT_STRTAB:   strtab = (const char*)(info->dlpi_addr + d->d_un.d_ptr); break;
        }
    }
    if (!symtab || !strtab) return 1;

    auto patch = [&](ElfW(Rela)* r, size_t sz) {
        if (!r) return;
        for (size_t i = 0; i < sz / sizeof(ElfW(Rela)); i++) {
            const char* nm = strtab + symtab[ELF64_R_SYM(r[i].r_info)].st_name;
            void* newfn = nullptr; void** save = nullptr;
            if      (!strcmp(nm, "fork"))    { newfn = (void*)my_fork;    save = (void**)&real_fork; }
            else if (!strcmp(nm, "pipe"))    { newfn = (void*)my_pipe;    save = (void**)&real_pipe; }
            else if (!strcmp(nm, "waitpid")) { newfn = (void*)my_waitpid; save = (void**)&real_waitpid; }
            else if (!strcmp(nm, "execv"))   { newfn = (void*)my_execv;   save = (void**)&real_execv; }
            else if (!strcmp(nm, "fopen"))   { newfn = (void*)my_fopen;   save = (void**)&real_fopen; }
            else if (!strcmp(nm, "stat"))    { newfn = (void*)my_stat;    save = (void**)&real_stat; }
            else if (!strcmp(nm, "open"))    { newfn = (void*)my_open;    save = (void**)&real_open; }
            else if (!strcmp(nm, "openat"))  { newfn = (void*)my_openat;  save = (void**)&real_openat; }
            else if (!strcmp(nm, "read"))    { newfn = (void*)my_read;    save = (void**)&real_read; }
            else if (!strcmp(nm, "readlink")){ newfn = (void*)my_readlink; save = (void**)&real_readlink; }
            else if (!strcmp(nm, "fread"))   { newfn = (void*)my_fread;   save = (void**)&real_fread; }
            else if (!strcmp(nm, "strcmp"))  { newfn = (void*)my_strcmp;  save = (void**)&real_strcmp; }
            else continue;
            void** got = (void**)(info->dlpi_addr + r[i].r_offset);
            uintptr_t pg = (uintptr_t)got & ~(uintptr_t)(sysconf(_SC_PAGESIZE) - 1);
            if (mprotect((void*)pg, sysconf(_SC_PAGESIZE), PROT_READ | PROT_WRITE) == 0) {
                if (save && !*save) *save = *got;
                *got = newfn;
                LOGI("HOOK instalado: %s (got=%p)", nm, got);
            } else LOGE("mprotect falhou para %s", nm);
        }
    };
    patch(jmprel, pltsz);
    patch(rela, relasz);
    return 1;
}

extern "C" void libre2_install_hooks() {
    if (g_installed) return;
    HookReq req{ "libDataProcessing.so" };
    dl_iterate_phdr(hookCb, &req);
    g_installed = true;
    LOGI("hooks instalados (log-only)");
}
