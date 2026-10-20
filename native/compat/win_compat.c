// Windows implementations behind win_compat.h. See that file for the fd numbering scheme.
#define WIN_COMPAT_IMPL
#include "win_compat.h"

#include <stdarg.h>

// ---- process setup -----------------------------------------------------------------------

// The UCRT aborts on an invalid fd or parameter by default (readsb writes to fd -1 when it has
// no eventfd, for instance). Return the error to the caller instead, as POSIX would.
static void ignore_invalid_parameter(const wchar_t *expr, const wchar_t *func, const wchar_t *file,
        unsigned int line, uintptr_t reserved) {
    (void) expr; (void) func; (void) file; (void) line; (void) reserved;
}

__attribute__((constructor)) static void win_compat_init(void) {
    _set_invalid_parameter_handler(ignore_invalid_parameter);
    // Binary mode everywhere: readsb writes binary files and expects "\n" to stay "\n".
    _set_fmode(_O_BINARY);
    _setmode(_fileno(stdin), _O_BINARY);
    _setmode(_fileno(stdout), _O_BINARY);
    _setmode(_fileno(stderr), _O_BINARY);
    WSADATA wsa;
    if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) {
        fprintf(stderr, "FATAL: WSAStartup failed\n");
        _exit(1);
    }
}

// ---- errno -------------------------------------------------------------------------------

static int errno_from_wsa(int wsa) {
    switch (wsa) {
        case WSAEWOULDBLOCK: return EAGAIN;
        case WSAEINPROGRESS: return EINPROGRESS;
        case WSAEALREADY: return EALREADY;
        case WSAEINTR: return EINTR;
        case WSAEBADF: return EBADF;
        case WSAEACCES: return EACCES;
        case WSAEFAULT: return EFAULT;
        case WSAEINVAL: return EINVAL;
        case WSAEMFILE: return EMFILE;
        case WSAENOTSOCK: return ENOTSOCK;
        case WSAEDESTADDRREQ: return EDESTADDRREQ;
        case WSAEMSGSIZE: return EMSGSIZE;
        case WSAEPROTOTYPE: return EPROTOTYPE;
        case WSAENOPROTOOPT: return ENOPROTOOPT;
        case WSAEPROTONOSUPPORT: return EPROTONOSUPPORT;
        case WSAEOPNOTSUPP: return EOPNOTSUPP;
        case WSAEAFNOSUPPORT: return EAFNOSUPPORT;
        case WSAEADDRINUSE: return EADDRINUSE;
        case WSAEADDRNOTAVAIL: return EADDRNOTAVAIL;
        case WSAENETDOWN: return ENETDOWN;
        case WSAENETUNREACH: return ENETUNREACH;
        case WSAENETRESET: return ENETRESET;
        case WSAECONNABORTED: return ECONNABORTED;
        case WSAECONNRESET: return ECONNRESET;
        case WSAENOBUFS: return ENOBUFS;
        case WSAEISCONN: return EISCONN;
        case WSAENOTCONN: return ENOTCONN;
        case WSAESHUTDOWN: return EPIPE;
        case WSAETIMEDOUT: return ETIMEDOUT;
        case WSAECONNREFUSED: return ECONNREFUSED;
        case WSAELOOP: return ELOOP;
        case WSAENAMETOOLONG: return ENAMETOOLONG;
        case WSAEHOSTUNREACH: return EHOSTUNREACH;
        case WSAEHOSTDOWN: return EHOSTUNREACH;
        case WSANOTINITIALISED: return EINVAL;
        case 0: return 0;
        default: return EIO;
    }
}

static int fail_wsa(void) {
    errno = errno_from_wsa(WSAGetLastError());
    return -1;
}

static int fail_errno(int e) {
    errno = e;
    return -1;
}

// ---- fd ranges ---------------------------------------------------------------------------

