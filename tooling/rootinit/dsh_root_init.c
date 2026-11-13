/*
 * dsh-root-init: enter the bundled rootfs as the real uid 0.
 *
 * The app's privileged peer (started through `su`, already uid 0) execs this file
 * from the APK's nativeLibraryDir. We create a private mount namespace, mount the
 * kernel filesystems the guest needs, chroot into the rootfs and exec the engine.
 * Because the namespace is private, the bind mounts never reach the phone's mount
 * table and disappear as soon as this process exits.
 *
 * Build (see runtime/build-runtime.sh):
 *   $NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang \
 *       -O2 -static -o libdshroot-init.so dsh_root_init.c
 *
 * Usage:
 *   libdshroot-init.so --root <rootfs> [--bind host:guest[:ro]]... \
 *                      [--env KEY=VALUE]... [--no-unshare] -- <argv...>
 *
 * Exit codes: 1 bad usage, 3 mount, 4 chroot/chdir, 5 exec.
 */
#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

/* The manifest's guest environment is 14 entries today; leave room for the app's own. */
#define MAX_BINDS 24
#define MAX_ENVS 32
#define PATH_CAP 4096

static const char *g_rootfs;
static int g_private_ns; /* 1 while our mounts live in a private namespace */
static char g_mounted[32][PATH_CAP];
static int g_nmounted;

static void mkdir_p(const char *path) {
    char buf[PATH_CAP];
    snprintf(buf, sizeof(buf), "%s", path);
    for (char *p = buf + 1; *p != '\0'; p++) {
        if (*p != '/') continue;
        *p = '\0';
        mkdir(buf, 0755);
        *p = '/';
    }
    mkdir(buf, 0755);
}

static void report(int code, const char *step) {
    int saved = errno;
    dprintf(2, "dsh-root-init: %s failed: %s (errno=%d)\n", step, strerror(saved), saved);
    if (g_rootfs == NULL) return;
    char path[PATH_CAP];
    snprintf(path, sizeof(path), "%s/tmp/root-init.err", g_rootfs);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644);
    if (fd < 0) return;
    dprintf(fd, "code=%d step=%s errno=%d (%s) privateNs=%d\n", code, step, saved,
            strerror(saved), g_private_ns);
    close(fd);
}

/* Without a private namespace our mounts would outlive us, so take them down. */
static void unmount_all(void) {
    if (g_private_ns) return;
    for (int i = g_nmounted - 1; i >= 0; i--) {
        if (umount2(g_mounted[i], MNT_DETACH) != 0) {
            dprintf(2, "dsh-root-init: leftover mount %s (%s)\n", g_mounted[i], strerror(errno));
        }
    }
}

static void fail(int code, const char *step) {
    report(code, step);
    unmount_all();
    _exit(code);
}

static void track(const char *target) {
    if (g_nmounted < (int)(sizeof(g_mounted) / sizeof(g_mounted[0]))) {
        snprintf(g_mounted[g_nmounted++], PATH_CAP, "%s", target);
    }
}

static void guest_path(char *out, size_t len, const char *guest) {
    snprintf(out, len, "%s%s", g_rootfs, guest);
}

static void mount_kernel_fs(const char *type, const char *guest, int fatal) {
    char target[PATH_CAP];
    guest_path(target, sizeof(target), guest);
    mkdir_p(target);
    if (mount(type, target, type, 0, NULL) != 0) {
        if (fatal) fail(3, type);
        dprintf(2, "dsh-root-init: warning: %s not mounted (%s)\n", guest, strerror(errno));
        return;
    }
    track(target);
}

static void bind_mount(const char *host, const char *guest, int read_only) {
    char target[PATH_CAP];
    struct stat st;
    if (stat(host, &st) != 0) {
        dprintf(2, "dsh-root-init: warning: host path %s missing, skipped\n", host);
        return;
    }
    guest_path(target, sizeof(target), guest);
    mkdir_p(target);
    if (mount(host, target, NULL, MS_BIND | MS_REC, NULL) != 0) fail(3, guest);
    track(target);
    if (read_only && mount(NULL, target, NULL, MS_BIND | MS_REMOUNT | MS_RDONLY, NULL) != 0) {
        dprintf(2, "dsh-root-init: warning: %s left writable (%s)\n", guest, strerror(errno));
    }
}

