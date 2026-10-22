import 'package:flutter/material.dart';

import '../config.dart';
import '../theme/app_theme.dart';

/// 서버 사진. 실패하면 회색 상자에 아이콘을 보여 준다.
class NetImage extends StatelessWidget {
  const NetImage(this.path, {super.key, this.size, this.radius = 14, this.fit = BoxFit.cover});

  final String? path;
  final double? size;
  final double radius;
  final BoxFit fit;

  @override
  Widget build(BuildContext context) {
    final url = AppConfig.imageUrl(path);
    final placeholder = Container(
      width: size,
      height: size,
      color: Sb.bg,
      alignment: Alignment.center,
      child: Icon(Icons.image_outlined, color: Sb.text3),
    );
    return ClipRRect(
      borderRadius: BorderRadius.circular(radius),
      child: url.isEmpty
          ? placeholder
          : Image.network(
              url,
              width: size,
              height: size,
              fit: fit,
              // 화면에 그리는 크기만큼만 풀어서(decode) 메모리 · 스크롤 렉을 줄인다(예: 96dp × 3배 = 288px).
              // 크기를 모르는(size 없음) 큰 사진은 원본 그대로.
              cacheWidth: size == null ? null : (size! * MediaQuery.devicePixelRatioOf(context)).round(),
              errorBuilder: (context, error, stack) => placeholder,
              loadingBuilder: (context, child, progress) => progress == null ? child : placeholder,
            ),
    );
  }
}
