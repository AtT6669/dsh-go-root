import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/phone_state.dart';
import '../common/brief_notice.dart';
import '../common/list_page_row.dart';
import '../common/risk_notice.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'phone_widgets.dart';

/// Native exec errors are few and stable; anything else is shown verbatim so a
/// root manager's own message is not hidden behind 「未知错误」.
String phoneRootExecErrorText(L10n l10n, String? code) {
  if (code == null || code.isEmpty) return l10n.phoneRootExecErrorUnknown;
  return switch (code) {
    'unauthorized' || 'notAuthorized' => l10n.phoneRootExecErrorUnauthorized,
    'busy' => l10n.phoneRootExecErrorBusy,
    'rootUnavailable' || 'unavailable' || 'notConnected' => l10n.phoneRootExecErrorUnavailable,
    'timeout' || 'timedOut' => l10n.phoneRootExecErrorTimeout,
    'executeFailed' || 'failed' || 'bad-request' || 'invalidArguments' => l10n.phoneRootExecErrorExecute,
    _ => code,
  };
}

/// 设置 › 能力 › Root 增强: one consent, a self-test, and the audit log.
class RootExecPage extends ConsumerStatefulWidget {
  const RootExecPage({super.key});

  @override
  ConsumerState<RootExecPage> createState() => _RootExecPageState();
}

class _RootExecPageState extends ConsumerState<RootExecPage> {
  static const _margin = EdgeInsets.fromLTRB(16, 12, 16, 12);

  bool _refreshing = true;
  bool _authorizing = false;
  bool _revoking = false;
  bool _selfTesting = false;
  bool _auditLoading = true;
  bool _exporting = false;
  bool _clearing = false;
  String? _operationError;
  bool _operationRevoke = false;
  bool _auditFailed = false;
  List<PhoneRootExecAuditEntry> _audit = const [];
  PhoneRootExecSelfTest? _selfTestResult;

  @override
  void initState() {
    super.initState();
    unawaited(_refresh());
    unawaited(_loadAudit());
  }

  Future<void> _refresh() async {
    await ref.read(phoneProvider.notifier).refreshRootExec();
    if (!mounted) return;
    setState(() {
      _refreshing = false;
      _operationError = null;
    });
  }

  Future<void> _loadAudit() async {
    final entries = await ref.read(phoneProvider.notifier).rootExecAuditTail();
    if (!mounted) return;
    setState(() {
      _auditLoading = false;
      _auditFailed = entries == null;
      _audit = List.unmodifiable([...?entries].reversed);
    });
  }

  Future<void> _authorize() async {
    final l10n = context.l10n;
    final accepted = await confirmRisk(
      context,
      RiskNotice(
        id: 'phone.root-exec.authorize',
        level: RiskLevel.high,
        title: l10n.phoneRootExecAuthorizeTitle,
        body: l10n.phoneRootExecAuthorizeBody,
        action: l10n.phoneRootExecAuthorizeAction,
      ),
    );
    if (!accepted || !mounted) return;
    setState(() {
      _authorizing = true;
      _operationError = null;
      _operationRevoke = false;
      _selfTestResult = null;
    });
    final status = await ref.read(phoneProvider.notifier).authorizeRootExec();
    if (!mounted) return;
    final failed = status == null || !status.authorized;
    setState(() {
      _authorizing = false;
      _operationError = status == null
          ? 'rootUnavailable'
          : status.authorized
          ? null
          : (status.lastErrorCode ?? 'unauthorized');
    });
    if (!failed) showBriefNotice(context, l10n.phoneRootExecAuthorizeDone);
    unawaited(_loadAudit());
  }

  Future<void> _revoke() async {
    final l10n = context.l10n;
    final accepted = await confirmRisk(
      context,
      RiskNotice(
        id: 'phone.root-exec.revoke',
        level: RiskLevel.high,
        title: l10n.phoneRootExecRevokeTitle,
        body: l10n.phoneRootExecRevokeBody,
        action: l10n.phoneRootExecRevoke,
      ),
    );
    if (!accepted || !mounted) return;
    setState(() {
      _revoking = true;
      _operationError = null;
      _operationRevoke = true;
      _selfTestResult = null;
    });
    final status = await ref.read(phoneProvider.notifier).revokeRootExec();
    if (!mounted) return;
    final failed = status == null || status.authorized;
    setState(() {
      _revoking = false;
      _operationError = status == null
          ? 'rootUnavailable'
          : status.authorized
          ? (status.lastErrorCode ?? 'executeFailed')
          : null;
    });
    if (!failed) showBriefNotice(context, l10n.phoneRootExecRevoked);
    unawaited(_loadAudit());
  }

