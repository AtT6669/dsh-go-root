# DSH Go · Root 增强版

> 这是 [ccch1mneyyy/dsh-go](https://github.com/ccch1mneyyy/dsh-go) 的**非官方二开分支**，只做两件事：
> **让 root 用户在 App 里真正能用 root**，以及**把 [dsh-purge](https://github.com/kingcharleslzy-ai/dsh-purge) 预装进内置运行时**。
> 原版的其他功能（PRoot Linux 环境、遥控电脑、插件市场、宠物等）保持原样。

<p>
  <a href="LICENSE"><img alt="许可证 AGPL-3.0" src="https://img.shields.io/badge/%E8%AE%B8%E5%8F%AF%E8%AF%81-AGPL--3.0-4B67B4?style=flat-square"></a>
  <img alt="Android 8+" src="https://img.shields.io/badge/Android-8%2B-4B67B4?style=flat-square">
  <img alt="Root" src="https://img.shields.io/badge/Root-Magisk%20%2F%20KernelSU-4B67B4?style=flat-square">
  <a href="https://github.com/ccch1mneyyy/dsh-go"><img alt="上游仓库" src="https://img.shields.io/badge/%E4%B8%8A%E6%B8%B8-ccch1mneyyy%2Fdsh--go-2B3245?style=flat-square"></a>
</p>

---

## 为什么要这个分支

原版给 root 预留了一条通道，但实际用起来"连不连都一样"：命令要么被层层的保活/路径/API 预检挡住，要么只能做很窄的事，Agent 在里面基本拿不到真正的 root 能力。

这一版把 root 当成一等公民：**一次授权，之后 App 与内置 CLI 的所有 root 命令静默执行、全部留痕**，并且把 Agent 发现入口也一并打通。

## 与原版的差异

| 方面 | 原版 | 本分支 |
| --- | --- | --- |
| Root 执行 | 预留通道，能力受限、受环境影响 | **通用执行通道**：任意命令、stdout/stderr、超时、后台运行、按 pid 查询与终止 |
| 授权模型 | 逐条确认/多次弹窗 | **一次总授权**，可在页面随时撤销 |
| 留痕 | 无 | 每条命令写入审计日志 `files/root-exec/root-audit.log`（4 MiB 轮转） |
| 后台保活 | 受 API 34 门槛约束 | root 会话**默认保活**；API 34 的额外限制只作用于非 root 路径 |
| 插件 | 需自己装 | **构建期预装** `dsh-purge` 1.6.91 与依赖 `yaml` 2.9.1，装机即用，不可从插件市场卸载 |
| Agent 支持 | — | 内置 `dsh-root` 技能，Agent 自动发现并调用 |

## Root 增强怎么用

**前置条件**：手机已 root（Magisk / KernelSU / APatch 等），并且能给 App 授予 `su`。

1. 打开 **设置 → 能力 → Root 增强**（与「手机控制」同级，不在其子页里）。
2. 点「一次授权」，在 root 管理器里同意 `su` 请求。
3. 页面会显示 `已授权 / 已连接 / 当前 uid / 桥接端口`；点「自检」，`uid=0` 即成功。
4. 之后：

```bash
# 前台执行，返回退出码与输出
dsh-root id -u
dsh-root -c /data/local/tmp ls -l

# 后台执行，立即拿到 pid 与日志路径
dsh-root -d 'logcat -c; logcat > /data/local/tmp/agent.log'
dsh-root --poll 12345
dsh-root --kill 12345

# 状态与自检
dsh-root --status
dsh-root --self-test
```

`dsh-root` 是运行时里的 CLI（`usr/local/bin/dsh-root`），Agent 通过内置 `dsh-root` 技能调用它，也可以直接用。

**边界（都是硬限制，不是建议）**：

- 前台单条命令默认 20 s，上限 25 s；超时返回退出码 `124`。
- 后台任务最多 8 个，超出返回错误。
- 单次输出截断到 256 KiB。
- 桥接只监听 `127.0.0.1`，请求要带 Bearer 令牌并校验调用方 uid；令牌由引擎环境注入，不落盘。
- 撤销授权后通道立即失效。

## 内置插件 dsh-purge

- 包：`dsh-purge-community` **1.6.91**（MIT），依赖 `yaml` **2.9.1**。
- 位置：运行时内 `/opt/dsh-go/plugins/dsh-purge-community`，依赖在 `/opt/dsh-go/plugins/node_modules/yaml`。
- 来源与校验：构建时从上游 Release / npm 下载，按 `runtime/locks/bundled-plugins.json` 里的 sha256 校验后才解包，校验失败构建直接中止。
- 确认已装：设置 → 插件 里能看到；或在 guest 里 `ls /opt/dsh-go/plugins`。

> 内置插件在 `runtime/build-runtime.sh` 里完成，**不是**装完 App 再下载。想换版本改 `runtime/locks/bundled-plugins.json` 后重新构建运行时。

## 安装

### 直接用现成 APK

1. 打开 [Actions](https://github.com/AtT6669/dsh-go-root/actions/workflows/build-apk.yml) → **Test APK** → **Run workflow**（`flavor=arm64`）。
2. 跑完后在运行页底部 **Artifacts** 下载 `dsh-go-arm64-apk`，解压得到 `app-arm64-release.apk`。
3. 传到手机安装，arm64 覆盖绝大多数真机；模拟器选 `x64`。

> **签名**：仓库没有配置签名密钥时，构建回退到**调试签名**——能装能测，但不能覆盖官方签名版，也不能上架。
> 手机上有官方版时请先卸载；卸载会清空对话、Linux 环境与设置。

### 自己配签名（可选）

在仓库 Secrets 里配置以下四项，工作流会自动改用正式密钥签名：

```
ANDROID_KEYSTORE_BASE64      # .jks 的 base64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

## 从源码构建

### GitHub Actions（推荐，本机无需任何工具链）

`.github/workflows/build-apk.yml` 在 Ubuntu 24.04 上完成：装 JDK 17 / Flutter 3.47.7 / Android SDK → `python3 app/tool/l10n_merge.py` 生成多语言 → `bash runtime/build-runtime.sh arm64-v8a` → `bash tooling/build-apk.sh -f arm64` → 上传 artifact。公开仓库的运行器不计费。

可填三个输入：`flavor`（arm64/x64）、`build_name`（默认取 `app/pubspec.yaml`）、`build_number`（默认取构建分钟）。

### 本地 Linux

```bash
# 1) 一次性装工具链（JDK 17 / Flutter 3.47.7 / Android SDK / NDK）
bash tooling/setup-toolchain.sh && source tooling/env.sh

# 2) 重新生成多语言输出（ARB 改动后必须做）
python3 app/tool/l10n_merge.py

# 3) 构建运行时（PRoot + Ubuntu + Node + dsh 引擎 + 内置插件）
bash runtime/build-runtime.sh arm64-v8a

# 4) 打包
bash tooling/build-apk.sh -f arm64          # 产物在 app/build/app/outputs/flutter-apk/
```

注意：

- 第 3 步**必须**在 Linux（或 WSL2 + 发行版）上跑；Windows / macOS 只能跑 Dart 部分。
- release 打包前 `tooling/check_runtime_bundle.py` 会校验 `runtime/out/<abi>` 的四件套（`libproot.so`、`libproot-loader.so`、`libtalloc.so`、`libandroid-shmem.so`）与 manifest 的 size/sha256，缺一个就中止——**debug 包会跳过这个校验，装上也没有引擎**。
- `versionCode` 由构建分钟生成，`versionName` 取自 `app/pubspec.yaml` 的 `version:`（当前 `1.0.15`）。

## 文档

| 文档 | 内容 |
| --- | --- |
| [docs/guide/zh/13-root.md](docs/guide/zh/13-root.md) | 使用指南：Root 增强一章 |
| [docs/design/root-exec.md](docs/design/root-exec.md) | 设计：通道拓扑、四个操作、限额与失败语义 |
| [docs/maintenance/root-exec-contract.md](docs/maintenance/root-exec-contract.md) | 维护：CLI 与桥接的契约（改协议前必读） |
| [docs/design/bundled-plugins.md](docs/design/bundled-plugins.md) | 设计：构建期预装与锁文件 |
| [docs/guide/README.md](docs/guide/README.md) | 原版完整使用指南 |

## 风险提示

Root 权限意味着任何命令都能改动系统分区、框架与数据。本分支的设计（一次授权 + 审计日志 + 限额）只是降低误操作与滥用概率，**不构成安全保证**：

- 授权即等于把整台手机的写权限交给 App 与其 Agent；
- 审计日志只记录不阻止，泄漏或被清空都应由你自己负责；
- 请不要在含支付、银行、企业数据的主力机上随手授权。

使用本软件即表示你理解并自担上述风险。

## 与上游的关系

- 上游：`ccch1mneyyy/dsh-go`（作者 ccch1mneyyy）。本项目是**非官方**二开，不代表上游立场，也不向上游报 bug。
- 上游的英文说明仍是 [README.en.md](README.en.md)（原版内容，未改）。
- 许可证继承上游 **AGPL-3.0**：本分支的修改同样以 AGPL-3.0 发布；分发本项目的二进制时须一并提供对应源码。
- 内置的 `dsh-purge` 为 MIT，`yaml` 为 ISC/MIT，版权归各自作者。
