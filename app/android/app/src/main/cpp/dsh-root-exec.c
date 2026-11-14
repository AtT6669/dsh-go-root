/*
 * dsh-root-exec: enter a real root Linux environment for the DSH engine.
 *
 * Runs as uid 0 (spawned through `su -c`), creates a private mount namespace,
 * binds the Android pieces the engine needs, then chroots into the runtime
 * rootfs and execs the guest command.
 *
 * The private namespace matters: mounts disappear when this process group
 * exits, so a crashed engine cannot leave the device with stale bind mounts.
 *
 * Build (static; the guest has its own glibc, the host does not):
 *   aarch64-linux-android26-clang -static -O2 -o libdsh-root-exec.so dsh-root-exec.c
 *
 * Usage:
 *   dsh-root-exec --rootfs <dir> --cwd <dir> [--pidfile <host path>]
 *                 [--bind <src>:<dst>]... [--tmpfs <dst>:<size>]
 *                 [--mask <path>]... -- <argv...>
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <sched.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define MAX_BINDS 24
#define MAX_MASKS 8
#define MAX_TMPFS 4
#define MAX_ARGV 96

struct bind_spec { char *src; char *dst; };
struct tmpfs_spec { char *dst; char *size; };

static void fail(const char *what) {
  fprintf(stderr, "dsh-root-exec: %s failed: %s\n", what, strerror(errno));
  _exit(125);
}

static void mkdirs(const char *path) {
  char tmp[PATH_MAX];
  size_t len = strlen(path);
  if (len == 0 || len >= sizeof(tmp)) fail("mkdirs path");
  memcpy(tmp, path, len + 1);
  for (char *p = tmp + 1; *p; p++) {
    if (*p != '/') continue;
    *p = '\0';
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) fail("mkdir");
    *p = '/';
  }
  if (mkdir(tmp, 0755) != 0 && errno != EEXIST) fail("mkdir");
}

static void split_pair(char *value, char **left, char **right) {
  char *colon = strrchr(value, ':');
  if (colon == NULL || colon == value || colon[1] == '\0') {
    fprintf(stderr, "dsh-root-exec: bad pair '%s'\n", value);
    _exit(125);
  }
  *colon = '\0';
  *left = value;
  *right = colon + 1;
}

int main(int argc, char **argv) {
  char *rootfs = NULL;
  char *cwd = "/root";
  char *pidfile = NULL;
  struct bind_spec binds[MAX_BINDS];
  struct tmpfs_spec tmpfs[MAX_TMPFS];
  char *masks[MAX_MASKS];
  char *cmd[MAX_ARGV];
  int nb = 0, nt = 0, nm = 0, nc = 0;

  for (int i = 1; i < argc; i++) {
    if (strcmp(argv[i], "--rootfs") == 0 && i + 1 < argc) {
      rootfs = argv[++i];
    } else if (strcmp(argv[i], "--cwd") == 0 && i + 1 < argc) {
      cwd = argv[++i];
    } else if (strcmp(argv[i], "--pidfile") == 0 && i + 1 < argc) {
      pidfile = argv[++i];
    } else if (strcmp(argv[i], "--bind") == 0 && i + 1 < argc) {
      if (nb >= MAX_BINDS) fail("too many binds");
      split_pair(argv[++i], &binds[nb].src, &binds[nb].dst);
      nb++;
    } else if (strcmp(argv[i], "--tmpfs") == 0 && i + 1 < argc) {
      if (nt >= MAX_TMPFS) fail("too many tmpfs");
      split_pair(argv[++i], &tmpfs[nt].dst, &tmpfs[nt].size);
      nt++;
    } else if (strcmp(argv[i], "--mask") == 0 && i + 1 < argc) {
      if (nm >= MAX_MASKS) fail("too many masks");
      masks[nm++] = argv[++i];
    } else if (strcmp(argv[i], "--") == 0) {
      for (i++; i < argc && nc < MAX_ARGV; i++) cmd[nc++] = argv[i];
      break;
    } else {
      fprintf(stderr, "dsh-root-exec: unknown argument '%s'\n", argv[i]);
      _exit(125);
    }
  }
  if (rootfs == NULL || rootfs[0] != '/' || nc == 0) {
    fprintf(stderr, "dsh-root-exec: --rootfs and a guest command are required\n");
    _exit(125);
  }

  /* Own process group: the app stops the whole tree with kill(-pid). */
  if (setsid() < 0) fail("setsid");

  if (pidfile != NULL) {
    /* Written before chroot: this pid survives exec. */
    FILE *f = fopen(pidfile, "w");
    if (f != NULL) {
      fprintf(f, "%d\n", (int)getpid());
      fclose(f);
      chmod(pidfile, 0644);
    }
  }

  if (unshare(CLONE_NEWNS) != 0) fail("unshare(CLONE_NEWNS)");
  if (mount("none", "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) fail("mount private");

  for (int i = 0; i < nb; i++) {
    mkdirs(binds[i].dst);
    if (mount(binds[i].src, binds[i].dst, NULL, MS_BIND | MS_REC, NULL) != 0) {
      fprintf(stderr, "dsh-root-exec: bind %s -> %s failed: %s\n",
              binds[i].src, binds[i].dst, strerror(errno));
      _exit(125);
    }
  }

  for (int i = 0; i < nt; i++) {
    mkdirs(tmpfs[i].dst);
    if (mount("tmpfs", tmpfs[i].dst, "tmpfs", 0, tmpfs[i].size) != 0) {
      fprintf(stderr, "dsh-root-exec: tmpfs %s failed: %s\n", tmpfs[i].dst, strerror(errno));
      _exit(125);
    }
  }

  /* Hide the host SELinux view; the guest has no business reading it. */
  for (int i = 0; i < nm; i++) {
    char empty[PATH_MAX];
    snprintf(empty, sizeof(empty), "%s/var/lib/dsh-app/empty", rootfs);
    mkdirs(empty);
    mkdirs(masks[i]);
    if (mount(empty, masks[i], NULL, MS_BIND | MS_REC, NULL) != 0) {
      fprintf(stderr, "dsh-root-exec: mask %s failed: %s\n", masks[i], strerror(errno));
      _exit(125);
    }
  }

  if (chdir(rootfs) != 0) fail("chdir rootfs");
  if (chroot(rootfs) != 0) fail("chroot");
  if (chdir(cwd) != 0) fail("chdir cwd");

  execv(cmd[0], cmd);
  fail("execv");
  return 125;
}