static int is_socket_fd(int fd) { return fd >= WIN_FD_SOCKET_BASE; }
static int is_epoll_fd(int fd) { return fd >= WIN_FD_EPOLL_BASE && fd < WIN_FD_SOCKET_BASE; }
static SOCKET to_socket(int fd) { return (SOCKET) (fd - WIN_FD_SOCKET_BASE); }
static HANDLE to_epoll(int fd) { return (HANDLE) (intptr_t) (fd - WIN_FD_EPOLL_BASE); }

// Kernel handle values fit comfortably below 2^29 in practice; refuse any that don't rather
// than hand readsb an fd from the wrong range.
static int from_socket(SOCKET s) {
    if (s >= (SOCKET) (INT32_MAX - WIN_FD_SOCKET_BASE)) {
        closesocket(s);
        return fail_errno(EMFILE);
    }
    return (int) s + WIN_FD_SOCKET_BASE;
}

#define SOCKET_OR_FAIL(fd, s) \
    if (!is_socket_fd(fd)) return fail_errno(ENOTSOCK); \
    SOCKET s = to_socket(fd)

static int set_nonblocking(SOCKET s, int on) {
    u_long mode = on ? 1 : 0;
    return ioctlsocket(s, FIONBIO, &mode) == 0 ? 0 : fail_wsa();
}

// Sockets must not leak into processes readsb might start (it starts none today).
static void no_inherit(SOCKET s) {
    SetHandleInformation((HANDLE) s, HANDLE_FLAG_INHERIT, 0);
}

// ---- sockets -----------------------------------------------------------------------------

int win_socket(int af, int type, int protocol) {
    int nonblock = type & SOCK_NONBLOCK;
    type &= ~(SOCK_NONBLOCK | SOCK_CLOEXEC);
    SOCKET s = WSASocketW(af, type, protocol, NULL, 0, WSA_FLAG_OVERLAPPED | WSA_FLAG_NO_HANDLE_INHERIT);
    if (s == INVALID_SOCKET) {
        return fail_wsa();
    }
    if (nonblock && set_nonblocking(s, 1) != 0) {
        int e = errno;
        closesocket(s);
        return fail_errno(e);
    }
    return from_socket(s);
}

int win_accept4(int fd, struct sockaddr *addr, socklen_t *len, int flags) {
    SOCKET_OR_FAIL(fd, ls);
    SOCKET s = accept(ls, addr, len);
    if (s == INVALID_SOCKET) {
        return fail_wsa();
    }
    no_inherit(s);
    // An accepted socket inherits the listener's non-blocking mode on Windows; set it
    // explicitly either way to match accept4() semantics.
    if (set_nonblocking(s, (flags & SOCK_NONBLOCK) != 0) != 0) {
        int e = errno;
        closesocket(s);
        return fail_errno(e);
    }
    return from_socket(s);
}

int win_bind(int fd, const struct sockaddr *addr, socklen_t len) {
    SOCKET_OR_FAIL(fd, s);
    return bind(s, addr, len) == 0 ? 0 : fail_wsa();
}

int win_connect(int fd, const struct sockaddr *addr, socklen_t len) {
    SOCKET_OR_FAIL(fd, s);
    if (connect(s, addr, len) == 0) {
        return 0;
    }
    int wsa = WSAGetLastError();
    // A non-blocking connect in progress is WSAEWOULDBLOCK on Windows, EINPROGRESS on POSIX.
    errno = wsa == WSAEWOULDBLOCK ? EINPROGRESS : errno_from_wsa(wsa);
    return -1;
}

int win_listen(int fd, int backlog) {
    SOCKET_OR_FAIL(fd, s);
    return listen(s, backlog) == 0 ? 0 : fail_wsa();
}

int win_setsockopt(int fd, int level, int name, const void *val, socklen_t len) {
    SOCKET_OR_FAIL(fd, s);
    // On Windows SO_REUSEADDR lets a second process bind a port that is already listening.
    // POSIX's meaning (rebinding over TIME_WAIT) is Windows' default, so drop it.
    if (level == SOL_SOCKET && name == SO_REUSEADDR) {
        return 0;
    }
    return setsockopt(s, level, name, (const char *) val, len) == 0 ? 0 : fail_wsa();
}

