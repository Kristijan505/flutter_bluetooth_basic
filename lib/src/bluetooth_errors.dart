import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

class BluetoothErrorCodes {
  BluetoothErrorCodes._();

  static const String connectTimeout = 'connect_timeout';
  static const String writeFailed = 'write_failed';
  static const String deviceDisconnected = 'device_disconnected';
  static const String jobTimeout = 'job_timeout';
}

PlatformException normalizeBluetoothPlatformException(
  final String method,
  final PlatformException error,
) {
  final normalizedCode = normalizeBluetoothErrorCode(method, error.code);
  if (normalizedCode == error.code) {
    return error;
  }

  return PlatformException(
    code: normalizedCode,
    message: error.message,
    details: error.details,
  );
}

@visibleForTesting
String normalizeBluetoothErrorCode(
  final String method,
  final String? code,
) {
  final normalizedCode = (code ?? '').trim().toLowerCase();

  if (normalizedCode.isEmpty) {
    return 'bluetooth_error';
  }

  if (_isConnectMethod(method)) {
    if (normalizedCode == 'timeout' ||
        normalizedCode == 'connecttimeout' ||
        normalizedCode == 'connection_timeout' ||
        normalizedCode == 'connect_timeout') {
      return BluetoothErrorCodes.connectTimeout;
    }
  }

  if (_isWriteMethod(method)) {
    if (normalizedCode == 'timeout' ||
        normalizedCode == 'job_timeout' ||
        normalizedCode == 'write_timeout' ||
        normalizedCode == 'print_timeout') {
      return BluetoothErrorCodes.jobTimeout;
    }
  }

  if (normalizedCode == BluetoothErrorCodes.connectTimeout) {
    return BluetoothErrorCodes.connectTimeout;
  }

  if (normalizedCode == BluetoothErrorCodes.writeFailed ||
      normalizedCode == 'write_error' ||
      normalizedCode == 'write_failure' ||
      normalizedCode == 'writefailed') {
    return BluetoothErrorCodes.writeFailed;
  }

  if (normalizedCode == BluetoothErrorCodes.deviceDisconnected ||
      normalizedCode == 'disconnected' ||
      normalizedCode == 'disconnect' ||
      normalizedCode == 'device_lost' ||
      normalizedCode == 'connection_lost') {
    return BluetoothErrorCodes.deviceDisconnected;
  }

  if (normalizedCode == BluetoothErrorCodes.jobTimeout) {
    return BluetoothErrorCodes.jobTimeout;
  }

  return normalizedCode;
}

bool _isConnectMethod(final String method) =>
    method == 'connect' || method == 'startScan';

bool _isWriteMethod(final String method) =>
    method == 'writeData' || method == 'write' || method == 'printTicket';
