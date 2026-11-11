# Root 增强：guest 到 root 同伴的通道

「Root 增强」让 PRoot 里的引擎（插件、Agent、`dsh-root`）在用户授权后以手机真正的 root 身份执行命令。它由三层组成：

| 位置 | 角色 |
| --- | --- |
| `app/.../root/RootExecServer.kt` | 只绑 `127.0.0.1` 的 HTTP 桥，校验 token 与调用方 uid，路由四个 op |
| `app/.../phone/background/RootExecController.kt` | 唯一的授权/审计入口，App UI 与 guest 共用 |
| `app/.../phone/background/RootExecOps.kt` | 在 root 同伴进程里用 `/system/bin/sh -c` 执行，持有并发槽与后台任务表 |
| `runtime/root-exec/dsh-root.mjs` + `/usr/local/bin/dsh-root` | guest 侧 CLI（rootfs 内，`/opt/dsh-go/root-exec`） |
| `runtime/skills/dsh-root/SKILL.md` | 给 Agent 的用法与失败处理说明（`/root/.dsh/skills/dsh-root`） |

PRoot 不隔离网络命名空间，所以 guest 里的 `127.0.0.1` 就是手机的 loopback，桥不需要额外转发。传输层另有 30 秒的 mux 请求上限（`FramedMuxSession.REQUEST_DEADLINE_MS`），本协议的预算必须留在这条线之内。

## 在 rootfs 里的位置与升级行为

| 路径 | 层 | 引擎升级时 |
| --- | --- | --- |
| `/opt/dsh-go/root-exec/dsh-root.mjs` | 引擎层（`ENGINE_PATHS` 的 `opt/dsh-go`） | 替换为随包的新版本 |
| `/usr/local/bin/dsh-root` | base 层 | 不重写；引擎升级只换白名单路径，shim 随完整安装更新 |
| `/root/.dsh/skills/dsh-root/SKILL.md` | base 层（用户 home，保留） | 不重写；用户改过的内容不会被覆盖 |

`ENGINE_PATHS` 是 Kotlin `EngineUpgrade` 与 `rtbuild.py` 共同校验的白名单，两边必须逐项一致，manifest 里的 `paths` 不符会直接拒绝升级。因此**不能**为了让 shim 跟随引擎升级而把 `usr/local/bin/dsh-root` 加进 `ENGINE_PATHS`——那需要同时改 Kotlin，否则升级会被 `runtime_upgrade_blocked:manifest` 挡下。shim 的内容是稳定的一行 `exec`，放在 base 层足够。

## 授权模型

- **一次总授权**：用户在 App「设置 → 能力 → Root 增强」页确认一次后，`RootExecController.authorize()` 把 `root-exec/authorized=true` 写进 SharedPreferences。之后每条命令不再逐条确认；UI 在按钮上方写明这一点。
- **默认关闭**：键不存在即未授权；`guard()` 在未授权时直接返回 `unauthorized`，不建立 root 连接。
- **可撤销**：`revoke()` 先把标记置 false，再 `root.disconnect()`。正在执行的 guest 调用会因为管道关闭而失败，后续调用返回 `unauthorized`。
- **审计不可关**：`RootAuditLog` 是 append-only 的 TSV（时间、caller、退出码、耗时、命令），没有关闭开关。App UI 的查看/导出/清空都走同一份文件。
- **token 不落盘**：32 字节随机数的十六进制串只存在于 App 进程内存和引擎进程的环境变量里，运行时启动时注入（见下文）。

## loopback + token + uid

1. 监听：`ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))`，端口随机，不监听外网地址。
2. token：`Authorization: Bearer <hex>`，常量时间比较；缺失或不符返回 403 `unauthorized`。
3. uid：API 29+ 用 `ConnectivityManager.getConnectionOwnerUid()` 比对 socket 两端，调用方 uid 必须是 App 自己的 uid，否则 403 `forbidden`。这是 token 之外的第二道锁。
4. 请求上限：请求头 16 KiB、请求体 64 KiB、socket 空闲 30 秒；一个连接只处理一个请求（`Connection: close`）。
5. 环境注入：`RootExecServer.Session.guestEnv()` 给出 `DSH_ROOT_EXEC_URL=http://127.0.0.1:<port>`、`DSH_ROOT_EXEC_TOKEN=<hex>`、`DSH_ROOT_EXEC_VERSION=1`。URL/端口/token 只在 App 进程存活期间有效，所以**授权或重启 App 后需要重启引擎**才能拿到新值。

## op 契约

所有 op 都是 `RootExecController` 的方法，App UI 与 guest 走同一条路径（caller 分别为 `app` / `guest`）。

