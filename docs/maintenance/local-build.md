# 本地打包指南（Linux）

这份文档只做一件事：在一台 Linux 机器上，从源码产出**能装、能跑、带 root 通道和内置 dsh-purge** 的 arm64 release APK。

- 不在 GitHub Actions 上出包，也不下载任何预编译包。
- 不需要 root 权限；脚本把工具链装在 `~/dev`。
- Windows / macOS 无法完成第 4 步，只能跑 Dart 侧。

## 0. 前置检查

```sh
uname -m                  # 期望 x86_64（arm64 主机不支持，见文末）
bash --version | head -1
python3 --version         # 3.8+
git --version
command -v curl unzip xz sha256sum
df -h "$HOME"             # 建议 ≥ 15 GB 可用
nproc                     # 建议 ≥ 4 核
```

网络需要能访问：`github.com`、`storage.flutter-io.cn`、`mirrors.tuna.tsinghua.edu.cn`、`packages.termux.dev`、`registry.npmjs.org`、`dl.google.com`。需要代理时在**同一个 shell** 里导出即可（curl、git、Gradle、Flutter 都会读）：

```sh
export HTTPS_PROXY=http://127.0.0.1:7890 HTTP_PROXY=http://127.0.0.1:7890
export ALL_PROXY=$HTTPS_PROXY   # apt 走 http 代理时也需要
```

不要用 `sudo` 执行仓库里的脚本。

## 1. 取代码

```sh
git clone https://github.com/AtT6669/dsh-go-root && cd dsh-go-root
```

## 2. 安装工具链（一次性）

```sh
bash tooling/setup-toolchain.sh
source tooling/env.sh
flutter doctor -v
```

脚本做的事（可重复执行，已完成的步骤会跳过）：

| 组件 | 版本 | 位置 |
| --- | --- | --- |
| Temurin JDK | 17 | `~/dev/jdk-17` |
| Flutter | 3.47.7（stable） | `~/dev/flutter` |
| Android cmdline-tools | build 13114758 | `~/dev/android-sdk` |
| Android SDK 包 | platform-tools、compileSdk、对应 build-tools、NDK、cmake | `~/dev/android-sdk` |

结束时生成 `tooling/env.sh`（`JAVA_HOME`、`ANDROID_HOME`、`PATH` 和国内镜像变量）。**每个新 shell 都要先 `source tooling/env.sh`**，`tooling/build-apk.sh` 在 PATH 里找不到 `flutter` 时也会自动 source 它。

机器上已有 Flutter 的话，仍建议用脚本装：仓库按 3.47.7 的 Gradle 插件与 SDK 默认版本取证，换版本容易出现 compileSdk / NDK 对不上的编译错误。

## 3. 生成多语言输出

```sh
python3 app/tool/l10n_merge.py
python3 app/tool/l10n_merge.py --check   # 期望退出码 0
```

`app/lib/l10n/gen/` 是**构建输入**且不入库忽略，跳过这一步会因为缺少生成文件而编译失败。

## 4. 构建 PRoot 运行时

```sh
bash runtime/build-runtime.sh arm64-v8a
python3 -I tooling/check_runtime_bundle.py arm64   # 自检
```

产出在 `runtime/out/arm64-v8a/`：

- `assets/runtime/base-arm64-v8a.tar.xz`、`engine-arm64-v8a.tar.xz`、`manifest.json`
- `jniLibs/arm64-v8a/libproot.so`、`libproot-loader.so`、`libtalloc.so`、`libandroid-shmem.so`

这一步会下载 Ubuntu base rootfs、Node、dsh 引擎与 pnpm 锁、内置插件，全部按 `runtime/build-runtime.sh` 顶部的 `PIN` 表校验 sha256；任何一项下载不到或被上游替换都会直接中止。缓存留在 `runtime/.cache/`，失败后重跑不会重复下载。

**上游删档（404）怎么处理**：

```sh
# Ubuntu deb 锁过期
UPDATE_DEB_LOCK=1 bash runtime/build-runtime.sh arm64-v8a
git diff runtime/locks/ubuntu-noble-*.json   # 确认版本变化后再提交

# Termux 包（proot / libtalloc / libandroid-shmem）过期
# 改 runtime/build-runtime.sh 顶部 *_VERSION 与下面 PIN 表里的文件名、sha256；
# 新哈希从 https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-<arch>/Packages 取，
# 或下载 deb 后自己 sha256sum 核对。
```

