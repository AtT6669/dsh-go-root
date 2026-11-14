import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'root_channel.dart';

/// The only screen a device without a grant ever sees. It cannot be dismissed
/// into the app: the runtime is not reachable from here.
class RootRequiredPage extends StatelessWidget {
  const RootRequiredPage({
    super.key,
    required this.snapshot,
    required this.busy,
    required this.onRequest,
    required this.onQuit,
  });

  final RootSnapshot snapshot;
  final bool busy;
  final VoidCallback onRequest;
  final VoidCallback onQuit;

  String get _title => switch (snapshot.phase) {
        RootPhase.noManager => '未检测到 Root',
        RootPhase.denied => 'Root 授权被拒绝',
        RootPhase.timeout => '等待 Root 授权超时',
        RootPhase.revoked => 'Root 授权已失效',
        _ => '需要 Root 权限',
      };

  String get _body => switch (snapshot.phase) {
        RootPhase.noManager =>
          '这台设备没有可用的 su。DSH Go Root 版没有非 Root 运行模式，请先在 Magisk / KernelSU / APatch 中完成 Root，再重新打开。',
        RootPhase.denied =>
          '授权请求被拒绝。请在 Root 管理器中允许本应用，然后点下面的按钮重试。',
        RootPhase.timeout =>
          '授权弹窗没有在限定时间内得到回应。请解锁屏幕、打开 Root 管理器后重试。',
        RootPhase.revoked =>
          '之前授予的权限已经失效（管理器被更新、撤销或系统重启后未恢复）。请重新授权。',
        _ => 'DSH Go Root 版会把引擎、终端和手机控制全部运行在 uid 0（chroot 到私有 rootfs）。'
            '没有 Root 授权，这些能力一个都不会启动。',
      };

  String get _managerHint {
    final manager = snapshot.manager;
    return switch (manager) {
      'magisk' => '检测到 Magisk',
      'kernelsu' => '检测到 KernelSU',
      'apatch' => '检测到 APatch',
      'su' => '检测到 su',
      _ => '未检测到 Root 管理器',
    };
  }

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: SingleChildScrollView(
              padding: const EdgeInsets.all(28),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  Icon(Icons.lock_outline, size: 56, color: scheme.primary),
                  const SizedBox(height: 20),
                  Text(_title, textAlign: TextAlign.center, style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(height: 12),
                  Text(_managerHint, textAlign: TextAlign.center, style: Theme.of(context).textTheme.labelMedium),
                  const SizedBox(height: 20),
                  Text(_body, style: Theme.of(context).textTheme.bodyMedium),
                  if (snapshot.detail != null && snapshot.detail!.isNotEmpty) ...[
                    const SizedBox(height: 16),
                    Container(
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(
                        color: scheme.surfaceContainerHighest,
                        borderRadius: BorderRadius.circular(8),
                      ),
                      child: Text(
                        snapshot.detail!,
                        style: Theme.of(context).textTheme.bodySmall?.copyWith(fontFamily: 'monospace'),
                      ),
                    ),
                  ],
                  const SizedBox(height: 28),
                  FilledButton(
                    onPressed: busy ? null : onRequest,
                    child: busy
                        ? const SizedBox(height: 20, width: 20, child: CircularProgressIndicator(strokeWidth: 2))
                        : const Text('授予 Root 权限'),
                  ),
                  const SizedBox(height: 10),
                  TextButton(onPressed: onQuit, child: const Text('退出')),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  static void quit() => SystemNavigator.pop();
}
