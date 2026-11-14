# DSH Go Root 版

[![Root APK](https://github.com/AtT6669/dsh-go-root/actions/workflows/root-apk.yml/badge.svg)](https://github.com/AtT6669/dsh-go-root/actions/workflows/root-apk.yml)

把 [DSH Go](https://github.com/ccch1mneyyy/dsh-go) 从「PRoot 里的假 root」改成「整机真 root」的二次开发分支。上游预留了一条 root 通道，但 agent 实际调不到；这个分支不再绕，直接把引擎和工具的执行面放进 uid 0。

- 引擎：`su -c` → 静态 helper → 私有 mount namespace → chroot → `dsh web`（uid 0）
- 首次启动：强制申请 Root，拒绝就不进入应用，也不启动引擎
- Agent：引擎自带 shell/文件/终端全部是 root；App 侧特权命令走常驻 `RootBroker`
- 安装包：`io.github.ccch1mneyyy.dsh_root`，可与上游共存

> 只支持 **arm64 + Android 8.0+**，且设备必须已经有可用的 Magisk / KernelSU / APatch。

## 和上游的区别

| | 上游 | 这个分支 |
|---|---|---|
| 引擎启动 | `libproot.so -0`，ptrace 假 root | 静态 helper + chroot，真 uid 0 |
| root 通道 | 只服务「不打扰模式」的虚拟屏 | 引擎、agent 工具、文件、特权服务全部可用 |
| `android_shell` | 固定 App uid，校验 `realUid == myUid` | 保留原语义；需要 root 的命令走引擎终端或 `RootBroker` |
| 首次启动 | 无 root 准入 | 未授权停在授权页，原生双入口也会拒绝启动 |
| 包名 | `io.github.ccch1mneyyy.dsh_app` | `io.github.ccch1mneyyy.dsh_root` |
| 构建 | 本地 Flutter + 运行时 | 同一套脚本，外加 GitHub Actions 在线出 APK |

## 启动链路

```text
RootGate (Flutter 首帧之前)
  └ su -c "id -u" == 0 ? 继续 : 授权页（不可跳过）

RuntimeController.start / startProcess
  └ RootProbe 已授权 ? 继续 : root_required + ERROR

DshProcess.start(root = true)
  └ RootHelper.ensure()        复制 helper 到 /data/adb/dsh-go/bin，0700
  └ 复用 manifest launchArgv 的 guest 部分（profile / phone / search / auth / speech overlay 不变）
  └ dsh-root-exec --rootfs ... --cwd /root --pidfile tmp/dsh-root.pid
                  --bind /proc /sys /dev /dev/pts /system /apex
                  --tmpfs /dev/shm:64m --mask /sys/fs/selinux
                  -- /usr/bin/env -i ... dsh web
  └ ProcessBuilder("su", "-c", ...)
```

停止时走 `su -c "kill -TERM -<pgid>"`，因为 App 进程无权给 uid 0 发信号；5 秒后 `KILL`。

## 首次运行

1. 安装 APK，打开应用。
2. Root 管理器弹出授权，选择允许并记住。
3. `su -c "id -u"` 返回 `0` 后才进入主界面并安装/启动运行时。
4. 拒绝、超时、没有 su、授权被撤销：停在授权页，只能重试或退出。冷启动会重新拉起 `RootBroker` 验证授权，不盲信缓存。

## Agent 的 root 能力

- **引擎内**：`id -u` 返回 0，`/system`、`/proc`、`/dev` 已绑定，apt/pip/npm 与系统命令都可直接跑。
- **App 侧**：`RootBroker` 是一个常驻 uid 0 的 `app_process`，JSON 行协议：
  `exec` / `fs.read` / `fs.write` / `fs.append` / `fs.remove` / `fs.list` / `signal` / `cleanup`。
  文件操作被限制在启动时 `--allow` 的根目录内。

命令按风险分级：

| 级别 | 例子 | 行为 |
|---|---|---|
| L0 读 | `pm list`、`getprop`、`dumpsys` | 直接执行 |
| L1 写 | 普通文件/目录操作 | 执行并审计 |
| L2 破坏性 | `rm -r`、`pm clear`、`reboot`、`settings put` | 执行并审计 |
| L3 禁止 | `mkfs`、`dd of=/dev/block`、`rm -rf /`、`setenforce 0`、卸载 Root 管理器 | 拒绝 |

审计写入 `/data/adb/dsh-go/audit.log`，token、邮箱、长密钥会脱敏，超过 1 MiB 自动轮转。

## 下载与安装

从 [Releases](https://github.com/AtT6669/dsh-go-root/releases) 下载 `app-arm64-release.apk`：

```bash
adb install -r app-arm64-release.apk
```

当前 Release 使用 debug 签名，可直接侧载测试；正式分发需要在仓库 Secrets 配置 keystore 后重跑工作流。

## 自己构建

依赖：Flutter 3.47.7、JDK 17、Android SDK、**NDK 28.2.13676358**、Python 3、`git`、`xz`。

```bash
export ANDROID_NDK_HOME=$ANDROID_SDK_ROOT/ndk/28.2.13676358

# 1. 静态 chroot helper（两个 ABI）
tooling/build-root-exec.sh

# 2. 随包 Linux 运行时（只做 arm64）
runtime/build-runtime.sh arm64-v8a

# 3. Dart 依赖与 APK
cd app && flutter pub get && cd ..
tooling/build-apk.sh -f arm64 --release
```

产物：`app/build/app/outputs/flutter-apk/app-arm64-release.apk`。

在线构建：`.github/workflows/root-apk.yml`，推 `main` 或手动 `workflow_dispatch`，产物为 Actions artifact `dsh-go-root-arm64`。

## 关键代码

| 路径 | 作用 |
|---|---|
| `app/android/app/src/main/cpp/dsh-root-exec.c` | 静态 helper：namespace、bind、tmpfs、mask、pidfile、chroot、exec |
| `app/android/app/src/main/kotlin/.../root/RootProbe.kt` | 授权探测与状态持久化 |
| `.../root/RootBroker.kt` / `RootBrokerMain.kt` | 常驻 root 服务的客户端与服务端 |
| `.../root/RootPolicy.kt` / `RootAudit.kt` | 风险分级与审计 |
| `.../root/RootEngine.kt` / `DshProcess.startAsRoot` | 引擎 root 启动 |
| `.../root/RootPlugin.kt` | `dsh/root` MethodChannel |
| `app/lib/root/root_gate.dart` / `root_required_page.dart` | Dart 首帧硬拦截与授权页 |
| `runtime/lib/rtbuild.py` | manifest 的 `rootArgv` 模板 |
| `tooling/build-root-exec.sh` | helper 的 NDK 静态编译 |

## 已知限制

- **UI 进程不是 uid 0**：Activity 由 zygote 以应用 uid 启动，第三方 APK 无法改变。本项目的「整个 App 真 Root」指所有执行面（引擎、终端、agent 工具、文件、特权服务）为 uid 0，UI 只作无特权客户端；没有授权时 UI 也进不去。
- 只构建 arm64；x86_64 需要把工作流与 `-f` 参数改成 `x64`。
- 当前 Release 为 debug 签名。
- 上游的非 root PRoot 启动路径仍然保留在代码里，但 Root 版构建固定走 root 分支。

## 上游与许可

- 上游：https://github.com/ccch1mneyyy/dsh-go
- 本项目为 AGPL-3.0 派生作品，继续遵循 [LICENSE](LICENSE)。
- 上游作者、插件与第三方组件的版权归各自作者所有。
