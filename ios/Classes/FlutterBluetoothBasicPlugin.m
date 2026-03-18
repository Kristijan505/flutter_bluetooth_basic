#import "FlutterBluetoothBasicPlugin.h"

static NSString *const BTMethodState = @"state";
static NSString *const BTMethodIsAvailable = @"isAvailable";
static NSString *const BTMethodIsConnected = @"isConnected";
static NSString *const BTMethodIsOn = @"isOn";
static NSString *const BTMethodStartScan = @"startScan";
static NSString *const BTMethodStopScan = @"stopScan";
static NSString *const BTMethodConnect = @"connect";
static NSString *const BTMethodDisconnect = @"disconnect";
static NSString *const BTMethodDestroy = @"destroy";
static NSString *const BTMethodWriteData = @"writeData";
static NSString *const BTMethodScanResult = @"ScanResult";

static NSString *const BTErrorConnectTimeout = @"connect_timeout";
static NSString *const BTErrorWriteFailed = @"write_failed";
static NSString *const BTErrorDeviceDisconnected = @"device_disconnected";
static NSString *const BTErrorJobTimeout = @"job_timeout";

static NSArray<NSString *> *BTPreferredWriteCharacteristicUUIDs(void) {
  static NSArray<NSString *> *uuids;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    uuids = @[
      @"49535343-FE7D-4AE5-8FA9-9FAFD205E455",
      @"49535343-1E4D-4BD9-BA61-23C647249616",
      @"49535343-8841-43F4-A8D4-ECBE34729BB3",
      @"49535343-6DAA-4D02-ABF6-19569ACA69FE",
      @"49535343-ACA3-481C-91EC-D85E28A60318",
    ];
  });
  return uuids;
}

static NSSet<NSString *> *BTPreferredWriteCharacteristicUUIDSet(void) {
  static NSSet<NSString *> *uuidSet;
  static dispatch_once_t onceToken;
  dispatch_once(&onceToken, ^{
    uuidSet = [NSSet setWithArray:BTPreferredWriteCharacteristicUUIDs()];
  });
  return uuidSet;
}

static FlutterError *BTError(NSString *code, NSString *message, id details) {
  return [FlutterError errorWithCode:code message:message details:details];
}

@interface FlutterBluetoothBasicPlugin ()
@property(nonatomic, weak) NSObject<FlutterPluginRegistrar> *registrar;
@property(nonatomic, strong) FlutterMethodChannel *channel;
@property(nonatomic, strong) BluetoothPrintStreamHandler *stateStreamHandler;
@property(nonatomic, strong) CBCentralManager *centralManager;
@property(nonatomic, strong) NSMutableDictionary<NSString *, CBPeripheral *> *scannedPeripherals;
@property(nonatomic, strong) NSMutableArray<NSString *> *scanOrder;
@property(nonatomic, strong) CBPeripheral *activePeripheral;
@property(nonatomic, strong) CBPeripheral *pendingConnectPeripheral;
@property(nonatomic, strong) CBCharacteristic *writeCharacteristic;
@property(nonatomic, strong) CBCharacteristic *preferredWriteCharacteristic;
@property(nonatomic, strong) NSData *currentWritePayload;
@property(nonatomic, strong) NSArray<NSData *> *currentWriteChunks;
@property(nonatomic, copy) void (^currentWriteCompletion)(FlutterError *error);
@property(nonatomic) NSUInteger currentWriteChunkIndex;
@property(nonatomic) CBCharacteristicWriteType currentWriteType;
@property(nonatomic) BOOL scanRequested;
@property(nonatomic) BOOL scanActive;
@property(nonatomic) BOOL connectRequested;
@property(nonatomic) BOOL connectionReady;
@property(nonatomic) BOOL writeInProgress;
@property(nonatomic) BOOL waitingForWriteWithoutResponseDrain;
@property(nonatomic, strong) NSMutableSet<NSString *> *pendingServiceDiscoveryIDs;
@property(nonatomic) NSInteger pendingServiceDiscoveryCount;
@property(nonatomic, strong) dispatch_source_t connectTimeoutTimer;
@property(nonatomic, strong) dispatch_source_t writeTimeoutTimer;
@end

