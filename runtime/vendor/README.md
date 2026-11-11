# runtime/vendor

构建运行时要用、但上游随时可能删掉的输入。`build-runtime.sh` 的 `fetch` 会**先**在这里找同名文件（按 `PIN` 表校验 sha256），找不到才联网。这样 `packages.termux.dev` 升级包版本、`termux/proot` 归档被清理都不会让构建失败。

| 文件 | 用途 | 许可 |
| --- | --- | --- |
| `proot_5.1.107.102_aarch64.deb` / `_x86_64.deb` | PRoot 本体，两份 `libproot.so` | GPL-2.0 |
| `libtalloc_2.5.0_{aarch64,x86_64}.deb` | proot 的依赖 | LGPL-3.0 |
| `libandroid-shmem_0.7_{aarch64,x86_64}.deb` | Android ashmem 兼容层 | MIT |
| `proot-v5.1.107.102.zip` | 对应源码（随 APK 一起提供，满足 GPL 源码要求） | GPL-2.0 |

为什么不含别的东西：npm 上的 tarball（`@deepseek-ai/dsh`、`dsh-purge`、`yaml`）按约定永不复用版本号，Node 与 python-build-standalone 的历史版本也长期保留，所以只归档真正会消失的两类——Termux 的 pool 和 GitHub 的源码归档。Ubuntu 的 deb 体积大且随安全更新频繁变动，改用“锁文件 + 发现被撤下就按当前索引重解析并提示提交”的方式处理。

## 升级 Termux 组件

1. 改 `runtime/build-runtime.sh` 顶部的 `PROOT_VERSION` / `TALLOC_VERSION` / `SHMEM_VERSION`；
2. 下载新版 deb 与对应源码 zip 到本目录，替换旧文件；
3. 用 `sha256sum` 改写脚本里 `PIN` 表对应条目（文件名也跟着改）；
4. 确认 `runtime/test-host.sh` 与 APK 内 `COPYING` 仍然对得上，然后提交。