/* Split "host:guest[:ro]" in place; returns 1 on success. */
static int parse_bind(char *spec, char **host, char **guest, int *read_only) {
    char *colon = strchr(spec, ':');
    if (colon == NULL) return 0;
    *colon = '\0';
    *host = spec;
    *guest = colon + 1;
    *read_only = 0;
    char *flag = strrchr(*guest, ':');
    if (flag != NULL && strcmp(flag, ":ro") == 0) {
        *flag = '\0';
        *read_only = 1;
    }
    return (**host != '\0' && **guest == '/');
}

int main(int argc, char **argv) {
    char *binds[MAX_BINDS];
    int nbinds = 0;
    char *envs[MAX_ENVS];
    int nenvs = 0;
    int no_unshare = 0;
    int i;

    for (i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--") == 0) {
            i++;
            break;
        } else if (strcmp(argv[i], "--root") == 0 && i + 1 < argc) {
            g_rootfs = argv[++i];
        } else if (strcmp(argv[i], "--bind") == 0 && i + 1 < argc) {
            if (nbinds >= MAX_BINDS) {
                dprintf(2, "dsh-root-init: too many --bind (max %d)\n", MAX_BINDS);
                return 1;
            }
            binds[nbinds++] = argv[++i];
        } else if (strcmp(argv[i], "--env") == 0 && i + 1 < argc) {
            if (nenvs >= MAX_ENVS) {
                dprintf(2, "dsh-root-init: too many --env (max %d)\n", MAX_ENVS);
                return 1;
            }
            envs[nenvs++] = argv[++i];
        } else if (strcmp(argv[i], "--no-unshare") == 0) {
            no_unshare = 1;
        } else {
            dprintf(2, "dsh-root-init: unknown argument %s\n", argv[i]);
            return 1;
        }
    }
    if (g_rootfs == NULL || i >= argc) {
        dprintf(2, "usage: %s --root <rootfs> [--bind host:guest[:ro]]... "
                   "[--env KEY=VALUE]... [--no-unshare] -- <argv...>\n", argv[0]);
        return 1;
    }

    setsid();
    umask(0022);

    if (!no_unshare && unshare(CLONE_NEWNS) == 0) {
        g_private_ns = 1;
        if (mount(NULL, "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) {
            dprintf(2, "dsh-root-init: warning: make-rprivate failed (%s)\n", strerror(errno));
        }
    } else if (!no_unshare) {
        dprintf(2, "dsh-root-init: warning: unshare(CLONE_NEWNS) unavailable (%s); "
                   "mounts will be torn down on exit\n", strerror(errno));
    }

    mkdir_p(g_rootfs);
    char tmp[PATH_CAP];
    guest_path(tmp, sizeof(tmp), "/tmp");
    mkdir_p(tmp);

    mount_kernel_fs("proc", "/proc", 1);
    mount_kernel_fs("sysfs", "/sys", 0);
    bind_mount("/dev", "/dev", 0);

    for (int b = 0; b < nbinds; b++) {
        char *spec = binds[b];
        char *host;
        char *guest;
        int read_only;
        if (!parse_bind(spec, &host, &guest, &read_only)) {
            dprintf(2, "dsh-root-init: bad --bind %s (want host:guest[:ro])\n", spec);
            unmount_all();
            return 1;
        }
        bind_mount(host, guest, read_only);
    }

    if (chroot(g_rootfs) != 0) fail(4, "chroot");
    if (chdir("/") != 0) fail(4, "chdir /");

    setenv("HOME", "/root", 1);
    setenv("USER", "root", 1);
    setenv("LOGNAME", "root", 1);
    setenv("SHELL", "/bin/bash", 1);
    setenv("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", 1);
    setenv("LANG", "C.UTF-8", 1);
    setenv("TERM", "xterm-256color", 1);
    setenv("TMPDIR", "/tmp", 1);
    for (int e = 0; e < nenvs; e++) {
        char *eq = strchr(envs[e], '=');
        if (eq == NULL) continue;
        *eq = '\0';
        setenv(envs[e], eq + 1, 1);
        *eq = '=';
    }

    execv(argv[i], &argv[i]);
    fail(5, "execv");
    return 5;
}
