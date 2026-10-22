import 'package:flutter/services.dart';

import 'location.dart';

const _channel = MethodChannel('sbada/location');

/// 앱(Android · iOS): 처음이면 위치 권한을 묻고 지금 위치를 한 번 읽는다(최대 약 15초)
Future<Position> currentPosition() async {
  try {
    final m = await _channel.invokeMapMethod<String, Object?>('current');
    final lat = (m?['lat'] as num?)?.toDouble();
    final lng = (m?['lng'] as num?)?.toDouble();
    if (lat == null || lng == null) throw const LocationException('TIMEOUT');
    return (lat: lat, lng: lng);
  } on PlatformException catch (e) {
    throw LocationException(e.code);
  } on MissingPluginException {
    throw const LocationException('UNSUPPORTED'); // 테스트 · 데스크톱
  }
}
