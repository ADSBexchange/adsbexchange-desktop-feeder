// Force-included (-include) into every readsb translation unit. Fills the gaps between glibc,
// which readsb targets, and Windows (mingw-w64 UCRT + winpthreads + wepoll).
//
// File descriptors: readsb keeps sockets and epoll instances in plain ints and calls read(),
// write() and close() on them. Windows sockets are SOCKET handles and wepoll instances are
// HANDLEs, neither of which the C runtime's fd functions understand. So the wrappers below hand
// readsb ints in separate ranges and route every call by range:
//
//   0 .. 0x1FFFFFFF            C runtime fds (files, stdin/stdout/stderr)
//   0x20000000 .. 0x3FFFFFFF   wepoll HANDLE + WIN_FD_EPOLL_BASE
//   0x40000000 ..              SOCKET + WIN_FD_SOCKET_BASE
//
// readsb never indexes arrays by fd, so the large values are safe.
#pragma once

#ifndef WIN32_LEAN_AND_MEAN
#define WIN32_LEAN_AND_MEAN
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#ifndef NOGDI
#define NOGDI
#endif

#include <winsock2.h>
#include <ws2tcpip.h>
#include <afunix.h>
#include <windows.h>
#include <io.h>
#include <direct.h>
#include <fcntl.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>

#include "wepoll.h"

// windows.h leftovers that collide with readsb identifiers.
#undef ERROR
#undef OPTIONAL
#undef IN
#undef OUT
#undef interface

#define WIN_FD_EPOLL_BASE  0x20000000
#define WIN_FD_SOCKET_BASE 0x40000000

// ---- errno -------------------------------------------------------------------------------
// The UCRT has distinct EAGAIN (11) and EWOULDBLOCK (140). readsb treats them alike, and the
// socket wrappers report WSAEWOULDBLOCK as EAGAIN, so make them the same value.
#undef EWOULDBLOCK
#define EWOULDBLOCK EAGAIN

// ---- endian.h / byteswap.h (x64 is little-endian) ----------------------------------------
#define __LITTLE_ENDIAN 1234
#define __BIG_ENDIAN 4321
#define __BYTE_ORDER __LITTLE_ENDIAN
#define LITTLE_ENDIAN __LITTLE_ENDIAN
#define BIG_ENDIAN __BIG_ENDIAN
#define BYTE_ORDER __BYTE_ORDER
#define bswap_16(x) __builtin_bswap16(x)
#define bswap_32(x) __builtin_bswap32(x)
#define bswap_64(x) __builtin_bswap64(x)
#define htole16(x) ((uint16_t)(x))
#define htole32(x) ((uint32_t)(x))
#define htole64(x) ((uint64_t)(x))
#define le16toh(x) ((uint16_t)(x))
#define le32toh(x) ((uint32_t)(x))
#define le64toh(x) ((uint64_t)(x))
#define htobe16(x) __builtin_bswap16(x)
#define htobe32(x) __builtin_bswap32(x)
#define htobe64(x) __builtin_bswap64(x)
#define be16toh(x) __builtin_bswap16(x)
#define be32toh(x) __builtin_bswap32(x)
#define be64toh(x) __builtin_bswap64(x)

// ---- sockets -----------------------------------------------------------------------------
typedef uint32_t in_addr_t;
typedef uint16_t in_port_t;
typedef unsigned short sa_family_t;

#define SOCK_NONBLOCK 0x0800
#define SOCK_CLOEXEC  0x80000
#ifndef SOL_TCP
#define SOL_TCP IPPROTO_TCP
#endif
#ifndef TCP_KEEPIDLE
#define TCP_KEEPIDLE 3
#endif
#ifndef TCP_KEEPCNT
#define TCP_KEEPCNT 16
#endif
#ifndef TCP_KEEPINTVL
#define TCP_KEEPINTVL 17
#endif
#ifndef MSG_NOSIGNAL
#define MSG_NOSIGNAL 0
#endif
#define SHUT_RD SD_RECEIVE
#define SHUT_WR SD_SEND
#define SHUT_RDWR SD_BOTH

// fcntl() is only used to make sockets non-blocking. O_NONBLOCK is chosen clear of the
// UCRT's _O_* open flags.
#define F_GETFD 1
#define F_SETFD 2
#define F_GETFL 3
#define F_SETFL 4
#define FD_CLOEXEC 1
#define O_NONBLOCK 0x01000000