## 5. 打包

```sh
bash tooling/build-apk.sh -f arm64
```

- 脚本先跑 `tooling/check_runtime_bundle.py arm64`；缺文件或 size/sha256 不符会直接退出。
- `versionName` 取 `app/pubspec.yaml` 的 `version:`；`versionCode` 是构建分钟（`(年-2026)×10^8 + MMDDhhmm`，UTC+8）。
- 打包后用 aapt2 回读 APK 里的 versionName / versionCode，对不上就报错。

常用参数：`-f x64`（模拟器）、`--build-number N`、`--build-name X.Y.Z`、`--dry-run`、`--keep-daemon`、`-- --dart-define=K=V`。

产物：

```
app/build/app/outputs/flutter-apk/app-arm64-release.apk
```

首次执行 `flutter build apk` 会自动补上未跟踪的 `gradlew` 与 wrapper jar。

## 6. 签名（可选）

不配置也能装能测，release 包会回退成 debug 签名。要覆盖官方签名的安装包，创建 `app/android/key.properties`：

```properties
storeFile=dshgo-release.jks        # 相对 app/android 目录，也可写绝对路径
storePassword=******
keyAlias=dshgo
keyPassword=******
```

```sh
chmod 600 app/android/key.properties
bash tooling/build-apk.sh -f arm64
```

`build.gradle.kts` 只在四项齐全且 keystore 文件存在时才启用 release 签名；缺项会静默回退 debug 签名（不会让构建失败）。

## 7. 验收

构建侧：

```sh
apk=app/build/app/outputs/flutter-apk/app-arm64-release.apk
sha256sum "$apk"
"$ANDROID_HOME"/build-tools/*/aapt2 dump badging "$apk" | head -3
"$ANDROID_HOME"/build-tools/*/apksigner verify --print-certs "$apk"
unzip -l "$apk" | grep -E 'assets/runtime|lib/arm64-v8a/libproot' | head
```

`unzip -l` 必须能看到 `assets/runtime/manifest.json`、两个 layer 压缩包和四个 `lib*.so`；没有就说明装进去的是个没有引擎的壳。

装机后（真机、已 root）：

1. 设置 → 能力 → Root 增强 → 一次授权 → 自检，显示 `uid=0`；
2. `dsh-root id -u` 返回 `0`；
3. 设置 → 插件 能看到 `dsh-purge`。

## 8. 排错

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `flutter: command not found` | 没进工具链环境 | `source tooling/env.sh` |
| 下载 404（`*.deb`） | 上游镜像删了锁定版本 | 见第 4 节 `UPDATE_DEB_LOCK=1` / 改 Termux 版本与 PIN |
| `sha256 mismatch` | 锁文件与实际产物不一致 | 下载该文件 `sha256sum` 后改 `runtime/locks/*`（或 PIN 表） |
| `missing runtime file` | 没建 runtime / ABI 不匹配 | 先跑第 4 步，确认 `runtime/out/arm64-v8a` |
| `l10n` 相关编译错 | 生成文件过期 | 重跑 `python3 app/tool/l10n_merge.py` |
| Gradle 依赖下载失败 | 网络 | 复用同一代理环境重跑；`--keep-daemon` 便于观察 |
| `versionCode` 冲突/降级 | 装的包号更大 | `--build-number` 指定更大的值，或先卸载 |
| 编译报 Kotlin / Dart 错误 | 源码问题 | 贴完整日志，这类才是代码缺陷 |
| WSL 下极慢或权限错 | `/mnt/c` 的 9p 文件系统 | 在 `~/` 下重新 clone 后构建 |

## 9. 交付清单

给测试的人至少提供：

- `app-arm64-release.apk` 及其 `sha256sum`
- `aapt2 dump badging` 里的 `versionName` / `versionCode`
- 构建时的 `runtime/out/arm64-v8a/assets/runtime/manifest.json`（runtime 版本标识、layer 大小）
- 是否配置了 release 签名（决定能否覆盖官方包）

## 附：为什么要求 x86_64 主机

`runtime/build-runtime.sh` 用宿主机的 `xz`、`tar`、`ar`、`python3` 组装 PRoot 运行时，并依赖 x86_64 的预编译工具链；PRoot 本身是给 Android arm64 用的预编译 `.so`。在 arm64 Linux（含多数云 ARM 机型）上可以尝试，但不在支持范围内。
