#!/usr/bin/env node
// dsh-root: the guest-side door to the phone's root peer.
//
// It talks to the app's loopback bridge (see the Root 增强 page in the app). PRoot shares the
// network namespace, so 127.0.0.1 here is the phone's loopback; the URL and token arrive through
// the engine's environment and are never written to disk.
//
// Exit codes: the remote command's own code when it ran, 124 on timeout, 125 when the bridge
// refused the call, 2 when root support is not enabled or the environment is missing.

import http from "node:http";

const USAGE = `dsh-root — 以 root 身份在手机上执行命令（需要 App 内「Root 增强」已授权）

用法:
  dsh-root [选项] <命令...>            执行并等待结果
  dsh-root -d <命令...>                后台执行，立即返回 pid 与日志路径
  dsh-root --poll <pid>                查看后台任务的输出与状态
  dsh-root --kill <pid>                终止后台任务
  dsh-root --status                    查看连接与授权状态
  dsh-root --self-test                 自检（id -u 应为 0）

选项:
  -c, --cwd <目录>       工作目录
  -t, --timeout <毫秒>   前台执行超时，默认 20000，上限 25000
  -e, --env <K=V>        追加环境变量，可重复
  -p, --pid <pid>        同 --poll
  -k, --kill <pid>       同 --kill
      --json             输出桥接层原始 JSON
  -q, --quiet            前台执行时不打印 stdout
  -h, --help             显示本帮助

示例:
  dsh-root id -u
  dsh-root -c /data/local/tmp ls -l
  dsh-root -d 'logcat -c; logcat > /data/local/tmp/agent.log'
  dsh-root --poll 12345
  dsh-root --kill 12345
`;

function fail(code, message) {
  if (message) process.stderr.write(`dsh-root: ${message}\n`);
  process.exit(code);
}

function parseArgs(argv) {
  const options = { command: [], env: {}, timeoutMs: 0, cwd: "", detach: false, json: false, quiet: false, pid: 0 };
  const rest = [];
  // Once the command word appears every later argv entry belongs to it: `dsh-root ls -l` must not
  // parse `-l`. Only leading options are dsh-root's own.
  let literal = false;
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (literal) {
      rest.push(arg);
      continue;
    }
    const next = () => {
      const value = argv[index + 1];
      if (value === undefined) fail(2, `${arg} 缺少参数`);
      index += 1;
      return value;
    };
    if (arg === "-h" || arg === "--help") {
      process.stdout.write(USAGE);
      process.exit(0);
    } else if (arg === "-c" || arg === "--cwd") {
      options.cwd = next();
    } else if (arg === "-t" || arg === "--timeout") {
      options.timeoutMs = Number(next());
      if (!Number.isFinite(options.timeoutMs) || options.timeoutMs <= 0) fail(2, "超时必须是正整数毫秒");
    } else if (arg === "-e" || arg === "--env") {
      const pair = next();
      const split = pair.indexOf("=");
      if (split <= 0) fail(2, "环境变量必须写成 K=V");
      options.env[pair.slice(0, split)] = pair.slice(split + 1);
    } else if (arg === "-d" || arg === "--detach") {
      options.detach = true;
    } else if (arg === "--json") {
      options.json = true;
    } else if (arg === "-q" || arg === "--quiet") {
      options.quiet = true;
    } else if (arg === "--poll" || arg === "-p") {
      options.pid = Number(next());
    } else if (arg === "--kill" || arg === "-k") {
      options.kill = Number(next());
    } else if (arg === "--status") {
      options.status = true;
    } else if (arg === "--self-test") {
      options.selfTest = true;
    } else if (arg.startsWith("-") && arg !== "-") {
      fail(2, `未知选项 ${arg}（用 --help 查看用法）`);
    } else {
      literal = true;
      rest.push(arg);
    }
  }
  options.command = rest;
  return options;
}

function bridge() {
  const url = process.env.DSH_ROOT_EXEC_URL;
  const token = process.env.DSH_ROOT_EXEC_TOKEN;
  if (!url || !token) {
    fail(2, "未检测到 Root 通道（DSH_ROOT_EXEC_URL/DSH_ROOT_EXEC_TOKEN）。请在 App 的「Root 增强」页授权后重启运行时。");
  }
  return { url: url.replace(/\/$/, ""), token };
}

