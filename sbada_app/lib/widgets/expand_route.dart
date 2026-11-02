import 'dart:ui' show lerpDouble;

import 'package:flutter/cupertino.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';

import '../theme/app_theme.dart';
import '../util/apple.dart';

/// 화면 전환 하나로 모은 곳.
///  - 안드로이드: 지금 그대로(MaterialPageRoute)
///  - iOS: 방금 누른 목록 칸(카드)이 그 자리에서 전체 화면으로 부드럽게 퍼지며 열린다(App Store '투데이' 카드처럼).
///         누른 칸을 못 찾으면 iOS 기본 화면 전환(오른쪽에서 밀려 들어오기 · 왼쪽 끝 밀어서 뒤로).
/// 쓰는 법: MaterialPageRoute(builder: …) 자리에 appRoute(builder: …)
PageRoute<T> appRoute<T>({required WidgetBuilder builder, RouteSettings? settings, bool fullscreenDialog = false}) {
  if (!Apple.on) return MaterialPageRoute<T>(builder: builder, settings: settings, fullscreenDialog: fullscreenDialog);
  final origin = ExpandOrigin.take();
  if (origin == null || fullscreenDialog) {
    return CupertinoPageRoute<T>(builder: builder, settings: settings, fullscreenDialog: fullscreenDialog);
  }
  return ExpandPageRoute<T>(builder: builder, origin: origin, settings: settings);
}

/// 손가락이 닿은 자리의 '카드'(목록 한 칸) 위치를 기억해 둔다. 앱 맨 바깥 Listener 가 부른다.
class ExpandOrigin {
  ExpandOrigin._();

  static Rect? _rect;
  static DateTime _at = DateTime(2000);

  /// 손가락이 닿은 점 아래에서 가장 가까운 ExpandSource(목록 한 칸)를 찾아 그 위치를 기억
  static void capture(Offset position) {
    if (!Apple.on) return;
    _rect = null;
    final binding = WidgetsBinding.instance;
    final view = binding.platformDispatcher.implicitView;
    if (view == null) return;
    final result = HitTestResult();
    binding.hitTestInView(result, position, view.viewId);
    for (final entry in result.path) {
      final t = entry.target;
      if (t is! _RenderExpandSource || !t.attached || !t.hasSize) continue;
      _rect = MatrixUtils.transformRect(t.getTransformTo(null), Offset.zero & t.size);
      _at = DateTime.now();
      return;
    }
  }

  /// 0.8초 안에 누른 카드가 있으면 그 위치를 한 번만 돌려준다
  static Rect? take() {
    final r = _rect;
    _rect = null;
    if (r == null || DateTime.now().difference(_at) > const Duration(milliseconds: 800)) return null;
    return r;
  }
}

/// 이 칸을 누르고 화면이 열리면(appRoute) iOS 에서는 이 칸 자리에서 화면이 퍼져 나온다. 안드로이드는 아무 일도 안 함.
class ExpandSource extends SingleChildRenderObjectWidget {
  const ExpandSource({super.key, super.child});

  @override
  RenderObject createRenderObject(BuildContext context) => _RenderExpandSource();
}

class _RenderExpandSource extends RenderProxyBox {}

/// 누른 카드 자리에서 전체 화면으로 퍼지는 화면 전환(닫을 때는 다시 그 카드로 줄어든다).
/// 왼쪽 끝을 오른쪽으로 밀면 손가락을 따라 카드로 줄어들며 닫힌다.
class ExpandPageRoute<T> extends PageRoute<T> {
  ExpandPageRoute({required this.builder, required this.origin, super.settings});

  final WidgetBuilder builder;
  final Rect origin;

  @override
  Duration get transitionDuration => const Duration(milliseconds: 520);

  @override
  Duration get reverseTransitionDuration => const Duration(milliseconds: 420);

  @override
  bool get maintainState => true;

  /// 밀어서 닫기에서 손가락으로 전환을 움직일 때 쓴다
  AnimationController? get swipeController => controller;

  @override
  Color? get barrierColor => null;

  @override
  String? get barrierLabel => null;

  /// 퍼질 때: 빠르게 출발해서 살짝 감속(애플의 'emphasized' 곡선과 비슷), 닫힐 때도 같은 곡선을 거꾸로
  static const _curve = Cubic(0.2, 0.85, 0.25, 1.0);

  @override
  Widget buildPage(BuildContext context, Animation<double> animation, Animation<double> secondaryAnimation) {
    return _EdgeSwipeBack(route: this, child: builder(context));
  }