@implementation FlutterBluetoothBasicPlugin

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar {
  FlutterMethodChannel *channel = [FlutterMethodChannel
      methodChannelWithName:NAMESPACE @"/methods"
            binaryMessenger:[registrar messenger]];
  FlutterEventChannel *stateChannel =
      [FlutterEventChannel eventChannelWithName:NAMESPACE @"/state"
                                binaryMessenger:[registrar messenger]];

  FlutterBluetoothBasicPlugin *instance =
      [[FlutterBluetoothBasicPlugin alloc] init];
  instance.registrar = registrar;
  instance.channel = channel;
  instance.scannedPeripherals = [NSMutableDictionary new];
  instance.scanOrder = [NSMutableArray new];
  instance.pendingServiceDiscoveryIDs = [NSMutableSet new];
  instance.centralManager = [[CBCentralManager alloc]
      initWithDelegate:instance
                queue:dispatch_get_main_queue()];

  BluetoothPrintStreamHandler *stateStreamHandler =
      [[BluetoothPrintStreamHandler alloc] init];
  [stateChannel setStreamHandler:stateStreamHandler];
  instance.stateStreamHandler = stateStreamHandler;

  [registrar addMethodCallDelegate:instance channel:channel];
}

- (void)handleMethodCall:(FlutterMethodCall *)call result:(FlutterResult)result {
  if ([BTMethodState isEqualToString:call.method]) {
    result(@(self.connectionReady ? 1 : 0));
    return;
  }

  if ([BTMethodIsAvailable isEqualToString:call.method]) {
    result(@(self.centralManager.state != CBManagerStateUnsupported));
    return;
  }

  if ([BTMethodIsConnected isEqualToString:call.method]) {
    result(@(self.connectionReady));
    return;
  }

  if ([BTMethodIsOn isEqualToString:call.method]) {
    result(@(self.centralManager.state == CBManagerStatePoweredOn));
    return;
  }

  if ([BTMethodStartScan isEqualToString:call.method]) {
    [self startScan];
    result(nil);
    return;
  }

  if ([BTMethodStopScan isEqualToString:call.method]) {
    [self stopScan];
    result(nil);
    return;
  }

  if ([BTMethodConnect isEqualToString:call.method]) {
    NSDictionary *device = [call arguments];
    [self connectToDevice:device result:result];
    return;
  }

  if ([BTMethodDisconnect isEqualToString:call.method]) {
    [self disconnectActivePeripheralEmitState:YES];
    result(nil);
    return;
  }

  if ([BTMethodDestroy isEqualToString:call.method]) {
    [self disconnectActivePeripheralEmitState:YES];
    [self stopScan];
    result(nil);
    return;
  }

  if ([BTMethodWriteData isEqualToString:call.method]) {
    NSDictionary *args = [call arguments];
    [self writeDataWithArguments:args result:result];
    return;
  }

  result(FlutterMethodNotImplemented);
}

- (void)centralManagerDidUpdateState:(CBCentralManager *)central {
  if (central.state == CBManagerStatePoweredOn) {
    if (self.scanRequested && !self.scanActive) {
      [self startScanInternal];
    }

    if (self.connectRequested && self.pendingConnectPeripheral != nil &&
        !self.connectionReady) {
      [self startConnectionToPeripheral:self.pendingConnectPeripheral];
    }

    return;
  }

  if (self.scanActive) {
    [self stopScanInternal];
  }

  if (self.connectionReady || self.activePeripheral != nil || self.pendingConnectPeripheral != nil) {
    [self disconnectActivePeripheralEmitState:YES];
  }
}

- (void)centralManager:(CBCentralManager *)central
didDiscoverPeripheral:(CBPeripheral *)peripheral
     advertisementData:(NSDictionary<NSString *,id> *)advertisementData
                  RSSI:(NSNumber *)RSSI {
  NSString *identifier = peripheral.identifier.UUIDString;
  if (identifier.length == 0) {
    return;
  }

  NSString *displayName = peripheral.name;
  if (displayName.length == 0) {
    displayName = advertisementData[CBAdvertisementDataLocalNameKey];
  }
  if (displayName.length == 0) {
    displayName = identifier;
  }

  self.scannedPeripherals[identifier] = peripheral;
  if (![self.scanOrder containsObject:identifier]) {
    [self.scanOrder addObject:identifier];
  }

  NSDictionary *device = @{
    @"address": identifier,
    @"name": displayName,
    @"type": @0,
  };
  [self.channel invokeMethod:BTMethodScanResult arguments:device];
}

- (void)centralManager:(CBCentralManager *)central
    didConnectPeripheral:(CBPeripheral *)peripheral {
  if (self.pendingConnectPeripheral != nil &&
      self.pendingConnectPeripheral != peripheral &&
      ![self.pendingConnectPeripheral.identifier.UUIDString isEqualToString:peripheral.identifier.UUIDString]) {
    return;
  }

  self.activePeripheral = peripheral;
  peripheral.delegate = self;
  [peripheral discoverServices:nil];
}