int win_socket(int af, int type, int protocol);
int win_accept4(int fd, struct sockaddr *addr, socklen_t *len, int flags);
int win_bind(int fd, const struct sockaddr *addr, socklen_t len);
int win_connect(int fd, const struct sockaddr *addr, socklen_t len);
int win_listen(int fd, int backlog);
int win_setsockopt(int fd, int level, int name, const void *val, socklen_t len);
int win_getsockopt(int fd, int level, int name, void *val, socklen_t *len);
int win_getsockname(int fd, struct sockaddr *addr, socklen_t *len);
int win_getpeername(int fd, struct sockaddr *addr, socklen_t *len);
int win_shutdown(int fd, int how);
ssize_t win_send(int fd, const void *buf, size_t len, int flags);
ssize_t win_recv(int fd, void *buf, size_t len, int flags);
ssize_t win_read(int fd, void *buf, size_t len);
ssize_t win_write(int fd, const void *buf, size_t len);
int win_close(int fd);
int win_fcntl(int fd, int cmd, ...);

// ---- epoll (wepoll) ----------------------------------------------------------------------
int win_epoll_create(int size);
int win_epoll_ctl(int epfd, int op, int fd, struct epoll_event *event);
int win_epoll_wait(int epfd, struct epoll_event *events, int maxevents, int timeout);

// ---- files -------------------------------------------------------------------------------
// POSIX rename() replaces an existing target; the UCRT's fails. readsb writes every JSON file
// to a temporary name and renames it over the old one.
int win_rename(const char *from, const char *to);
int win_mkdir(const char *path, int mode);
int win_symlink(const char *target, const char *linkpath);

// ---- memory (sys/mman.h, anonymous mappings only) ----------------------------------------
#define PROT_NONE  0
#define PROT_READ  1
#define PROT_WRITE 2
#define MAP_PRIVATE   0x02
#define MAP_ANONYMOUS 0x20
#define MAP_ANON MAP_ANONYMOUS
#define MAP_FAILED ((void *) -1)
void *win_mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off);
int win_munmap(void *addr, size_t len);

// ---- sys/resource.h ----------------------------------------------------------------------
#define PRIO_PROCESS 0
#define RLIMIT_NOFILE 7
typedef unsigned long rlim_t;
#define RLIM_INFINITY ((rlim_t) -1)
struct rlimit { rlim_t rlim_cur; rlim_t rlim_max; };
int getrlimit(int resource, struct rlimit *rl);
int setrlimit(int resource, const struct rlimit *rl);
// readsb nices its own worker threads; mapped to the calling thread's Windows priority.
int getpriority(int which, int who);
int setpriority(int which, int who, int prio);

// ---- signals -----------------------------------------------------------------------------
#ifndef SIGHUP
#define SIGHUP 1
#endif
#ifndef SIGQUIT
#define SIGQUIT 3
#endif
#ifndef SIGPIPE
#define SIGPIPE 13
#endif
typedef void (*win_sighandler_t)(int);
win_sighandler_t win_signal(int sig, win_sighandler_t handler);
const char *strsignal(int sig);
// Calls handler(SIGTERM) when stdin reaches end of file, if READSB_EXIT_ON_STDIN_EOF=1 is set.
// The desktop app holds readsb's stdin open and closes it to ask for a clean shutdown; if the
// app dies, Windows closes the pipe and readsb exits instead of lingering.
void win_watch_stdin(win_sighandler_t handler);

// ---- libc extras (glibc / BSD functions the UCRT lacks) ----------------------------------
// readsb's aligned allocation path is disabled upstream ("if (alignment && 0)"). x64 malloc is
// 16-byte aligned, which covers smaller alignments; larger ones fail rather than misalign.
static inline void *aligned_alloc(size_t alignment, size_t size) {
    return alignment <= 16 ? malloc(size) : NULL;
}
long random(void);
void srandom(unsigned int seed);
char *strcasestr(const char *haystack, const char *needle);
char *strsep(char **stringp, const char *delim);
// Supports the %Y %m %d %H %M %S conversions readsb uses (SBS input timestamps).
char *strptime(const char *s, const char *format, struct tm *tm);
#define timegm(tm) _mkgmtime(tm)

// readsb counts usable CPUs with sched_getaffinity() + CPU_COUNT(), and can pin its decoder
// thread to a core (sched_setaffinity on the calling thread).
typedef struct { uint64_t mask; } cpu_set_t;
int sched_getaffinity(int pid, size_t size, cpu_set_t *set);
int sched_setaffinity(int pid, size_t size, const cpu_set_t *set);
#define CPU_COUNT(set) __builtin_popcountll((set)->mask)
#define CPU_ZERO(set) ((set)->mask = 0)
#define CPU_SET(cpu, set) ((set)->mask |= (cpu) < 64 ? 1ull << (cpu) : 0)

