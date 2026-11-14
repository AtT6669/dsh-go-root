import 'package:flutter/material.dart';

import 'root_channel.dart';
import 'root_required_page.dart';

/// Blocks the whole application behind the root grant.
///
/// First launch asks the manager once. Later launches re-verify the grant by
/// starting the root broker (a real `su` child), so a revoked permission sends
/// the user back here instead of failing deep inside the runtime.
class RootGate extends StatefulWidget {
  const RootGate({super.key, required this.child});

  final Widget child;

  @override
  State<RootGate> createState() => _RootGateState();
}

class _RootGateState extends State<RootGate> {
  RootSnapshot _snapshot = const RootSnapshot(phase: RootPhase.unknown);
  bool _busy = true;
  bool _open = false;
  bool _asked = false;

  @override
  void initState() {
    super.initState();
    _verify(autoAsk: true);
  }

  Future<void> _verify({required bool autoAsk, bool force = false}) async {
    setState(() => _busy = true);
    var snapshot = await RootChannel.status();
    if (snapshot.phase.legacy) {
      if (mounted) setState(() => _open = true);
      return;
    }
    if (snapshot.phase.grantedNow) {
      final broker = await RootChannel.startBroker();
      if (!broker) {
        final why = await RootChannel.brokerError();
        snapshot = RootSnapshot(
          phase: RootPhase.revoked,
          detail: why.isEmpty ? 'RootBroker 启动失败：授权可能已被撤销' : 'RootBroker 启动失败：$why',
          manager: snapshot.manager,
        );
      }
    } else if (autoAsk && (force || !_asked)) {
      _asked = true;
      snapshot = await RootChannel.request();
      if (snapshot.phase.grantedNow) {
        final broker = await RootChannel.startBroker();
        if (!broker) {
          final why = await RootChannel.brokerError();
          snapshot = RootSnapshot(
            phase: RootPhase.revoked,
            detail: why.isEmpty ? '已授权但 RootBroker 启动失败' : 'RootBroker 启动失败：$why',
            manager: snapshot.manager,
          );
        }
      }
    }
    if (!mounted) return;
    setState(() {
      _snapshot = snapshot;
      _open = snapshot.phase.grantedNow;
      _busy = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_open) return widget.child;
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: const Color(0xFF3F5BD9), useMaterial3: true),
      home: RootRequiredPage(
        snapshot: _snapshot,
        busy: _busy,
        onRequest: () => _verify(autoAsk: true, force: true),
        onQuit: RootRequiredPage.quit,
      ),
    );
  }
}