int win_getsockopt(int fd, int level, int name, void *val, socklen_t *len) {
    SOCKET_OR_FAIL(fd, s);
    if (getsockopt(s, level, name, (char *) val, len) != 0) {
        return fail_wsa();
    }
    // readsb passes SO_ERROR to strerror() and compares it with errno values.
    if (level == SOL_SOCKET && name == SO_ERROR && val && *len >= (socklen_t) sizeof(int)) {
        *(int *) val = errno_from_wsa(*(int *) val);
    }
    return 0;
}

int win_getsockname(int fd, struct sockaddr *addr, socklen_t *len) {
    SOCKET_OR_FAIL(fd, s);
    return getsockname(s, addr, len) == 0 ? 0 : fail_wsa();
}

int win_getpeername(int fd, struct sockaddr *addr, socklen_t *len) {
    SOCKET_OR_FAIL(fd, s);
    return getpeername(s, addr, len) == 0 ? 0 : fail_wsa();
}

int win_shutdown(int fd, int how) {
    SOCKET_OR_FAIL(fd, s);
    return shutdown(s, how) == 0 ? 0 : fail_wsa();
}

ssize_t win_send(int fd, const void *buf, size_t len, int flags) {
    SOCKET_OR_FAIL(fd, s);
    int n = send(s, (const char *) buf, len > INT_MAX ? INT_MAX : (int) len, flags);
    return n == SOCKET_ERROR ? fail_wsa() : n;
}

ssize_t win_recv(int fd, void *buf, size_t len, int flags) {
    SOCKET_OR_FAIL(fd, s);
    int n = recv(s, (char *) buf, len > INT_MAX ? INT_MAX : (int) len, flags);
    return n == SOCKET_ERROR ? fail_wsa() : n;
}

ssize_t win_read(int fd, void *buf, size_t len) {
    if (is_socket_fd(fd)) {
        return win_recv(fd, buf, len, 0);
    }
    if (fd < 0 || is_epoll_fd(fd)) {
        return fail_errno(EBADF);
    }
    return _read(fd, buf, len > INT_MAX ? INT_MAX : (unsigned) len);
}

ssize_t win_write(int fd, const void *buf, size_t len) {
    if (is_socket_fd(fd)) {
        return win_send(fd, buf, len, 0);
    }
    if (fd < 0 || is_epoll_fd(fd)) {
        return fail_errno(EBADF);
    }
    return _write(fd, buf, len > INT_MAX ? INT_MAX : (unsigned) len);
}

int win_close(int fd) {
    if (is_socket_fd(fd)) {
        return closesocket(to_socket(fd)) == 0 ? 0 : fail_wsa();
    }
    if (is_epoll_fd(fd)) {
        return epoll_close(to_epoll(fd)) == 0 ? 0 : -1;
    }
    if (fd < 0) {
        return fail_errno(EBADF);
    }
    return _close(fd);
}

int win_fcntl(int fd, int cmd, ...) {
    va_list ap;
    va_start(ap, cmd);
    long arg = va_arg(ap, long);
    va_end(ap);
    switch (cmd) {
        case F_GETFD:
        case F_GETFL:
            // Windows can't report a socket's blocking mode; readsb only ORs O_NONBLOCK in.
            return fd < 0 ? fail_errno(EBADF) : 0;
        case F_SETFD:
            return 0;
        case F_SETFL:
            if (is_socket_fd(fd)) {
                return set_nonblocking(to_socket(fd), (arg & O_NONBLOCK) != 0);
            }
            return 0;
        default:
            return fail_errno(EINVAL);
    }
}

// ---- epoll -------------------------------------------------------------------------------

