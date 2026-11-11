# 内置插件

`dsh-purge-community`（市场里的 dsh-purge）随 rootfs 一起预装：装好 App、启动引擎就能用，默认启用，市场里卸载不掉。本文件说明它怎么进来、落到哪、改了哪些行，以及升级怎么做。代码在 `runtime/locks/bundled-plugins.json`、`runtime/lib/rtbuild.py`、`runtime/build-runtime.sh`。

## 为什么构建期预装

- **开箱即用**：用户不需要先登录市场、等下载、再装一遍；冷启动后的第一次对话里插件就已经在。
- **可复现**：tarball + sha256 固定在 lock 里，离线构建、CI 和发布包是同一份字节。市场安装走的是用户设备上的 pnpm，版本会随时间漂移。
- **不受市场卸载影响**：它不是 profile 里的依赖，删掉 profile 的 `node_modules` 或点卸载都不会让它消失；卸载请求交由后续的「不可卸载」策略处理。
- **依赖可控**：插件的运行期依赖（当前只有 `yaml@2.9.1`）也在构建期一起固定，手机上不需要联网装。

代价是升级要重新构建运行时，而不是在设备上点「更新」；见文末步骤。

## lock 文件

`runtime/locks/bundled-plugins.json` 是唯一输入，构建脚本只读它，不读插件包的 `package.json`：

```json
{
  "schema": 1,
  "plugins": [
    {
      "name": "dsh-purge",
      "package": "dsh-purge-community",
      "version": "1.6.91",
      "url": "https://github.com/kingcharleslzy-ai/dsh-purge/releases/download/v1.6.91/dsh-purge-community-1.6.91.tgz",
      "sha256": "ae22bb7d1ac1e055102c8fbd24ef2eedc282a94bfa3fb09e7ab91226fc033058",
      "target": "/opt/dsh-go/plugins/dsh-purge-community",
      "license": "MIT"
    },
    {
      "name": "yaml",
      "package": "yaml",
      "version": "2.9.1",
      "url": "https://registry.npmjs.org/yaml/-/yaml-2.9.1.tgz",
      "sha256": "4ef6c54cf559b8a207b7b518378230805a1c84af239b14960e8c67c7d59de5d3",
      "target": "/opt/dsh-go/plugins/node_modules/yaml",
      "license": "MIT"
    }
  ]
}
```

字段含义：

| 字段 | 用途 |
| --- | --- |
| `name` | 逻辑名；`dsh-purge` 这一条同时决定 overlay 里写哪一行 |
| `package` / `version` | 拼 tarball 文件名、缓存目录名、overlay 注释 |
| `url` | 下载地址，必须是 `.tgz` |
| `sha256` | `fetch` 校验；不符直接失败，不会带着半个包继续构建 |
| `target` | rootfs 内的绝对路径；包内容直接铺在这里（npm 的 `package/` 前缀被剥掉） |
| `license` | 许可证标识，MIT；许可证正文随包里的 `LICENSE` 一起进 rootfs |

## 下载、校验与解包

`runtime/build-runtime.sh` 的 `bundled_plugins()`：

1. 用 `python3 -I` 把 lock 读成 `name / version / url / sha256 / target` 五列，避免在 shell 里解析 JSON。
2. 每个条目调既有的 `fetch`：下载到 `runtime/.cache/`（`$CACHE/bundled/<name>-<version>.tgz`），与 lock 里的 sha256 比对，失败给出可读错误并中止。
3. 校验 tarball 的所有成员都在 `package/` 下（`tar -tzf | grep -vE '^package/?$|^package/'`），再用 `--strip-components=1` 展开到 `$CACHE/bundled/<name>-<version>/`，成功后写 `<目录>.complete` 标记；同一版本第二次构建直接复用。
4. 输出 `TARGET=DIR` 行，由 `build_abi` 转成 `assemble --bundled-plugin TARGET=DIR`。

`rtbuild.py` 用既有的 `add_tree()` 把目录铺进 rootfs，origin 记为 `bundled`。下载物只落在 `runtime/.cache/`，该目录已在 `.gitignore` 里，不进 git；仓库里只有 lock 和代码。

## rootfs 布局

```
/opt/dsh-go/plugins/
├── dsh-purge-community/          # tgz 的 package/ 内容：lib/、bin/、presets/、skills/、client.js…
│   ├── lib/index.js
│   └── package.json
└── node_modules/
    └── yaml/                     # yaml@2.9.1，`import 'yaml'` 从上面任何包向上查找都能命中
```

`/opt/dsh-go` 在 `ENGINE_PATHS` 里，属于 App 自有的引擎层：运行时升级会整体替换，用户在里面手动加的文件不保证保留。

