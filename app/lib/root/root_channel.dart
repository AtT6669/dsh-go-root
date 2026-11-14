import 'package:flutter/services.dart';

/// Dart side of the native `dsh/root` channel.
///
/// `status` is cheap and never prompts; `request` runs the manager dialog.
class RootChannel {
  RootChannel._();

  static const MethodChannel _channel = MethodChannel('dsh/root');

  static Future<RootSnapshot> status() async {
    try {
      final value = await _channel.invokeMapMethod<String, Object?>('status');
      return RootSnapshot.fromMap(value ?? const {});
    } on MissingPluginException {
      // Upstream (non-root) build: no gate, no privileged surface.
      return const RootSnapshot(phase: RootPhase.unsupported);
    } on PlatformException catch (error) {
      return RootSnapshot(phase: RootPhase.unknown, detail: error.message);
    }
  }

  static Future<RootSnapshot> request() async {
    try {
      final value = await _channel.invokeMapMethod<String, Object?>('request');
      return RootSnapshot.fromMap(value ?? const {});
    } on MissingPluginException {
      return const RootSnapshot(phase: RootPhase.unsupported);
    } on PlatformException catch (error) {
      return RootSnapshot(phase: RootPhase.denied, detail: error.message);
    }
  }

  /// Runs one host command as uid 0 after the policy check on the native side.
  static Future<RootExecResult> exec(String command, {int timeoutMs = 60000, String? purpose}) async {
    final value = await _channel.invokeMapMethod<String, Object?>('exec', {
      'command': command,
      'timeoutMs': timeoutMs,
      'purpose': purpose,
    });
    return RootExecResult.fromMap(value ?? const {});
  }

  static Future<bool> startBroker() async {
    try {
      final value = await _channel.invokeMapMethod<String, Object?>('brokerStart');
      return (value?['ok'] as bool?) ?? false;
    } on MissingPluginException {
      return false;
    } on PlatformException {
      return false;
    }
  }

  static Future<void> stopBroker() => _channel.invokeMethod<void>('brokerStop');

  static Future<String> audit({int lines = 200}) async =>
      (await _channel.invokeMethod<String>('audit', {'lines': lines}) ?? '');

  static Future<bool> clearData() async =>
      (await _channel.invokeMethod<bool>('clearData')) ?? false;

  static Future<String> helperPath() async =>
      (await _channel.invokeMethod<String>('helper')) ?? '';
}

enum RootPhase {
  unknown,
  checking,
  granted,
  denied,
  noManager,
  timeout,
  revoked,
  unsupported;

  static RootPhase parse(String? value) => RootPhase.values.firstWhere(
        (phase) => phase.name == value,
        orElse: () => RootPhase.unknown,
      );

  bool get grantedNow => this == RootPhase.granted;
  bool get legacy => this == RootPhase.unsupported;
}

class RootSnapshot {
  const RootSnapshot({
    required this.phase,
    this.detail,
    this.manager,
    this.uid,
  });

  final RootPhase phase;
  final String? detail;
  final String? manager;
  final int? uid;

  factory RootSnapshot.fromMap(Map<Object?, Object?> map) => RootSnapshot(
        phase: RootPhase.parse(map['phase'] as String?),
        detail: map['detail'] as String?,
        manager: map['manager'] as String?,
        uid: map['uid'] as int?,
      );
}

class RootExecResult {
  const RootExecResult({
    required this.code,
    required this.output,
    this.level = 'write',
    this.timedOut = false,
  });

  final int code;
  final String output;
  final String level;
  final bool timedOut;

  bool get ok => code == 0 && !timedOut;

  factory RootExecResult.fromMap(Map<Object?, Object?> map) => RootExecResult(
        code: (map['code'] as int?) ?? -1,
        output: (map['output'] as String?) ?? '',
        level: (map['level'] as String?) ?? 'write',
        timedOut: (map['timedOut'] as bool?) ?? false,
      );
}
