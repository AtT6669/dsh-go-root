# Root tools

Some work needs the phone's real root identity: reading GPU and display nodes, other apps' data, system properties, or keeping a daemon alive. Root tools let DSH run those commands as root after one consent. Paths PRoot cannot see are visible here.

Root tools are only available on this phone's engine. The phone must be rooted (Magisk, KernelSU and APatch all work) and the root manager must allow DSH Go.

## Turning it on

<!-- nav: commonSettings,hubGroupCapabilities,phoneRootExecTitle -->
`Settings › Capabilities › Root tools`

1. Open the page and check that authorization and connection both show as not authorized and not connected.
2. Tap the one-time authorization, read the explanation, then tap authorize and connect. Your root manager asks for permission; allow it. The explanation is explicit: after this, every root command from the app and the built-in CLI runs silently, with no per-command prompt.
3. The status should now show authorized and connected, with a bridge port number. That means the channel is up.
4. Go back to the engine page and stop and start the engine once. The address and token are handed to the engine when it starts, so a running engine keeps the old ones. Restarting interrupts the reply that is being generated.

After that, just say what you need in a chat, for example "use root to check this phone's GPU frequency".

On a phone with `su`, 不打扰模式 keeps root by default: it reconnects after a reboot or an app restart, with no manual preparation each time. A phone without `su` is never pushed into a permission prompt by that default; once you pick a channel yourself, keeping follows your choice.

## Status

The top of the page shows:

- Authorization and connection: authorized or not authorized, connected or not connected. Nothing connects to root before you authorize.
- Current uid: the identity of the root peer. Only 0 is real root.
- Bridge port: not running means the channel is down, usually because nothing is authorized or the app process was just reclaimed.
- Last error: the most recent failure code, such as a timeout, another command running, or root unavailable. The English code in brackets is the protocol reason code and is what to quote when reporting a problem.
- Refresh: read the status again.

## Self-test

Tap the self-test to run `id -u` as root. It shows uid=0 when it passes, another uid when the root manager did not grant root, and a reason when it fails. It never asks for new permission; it only checks the current channel.

## Built-in CLI

The rootfs ships `dsh-root`, which both the agent and you can use. Common forms:

```sh
dsh-root id -u
dsh-root 'getprop ro.product.model'
dsh-root -c /data/local/tmp ls -l
dsh-root -t 10000 'dumpsys gpu | head -n 40'
dsh-root --status
```

Long-running work goes in the background: the command returns at once with a process id and a log path.

```sh
dsh-root -d 'logcat -c; logcat > /data/local/tmp/agent.log'
dsh-root --poll <pid>
dsh-root --kill <pid>
```

Limits:

- A foreground command runs for at most 25 seconds (20 by default) and then ends with a timeout. Use the background mode for longer work.
- One run returns at most 256 KiB per stream (standard output and standard error separately); anything beyond that is truncated and reported.
- At most 8 background jobs, and at most 2 foreground commands at a time; beyond that the bridge reports busy.
- Each call runs one non-interactive Android `sh` command. There is no terminal, no way to type a password, and no inheritance of Ubuntu paths, working directory or environment variables.
- Use it only for work that needs root; ordinary Linux commands belong in PRoot.

## Audit log

Every command is recorded, including failed ones: time, source (App or built-in CLI), exit code, duration and the command itself. The page shows the latest 50.

- Export: copies the log for the system share sheet and leaves the live file in place.
- Clear: removes the current log and the previous backup, and cannot be undone. Commands run afterwards are still recorded.
- A failed write never changes the result of the command.

Commands run from a chat are recorded with their full text, so never put passwords, verification codes or keys in a root command.

## Revoking, and what to keep in mind

Revoke authorization in the danger zone. The root connection closes immediately: a running foreground command fails, and root commands need a new authorization afterwards.

- This is not a sandbox. Authorization hands root shell access to DSH and the plugins it loads. Use it only with plugins and chats you trust.
- Revoke it when you are done; if a task works in PRoot, do not turn this on.
- Background jobs are held by the app's root peer and may end when the system reclaims the app. Use `--kill` when you are done.
- Output can contain sensitive device data. Check that it may leave the phone before sending it to a model.
