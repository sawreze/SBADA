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
              errorBuilder: (context, error, stack) => placeholder,
              loadingBuilder: (context, child, progress) => progress == null ? child : placeholder,
            ),
    );
  }
}