int win_epoll_create(int size) {
    HANDLE h = epoll_create(size > 0 ? size : 1);
    if (h == NULL) {
        return -1; // wepoll sets errno
    }
    if ((uintptr_t) h >= (uintptr_t) (WIN_FD_SOCKET_BASE - WIN_FD_EPOLL_BASE)) {
        epoll_close(h);
        return fail_errno(EMFILE);
    }
    return (int) (intptr_t) h + WIN_FD_EPOLL_BASE;
}

int win_epoll_ctl(int epfd, int op, int fd, struct epoll_event *event) {
    if (!is_epoll_fd(epfd)) {
        return fail_errno(EBADF);
    }
    // wepoll only polls sockets (that is all readsb registers on Windows: no eventfds).
    if (!is_socket_fd(fd)) {
        return fail_errno(EPERM);
    }
    return epoll_ctl(to_epoll(epfd), op, to_socket(fd), event);
}

int win_epoll_wait(int epfd, struct epoll_event *events, int maxevents, int timeout) {
    if (!is_epoll_fd(epfd)) {
        return fail_errno(EBADF);
    }
    return epoll_wait(to_epoll(epfd), events, maxevents, timeout);
}

// ---- files -------------------------------------------------------------------------------

int win_rename(const char *from, const char *to) {
    if (MoveFileExA(from, to, MOVEFILE_REPLACE_EXISTING | MOVEFILE_COPY_ALLOWED)) {
        return 0;
    }
    switch (GetLastError()) {
        case ERROR_FILE_NOT_FOUND:
        case ERROR_PATH_NOT_FOUND: return fail_errno(ENOENT);
        case ERROR_ACCESS_DENIED:
        case ERROR_SHARING_VIOLATION: return fail_errno(EACCES);
        default: return fail_errno(EIO);
    }
}

int win_mkdir(const char *path, int mode) {
    (void) mode;
    return _mkdir(path);
}

int win_symlink(const char *target, const char *linkpath) {
    (void) target; (void) linkpath;
    return fail_errno(ENOSYS);
}

// ---- memory ------------------------------------------------------------------------------

void *win_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off) {
    (void) addr; (void) off;
    if (!(flags & MAP_ANONYMOUS) || fd != -1) {
        errno = ENOSYS;
        return MAP_FAILED;
    }
    DWORD protect = (prot & PROT_WRITE) ? PAGE_READWRITE : (prot & PROT_READ) ? PAGE_READONLY : PAGE_NOACCESS;
    void *p = VirtualAlloc(NULL, len, MEM_RESERVE | MEM_COMMIT, protect);
    if (!p) {
        errno = ENOMEM;
        return MAP_FAILED;
    }
    return p;
}

int win_munmap(void *addr, size_t len) {
    (void) len; // readsb always unmaps whole mappings
    return VirtualFree(addr, 0, MEM_RELEASE) ? 0 : fail_errno(EINVAL);
}

// ---- sys/resource.h ----------------------------------------------------------------------

int getrlimit(int resource, struct rlimit *rl) {
    if (resource != RLIMIT_NOFILE || !rl) {
        return fail_errno(EINVAL);
    }
    // Sockets aren't limited by the C runtime's fd table; report a generous fixed value.
    rl->rlim_cur = rl->rlim_max = 16384;
    return 0;
}

int setrlimit(int resource, const struct rlimit *rl) {
    (void) resource; (void) rl;
    return 0;
}

static _Thread_local int thread_nice;

int getpriority(int which, int who) {
    (void) which; (void) who;
    errno = 0;
    return thread_nice;
}

int setpriority(int which, int who, int prio) {
    (void) which; (void) who;
    thread_nice = prio;
    int p = prio >= 10 ? THREAD_PRIORITY_LOWEST
          : prio > 0 ? THREAD_PRIORITY_BELOW_NORMAL
          : prio < 0 ? THREAD_PRIORITY_ABOVE_NORMAL
          : THREAD_PRIORITY_NORMAL;
    return SetThreadPriority(GetCurrentThread(), p) ? 0 : fail_errno(EPERM);
}