  @override
  Widget buildTransitions(
      BuildContext context, Animation<double> animation, Animation<double> secondaryAnimation, Widget child) {
    final curved = CurvedAnimation(parent: animation, curve: _curve, reverseCurve: _curve.flipped);
    return AnimatedBuilder(
      animation: curved,
      child: child,
      builder: (context, child) {
        final size = MediaQuery.sizeOf(context);
        final full = Offset.zero & size;
        final t = curved.value;
        if (animation.status == AnimationStatus.completed) return child!;
        final rect = Rect.lerp(origin, full, t)!;
        final scale = (rect.width / size.width).clamp(0.01, 1.0);
        final radius = lerpDouble(18, 0, t)!;
        // 내용은 처음 35% 동안 서서히 나타난다(카드 → 화면으로 바뀌는 느낌)
        final fade = Curves.easeOut.transform(((t - 0.05) / 0.35).clamp(0.0, 1.0));
        return Stack(children: [
          // 뒤 화면을 살짝 어둡게
          Positioned.fill(child: IgnorePointer(child: ColoredBox(color: Colors.black.withValues(alpha: 0.32 * t)))),
          Positioned.fromRect(
            rect: rect,
            child: DecoratedBox(
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(radius),
                boxShadow: [BoxShadow(color: Colors.black.withValues(alpha: 0.25 * (1 - t)), blurRadius: 30, offset: const Offset(0, 12))],
              ),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(radius),
                child: ColoredBox(
                  color: Sb.base,
                  child: OverflowBox(
                    alignment: Alignment.topLeft,
                    minWidth: size.width,
                    maxWidth: size.width,
                    minHeight: size.height,
                    maxHeight: size.height,
                    child: Transform.scale(
                      scale: scale,
                      alignment: Alignment.topLeft,
                      child: Opacity(opacity: fade, child: child),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ]);
      },
    );
  }
}

/// 왼쪽 가장자리(24dp)를 밀어서 닫기: 손가락을 따라 화면이 카드로 줄어들고, 놓으면 닫히거나 되돌아간다.
class _EdgeSwipeBack extends StatefulWidget {
  const _EdgeSwipeBack({required this.route, required this.child});

  final ExpandPageRoute<dynamic> route;
  final Widget child;

  @override
  State<_EdgeSwipeBack> createState() => _EdgeSwipeBackState();
}

class _EdgeSwipeBackState extends State<_EdgeSwipeBack> {
  bool _dragging = false;

  AnimationController? get _c => widget.route.swipeController;

  void _start(DragStartDetails d) {
    if (d.globalPosition.dx > 24 || !widget.route.isCurrent) return;
    _dragging = true;
    widget.route.navigator?.didStartUserGesture();
  }

  void _update(DragUpdateDetails d) {
    if (!_dragging) return;
    final w = MediaQuery.sizeOf(context).width;
    _c?.value = (_c!.value - d.primaryDelta! / w * 1.2).clamp(0.0, 1.0);
  }

  void _end(DragEndDetails d) {
    if (!_dragging) return;
    _dragging = false;
    final c = _c;
    final nav = widget.route.navigator;
    if (c == null || nav == null) return;
    final close = (d.primaryVelocity ?? 0) > 600 || c.value < 0.6;
    if (close) {
      Apple.tick();
      nav.pop();
    } else {
      c.forward();
    }
    nav.didStopUserGesture();
  }

  @override
  Widget build(BuildContext context) {
    return Stack(children: [
      widget.child,
      Positioned(
        left: 0,
        top: 0,
        bottom: 0,
        width: 24,
        child: GestureDetector(
          behavior: HitTestBehavior.translucent,
          onHorizontalDragStart: _start,
          onHorizontalDragUpdate: _update,
          onHorizontalDragEnd: _end,
          onHorizontalDragCancel: () => _end(DragEndDetails(primaryVelocity: 0)),
        ),
      ),
    ]);
  }
}

/// iOS: 누르는 동안 카드가 살짝 작아졌다가(0.97배) 놓으면 통통 튀며 돌아온다. 안드로이드는 그대로.
class PressScale extends StatefulWidget {
  const PressScale({super.key, required this.child});

  final Widget child;

  @override
  State<PressScale> createState() => _PressScaleState();
}

class _PressScaleState extends State<PressScale> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    if (!Apple.on) return widget.child;
    return Listener(
      onPointerDown: (_) => setState(() => _down = true),
      onPointerUp: (_) => setState(() => _down = false),
      onPointerCancel: (_) => setState(() => _down = false),
      child: AnimatedScale(
        scale: _down ? 0.97 : 1.0,
        duration: Duration(milliseconds: _down ? 120 : 380),
        curve: _down ? Curves.easeOut : Curves.elasticOut,
        child: widget.child,
      ),
    );
  }
}