Node 的解析规则保证 `import 'yaml'` 生效：从 `/opt/dsh-go/plugins/dsh-purge-community/lib/index.js` 出发，`node_modules` 的查找路径依次是 `.../dsh-purge-community/lib/node_modules`、`.../dsh-purge-community/node_modules`、`/opt/dsh-go/plugins/node_modules`，最后命中内置的 yaml。因此 yaml 的 `target` 必须是 `/opt/dsh-go/plugins/node_modules/yaml`，不能平铺到 plugins 下。

## overlay 注册

`rtbuild.py` 的 `bundled_plugin_rows()` 往 `/etc/dsh-app/dsh-web.patch.yml` 追加的，就是插件包 `cordis.patch.yml` 里那一行 `dsh-purge`，写法与 `bridge_shared_rows()` 完全一致（新的 `- insert:` 顶格、条目缩进 4 格）：

```yaml
- insert:
    - id: dsh-purge
      name: /opt/dsh-go/plugins/dsh-purge-community/lib/index.js
      inject: [systemPrompt, tools]
      config: {enabled: true, excludePresets: []}
```

`name` 是绝对路径，和 dsh-go-bridge 一样按路径加载，不是 profile 依赖；插件包的 `dsh.client.platform=web` 与 `inject` 列表由 bridge 既有的客户端机制读取，面板照常出现在 Web 端。

### 为什么只写 insert 这一行

插件包自带的 `cordis.patch.yml` 还有两类行，故意不写：

| 包里的行 | 不写的原因 | 影响 |
| --- | --- | --- |
| `- id: system-prompt` 配置 `includeHarnessIdentity: false`、空的 `personaPrefix`/`personaSuffix` | 这个 runtime 的系统提示与人设由 companion 预设（`preset-dsh-go-companion`）拥有，覆盖它会破坏「伙伴小屋」的人设 | 插件的 prompt 清洗照常工作，但不会改写 harness 身份或人设前后缀 |
| `- id: redteam-store` / `redteam-ui` / `redteam-mode` / `redteam-mode-store` / `redteam-mode-ui` 的 `disabled: true` | 这些 id 在本 runtime 的 patch 里不存在；写进去要么命中未知 id，要么误伤用户自己装的同名插件 | 不产生任何效果；旧版独立红队包本来也没装 |
| `dsh-purge-redteam`、`dsh-purge-presets`、`dsh-purge-redteam-store`、`dsh-purge-redteam-ui` 四个 insert | 演练台（HTTP 桥 `POST /redteam/api` + SQLite 事实库）和自带的 unrestricted 预设不在这次预装范围内；它们的 id、数据目录、HTTP 路由都要额外评估 | 演练台入口与 unrestricted 预设不加载；插件的主功能（状态自检、提示词与规则管理、本机备份与回滚、面板）不受影响 |
| `config` 里除 `enabled`/`excludePresets` 外的键（`autoApplyOnStart`、`stripMnemon`、`hooksDenyBypass` 等） | 少写即用插件 schema 的默认值，避免把 bundle 的桌面假设带进手机 | 启动时不会自动 apply/update；用户可在插件面板里自行开启 |

这是有意的取舍：**未知 id 的风险大于少加载几个子功能**。要启用演练台时，先确认它的 id、数据目录与 HTTP 路由在 PRoot 下可用，再把对应行加进 `bundled_plugin_rows()`，并在 `runtime/tests/bundled-plugins.test.mjs` 里补断言。

## 升级步骤

1. 改 `runtime/locks/bundled-plugins.json`：新版本号、新 `url`、新 `sha256`（`sha256sum dsh-purge-community-<ver>.tgz`），`target` 一般不变；`package` 名变了才动目录。
2. 若新版多出/去掉了运行期依赖，同步增删 lock 里的条目（依赖也要有 target 和 sha256）。
3. 重新构建：`bash runtime/build-runtime.sh`（或发布流程里的等价命令）。lock 参与 `inputs_sha`，改 lock 一定会产生新的 `runtimeVersion`，旧的构建缓存不会被误用。
4. 检查 `runtime/out/<abi>/build-report.json` 里 `origins.bundled` 的计数、`uncompressedBytes` 是否合理；在设备上首次启动后看插件面板是否加载。
5. 升级或降级插件前先看它 `package.json` 的 `dsh.compatibility.dsh`：超出范围时 DSH 可能拒绝加载。

## 已知边界

- 缓存只按 `<name>-<version>` 命名；同一版本换了 tarball 内容（理论上不该发生）会因为 sha256 不符而在 `fetch` 阶段失败，删掉 `runtime/.cache/bundled/` 即可重来。
- 预装不等于 profile 依赖：`dsh plugin list` 一类的 profile 视角看不到它，插件清单以 bridge 的 inventory 为准。
- 包里的 `bin/`、`scripts/` 原样进 rootfs，但构建期不执行它们的 `scripts`（不跑 install 脚本）；运行时也不需要它们。