- (void)centralManager:(CBCentralManager *)central
didFailToConnectPeripheral:(CBPeripheral *)peripheral
                   error:(NSError *)error {
  [self cancelConnectTimeout];
  [self clearConnectionState];
  [self emitState:@0];
}

- (void)centralManager:(CBCentralManager *)central
 didDisconnectPeripheral:(CBPeripheral *)peripheral
                   error:(NSError *)error {
  if (self.activePeripheral != nil &&
      ![self.activePeripheral.identifier.UUIDString isEqualToString:peripheral.identifier.UUIDString]) {
    return;
  }

  [self cancelConnectTimeout];
  [self cancelWriteTimeout];
  [self completeCurrentWriteWithError:BTError(BTErrorDeviceDisconnected,
                                              @"Peripheral disconnected.",
                                              error.localizedDescription ?: [NSNull null])
                           disconnect:NO];
  [self clearConnectionState];
  [self emitState:@0];
}

- (void)peripheral:(CBPeripheral *)peripheral didDiscoverServices:(NSError *)error {
  if (error != nil) {
    [self failConnectionWithCode:BTErrorDeviceDisconnected
                         message:@"Failed to discover services."
                         details:error.localizedDescription];
    return;
  }

  NSArray<CBService *> *services = peripheral.services;
  if (services.count == 0) {
    [self failConnectionWithCode:BTErrorWriteFailed
                         message:@"No services discovered on peripheral."
                         details:nil];
    return;
  }

  [self.pendingServiceDiscoveryIDs removeAllObjects];
  self.pendingServiceDiscoveryCount = 0;
  self.writeCharacteristic = nil;
  self.preferredWriteCharacteristic = nil;

  for (CBService *service in services) {
    if (service == nil || service.UUID == nil) {
      continue;
    }
    self.pendingServiceDiscoveryCount += 1;
    [self.pendingServiceDiscoveryIDs addObject:service.UUID.UUIDString];
    [peripheral discoverCharacteristics:nil forService:service];
  }

  if (self.pendingServiceDiscoveryCount == 0) {
    [self failConnectionWithCode:BTErrorWriteFailed
                         message:@"No discoverable characteristics found."
                         details:nil];
  }
}

- (void)peripheral:(CBPeripheral *)peripheral
didDiscoverCharacteristicsForService:(CBService *)service
               error:(NSError *)error {
  [self finishServiceDiscoveryForService:service
                                   error:error
                               peripheral:peripheral];
}

- (void)peripheral:(CBPeripheral *)peripheral
didWriteValueForCharacteristic:(CBCharacteristic *)characteristic
                        error:(NSError *)error {
  if (!self.writeInProgress) {
    return;
  }

  if (error != nil) {
    [self completeCurrentWriteWithError:BTError(BTErrorWriteFailed,
                                                @"Failed to write data with response.",
                                                error.localizedDescription ?: [NSNull null])
                             disconnect:YES];
    return;
  }

  [self sendNextWriteChunk];
}

- (void)peripheralIsReadyToSendWriteWithoutResponse:(CBPeripheral *)peripheral {
  if (!self.writeInProgress || self.currentWriteType != CBCharacteristicWriteWithoutResponse) {
    return;
  }

  [self sendNextWriteChunk];
}

- (void)startScan {
  self.scanRequested = YES;
  [self.scannedPeripherals removeAllObjects];
  [self.scanOrder removeAllObjects];

  if (self.centralManager.state == CBManagerStatePoweredOn) {
    [self startScanInternal];
  }
}

- (void)stopScan {
  self.scanRequested = NO;
  [self stopScanInternal];
}

- (void)connectToDevice:(NSDictionary *)device result:(FlutterResult)result {
  NSString *address = [device objectForKey:@"address"];
  if (address.length == 0) {
    result(BTError(BTErrorDeviceDisconnected,
                   @"Missing device address.",
                   nil));
    return;
  }

  CBPeripheral *peripheral = self.scannedPeripherals[address];
  if (peripheral == nil) {
    result(BTError(BTErrorDeviceDisconnected,
                   @"Device was not found in the current scan results.",
                   address));
    return;
  }

  self.connectRequested = YES;
  self.pendingConnectPeripheral = peripheral;
  [self cancelConnectTimeout];
  [self scheduleConnectTimeout];

  if (self.centralManager.state == CBManagerStatePoweredOn) {
    [self startConnectionToPeripheral:peripheral];
  }

  result(nil);
}

