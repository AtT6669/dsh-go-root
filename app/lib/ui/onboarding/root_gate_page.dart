// The root gate: the bundled runtime is deployed under the phone's real system
// root (the privileged peer unpacks it, then chroots into it), so the first run
// must obtain root before anything is unpacked. This page is the only place
// that asks for it, and it cannot be dismissed: without the grant there is
// nothing to fall back to.

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_state.dart';
import '../theme/app_icons.dart';
import '../theme/theme.dart';
import 'onboarding_scaffold.dart';

/// Shown before the first unpack; true when the peer answered as uid 0.
Future<bool> showRootGate(BuildContext context) async {
  final granted = await Navigator.of(context).push<bool>(
    MaterialPageRoute(builder: (_) => const RootGatePage(), fullscreenDialog: true),
  );
  return granted == true;
}

/// True when the unpack may start: root mode gets its grant first, and no
/// runtime at all is touched before that.
Future<bool> ensureRootForInstall(BuildContext context, WidgetRef ref) async {
  final status = ref.read(localRuntimeProvider).status;
  if (!status.rootRequired || status.rootAuthorized) return true;
  if (!await showRootGate(context) || !context.mounted) return false;
  await ref.read(localRuntimeProvider.notifier).refresh();
  return true;
}

/// 「需要安装 Root 权限」 with one grant key and, on failure, one line saying why.
class RootGatePage extends ConsumerStatefulWidget {
  const RootGatePage({super.key});

  @override
  ConsumerState<RootGatePage> createState() => _RootGatePageState();
}

class _RootGatePageState extends ConsumerState<RootGatePage> {
  bool _asking = false;
  String? _reason;

  Future<void> _grant() async {
    if (_asking) return;
    setState(() {
      _asking = true;
      _reason = null;
    });
    final reason = await ref.read(runtimeBridgeProvider).requestRoot();
    if (!mounted) return;
    if (reason == null) {
      // The native snapshot now says root is on file; the install reads it again.
      await ref.read(localRuntimeProvider.notifier).refresh();
      if (!mounted) return;
      Navigator.of(context).pop(true);
      return;
    }
    setState(() {
      _asking = false;
      _reason = reason;
    });
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final theme = Theme.of(context);
    final colors = DshColors.of(context);
    return PopScope(
      canPop: false,
      child: OnboardingScaffold(
        showRuntime: false,
        children: [
          const Spacer(),
          OnboardingHeader(
            icon: AppIcons.permissions,
            title: l10n.runtimeRootGateTitle,
            message: l10n.runtimeRootGateBody,
          ),
          const SizedBox(height: 28),
          OnboardingFact(icon: AppIcons.done, text: l10n.runtimeRootGateFactOnce),
          OnboardingFact(icon: AppIcons.done, text: l10n.runtimeRootGateFactBlocked),
          if (_reason case final reason?) ...[
            const SizedBox(height: 12),
            OnboardingFact(
              icon: AppIcons.attention,
              text: l10n.runtimeRootGateFailed(reason),
              color: colors.danger,
            ),
          ],
          const Spacer(flex: 2),
          const SizedBox(height: 24),
          FilledButton(
            key: const ValueKey('root-grant'),
            onPressed: _asking ? null : _grant,
            child: _asking
                ? SizedBox.square(
                    dimension: 20,
                    child: CircularProgressIndicator(strokeWidth: 2, color: theme.colorScheme.onPrimary),
                  )
                : Text(l10n.runtimeRootGateGrant),
          ),
        ],
      ),
    );
  }
}
