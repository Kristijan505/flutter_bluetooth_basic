#import <Flutter/Flutter.h>
#import <CoreBluetooth/CoreBluetooth.h>

#define NAMESPACE @"flutter_bluetooth_basic"

@interface FlutterBluetoothBasicPlugin : NSObject<FlutterPlugin, CBCentralManagerDelegate, CBPeripheralDelegate>
@end

@interface BluetoothPrintStreamHandler : NSObject<FlutterStreamHandler>
@property(nonatomic, copy) FlutterEventSink sink;
@end
