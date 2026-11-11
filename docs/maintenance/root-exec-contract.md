# Root 执行协议：改动时必须同步的六个位置

`dsh-root` 到 root 同伴之间是一条跨语言、跨进程的协议：Kotlin 的 HTTP 桥与 root 执行器、Dart 的状态模型、rootfs 里的 Node CLI，再加一份测试。**任何一处改字段、改错误码、改上限，都要把下面六处一起改**，否则故障只会在设备上以「命令莫名失败」的形式出现。设计背景见 `docs/design/root-exec.md`。

## 六个位置

| # | 位置 | 负责什么 | 改这里时容易漏什么 |
| --- | --- | --- | --- |
| 1 | `app/android/app/src/main/kotlin/.../phone/background/RootExecOps.kt` | 协议常量（`OP_*`、`COMMAND`、`CWD`、`ENV`、`TIMEOUT_MS`、`PID`、`TAIL_BYTES`、返回字段名）、上限（`MAX_TIMEOUT_MS=25_000`、`MAX_COMMAND_BYTES=8 KiB`、`STREAM_LIMIT=256 KiB`、`TAIL_LIMIT=64 KiB`、`MAX_DETACHED=8`）、错误码（`BUSY`/`TIMEOUT`/`INVALID`/`FAILED`） | 常量只被这一层和控制器共享；改名后 Bundle 的键会静默失配，字段读成默认值 |
| 2 | `app/android/app/src/main/kotlin/.../phone/background/RootExecController.kt` | 授权（`isAuthorized`/`authorize`/`revoke`）、并发槽（2 个，等待 250 ms）、`exec`/`execDetach`/`poll`/`kill` 的字段拼装、审计写入、`Status.map()` 的 Dart 侧字段 | 新增参数只加在 Ops 忘了在这里透传；`Status.map()` 少键会让 Dart 侧读到 null |
| 3 | `app/android/app/src/main/kotlin/.../root/RootExecServer.kt` | HTTP 路由（`GET /v1/ping`、`POST /v1/exec`、`GET /v1/status`、`POST /v1/kill`）、request/response 的 JSON 键、`authorized`/`forbidden`/`busy` 等状态码、token 与 uid 校验、`guestEnv()` 的环境变量 | 路由或键名改了不同步 CLI；错误码和 HTTP 状态码的对应关系（403 / 409 / 503 / 404）容易只改一半 |
| 4 | `app/lib/phone/phone_channel.dart` 的 `PhoneRootExecStatus` | App 页面消费的状态模型（`authorized`、`connected`、`busy`、`lastErrorCode`、`auditPath`、`uid`、`packageName`、`port`、`sdk`）与 `v=1` 校验 | 页面只读这些键；控制器新增状态而不加字段时页面看不到，也不会报错 |
| 5 | `runtime/root-exec/dsh-root.mjs` | CLI 参数、请求体（`command`/`cwd`/`env`/`timeoutMs`/`detach`）、响应解析、退出码（命令码 / 2 / 3 / 124 / 125）、`--status`/`--poll`/`--kill`/`--self-test` 的输出格式 | 上限（25 s、8 KiB、256 KiB）在这里只是提示，真正的拒绝在 Ops；退出码改了要同步 skill 里的表 |
| 6 | `runtime/tests/root-exec-cli.test.mjs` | 用假桥覆盖空环境（2）、未授权（3）、正常透传、错误 token（125）、`--help`（0） | 协议改了测试还全绿，说明测试只验证了 argv 解析，没验证新字段 |

配套但非协议的：`runtime/skills/dsh-root/SKILL.md`（给 Agent 的用法与失败处理）与 `runtime/lib/rtbuild.py` 里 `/usr/local/bin/dsh-root` 的 shim（Node 路径）。改 CLI 用法时顺手看一眼这两处。

## 一次协议的改动顺序

1. 先定字段/上限：写在 `RootExecOps.kt` 常量与注释里，作为唯一权威。
2. 控制器透传：`exec`/`execDetach`/`poll`/`kill` 与 `Status.map()`。
3. 桥的路由与 JSON：`RootExecServer.route/exec/status/kill`，以及 `guestEnv()` 是否需要新变量。
4. CLI 解析与退出码：`runtime/root-exec/dsh-root.mjs`。
5. Dart 状态：`PhoneRootExecStatus.fromMap()` 加字段，页面才有东西可显示。
6. 测试：`runtime/tests/root-exec-cli.test.mjs` 加用例；能离线跑就不要依赖真机。

## 不可协商的约束

- **超时预算**：命令 25 s 上限 < mux `REQUEST_DEADLINE_MS` 30 s。任何一侧收紧或放宽，另一侧与 CLI 的 `+5 s` 兜底都要重新算。
- **输出上限**：`STREAM_LIMIT` 256 KiB 是截断而非报错，`truncated=true` 必须继续回传；CLI 的提示文案依赖它。
- **后台上限**：8 个在跑任务，超出返回 `busy`；不要改成排队，排队会在 root 侧无限堆积。
- **授权单点**：只有 `RootExecController.guard()` 判断授权；桥的 `authorized` 只是展示，不要另起一套判断。
- **审计不可绕过**：所有执行入口都写 `RootAuditLog`，新增 op 也必须写。
- **token 不进文件**：只能通过 `guestEnv()` 注入引擎环境，不能写进 rootfs 或命令行参数。

## 验证

```sh
# 离线：假桥 + CLI（不需要真机、不需要网络）
node --test runtime/tests/root-exec-cli.test.mjs
```

设备上第一次联调：

```sh
dsh-root --status          # 已授权: 是
dsh-root --self-test       # 自检通过：uid=0
dsh-root -d 'logcat -c; logcat > /data/local/tmp/agent.log'
dsh-root --poll <pid>
dsh-root --kill <pid>
```

失败时先看 App「Root 增强」页的审计与 `lastErrorCode`，再对照 CLI 的退出码表；不要靠反复重试判断。