- (void)writeDataWithArguments:(NSDictionary *)args result:(FlutterResult)result {
  if (self.writeInProgress) {
    result(BTError(BTErrorWriteFailed,
                   @"Another write is already in progress.",
                   nil));
    return;
  }

  if (!self.connectionReady || self.activePeripheral == nil || self.writeCharacteristic == nil) {
    result(BTError(BTErrorDeviceDisconnected,
                   @"Printer is not connected.",
                   nil));
    return;
  }

  NSArray *bytes = args[@"bytes"];
  NSNumber *lenBuf = args[@"length"];
  NSUInteger length = lenBuf != nil ? [lenBuf unsignedIntegerValue] : bytes.count;
  if (bytes.count < length) {
    length = bytes.count;
  }

  if (length == 0) {
    result(nil);
    return;
  }

  NSMutableData *payload = [NSMutableData dataWithLength:length];
  uint8_t *buffer = payload.mutableBytes;
  for (NSUInteger index = 0; index < length && index < bytes.count; index += 1) {
    buffer[index] = (uint8_t)[bytes[index] unsignedCharValue];
  }

  self.writeInProgress = YES;
  self.currentWritePayload = [payload copy];
  self.currentWriteType =
      (self.writeCharacteristic.properties & CBCharacteristicPropertyWrite) != 0
          ? CBCharacteristicWriteWithResponse
          : CBCharacteristicWriteWithoutResponse;
  self.currentWriteChunks = [self splitData:self.currentWritePayload
                              maximumLength:[self writeChunkLength]];
  self.currentWriteChunkIndex = 0;
  self.currentWriteCompletion = ^(FlutterError *error) {
    result(error);
  };
  self.waitingForWriteWithoutResponseDrain = NO;

  [self cancelWriteTimeout];
  [self scheduleWriteTimeout];

  if (self.currentWriteChunks.count == 0) {
    [self completeCurrentWriteWithError:nil disconnect:NO];
    return;
  }

  [self sendNextWriteChunk];
}

- (void)startScanInternal {
  if (self.scanActive) {
    [self.centralManager stopScan];
    self.scanActive = NO;
  }

  NSDictionary *options = @{ CBCentralManagerScanOptionAllowDuplicatesKey : @NO };
  [self.centralManager scanForPeripheralsWithServices:nil options:options];
  self.scanActive = YES;
}

- (void)stopScanInternal {
  if (!self.scanActive) {
    return;
  }

  [self.centralManager stopScan];
  self.scanActive = NO;
}

- (void)startConnectionToPeripheral:(CBPeripheral *)peripheral {
  if (peripheral == nil) {
    return;
  }

  self.pendingConnectPeripheral = peripheral;
  peripheral.delegate = self;

  if (self.centralManager.state != CBManagerStatePoweredOn) {
    return;
  }

  if (self.activePeripheral != nil &&
      ![self.activePeripheral.identifier.UUIDString isEqualToString:peripheral.identifier.UUIDString]) {
    [self.centralManager cancelPeripheralConnection:self.activePeripheral];
  }

  self.connectionReady = NO;
  self.writeCharacteristic = nil;
  self.preferredWriteCharacteristic = nil;
  self.pendingServiceDiscoveryCount = 0;
  [self.pendingServiceDiscoveryIDs removeAllObjects];

  [self.centralManager connectPeripheral:peripheral options:nil];
}

- (void)finishServiceDiscoveryForService:(CBService *)service
                                  error:(NSError *)error
                              peripheral:(CBPeripheral *)peripheral {
  NSString *serviceID = service.UUID.UUIDString;
  if (serviceID.length > 0) {
    [self.pendingServiceDiscoveryIDs removeObject:serviceID];
  }

  if (error != nil) {
    [self failConnectionWithCode:BTErrorWriteFailed
                         message:@"Failed to discover characteristics."
                         details:error.localizedDescription];
    return;
  }

  for (CBCharacteristic *characteristic in service.characteristics) {
    if (![self isWritableCharacteristic:characteristic]) {
      continue;
    }

    if ([self isPreferredWriteCharacteristic:characteristic]) {
      self.preferredWriteCharacteristic = characteristic;
      self.writeCharacteristic = characteristic;
      break;
    }

    if (self.writeCharacteristic == nil) {
      self.writeCharacteristic = characteristic;
    }
  }

  if (self.pendingServiceDiscoveryCount > 0) {
    self.pendingServiceDiscoveryCount -= 1;
  }

  if (self.pendingServiceDiscoveryCount > 0) {
    return;
  }

  self.writeCharacteristic = self.preferredWriteCharacteristic ?: self.writeCharacteristic;

  if (self.writeCharacteristic == nil) {
    [self failConnectionWithCode:BTErrorWriteFailed
                         message:@"No writable characteristic found."
                         details:nil];
    return;
  }

  self.activePeripheral = peripheral;
  self.connectionReady = YES;
  self.connectRequested = NO;
  [self cancelConnectTimeout];
  [self emitState:@1];
}

