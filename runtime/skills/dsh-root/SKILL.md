---
name: dsh-root
description: 以手机真正的 root 身份执行 Android 命令（`dsh-root`），用于 PRoot 环境做不到的事，比如访问 GPU/freedreno 节点、读写 /data 下其他应用的数据、挂载、属性与守护进程。需要 App 的「Root 增强」页已授权。Run Android commands as real root through the app's Root 增强 bridge.
whenToUse: 任务需要在 Android 侧以 root 执行命令，而 PRoot 内的 bash 权限不够或看不到目标路径；例如 freedreno/GPU、/data/data、/system、mount、setprop、logcat、常驻守护进程。普通 Linux 命令、编译、装包不要用它。
---

# dsh-root：以 root 身份操作手机

`dsh-root` 是 App「Root 增强」通道的命令行入口。它把命令交给手机上的 root 同伴（`/system/bin/sh`）执行，不是 Ubuntu 的 bash，也不经过 PRoot 的文件系统。

## 什么时候用

只有 PRoot 里做不到、又确实需要 Android root 的时候：

- 访问 GPU/显示相关节点（如 `/dev/kgsl-3d0`、`/sys/class/kgsl`、freedreno 调试、GPU 频率与负载）。
- 读写 `/data/data/<其它应用>`、`/data/local/tmp` 之外的系统目录，或 `/system`、`/vendor`。
- `mount`、`setprop`、`settings`、`cmd`、`dumpsys`、`logcat`、`svc` 这类系统命令。
- 需要在 App 退出后继续运行的守护进程（用后台模式）。

PRoot 内能做的事（编译、跑 Linux 工具、装包、读写 `/root` 和工作区）继续用普通 shell，不要绕到 root 通道。

## 前提

1. App 里打开「设置 → Root 增强」，点**一次授权**并连接（root 管理器会弹窗，需要用户自己点允许）。
2. 授权后必须重启运行时（停止并重新启动本机引擎），新的 `DSH_ROOT_EXEC_URL/TOKEN` 才会注入当前引擎进程。
3. 用 `dsh-root --status` 确认。输出 `已授权: 是` 才能继续。

## 用法

```sh
dsh-root id -u                          # 应为 0
dsh-root 'getprop ro.product.model'
dsh-root -c /data/local/tmp ls -l        # 工作目录
dsh-root -t 10000 'dumpsys gpu | head -n 40'
dsh-root -e FOO=bar 'sh -c "echo $FOO"'
dsh-root --json 'ls /data/data'          # 原始 JSON（ok/exitCode/stdout/stderr/truncated/durationMs）
```

后台任务（命令立即返回 `pid` 与日志路径）：

```sh
dsh-root -d 'logcat -c; logcat > /data/local/tmp/agent.log'
dsh-root --poll 12345                    # 最近的输出与状态；已结束时退出码为命令自身退出码
dsh-root --kill 12345
```

自检：

```sh
dsh-root --self-test
```

## 限制

- 前台单条命令上限 **25 秒**（默认 20 秒，`-t` 只能调小），超时退出码 124；更长的活请用 `-d` 后台模式。
- 单次输出（stdout/stderr 各自）最多 **256 KiB**，超出截断并提示，不是完整日志。
- 后台任务最多同时 **8 个**；前台同时最多 2 条，超出返回 busy。
- 每次调用只跑一条非交互的 `sh -c` 命令，没有 TTY、不能交互、不继承 PRoot 的 `PATH`/环境（默认 `PATH=/system/bin:/system/xbin`）。
- 命令长度上限 8 KiB（UTF-8）。
- 它不是沙箱：授权后所有命令静默执行，不再逐条确认，每条都写入审计日志（App「Root 增强」页可查看、导出、清空），可随时撤销授权。破坏性命令（清数据、改系统、刷模块）先跟用户确认。

## 失败时怎么处理

| 退出码 | 含义 | 怎么跟用户说 |
| --- | --- | --- |
| 2 | 引擎里没有 `DSH_ROOT_EXEC_URL/TOKEN` | App 的「设置 → Root 增强」未授权，或授权后没重启引擎。先授权，再重启本机引擎，然后重试 |
| 3 | `--status` 显示未授权 | 同上：打开「Root 增强」点**一次授权**→**授权并连接**，重启引擎 |
| 124 | 命令超时 | 缩小范围、加 `-t`，或者改成 `-d` 后台跑再用 `--poll` |
| 125 | 桥接拒绝执行（未授权、busy、root 不可用、token 失效） | 看 stderr 的错误码；`unauthorized` 去授权页，`busy` 等前一条结束，`rootUnavailable` 让用户检查 root 管理器（Magisk/KernelSU/APatch）是否允许 DSH Go |
| 其它 | 命令自己的退出码 | 原样转述，不要猜测成功 |

- 不要反复重试同一条失败命令，也不要用切到 PRoot/无障碍通道的方式绕过 root 授权。
- 输出里可能有密钥、账号、聊天内容：不要整段复述或写进文件，必要时先脱敏。