  Future<void> _selfTest() async {
    setState(() {
      _selfTesting = true;
      _selfTestResult = null;
      _operationError = null;
    });
    final result = await ref.read(phoneProvider.notifier).rootExecSelfTest();
    if (!mounted) return;
    setState(() {
      _selfTesting = false;
      _selfTestResult = result;
    });
    unawaited(_loadAudit());
  }

  Future<void> _exportAudit() async {
    if (_exporting || _auditLoading) return;
    final hadEntries = _audit.isNotEmpty;
    setState(() => _exporting = true);
    final path = await ref.read(phoneProvider.notifier).rootExecAuditExport();
    if (!mounted) return;
    setState(() => _exporting = false);
    final l10n = context.l10n;
    showBriefNotice(
      context,
      path != null
          ? l10n.phoneRootExecAuditExported(path)
          : hadEntries
          ? l10n.phoneRootExecAuditExportFailed
          : l10n.phoneRootExecAuditExportEmpty,
    );
  }

  Future<void> _clearAudit() async {
    if (_clearing || _auditLoading) return;
    final l10n = context.l10n;
    final accepted = await confirmRisk(
      context,
      RiskNotice(
        id: 'phone.root-exec.audit-clear',
        level: RiskLevel.high,
        title: l10n.phoneRootExecAuditClearTitle,
        body: l10n.phoneRootExecAuditClearBody,
        action: l10n.phoneRootExecAuditClear,
      ),
    );
    if (!accepted || !mounted) return;
    setState(() => _clearing = true);
    final ok = await ref.read(phoneProvider.notifier).rootExecAuditClear();
    if (!mounted) return;
    setState(() {
      _clearing = false;
      if (ok) {
        _audit = const [];
        _auditFailed = false;
      }
    });
    showBriefNotice(context, ok ? l10n.phoneRootExecAuditCleared : l10n.phoneRootExecAuditClearFailed);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final phone = ref.watch(phoneProvider);
    final status = phone.rootExecStatus;
    final available = phone.rootExecLoaded;
    final busy = _authorizing || _revoking || _selfTesting || _exporting || _clearing;
    final canUse = available && !_refreshing && !busy;
    return AppPage.list(
      title: l10n.phoneRootExecTitle,
      subtitle: Text(l10n.phoneRootExecSubtitle),
      children: [
        if (_refreshing) PhoneNote(text: l10n.phoneRootExecLoading),
        if (!_refreshing && !available)
          PhoneNote(key: const ValueKey('root-exec-unavailable'), tone: NoteTone.warn, text: l10n.phoneRootExecUnavailable),
        ListPageCard(
          margin: _margin,
          title: l10n.phoneRootExecStatus,
          children: [
            ListPageRow(
              key: const ValueKey('root-exec-authorized'),
              title: l10n.phoneRootExecAuthorized,
              value: status.authorized ? l10n.phoneRootExecAuthorizedYes : l10n.phoneRootExecAuthorizedNo,
            ),
            ListPageRow(
              key: const ValueKey('root-exec-connected'),
              title: l10n.phoneRootExecConnected,
              value: status.connected ? l10n.phoneRootExecConnectedYes : l10n.phoneRootExecConnectedNo,
            ),
            ListPageRow(
              key: const ValueKey('root-exec-uid'),
              title: l10n.phoneRootExecUid,
              value: status.uid?.toString() ?? '—',
            ),
            ListPageRow(
              key: const ValueKey('root-exec-port'),
              title: l10n.phoneRootExecPort,
              value: status.port?.toString() ?? l10n.phoneRootExecPortOff,
            ),
            if (status.busy)
              ListPageRow(
                key: const ValueKey('root-exec-busy'),
                icon: AppIcons.queued,
                title: l10n.phoneRootExecBusy,
              ),
            if (status.lastErrorCode case final code?)
              ListPageRow(
                key: const ValueKey('root-exec-last-error'),
                icon: AppIcons.warning,
                title: l10n.phoneRootExecLastError(code),
                titleMaxLines: 2,
                subtitle: phoneRootExecErrorText(l10n, code),
                subtitleMaxLines: 3,
              ),
            ListPageRow(
              key: const ValueKey('root-exec-refresh'),
              icon: AppIcons.refresh,
              title: l10n.phoneRootExecRefresh,
              onTap: _refreshing || busy
                  ? null
                  : () {
                      setState(() => _refreshing = true);
                      unawaited(_refresh());
                      unawaited(_loadAudit());
                    },
            ),
          ],
        ),
        if (_operationError case final code?)
          PhoneNote(
            key: const ValueKey('root-exec-operation-error'),
            tone: NoteTone.warn,
            title: _operationRevoke ? l10n.phoneRootExecRevokeFailed : l10n.phoneRootExecAuthorizeFailed,
            text: phoneRootExecErrorText(l10n, code),
          ),
        Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 0),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              FilledButton.icon(
                key: const ValueKey('root-exec-authorize'),
                onPressed: canUse ? () => unawaited(_authorize()) : null,
                icon: const Icon(AppIcons.terminal, size: 18),
                label: Text(l10n.phoneRootExecAuthorize),
              ),
              const SizedBox(height: 8),
              FilledButton.tonalIcon(
                key: const ValueKey('root-exec-self-test'),
                onPressed: canUse && !_selfTesting ? () => unawaited(_selfTest()) : null,
                icon: const Icon(AppIcons.verified, size: 18),
                label: Text(_selfTesting ? l10n.phoneRootExecSelfTestRunning : l10n.phoneRootExecSelfTest),
              ),
            ],
          ),
        ),
        ?_selfTestNote(l10n),
        ListPageCard(
          margin: _margin,
          title: l10n.phoneRootExecAuditTitle,
          children: [
            ListPageRow(
              title: l10n.phoneRootExecAuditHint,
              titleMaxLines: 3,
            ),
            if (_auditLoading)
              ListPageRow(
                key: const ValueKey('root-exec-audit-loading'),
                title: l10n.phoneRootExecAuditLoading,
              )
            else if (_auditFailed)
              ListPageRow(
                key: const ValueKey('root-exec-audit-failed'),
                icon: AppIcons.warning,
                title: l10n.phoneRootExecAuditLoadFailed,
                titleMaxLines: 2,
              )
            else if (_audit.isEmpty)
              ListPageRow(
                key: const ValueKey('root-exec-audit-empty'),
                title: l10n.phoneRootExecAuditEmpty,
              )
            else
              for (final (index, entry) in _audit.indexed)
                ListPageRow(
                  key: ValueKey('root-exec-audit-$index'),
                  icon: AppIcons.command,
                  title: entry.command.isEmpty ? '—' : entry.command,
                  titleMaxLines: 3,
                  subtitle: l10n.phoneRootExecAuditMeta(
                    _auditTime(entry.time),
                    _callerLabel(l10n, entry.caller),
                    entry.exitCode ?? -1,
                    entry.durationMs,
                  ),
                  subtitleMaxLines: 2,
                ),
            ListPageRow(
              key: const ValueKey('root-exec-audit-export'),
              icon: AppIcons.openExternal,
              title: l10n.phoneRootExecAuditExport,
              subtitle: _exporting ? l10n.phoneRootExecAuditLoading : null,
              onTap: canUse && !_exporting && !_auditLoading ? () => unawaited(_exportAudit()) : null,
            ),
            ListPageRow(
              key: const ValueKey('root-exec-audit-clear'),
              icon: AppIcons.delete,
              title: l10n.phoneRootExecAuditClear,
              subtitle: _clearing ? l10n.phoneRootExecAuditLoading : null,
              danger: true,
              onTap: canUse && !_clearing && !_auditLoading ? () => unawaited(_clearAudit()) : null,
            ),
          ],
        ),
        ListPageCard(
          margin: _margin,
          title: l10n.phoneRootExecDanger,
          children: [
            ListPageRow(
              key: const ValueKey('root-exec-revoke'),
              icon: AppIcons.blocked,
              title: l10n.phoneRootExecRevoke,
              titleMaxLines: 2,
              subtitle: l10n.phoneRootExecRevokeBody,
              subtitleMaxLines: 4,
              danger: true,
              onTap: canUse && status.authorized && !_revoking ? () => unawaited(_revoke()) : null,
            ),
          ],
        ),
      ],
    );
  }

  PhoneNote? _selfTestNote(L10n l10n) {
    if (_selfTesting) return PhoneNote(key: const ValueKey('root-exec-self-test-running'), text: l10n.phoneRootExecSelfTestRunning);
    final result = _selfTestResult;
    if (result == null) return null;
    if (result.ok) {
      return PhoneNote(key: const ValueKey('root-exec-self-test-ok'), text: l10n.phoneRootExecSelfTestOk);
    }
    final uid = result.uid;
    return PhoneNote(
      key: const ValueKey('root-exec-self-test-failed'),
      tone: NoteTone.warn,
      text: uid != null
          ? l10n.phoneRootExecSelfTestWrongUid('$uid')
          : l10n.phoneRootExecSelfTestFailed(phoneRootExecErrorText(l10n, result.reason)),
    );
  }

  String _auditTime(String raw) => raw.length < 19
      ? raw
      : '${raw.substring(0, 10)} ${raw.substring(11, 19)}';

  String _callerLabel(L10n l10n, String caller) => switch (caller) {
    'app' => l10n.phoneRootExecCallerApp,
    'guest' => l10n.phoneRootExecCallerGuest,
    _ => caller,
  };
}