- (BOOL)isWritableCharacteristic:(CBCharacteristic *)characteristic {
  CBCharacteristicProperties properties = characteristic.properties;
  return (properties & CBCharacteristicPropertyWrite) != 0 ||
         (properties & CBCharacteristicPropertyWriteWithoutResponse) != 0;
}

- (BOOL)isPreferredWriteCharacteristic:(CBCharacteristic *)characteristic {
  NSString *uuidString = characteristic.UUID.UUIDString.uppercaseString;
  if (uuidString.length == 0) {
    return NO;
  }
  return [BTPreferredWriteCharacteristicUUIDSet() containsObject:uuidString];
}

- (NSUInteger)writeChunkLength {
  if (self.activePeripheral == nil || self.writeCharacteristic == nil) {
    return 20;
  }

  NSUInteger maxLength = [self.activePeripheral maximumWriteValueLengthForType:self.currentWriteType];
  return maxLength > 0 ? maxLength : 20;
}

- (NSArray<NSData *> *)splitData:(NSData *)data maximumLength:(NSUInteger)maximumLength {
  if (data.length == 0) {
    return @[];
  }

  NSUInteger chunkSize = maximumLength > 0 ? maximumLength : 20;
  NSMutableArray<NSData *> *chunks = [NSMutableArray array];
  const uint8_t *bytes = data.bytes;

  for (NSUInteger offset = 0; offset < data.length; offset += chunkSize) {
    NSUInteger remaining = data.length - offset;
    NSUInteger length = MIN(chunkSize, remaining);
    [chunks addObject:[NSData dataWithBytes:bytes + offset length:length]];
  }

  return chunks;
}

- (void)sendNextWriteChunk {
  if (!self.writeInProgress || self.activePeripheral == nil || self.writeCharacteristic == nil) {
    return;
  }

  if (self.currentWriteChunkIndex >= self.currentWriteChunks.count) {
    [self completeCurrentWriteWithError:nil disconnect:NO];
    return;
  }

  if (self.currentWriteType == CBCharacteristicWriteWithResponse) {
    NSData *chunk = self.currentWriteChunks[self.currentWriteChunkIndex];
    self.currentWriteChunkIndex += 1;
    [self.activePeripheral writeValue:chunk
                    forCharacteristic:self.writeCharacteristic
                                 type:CBCharacteristicWriteWithResponse];
    return;
  }

  while (self.currentWriteChunkIndex < self.currentWriteChunks.count) {
    if (![self.activePeripheral canSendWriteWithoutResponse]) {
      self.waitingForWriteWithoutResponseDrain = YES;
      return;
    }

    NSData *chunk = self.currentWriteChunks[self.currentWriteChunkIndex];
    self.currentWriteChunkIndex += 1;
    [self.activePeripheral writeValue:chunk
                    forCharacteristic:self.writeCharacteristic
                                 type:CBCharacteristicWriteWithoutResponse];
  }

  [self completeCurrentWriteWithError:nil disconnect:NO];
}

- (void)failConnectionWithCode:(NSString *)code
                       message:(NSString *)message
                       details:(id)details {
  [self cancelConnectTimeout];
  CBPeripheral *pendingPeripheral = self.activePeripheral ?: self.pendingConnectPeripheral;
  [self clearConnectionState];
  [self emitState:@0];

  if (pendingPeripheral != nil && self.centralManager.state == CBManagerStatePoweredOn) {
    [self.centralManager cancelPeripheralConnection:pendingPeripheral];
  }

  NSLog(@"Bluetooth connection failed (%@): %@", code, message);
}

- (void)clearConnectionState {
  self.connectRequested = NO;
  self.connectionReady = NO;
  self.activePeripheral = nil;
  self.pendingConnectPeripheral = nil;
  self.writeCharacteristic = nil;
  self.preferredWriteCharacteristic = nil;
  self.pendingServiceDiscoveryCount = 0;
  [self.pendingServiceDiscoveryIDs removeAllObjects];
}

