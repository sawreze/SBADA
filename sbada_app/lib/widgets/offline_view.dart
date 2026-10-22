import 'dart:async';

import 'package:flutter/material.dart';

import '../network/api_exception.dart';
import '../theme/app_theme.dart';

/// 인터넷이 없거나 약할 때 보여 줄 때까지 기다리는 시간(2026-10-22 사용자 요청: 15초)
const offlineWait = Duration(seconds: 15);

/// 연결 실패(인터넷 없음 · 서버 꺼짐)는 보통 바로 오류가 나는데, 잠깐 끊긴 것일 수도 있으니
/// 시작부터 [wait](15초)가 지날 때까지는 계속 '불러오는 중'으로 두고 그 뒤에 오류를 넘긴다.
///  - 인터넷이 약해 응답이 늦으면 ApiClient 의 15초 제한(TIMEOUT)이 걸려 거의 같은 때 오류가 난다.
///  - 연결 오류가 아닌 것(403, 404 등)과 성공은 기다리지 않고 바로 넘긴다.
Future<T> holdNetworkError<T>(Future<T> future, {Duration wait = offlineWait}) async {
  final reached = Completer<void>();
  final timer = Timer(wait, reached.complete); // 시작부터 15초
  try {
    return await future;
  } on ApiException catch (e) {
    if (e.isNetwork && timer.isActive) await reached.future;
    rethrow;
  } finally {
    timer.cancel(); // 성공 · 다른 오류면 남은 타이머를 치운다
  }
}

/// 화면 가운데: '화면을 불러오지 못했어요' + 아래 우는 얼굴 그림 + 다시 불러오기.
/// 그림은 `assets/offline/offline.png` 에 넣으면 그 그림을 쓰고, 없으면 😭 이모지.
class OfflineView extends StatelessWidget {
  const OfflineView({super.key, required this.onRetry});

  final VoidCallback onRetry;

  static const imageAsset = 'assets/offline/offline.png';

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(mainAxisSize: MainAxisSize.min, children: [
          Text('화면을 불러오지 못했어요', style: sbH2, textAlign: TextAlign.center),
          const SizedBox(height: 6),
          Text('인터넷 연결을 확인해 주세요.', style: sbBody, textAlign: TextAlign.center),
          const SizedBox(height: 20),
          Semantics(
            label: '우는 얼굴',
            image: true,
            child: Image.asset(
              imageAsset,
              width: 120,
              height: 120,
              fit: BoxFit.contain,
              // 그림 파일이 아직 없으면 이모지로 대신한다
              errorBuilder: (context, error, stack) => const SizedBox(
                width: 120,
                height: 120,
                child: Center(child: Text('😭', key: ValueKey('offline-emoji'), style: TextStyle(fontSize: 80))),
              ),
            ),
          ),
          const SizedBox(height: 24),
          OutlinedButton.icon(
            onPressed: onRetry,
            style: OutlinedButton.styleFrom(minimumSize: const Size(0, 48)),
            icon: const Icon(Icons.refresh),
            label: const Text('다시 불러오기'),
          ),
        ]),
      ),
    );
  }
}