// ---- libc extras -------------------------------------------------------------------------

// random()/srandom(): 31-bit results like glibc's. readsb uses them for jitter and IDs, not
// security. Per-thread xorshift state, seeded from the srandom() seed and the thread id.
static volatile unsigned int random_seed = 1;
static _Thread_local uint64_t random_state;

void srandom(unsigned int seed) {
    random_seed = seed;
    random_state = 0;
}

long random(void) {
    if (random_state == 0) {
        random_state = ((uint64_t) random_seed << 32) ^ GetCurrentThreadId() ^ 0x9E3779B97F4A7C15ull;
    }
    uint64_t x = random_state;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    random_state = x;
    return (long) (x >> 33);
}

char *strcasestr(const char *haystack, const char *needle) {
    size_t n = strlen(needle);
    if (n == 0) {
        return (char *) haystack;
    }
    for (; *haystack; haystack++) {
        if (_strnicmp(haystack, needle, n) == 0) {
            return (char *) haystack;
        }
    }
    return NULL;
}

char *strsep(char **stringp, const char *delim) {
    char *start = *stringp;
    if (!start) {
        return NULL;
    }
    char *end = start + strcspn(start, delim);
    if (*end) {
        *end = '\0';
        *stringp = end + 1;
    } else {
        *stringp = NULL;
    }
    return start;
}

static const char *parse_int(const char *s, int maxdigits, int *out) {
    int v = 0, n = 0;
    while (n < maxdigits && *s >= '0' && *s <= '9') {
        v = v * 10 + (*s++ - '0');
        n++;
    }
    if (n == 0) {
        return NULL;
    }
    *out = v;
    return s;
}

char *strptime(const char *s, const char *format, struct tm *tm) {
    int v;
    for (const char *f = format; *f; f++) {
        if (*f != '%') {
            if (*s++ != *f) {
                return NULL;
            }
            continue;
        }
        switch (*++f) {
            case 'Y': if (!(s = parse_int(s, 4, &v))) return NULL; tm->tm_year = v - 1900; break;
            case 'm': if (!(s = parse_int(s, 2, &v))) return NULL; tm->tm_mon = v - 1; break;
            case 'd': if (!(s = parse_int(s, 2, &v))) return NULL; tm->tm_mday = v; break;
            case 'H': if (!(s = parse_int(s, 2, &v))) return NULL; tm->tm_hour = v; break;
            case 'M': if (!(s = parse_int(s, 2, &v))) return NULL; tm->tm_min = v; break;
            case 'S': if (!(s = parse_int(s, 2, &v))) return NULL; tm->tm_sec = v; break;
            case '%': if (*s++ != '%') return NULL; break;
            default: return NULL; // not needed by readsb
        }
    }
    return (char *) s;
}

int sched_getaffinity(int pid, size_t size, cpu_set_t *set) {
    (void) pid; (void) size;
    DWORD_PTR process_mask, system_mask;
    if (!GetProcessAffinityMask(GetCurrentProcess(), &process_mask, &system_mask) || !process_mask) {
        process_mask = 1;
    }
    set->mask = process_mask;
    return 0;
}

int sched_setaffinity(int pid, size_t size, const cpu_set_t *set) {
    (void) size;
    if (pid != 0) {
        return fail_errno(ESRCH);
    }
    return SetThreadAffinityMask(GetCurrentThread(), (DWORD_PTR) set->mask) ? 0 : fail_errno(EINVAL);
}

// ---- sleeping ----------------------------------------------------------------------------

static _Thread_local HANDLE sleep_timer;

