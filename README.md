# flutter_bluetooth_basic

Flutter plugin that allows to find bluetooth devices & send raw bytes data.
Supports both Android and iOS.

Inspired by [bluetooth_print](https://github.com/thon-ju/bluetooth_print).


## Main Features
* Android and iOS support
* Scan for bluetooth devices
* Send raw `List<int> bytes` data to a device

## Android Build Requirements
* Android Gradle Plugin: `8.13.2`
* Gradle distribution: `8.13`
* Build runtime JDK: `21` (build fails fast if a different JDK is used)
* Java source/target level: `21`
* Kotlin plugin is intentionally not configured in this module
* Future Kotlin policy: if Kotlin is introduced, pin `kotlin-gradle-plugin` to `2.3.10+` with AGP `8.13.2+`
* Vendor binary gate: `verifyVendorPrinterJar` runs before `preBuild` and logs SHA-256 of `android/libs/gprintersdkv2.jar` because the JAR has no embedded version metadata
* Regression fallback: if Java 21 causes D8/R8/desugaring issues, keep JDK 21 runtime and lower Java source/target to `17`


## Getting Started

For a full example please check */example* folder. Here are only the most important parts of the code to illustrate how to use the library.

```dart
BluetoothManager bluetoothManager = BluetoothManager.instance;
BluetoothDevice _device;

bluetoothManager.startScan(timeout: Duration(seconds: 4));
bluetoothManager.state.listen((state) {
    switch (state) {
    case BluetoothManager.CONNECTED:
        // ...
        break;
    case BluetoothManager.DISCONNECTED:
        // ...
        break;
    default:
        break;
    }
});
// bluetoothManager.scanResults is a Stream<List<BluetoothDevice>> sending the found devices.

// _device = <from bluetoothManager.scanResults>

await bluetoothManager.connect(_device);

List<int> bytes = latin1.encode('Hello world!\n').toList();
await bluetoothManager.writeData(bytes);

await bluetoothManager.disconnect();
```

## See also
* Example of usage in a project: [esc_pos_printer](https://github.com/andrey-ushakov/esc_pos_printer)
