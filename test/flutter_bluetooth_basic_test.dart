import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter_bluetooth_basic/src/bluetooth_errors.dart';
import 'package:flutter_bluetooth_basic/src/bluetooth_manager.dart';

void main() {
  const MethodChannel channel = MethodChannel('flutter_bluetooth_basic/methods');

  TestWidgetsFlutterBinding.ensureInitialized();

  late MethodCall? lastCall;

  setUp(() {
    lastCall = null;

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
          lastCall = methodCall;

          switch (methodCall.method) {
            case 'writeData':
              return true;
            case 'isConnected':
              return true;
            case 'state':
              return 1;
            case 'startScan':
            case 'stopScan':
            case 'connect':
            case 'disconnect':
            case 'destroy':
              return true;
            default:
              return null;
          }
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('writeData awaits and returns the native method result', () async {
    final result = await BluetoothManager.instance.writeData(<int>[1, 2, 3]);

    expect(result, isTrue);
    expect(lastCall?.method, 'writeData');

    final args = lastCall!.arguments as Map<dynamic, dynamic>;
    expect(args['length'], 3);
    expect(args['bytes'], <int>[1, 2, 3]);
  });

  test('normalizeBluetoothErrorCode maps legacy codes to stable codes', () {
    expect(
      normalizeBluetoothErrorCode('connect', 'timeout'),
      BluetoothErrorCodes.connectTimeout,
    );
    expect(
      normalizeBluetoothErrorCode('writeData', 'timeout'),
      BluetoothErrorCodes.jobTimeout,
    );
    expect(
      normalizeBluetoothErrorCode('writeData', 'write_error'),
      BluetoothErrorCodes.writeFailed,
    );
    expect(
      normalizeBluetoothErrorCode('writeData', 'connection_lost'),
      BluetoothErrorCodes.deviceDisconnected,
    );
  });

  test('writeData rethrows normalized PlatformException codes', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
          lastCall = methodCall;

          if (methodCall.method == 'writeData') {
            throw PlatformException(code: 'timeout', message: 'printer stalled');
          }

          return true;
        });

    await expectLater(
      BluetoothManager.instance.writeData(<int>[1, 2, 3]),
      throwsA(
        isA<PlatformException>()
            .having((PlatformException e) => e.code, 'code', BluetoothErrorCodes.jobTimeout)
            .having((PlatformException e) => e.message, 'message', 'printer stalled'),
      ),
    );
  });

  test('queryStatus forwards the request and returns the native byte response', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
          lastCall = methodCall;

          if (methodCall.method == 'queryStatus') {
            return Uint8List.fromList(<int>[0x12, 0x34]);
          }

          return true;
        });

    final result = await BluetoothManager.instance.queryStatus(
      <int>[0x10, 0x04, 0x01],
      timeout: const Duration(seconds: 2),
      maxBytes: 4,
    );

    expect(result, isA<Uint8List>());
    expect(result, <int>[0x12, 0x34]);
    expect(lastCall?.method, 'queryStatus');

    final args = lastCall!.arguments as Map<dynamic, dynamic>;
    expect(args['bytes'], <int>[0x10, 0x04, 0x01]);
    expect(args['timeoutMs'], 2000);
    expect(args['maxBytes'], 4);
  });

  test('queryStatus returns an empty Uint8List when the printer does not answer', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
          lastCall = methodCall;

          if (methodCall.method == 'queryStatus') {
            return null;
          }

          return true;
        });

    final result = await BluetoothManager.instance.queryStatus(<int>[0x10, 0x04, 0x01]);

    expect(result, isA<Uint8List>());
    expect(result, isEmpty);
  });

  test('queryStatus rethrows normalized PlatformException codes', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (MethodCall methodCall) async {
          lastCall = methodCall;

          if (methodCall.method == 'queryStatus') {
            throw PlatformException(code: 'device_lost', message: 'printer disconnected');
          }

          return true;
        });

    await expectLater(
      BluetoothManager.instance.queryStatus(<int>[0x10, 0x04, 0x01]),
      throwsA(
        isA<PlatformException>()
            .having((PlatformException e) => e.code, 'code', BluetoothErrorCodes.deviceDisconnected)
            .having((PlatformException e) => e.message, 'message', 'printer disconnected'),
      ),
    );
  });
}
