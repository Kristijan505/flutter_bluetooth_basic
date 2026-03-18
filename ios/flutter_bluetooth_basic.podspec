#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_bluetooth_basic.podspec' to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_bluetooth_basic'
  s.version          = '0.1.7'
  s.summary          = 'Flutter Bluetooth scan and raw byte transport plugin.'
  s.description      = <<-DESC
Flutter plugin that scans Bluetooth devices and sends raw bytes data to printers
on Android and iOS.
                       DESC
  s.homepage         = 'https://github.com/Kristijan505/flutter_bluetooth_basic'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Kristijan505' => 'kristijan505@users.noreply.github.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/FlutterBluetoothBasicPlugin.{h,m}'
  s.public_header_files = 'Classes/FlutterBluetoothBasicPlugin.h'
  s.static_framework = true
  s.dependency 'Flutter'
  s.platform = :ios, '13.0'

  s.frameworks = ['CoreBluetooth']

  # Flutter.framework does not contain a i386 slice. Only x86_64 simulators are supported.
  # s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'VALID_ARCHS[sdk=iphonesimulator*]' => 'x86_64' }
end