| op | 字段 | 上限/默认 | 返回 |
| --- | --- | --- | --- |
| `exec` | `command`、`cwd`、`env`（K=V）、`timeoutMs` | command ≤ 8 KiB；timeout 默认 20 s、上限 25 s；cwd 必须是已存在目录；`PATH` 缺省 `/system/bin:/system/xbin` | `exitCode`、`stdout`、`stderr`、`truncated`、`durationMs` |
| `execDetach` | `command`、`cwd`、`env`、`logDir`（App 私有 `files/root-exec/jobs`） | command ≤ 8 KiB；同时在跑的后台任务 ≤ 8，超出返回 `busy`；stdout/stderr 合并进 `<uuid>.log` | `pid`、`id`、`logPath`、`startedAtMs` |
| `execStatus` | `pid`；传输层另有 `tailBytes` | tail 由控制器固定为 64 KiB（HTTP 路由目前不接受 `tailBytes`） | `pid`、`running`、`exitCode`（结束时，否则 `null`）、`output`、`sizeBytes`、`logPath` |
| `execKill` | `pid` | 2 s 内 SIGTERM 未退出则 SIGKILL | `pid`；找不到任务返回 `notRunning` |

补充约束：

- 前台 `exec` 在 root 侧与 App 侧各有 2 个并发槽；App 侧等待 250 ms 后返回 `busy`，不排队。
- 单条命令的 `stdout`/`stderr` 各自最多 256 KiB（`STREAM_LIMIT`），超出置 `truncated=true` 并截断；完整输出不会另存。
- 只有 `exec` 有超时；`execDetach` 的寿命由用户用 `--kill` 或 `execStatus` 管理，8 个在跑任务的上限就是它的刹车。
- 错误码：`unauthorized`、`forbidden`、`busy`、`timeout`、`invalidArguments`、`executeFailed`、`rootUnavailable`、`rootDisconnected`、`notFound`、`notRunning`、`payload-too-large`、`bad-request`。

## HTTP 路由

| 方法与路径 | 说明 |
| --- | --- |
| `GET /v1/ping` | 只需要 token 和 uid，不需要用户授权；返回 `{ok, v: 1, authorized}`，CLI 的 `--status` 用它 |
| `POST /v1/exec` | `exec`；body 带 `detach: true` 时走 `execDetach` |
| `GET /v1/status?pid=` | `execStatus` |
| `POST /v1/kill` | `execKill` |
| 其它 | 404 `notFound`；除 ping 外未授权一律 403 `unauthorized` |

HTTP 状态码与错误码的对应：400 `bad-request`、403 `unauthorized`/`forbidden`、404 `notFound`/`notRunning`、409 `busy`/`timeout`/`invalidArguments`/`executeFailed`、413 `payload-too-large`、503 后台任务起不来（`rootUnavailable` 等）。

## 超时预算

| 层 | 预算 | 说明 |
| --- | --- | --- |
| 命令 | 20 s 默认 / **25 s 上限** | `RootExecOps.MAX_TIMEOUT_MS`，请求值再大也被夹到 25 s |
| mux 传输 | **30 s** | `FramedMuxSession.REQUEST_DEADLINE_MS`；25 s 留出 5 s 给建连、序列化和回包 |
| CLI HTTP | `min(timeout, 25 s) + 5 s` | `dsh-root.mjs` 自己先超时，避免比桥更晚放弃 |
| CLI `--status` | 10 s | 只读 ping |
| CLI `--kill` | 15 s | SIGTERM + 2 s + SIGKILL |
| 桥 socket | 30 s | 与 mux 同量级，防止半开连接占线程 |

预算的规则是**内层必须小于外层**：命令 25 s < mux 30 s，CLI 35 s 只是最后的兜底。任何一层收紧都要同步检查这张表（见 `docs/maintenance/root-exec-contract.md`）。

## 日志与轮转

- 审计：`files/root-exec/root-audit.log`，单文件上限 4 MiB；写入前若会超限，先删旧 `.1` 再把当前文件改名 `.1`（保留一代），然后追加。命令文本截断到 2 KiB，`\n`/`\r`/`\t`/`\` 转义，保证一行一条。
- 导出：`export()` 复制成 `root-audit-<ms>.log` 再交给系统分享，不截断、不移动原文件；`clear()` 同时删当前与 `.1`。
- 后台任务日志：`files/root-exec/jobs/<uuid>.log`，由 `execStatus` 的 `logPath` 暴露；轮转由用户在 App 内清理，协议不自动删除。
- 写日志失败不改变命令结果（磁盘满时命令仍然成功返回），这是有意的：审计可用性不能反过来让执行失败。
- guest 侧不写日志文件：`dsh-root` 只把结果打到 stdout/stderr，唯一记录是上面那份 App 审计。

## CLI 退出码

| 退出码 | 含义 |
| --- | --- |
| 0–255 | 远端命令自己的退出码（`--status` 授权时返回 0） |
| 2 | 缺少 `DSH_ROOT_EXEC_URL/TOKEN`、参数错误、没有命令 |
| 3 | `--status` 发现未授权 |
| 124 | 桥或 CLI 请求超时 |
| 125 | 桥拒绝（403/409/503、连接失败、token 错误、busy、root 不可用） |

## 安全边界

- 授权后的命令等价于 root shell：没有沙箱、没有白名单、没有逐条确认。安全边界就是 token + uid + 用户那一次授权。
- 桥只绑 loopback，且校验调用方 uid，因此同机其它应用即使猜到端口也拿不到执行权。
- 不要把这套通道包装成「安全执行环境」：`exec` 的 `cwd`/`env` 来自调用方，命令是调用方自己的选择。