// ---- sleeping ----------------------------------------------------------------------------
// winpthreads' clock_nanosleep() rejects CLOCK_MONOTONIC with TIMER_ABSTIME without sleeping,
// which readsb's paced file input (--throttle) relies on. This one sleeps on a high-resolution
// waitable timer (about 0.5 ms precision instead of Sleep()'s 15.6 ms).
int win_clock_nanosleep(clockid_t clock, int flags, const struct timespec *req, struct timespec *rem);

// ---- threads -----------------------------------------------------------------------------
// readsb only uses pthread_tryjoin_np() to join workers at shutdown, polling until a timeout.
// A blocking join is fine: a hung shutdown ends with the desktop app killing the process.
#define pthread_tryjoin_np(thread, retval) pthread_join((thread), (retval))

// ---- termios (serial Beast devices; not supported on Windows) ----------------------------
typedef unsigned int tcflag_t;
typedef unsigned char cc_t;
typedef unsigned int speed_t;
#define NCCS 32
struct termios {
    tcflag_t c_iflag, c_oflag, c_cflag, c_lflag;
    cc_t c_cc[NCCS];
};
#define TCSANOW 0
#define TCIFLUSH 0
#define TCIOFLUSH 2
#define VMIN 6
#define VTIME 5
#define CS8 0x30
#define CSIZE 0x30
#define CREAD 0x80
#define CLOCAL 0x800
#define PARENB 0x100
#define CSTOPB 0x40
#define CRTSCTS 0x80000000u
#define IGNPAR 0x4
#define TIOCMBIS 0x5416
#define TIOCM_RTS 0x004
#define TIOCM_DTR 0x002
#define O_NOCTTY 0
#define B0 0
#define B9600 9600
#define B19200 19200
#define B38400 38400
#define B57600 57600
#define B115200 115200
#define B230400 230400
#define B460800 460800
#define B500000 500000
#define B576000 576000
#define B921600 921600
#define B1000000 1000000
#define B1152000 1152000
#define B1500000 1500000
#define B2000000 2000000
#define B2500000 2500000
#define B3000000 3000000
#define B3500000 3500000
#define B4000000 4000000
int tcgetattr(int fd, struct termios *t);
int tcsetattr(int fd, int act, const struct termios *t);
int tcflush(int fd, int queue);
int cfsetispeed(struct termios *t, speed_t s);
int cfsetospeed(struct termios *t, speed_t s);
void cfmakeraw(struct termios *t);
int ioctl(int fd, unsigned long req, ...);

#ifndef WIN_COMPAT_IMPL
// Route readsb's calls to the wrappers. Function-like macros, defined after the system
// headers above have declared the real functions.
#define socket(a, t, p) win_socket(a, t, p)
#define accept(fd, a, l) win_accept4(fd, a, l, 0)
#define accept4(fd, a, l, f) win_accept4(fd, a, l, f)
#define bind(fd, a, l) win_bind(fd, a, l)
#define connect(fd, a, l) win_connect(fd, a, l)
#define listen(fd, b) win_listen(fd, b)
#define setsockopt(fd, l, n, v, s) win_setsockopt(fd, l, n, v, s)
#define getsockopt(fd, l, n, v, s) win_getsockopt(fd, l, n, v, s)
#define getsockname(fd, a, l) win_getsockname(fd, a, l)
#define getpeername(fd, a, l) win_getpeername(fd, a, l)
#define shutdown(fd, h) win_shutdown(fd, h)
#define send(fd, b, l, f) win_send(fd, b, l, f)
#define recv(fd, b, l, f) win_recv(fd, b, l, f)
#define read(fd, b, l) win_read(fd, b, l)
#define write(fd, b, l) win_write(fd, b, l)
#define close(fd) win_close(fd)
#define fcntl(fd, ...) win_fcntl(fd, __VA_ARGS__)
#define epoll_create(n) win_epoll_create(n)
#define epoll_create1(f) win_epoll_create(1)
#define epoll_ctl(ep, op, fd, ev) win_epoll_ctl(ep, op, fd, ev)
#define epoll_wait(ep, ev, n, t) win_epoll_wait(ep, ev, n, t)
#define rename(a, b) win_rename(a, b)
#define mkdir(p, m) win_mkdir(p, m)
#define symlink(t, l) win_symlink(t, l)
#define mmap(a, l, p, f, fd, o) win_mmap(a, l, p, f, fd, o)
#define munmap(a, l) win_munmap(a, l)
#define signal(s, h) win_signal(s, h)
#define clock_nanosleep(c, f, req, rem) win_clock_nanosleep(c, f, req, rem)
#endif