static void sleep_ns(int64_t ns) {
    if (!sleep_timer) {
        sleep_timer = CreateWaitableTimerExW(NULL, NULL, CREATE_WAITABLE_TIMER_HIGH_RESOLUTION, TIMER_ALL_ACCESS);
        if (!sleep_timer) {
            // Older Windows: a plain timer (default 15.6 ms resolution) is still correct, just coarser.
            sleep_timer = CreateWaitableTimerExW(NULL, NULL, 0, TIMER_ALL_ACCESS);
        }
    }
    LARGE_INTEGER due;
    due.QuadPart = -(ns + 99) / 100; // relative, in 100 ns units
    if (sleep_timer && SetWaitableTimer(sleep_timer, &due, 0, NULL, NULL, FALSE)) {
        WaitForSingleObject(sleep_timer, INFINITE);
    } else {
        Sleep((DWORD) ((ns + 999999) / 1000000));
    }
}

int win_clock_nanosleep(clockid_t clock, int flags, const struct timespec *req, struct timespec *rem) {
    if (!req || req->tv_nsec < 0 || req->tv_nsec >= 1000000000L) {
        return EINVAL;
    }
    int64_t ns;
    if (flags & TIMER_ABSTIME) {
        struct timespec now;
        if (clock_gettime(clock, &now) != 0) {
            return EINVAL;
        }
        ns = ((int64_t) req->tv_sec - now.tv_sec) * 1000000000LL + (req->tv_nsec - now.tv_nsec);
    } else {
        ns = (int64_t) req->tv_sec * 1000000000LL + req->tv_nsec;
    }
    if (ns > 0) {
        sleep_ns(ns);
    }
    if (rem) {
        rem->tv_sec = 0;
        rem->tv_nsec = 0;
    }
    return 0;
}

// ---- signals -----------------------------------------------------------------------------

win_sighandler_t win_signal(int sig, win_sighandler_t handler) {
    switch (sig) {
        case SIGINT: case SIGTERM: case SIGABRT: case SIGFPE: case SIGILL: case SIGSEGV:
            return signal(sig, handler);
        default:
            return SIG_DFL; // SIGPIPE, SIGHUP, SIGQUIT...: no such thing on Windows
    }
}

const char *strsignal(int sig) {
    switch (sig) {
        case SIGINT: return "Interrupt";
        case SIGTERM: return "Terminated";
        case SIGABRT: return "Aborted";
        case SIGSEGV: return "Segmentation fault";
        default: return "Unknown signal";
    }
}

static win_sighandler_t stdin_eof_handler;

static DWORD WINAPI stdin_watch_thread(LPVOID unused) {
    (void) unused;
    HANDLE in = GetStdHandle(STD_INPUT_HANDLE);
    char buf[256];
    DWORD n;
    while (ReadFile(in, buf, sizeof(buf), &n, NULL) && n > 0) {
        // Input is ignored; only end of file matters.
    }
    stdin_eof_handler(SIGTERM);
    return 0;
}

void win_watch_stdin(win_sighandler_t handler) {
    const char *on = getenv("READSB_EXIT_ON_STDIN_EOF");
    if (!on || strcmp(on, "1") != 0) {
        return;
    }
    stdin_eof_handler = handler;
    HANDLE t = CreateThread(NULL, 64 * 1024, stdin_watch_thread, NULL, 0, NULL);
    if (t) {
        CloseHandle(t);
    } else {
        fprintf(stderr, "win_watch_stdin: CreateThread failed (%lu)\n", GetLastError());
    }
}

// ---- termios -----------------------------------------------------------------------------

int tcgetattr(int fd, struct termios *t) { (void) fd; (void) t; return fail_errno(ENOSYS); }
int tcsetattr(int fd, int act, const struct termios *t) { (void) fd; (void) act; (void) t; return fail_errno(ENOSYS); }
int tcflush(int fd, int queue) { (void) fd; (void) queue; return fail_errno(ENOSYS); }
int cfsetispeed(struct termios *t, speed_t s) { (void) t; (void) s; return 0; }
int cfsetospeed(struct termios *t, speed_t s) { (void) t; (void) s; return 0; }
void cfmakeraw(struct termios *t) { (void) t; }
int ioctl(int fd, unsigned long req, ...) { (void) fd; (void) req; return fail_errno(ENOSYS); }
