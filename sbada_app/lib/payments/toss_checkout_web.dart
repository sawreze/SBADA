import 'package:flutter/material.dart';

import '../repositories/toss_repository.dart';
import 'toss_checkout_result.dart';

export 'toss_checkout_result.dart';

/// 웹 빌드: 토스 결제창(WebView)은 앱에서만
Future<TossCheckoutResult?> openTossCheckout(BuildContext context, TossOrder order, {required String method}) async =>
    const TossCheckoutResult.failure('토스 결제는 앱(iOS · Android)에서 해 주세요.');
