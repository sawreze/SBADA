import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:sbada/map/geo.dart';
import 'package:sbada/network/api_client.dart';
import 'package:sbada/network/realtime_client.dart';
import 'package:sbada/network/session.dart';
import 'package:sbada/services/app_services.dart';
import 'package:sbada/services/local_notifier.dart';
import 'package:sbada/widgets/gps_dong_button.dart';

/// GPS 동네 찾기: 휴대폰 위치(MethodChannel 'sbada/location')와 서버(/api/geo/reverse)를 가짜로 바꿔 검사한다
void main() {
  const channel = MethodChannel('sbada/location');
  final calls = <Uri>[];

  AppServices services(MemoryStore store) {
    final session = Session(store);
    final client = MockClient((req) async {
      calls.add(req.url);
      if (req.url.path == '/api/geo/reverse') {
        return http.Response.bytes(
            utf8.encode(jsonEncode({'region': '서울 마포구 망원동', 'dong': '망원동', 'lat': 37.556, 'lng': 126.901})), 200);
      }
      return http.Response('{}', 200);
    });
    return AppServices(
      session: session,
      api: ApiClient(baseUrl: 'http://test', session: session, client: client),
      realtime: RealtimeClient(wsUrl: 'ws://test/ws', session: session),
      notifier: LocalNotifier(),
    );
  }

  Future<String?> pumpButton(WidgetTester tester, AppServices s) async {
    String? found;
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: StatefulBuilder(
          builder: (context, setState) => GpsDongButton(
            services: s,
            found: found,
            onFound: (d) => setState(() => found = d.region),
          ),
        ),
      ),
    ));
    await tester.tap(find.byKey(const ValueKey('gps-find-dong')));
    await tester.pumpAndSettle();
    return found;
  }

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    calls.clear();
    gpsRegionCenters.clear();
  });

  testWidgets('현재 위치로 동네 찾기 → 서버가 동네 이름으로 바꿔 주고, 좌표는 이 기기의 지도 중심으로 기억한다', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async => {'lat': 37.5560, 'lng': 126.9010});
    final store = MemoryStore();
    await pumpButton(tester, services(store));

    expect(calls.single.path, '/api/geo/reverse');
    expect(calls.single.queryParameters['lat'], '37.556000');
    expect(find.text('망원동  서울 마포구 망원동', findRichText: true), findsOneWidget); // 굵은 '동' 이름 + 전체 주소
    expect(find.textContaining('서울 마포구 망원동'), findsOneWidget);
    expect(dongLatLng('서울 마포구 망원동').lat, closeTo(37.556, 1e-6)); // 지도 '내 동네' 중심 = GPS 자리
    expect(await store.read('sbada.gps.centers'), contains('서울 마포구 망원동'));
  });

  testWidgets('위치 권한을 거부하면 서버에 묻지 않고 안내 문구를 보여 준다', (tester) async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        channel, (call) async => throw PlatformException(code: 'PERMISSION_DENIED'));
    await pumpButton(tester, services(MemoryStore()));

    expect(calls, isEmpty);
    expect(find.textContaining('위치 권한이 꺼져 있어요'), findsOneWidget);
    expect(find.text('현재 위치로 동네 찾기'), findsOneWidget); // 다시 누를 수 있다
  });
}