- (void)emitState:(NSNumber *)state {
  dispatch_async(dispatch_get_main_queue(), ^{
    if (self.stateStreamHandler.sink != nil) {
      self.stateStreamHandler.sink(state);
    }
  });
}

- (void)scheduleConnectTimeout {
  __weak typeof(self) weakSelf = self;
  dispatch_source_t timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, dispatch_get_main_queue());
  dispatch_source_set_timer(timer,
                            dispatch_time(DISPATCH_TIME_NOW, (int64_t)(12.0 * NSEC_PER_SEC)),
                            DISPATCH_TIME_FOREVER,
                            0);
  dispatch_source_set_event_handler(timer, ^{
    __strong typeof(weakSelf) strongSelf = weakSelf;
    if (strongSelf == nil) {
      return;
    }
    if (!strongSelf.connectionReady) {
      [strongSelf failConnectionWithCode:BTErrorConnectTimeout
                                 message:@"Bluetooth connection timed out."
                                 details:nil];
    }
    [strongSelf cancelConnectTimeout];
  });
  self.connectTimeoutTimer = timer;
  dispatch_resume(timer);
}

- (void)cancelConnectTimeout {
  if (self.connectTimeoutTimer != nil) {
    dispatch_source_cancel(self.connectTimeoutTimer);
    self.connectTimeoutTimer = nil;
  }
}

- (void)scheduleWriteTimeout {
  __weak typeof(self) weakSelf = self;
  dispatch_source_t timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, dispatch_get_main_queue());
  dispatch_source_set_timer(timer,
                            dispatch_time(DISPATCH_TIME_NOW, (int64_t)(60.0 * NSEC_PER_SEC)),
                            DISPATCH_TIME_FOREVER,
                            0);
  dispatch_source_set_event_handler(timer, ^{
    __strong typeof(weakSelf) strongSelf = weakSelf;
    if (strongSelf == nil) {
      return;
    }
    if (strongSelf.writeInProgress) {
      [strongSelf completeCurrentWriteWithError:BTError(BTErrorJobTimeout,
                                                        @"Bluetooth print job timed out.",
                                                        nil)
                                       disconnect:YES];
    }
    [strongSelf cancelWriteTimeout];
  });
  self.writeTimeoutTimer = timer;
  dispatch_resume(timer);
}

- (void)cancelWriteTimeout {
  if (self.writeTimeoutTimer != nil) {
    dispatch_source_cancel(self.writeTimeoutTimer);
    self.writeTimeoutTimer = nil;
  }
}

- (void)completeCurrentWriteWithError:(FlutterError *)error disconnect:(BOOL)disconnect {
  if (!self.writeInProgress && self.currentWriteCompletion == nil) {
    return;
  }

  [self cancelWriteTimeout];

  void (^completion)(FlutterError *) = self.currentWriteCompletion;
  self.currentWriteCompletion = nil;
  self.writeInProgress = NO;
  self.waitingForWriteWithoutResponseDrain = NO;
  self.currentWritePayload = nil;
  self.currentWriteChunks = nil;
  self.currentWriteChunkIndex = 0;

  if (disconnect && self.activePeripheral != nil) {
    [self.centralManager cancelPeripheralConnection:self.activePeripheral];
  }

  if (completion != nil) {
    completion(error);
  }
}

- (void)disconnectActivePeripheralEmitState:(BOOL)emitState {
  [self cancelConnectTimeout];
  [self cancelWriteTimeout];

  CBPeripheral *targetPeripheral = self.activePeripheral ?: self.pendingConnectPeripheral;
  if (targetPeripheral != nil &&
      self.centralManager.state == CBManagerStatePoweredOn) {
    [self.centralManager cancelPeripheralConnection:targetPeripheral];
  }

  [self completeCurrentWriteWithError:BTError(BTErrorDeviceDisconnected,
                                              @"Printer disconnected.",
                                              nil)
                           disconnect:NO];
  [self clearConnectionState];

  if (emitState) {
    [self emitState:@0];
  }
}

@end

@implementation BluetoothPrintStreamHandler

- (FlutterError *)onListenWithArguments:(id)arguments eventSink:(FlutterEventSink)eventSink {
  self.sink = eventSink;
  return nil;
}

- (FlutterError *)onCancelWithArguments:(id)arguments {
  self.sink = nil;
  return nil;
}

@end