function request(method, path, body, timeoutMs) {
  const { url, token } = bridge();
  const target = new URL(url + path);
  return new Promise((resolve, reject) => {
    const payload = body === undefined ? null : Buffer.from(JSON.stringify(body), "utf8");
    const req = http.request(
      {
        host: target.hostname,
        port: target.port,
        path: target.pathname + target.search,
        method,
        agent: false,
        headers: {
          authorization: `Bearer ${token}`,
          "content-type": "application/json",
          "content-length": payload ? payload.length : 0,
          connection: "close",
        },
      },
      (res) => {
        const chunks = [];
        res.on("data", (chunk) => chunks.push(chunk));
        res.on("end", () => {
          const text = Buffer.concat(chunks).toString("utf8");
          let json = null;
          try {
            json = text ? JSON.parse(text) : null;
          } catch {
            json = null;
          }
          resolve({ status: res.statusCode || 0, json, text });
        });
      },
    );
    req.setTimeout(timeoutMs, () => req.destroy(new Error("dsh-root 桥接请求超时")));
    req.on("error", reject);
    if (payload) req.write(payload);
    req.end();
  });
}

async function call(method, path, body, timeoutMs) {
  const reply = await request(method, path, body, timeoutMs).catch((error) => {
    fail(125, `无法连接 Root 通道: ${error.message}`);
  });
  if (reply.status !== 200) {
    const code = reply.json?.error ?? `http-${reply.status}`;
    if (code === "unauthorized") fail(125, "Root 执行未授权：请打开 App 的「Root 增强」页并点击授权。");
    fail(125, `Root 通道拒绝执行: ${code}`);
  }
  return reply.json ?? {};
}

function writeOutput(result, options) {
  if (options.json) {
    process.stdout.write(JSON.stringify(result, null, 2) + "\n");
    return;
  }
  if (!options.quiet) {
    if (result.stdout) process.stdout.write(result.stdout.endsWith("\n") ? result.stdout : result.stdout + "\n");
    if (result.stderr) process.stderr.write(result.stderr.endsWith("\n") ? result.stderr : result.stderr + "\n");
    if (result.truncated) process.stderr.write("dsh-root: 输出超过 256 KiB，已截断\n");
  }
}

async function main() {
  const options = parseArgs(process.argv.slice(2));

  if (options.status) {
    const ping = await call("GET", "/v1/ping", undefined, 10_000);
    if (options.json) {
      process.stdout.write(JSON.stringify(ping, null, 2) + "\n");
    } else {
      process.stdout.write(`root 通道: ${ping.ok ? "可用" : "不可用"}\n`);
      process.stdout.write(`已授权: ${ping.authorized ? "是" : "否（请在 App 的「Root 增强」页授权）"}\n`);
    }
    process.exit(ping.authorized ? 0 : 3);
  }

  if (options.kill) {
    const reply = await call("POST", "/v1/kill", { pid: options.kill }, 15_000);
    process.stdout.write(`已终止 ${reply.pid}\n`);
    return;
  }

  if (options.pid) {
    const job = await call("GET", `/v1/status?pid=${encodeURIComponent(options.pid)}`, undefined, 20_000);
    if (options.json) {
      process.stdout.write(JSON.stringify(job, null, 2) + "\n");
    } else {
      if (job.output) process.stdout.write(job.output.endsWith("\n") ? job.output : job.output + "\n");
      process.stderr.write(job.running ? `dsh-root: ${job.pid} 仍在运行\n` : `dsh-root: ${job.pid} 已结束，退出码 ${job.exitCode}\n`);
    }
    process.exit(job.running ? 0 : Number(job.exitCode ?? 0));
  }

  const command = (options.command.join(" ").trim() || (options.selfTest ? "id -u" : "")).trim();
  if (!command) fail(2, "缺少命令（用 --help 查看用法）");

  if (options.detach) {
    const job = await call("POST", "/v1/exec", {
      command, cwd: options.cwd || undefined, env: options.env, detach: true,
    }, 20_000);
    if (options.json) process.stdout.write(JSON.stringify(job, null, 2) + "\n");
    else process.stdout.write(`${job.pid}\t${job.logPath}\n`);
    return;
  }

  const result = await call("POST", "/v1/exec", {
    command, cwd: options.cwd || undefined, env: options.env,
    timeoutMs: options.timeoutMs || undefined,
  }, Math.min(options.timeoutMs || 20_000, 25_000) + 5_000);

  if (options.selfTest) {
    const uid = String(result.stdout ?? "").trim();
    if (result.ok && uid === "0") {
      process.stdout.write("dsh-root 自检通过：uid=0\n");
      process.exit(0);
    }
    fail(125, `自检失败：uid=${uid || "?"}，退出码 ${result.exitCode}`);
  }

  writeOutput(result, options);
  process.exit(Number(result.exitCode ?? 0));
}

main().catch((error) => fail(125, error?.message ?? String(error)));
