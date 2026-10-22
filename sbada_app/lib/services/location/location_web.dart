import 'dart:async';
import 'dart:js_interop';

import 'package:web/web.dart' as web;

import 'location.dart';

/// 웹(브라우저 테스트 화면): navigator.geolocation. 브라우저가 위치 허용을 묻는다.
Future<Position> currentPosition() {
  final done = Completer<Position>();
  web.window.navigator.geolocation.getCurrentPosition(
    (web.GeolocationPosition p) {
      if (!done.isCompleted) done.complete((lat: p.coords.latitude, lng: p.coords.longitude));
    }.toJS,
    (web.GeolocationPositionError e) {
      // 1 = 권한 거부, 2 = 위치 없음, 3 = 시간 초과
      final code = switch (e.code) { 1 => 'PERMISSION_DENIED', 2 => 'LOCATION_OFF', _ => 'TIMEOUT' };
      if (!done.isCompleted) done.completeError(LocationException(code));
    }.toJS,
    web.PositionOptions(enableHighAccuracy: false, timeout: 15000, maximumAge: 600000),
  );
  return done.future;
}
