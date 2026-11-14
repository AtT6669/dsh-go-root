# DSH Go Root

A fork of [DSH Go](https://github.com/ccch1mneyyy/dsh-go) that runs the whole execution surface as real uid 0 instead of PRoot's fake root.

- Engine: `su -c` → static helper → private mount namespace → chroot → `dsh web`
- First launch: asks for root; a refused or missing grant keeps the app on the gate screen
- Agent: the engine's own shell, file and terminal tools are uid 0; app-side privileged calls go through a resident `RootBroker`
- Package: `io.github.ccch1mneyyy.dsh_root` (installs alongside upstream)

**Requirements:** arm64, Android 8.0+, a working Magisk / KernelSU / APatch grant.

## What changed

| | Upstream | This fork |
|---|---|---|
| Engine | `libproot.so -0` | static helper + chroot, uid 0 |
| Root channel | virtual display only | engine, tools, files, services |
| First launch | no gate | hard gate in Dart and in `RuntimeController` |
| Package id | `...dsh_app` | `...dsh_root` |
| Build | local | same scripts + GitHub Actions |

## Startup

```text
RootGate → su -c "id -u" == 0 → RuntimeController → DshProcess.start(root=true)
  └ dsh-root-exec --rootfs ... --bind /proc /sys /dev /system /apex
                  --tmpfs /dev/shm:64m --mask /sys/fs/selinux
                  -- /usr/bin/env -i ... dsh web
```

Stop uses `su -c "kill -TERM -<pgid>"`; the app cannot signal uid 0 directly.

## Install

Download `app-arm64-release.apk` from [Releases](https://github.com/AtT6669/dsh-go-root/releases):

```bash
adb install -r app-arm64-release.apk
```

The current release is debug-signed. Configure a keystore in repository secrets for production signing.

## Build

```bash
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/28.2.13676358
tooling/build-root-exec.sh
runtime/build-runtime.sh arm64-v8a
cd app && flutter pub get && cd ..
tooling/build-apk.sh -f arm64 --release
```

Online: `.github/workflows/root-apk.yml` (push to `main` or `workflow_dispatch`).

## Limits

- The Activity/Flutter UI keeps the normal app uid; Android cannot change that for a sideloaded APK. All execution surfaces (engine, terminal, agent tools, files, privileged services) run as uid 0, and the UI is a client that cannot start the runtime without a grant.
- **L3 commands** (`mkfs`, `dd of=/dev/block`, `rm -rf /`, `setenforce 0`, uninstalling the root manager) are refused; every command is audited to `/data/adb/dsh-go/audit.log`.
- arm64 only in CI; switch the workflow and `-f x64` for x86_64.

## License

AGPL-3.0 derivative of the upstream project. See [LICENSE](LICENSE).
