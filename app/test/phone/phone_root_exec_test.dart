// Root 增强: the `privileged.exec` projection and the `dsh/root` maps parse
// without a native side, and anything missing falls back to safe defaults.

import 'package:dsh_app/state/phone_state.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('privileged.exec parses the projection and defaults when absent', () {
    final status = PhonePrivilegedStatus.parse({
      'v': 1,
      'mode': 'root',
      'supportedModes': ['root'],
      'exec': {
        'v': 1,
        'authorized': true,
        'connected': true,
        'busy': false,
        'lastErrorCode': 'busy',
        'auditPath': '/data/user/0/app/files/root-exec/root-audit.log',
      },
    });
    expect(status, isNotNull);
    expect(status!.exec.authorized, isTrue);
    expect(status.exec.connected, isTrue);
    expect(status.exec.busy, isFalse);
    expect(status.exec.lastErrorCode, 'busy');
    expect(status.exec.auditPath, '/data/user/0/app/files/root-exec/root-audit.log');
    expect(status.exec.uid, isNull);
    expect(status.exec.port, isNull);

    final legacy = PhonePrivilegedStatus.parse({'v': 1, 'mode': 'root'});
    expect(legacy!.exec.authorized, isFalse);
    expect(legacy.exec.connected, isFalse);
    expect(legacy.exec.busy, isFalse);
    expect(legacy.exec.lastErrorCode, isNull);
    expect(legacy.exec.auditPath, '');

    final malformed = PhonePrivilegedStatus.parse({'v': 1, 'mode': 'root', 'exec': 'root'});
    expect(malformed!.exec.authorized, isFalse);
    expect(malformed.exec.auditPath, '');
  });

  test('dsh/root status keeps the bridge-only fields and ignores wrong types', () {
    final status = PhoneRootExecStatus.fromMap({
      'authorized': true,
      'connected': false,
      'busy': 1,
      'lastErrorCode': '  ',
      'auditPath': '/tmp/audit.log',
      'uid': 0,
      'packageName': 'io.github.ccch1mneyyy.dsh_app',
      'port': 43123,
      'sdk': 34,
    });
    expect(status.authorized, isTrue);
    expect(status.connected, isFalse);
    expect(status.busy, isFalse);
    expect(status.lastErrorCode, isNull);
    expect(status.auditPath, '/tmp/audit.log');
    expect(status.uid, 0);
    expect(status.packageName, 'io.github.ccch1mneyyy.dsh_app');
    expect(status.port, 43123);
    expect(status.sdk, 34);

    final empty = PhoneRootExecStatus.fromMap({'uid': '0', 'port': '43123', 'authorized': 'yes'});
    expect(empty.authorized, isFalse);
    expect(empty.uid, isNull);
    expect(empty.port, isNull);
  });

  test('exec replies and audit entries parse with empty defaults', () {
    final result = PhoneRootExecResult.fromMap({
      'ok': true,
      'exitCode': 0,
      'stdout': '0\n',
      'stderr': '',
      'truncated': false,
      'durationMs': 12,
    });
    expect(result.ok, isTrue);
    expect(result.exitCode, 0);
    expect(result.stdout.trim(), '0');
    expect(result.durationMs, 12);
    expect(result.errorCode, isNull);
    expect(result.reason, isNull);

    final failed = PhoneRootExecResult.fromMap({'ok': false, 'errorCode': 'unauthorized'});
    expect(failed.ok, isFalse);
    expect(failed.exitCode, isNull);
    expect(failed.stdout, '');
    expect(failed.stderr, '');
    expect(failed.truncated, isFalse);
    expect(failed.durationMs, 0);
    expect(failed.errorCode, 'unauthorized');

    final entry = PhoneRootExecAuditEntry.fromMap({
      'time': '2026-11-11T10:20:30+08:00',
      'caller': 'guest',
      'exitCode': 1,
      'durationMs': 230,
      'command': 'id -u',
    });
    expect(entry.time, '2026-11-11T10:20:30+08:00');
    expect(entry.caller, 'guest');
    expect(entry.exitCode, 1);
    expect(entry.durationMs, 230);
    expect(entry.command, 'id -u');

    final blank = PhoneRootExecAuditEntry.fromMap({});
    expect(blank.time, '');
    expect(blank.caller, '');
    expect(blank.exitCode, isNull);
    expect(blank.durationMs, 0);
    expect(blank.command, '');
  });

  test('state prefers dsh/root status and falls back to privileged.exec', () {
    final state = PhoneState(
      status: PhoneStatus.fromMap({
        'background': {
          'privileged': {
            'v': 1,
            'mode': 'root',
            'supportedModes': ['root'],
            'exec': {'v': 1, 'authorized': true, 'auditPath': '/projection.log'},
          },
        },
      }),
    );
    expect(state.rootExecLoaded, isFalse);
    expect(state.rootExecStatus.authorized, isTrue);
    expect(state.rootExecStatus.auditPath, '/projection.log');

    final loaded = state.copyWith(
      rootExec: const PhoneRootExecStatus(authorized: false, auditPath: '/root.log'),
      rootExecLoaded: true,
    );
    expect(loaded.rootExecStatus.authorized, isFalse);
    expect(loaded.rootExecStatus.auditPath, '/root.log');
  });
}
