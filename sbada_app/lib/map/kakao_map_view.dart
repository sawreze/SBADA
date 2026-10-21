import 'dart:convert';

import 'package:flutter/material.dart';

import '../config.dart';
import '../theme/app_theme.dart';
import '../widgets/match_pin.dart';
import 'geo.dart';
import 'map_host.dart';

/// 지도 위 핀 하나(카카오맵 HTML 핀으로 그린다)
class MapPin {
  const MapPin({required this.id, required this.at, this.imagePath, this.score, this.plain = false});

  final int id;
  final LatLng at;
  final String? imagePath;

  /// 매치도(0~100). null 이면 '–' 를 보여 주고 빛나지 않는다.
  final int? score;

  /// true 면 사진·매치도 없이 위치만 가리키는 민트 핀(상세 화면 '거래 희망 장소')
  final bool plain;

  Map<String, Object?> toJson() => {
        'plain': plain,
        'id': id,
        'lat': at.lat,
        'lng': at.lng,
        'img': AppConfig.imageUrl(imagePath),
        'score': score,
        'glow': glowStrength(score),
        'period': glowPeriod(score).inMilliseconds / 1000,
      };
}

/// 바깥에서 지도를 움직일 때 쓴다(목록에서 물건을 누르면 그 위치로 이동 등)
class KakaoMapController {
  _KakaoMapViewState? _state;

  Future<void> panTo(LatLng p, {int? level}) async =>
      _state?._js('panTo(${p.lat}, ${p.lng}, ${level ?? 'null'})');
}

/// 지도를 WebView 로 띄운다. 핀 모양·자바스크립트 함수(setPins/selectPin/panTo)는 두 지도가 같다.
///  - [jsKey] 가 null(기본, 서버 sbada.map.provider=osm): OpenStreetMap 타일 + Leaflet. 키·요금 없음
///  - [jsKey] 가 있으면 카카오맵 JavaScript SDK. 카카오 콘솔 [플랫폼 > Web] 에 http://localhost 등록 필요.
///  - 키가 틀리거나 인터넷이 없어 지도를 못 띄우면 [onError] 를 불러 화면이 약도로 바꿀 수 있게 한다.
class KakaoMapView extends StatefulWidget {
  const KakaoMapView({
    super.key,
    required this.jsKey,
    required this.center,
    this.level = 5,
    this.pins = const [],
    this.me,
    this.selectedId,
    this.interactive = true,
    this.onPinTap,
    this.onMapTap,
    this.onError,
    this.controller,
  });

  /// 카카오 JavaScript 키. null 이면 키가 필요 없는 OpenStreetMap(Leaflet) 으로 그린다.
  final String? jsKey;
  final LatLng center;

  /// 카카오맵 확대 단계(1 = 가장 가까이, 숫자가 클수록 넓게)
  final int level;
  final List<MapPin> pins;

  /// 내 동네 위치(민트 점 + 둘레 원)
  final LatLng? me;
  final int? selectedId;

  /// false 면 끌기·확대를 막는다(상세 화면의 작은 지도 카드)
  final bool interactive;
  final ValueChanged<int>? onPinTap;
  final VoidCallback? onMapTap;
  final ValueChanged<String>? onError;
  final KakaoMapController? controller;

  @override
  State<KakaoMapView> createState() => _KakaoMapViewState();
}

class _KakaoMapViewState extends State<KakaoMapView> {
  late final MapHost _host;
  bool _ready = false;

  @override
  void initState() {
    super.initState();
    widget.controller?._state = this;
    _host = MapHost(html: _html(), onMessage: _onMessage);
  }

  @override
  void didUpdateWidget(KakaoMapView old) {
    super.didUpdateWidget(old);
    widget.controller?._state = this;
    if (!_ready) return;
    if (!identical(old.pins, widget.pins)) _js('setPins(${_pinsJson()})');
    if (old.selectedId != widget.selectedId) _js('selectPin(${widget.selectedId ?? 'null'})');
  }

  @override
  void dispose() {
    if (widget.controller?._state == this) widget.controller?._state = null;
    _host.dispose();
    super.dispose();
  }

  Future<void> _js(String code) async {
    if (!_ready) return;
    try {
      await _host.run(code);
    } catch (_) {
      // 화면이 닫히는 중이면 무시
    }
  }

  void _onMessage(String message) {
    final data = jsonDecode(message) as Map<String, dynamic>;
    switch (data['type']) {
      case 'ready':
        _ready = true;
        _js('setPins(${_pinsJson()})');
        if (widget.selectedId != null) _js('selectPin(${widget.selectedId})');
      case 'tap':
        widget.onPinTap?.call((data['id'] as num).toInt());
      case 'mapTap':
        widget.onMapTap?.call();
      case 'error':
        widget.onError?.call('${data['message']}');
    }
  }

  String _pinsJson() => _safe(jsonEncode([for (final p in widget.pins) p.toJson()]));

  /// HTML 안에 넣는 JSON 에서 </script> 같은 글자가 태그로 읽히지 않게 막는다
  static String _safe(String json) => json.replaceAll('<', r'<');

  String _html() {
    final jsKey = widget.jsKey;
    if (jsKey == null || jsKey.isEmpty) return _leafletHtml();
    final key = Uri.encodeQueryComponent(jsKey);
    final me = widget.me == null ? 'null' : '{"lat":${widget.me!.lat},"lng":${widget.me!.lng}}';
    return '''
<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<style>
$_css
</style>
</head><body>
<div id="map"></div>
<script>
  function post(o){ try{ Sbada.postMessage(JSON.stringify(o)); }catch(e){} }
  window.onerror = function(msg){ post({type:'error', message:String(msg)}); };
  var failTimer = setTimeout(function(){ if(!window.__mapOk) post({type:'error', message:'timeout'}); }, 12000);
</script>
<script src="https://dapi.kakao.com/v2/maps/sdk.js?appkey=$key&autoload=false"
        onerror="post({type:'error', message:'sdk-load-failed'})"></script>
<script>
  var map, overlays = {}, selected = null;
$_pinJs
  var pinsData = [];
  function setPins(list){
    pinsData = list;
    if(!map) return;
    for (var k in overlays) overlays[k].setMap(null);
    overlays = {};
    list.forEach(function(p){
      var el = document.createElement('div');
      el.innerHTML = pinHtml(p);
      var node = el.firstChild;
      node.addEventListener('click', function(ev){ ev.stopPropagation(); post({type:'tap', id:p.id}); });
      // yAnchor: 핀 꼭짓점(아래 여백 12px 위)이 물건 위치를 가리키게
      var yAnchor = p.plain ? (88+6)/100 : 100/112;
      var o = new kakao.maps.CustomOverlay({position:new kakao.maps.LatLng(p.lat,p.lng), content:node, xAnchor:0.5, yAnchor:yAnchor, zIndex:(p.score||0)});
      o.setMap(map);
      overlays[p.id] = o;
    });
  }
  function selectPin(id){
    selected = id;
    setPins(pinsData);
    if (id !== null && overlays[id]) overlays[id].setZIndex(1000);
  }
  function panTo(lat, lng, level){
    if(!map) return;
    if(level) map.setLevel(level);
    map.panTo(new kakao.maps.LatLng(lat, lng));
  }

  kakao.maps.load(function(){
    window.__mapOk = true; clearTimeout(failTimer);
    map = new kakao.maps.Map(document.getElementById('map'), {
      center: new kakao.maps.LatLng(${widget.center.lat}, ${widget.center.lng}),
      level: ${widget.level}
    });
    map.setDraggable(${widget.interactive});
    map.setZoomable(${widget.interactive});
    kakao.maps.event.addListener(map, 'click', function(){ post({type:'mapTap'}); });
    var me = $me;
    if (me) {
      var d = document.createElement('div'); d.className='me'; d.innerHTML='<i></i>';
      new kakao.maps.CustomOverlay({position:new kakao.maps.LatLng(me.lat, me.lng), content:d, xAnchor:.5, yAnchor:.5, zIndex:0}).setMap(map);
    }
    post({type:'ready'});
  });
</script>
</body></html>
''';
  }

  /// 두 지도가 같이 쓰는 핀·내 위치 CSS
  String get _css => '''
  html,body,#map{margin:0;padding:0;width:100%;height:100%;background:#1B1E23;}
  .pin{position:relative;width:88px;height:112px;cursor:pointer;-webkit-tap-highlight-color:transparent;}
  .pin svg{position:absolute;left:0;top:0;overflow:visible;}
  .pin .photo{position:absolute;left:${12 + 32 - 21}px;top:${12 + 27.5 - 21}px;width:42px;height:42px;border-radius:50%;
              border:2px solid #fff;box-sizing:border-box;object-fit:cover;background:#2B2E35;}
  .pin .score{position:absolute;left:12px;width:64px;top:${12 + 27.5 + 21 + 1}px;text-align:center;
              font:900 12px/1.1 -apple-system,Roboto,'Noto Sans KR',sans-serif;color:#7CF5A8;}
  .pin.sel .score{color:#0D1F16;}
  .me{width:120px;height:120px;border-radius:50%;background:rgba(147,225,183,.12);border:1px solid rgba(147,225,183,.45);
      display:flex;align-items:center;justify-content:center;pointer-events:none;}
  .me i{width:16px;height:16px;border-radius:50%;background:#93E1B7;border:3px solid #fff;box-shadow:0 0 0 6px rgba(147,225,183,.3);}''';

  /// 두 지도가 같이 쓰는 핀 그리기 함수(pinHtml)
  static const String _pinJs = r'''
  // 물방울 핀(가로 64, 세로 88): 위쪽 원 + 꼭짓점으로 이어지는 두 접선 (앱의 MatchPin 과 같은 모양)
  var PIN = 'M32 88 L58.26 50.29 A32 32 0 1 0 5.74 50.29 Z';

  function pinHtml(p){
    if (p.plain) {
      return '<div class="pin plain" data-id="'+p.id+'" style="width:44px;height:58px">'
        + '<svg width="44" height="58" viewBox="-6 -6 76 100" style="filter:drop-shadow(0 3px 5px rgba(0,0,0,.45))">'
        + '<path d="'+PIN+'" fill="#93E1B7" stroke="#0D1F16" stroke-width="2"/>'
        + '<circle cx="32" cy="32" r="12" fill="#0D1F16"/></svg></div>';
    }
    var s = p.glow || 0;
    var fill = (p.id === selected) ? '#93E1B7' : 'rgba(42,45,52,.94)';
    var glow = s > 0
      ? 'filter:drop-shadow(0 0 '+(3+8*s).toFixed(1)+'px rgba(52,224,122,'+(0.35+0.6*s).toFixed(2)+'))'
         + ' drop-shadow(0 0 '+(1+3*s).toFixed(1)+'px rgba(185,255,210,'+(0.3+0.5*s).toFixed(2)+'));'
      : 'filter:drop-shadow(0 3px 5px rgba(0,0,0,.45));';
    var gid = 'g' + p.id;
    var stroke = s > 0 ? 'url(#'+gid+')' : 'rgba(255,255,255,.25)';
    var defs = s > 0
      ? '<defs><linearGradient id="'+gid+'" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="64" y2="0">'
        + '<stop offset="0" stop-color="#34E07A" stop-opacity="'+(0.25*s).toFixed(2)+'"/>'
        + '<stop offset=".45" stop-color="#34E07A"/>'
        + '<stop offset=".5" stop-color="#B9FFD2"/>'
        + '<stop offset=".55" stop-color="#34E07A"/>'
        + '<stop offset="1" stop-color="#34E07A" stop-opacity="'+(0.25*s).toFixed(2)+'"/>'
        + '<animateTransform attributeName="gradientTransform" type="rotate" from="0 32 40" to="360 32 40" dur="'+p.period+'s" repeatCount="indefinite"/>'
        + '</linearGradient></defs>'
      : '';
    var score = (p.score === null || p.score === undefined) ? '–' : (p.score + '%');
    return '<div class="pin'+(p.id===selected?' sel':'')+'" data-id="'+p.id+'">'
      + '<svg width="88" height="112" viewBox="-12 -12 88 112" style="'+glow+'">' + defs
      + '<path d="'+PIN+'" fill="'+fill+'" stroke="'+stroke+'" stroke-width="'+(s>0?(2+2*s).toFixed(1):1)+'"/></svg>'
      + '<img class="photo" src="'+p.img+'" onerror="this.style.visibility=\'hidden\'">'
      + '<div class="score">'+score+'</div></div>';
  }
''';

  /// OpenStreetMap 타일 + Leaflet 1.9.4 (키·요금 없음). 카카오 화면과 같은 핀·함수·메시지를 쓴다.
  ///  - 카카오 확대 단계(level, 클수록 넓게)를 Leaflet zoom(클수록 가까이)으로 바꾼다: zoom ≈ 20 - level
  ///  - 다크 모드에서는 밝은 OSM 타일을 CSS 필터로 어둡게 뒤집는다(핀은 그대로)
  ///  - 지도 출처 표기(© OpenStreetMap)는 OSM 이용 조건이라 지우지 않는다. 링크로 두면 누를 때 지도 화면이 다른 사이트로 넘어가서 글자로만 둔다
  String _leafletHtml() {
    final me = widget.me == null ? 'null' : '{"lat":${widget.me!.lat},"lng":${widget.me!.lng}}';
    final zoom = (20 - widget.level).clamp(3, 19);
    final i = widget.interactive;
    final tileFilter = Sb.isDark ? 'invert(1) hue-rotate(180deg) brightness(.9) contrast(.9) saturate(.6)' : 'none';
    final bg = Sb.isDark ? '#1B1E23' : '#F2EFE9';
    return '''
<!doctype html>
<html><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css">
<style>
$_css
  html,body,#map{background:$bg;}
  .leaflet-tile-pane{filter:$tileFilter;}
  .leaflet-div-icon{background:none;border:none;}
  /* leaflet.css 의 .leaflet-map-pane svg{z-index:200} 이 핀 모양을 사진 · 매치% 위로 올려서 가려 버린다 → 핀 안에서 순서를 다시 정한다 */
  .leaflet-map-pane .pin svg{z-index:0;}
  .leaflet-map-pane .pin .photo, .leaflet-map-pane .pin .score{z-index:1;}
  .leaflet-control-attribution{font-size:9px;background:rgba(0,0,0,.35)!important;color:#ddd;}
  .leaflet-control-attribution a{color:#cfe;}
</style>
</head><body>
<div id="map"></div>
<script>
  function post(o){ try{ Sbada.postMessage(JSON.stringify(o)); }catch(e){} }
  window.onerror = function(msg){ post({type:'error', message:String(msg)}); };
  var failTimer = setTimeout(function(){ if(!window.__mapOk) post({type:'error', message:'timeout'}); }, 12000);
</script>
<script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"
        onerror="post({type:'error', message:'sdk-load-failed'})"></script>
<script>
  var map, overlays = {}, selected = null;
$_pinJs

  var pinsData = [];
  function setPins(list){
    pinsData = list;
    if(!map) return;
    for (var k in overlays) map.removeLayer(overlays[k]);
    overlays = {};
    list.forEach(function(p){
      // 핀 꼭짓점(아래 여백 12px 위)이 물건 위치를 가리키게
      var icon = p.plain
        ? L.divIcon({html:pinHtml(p), className:'', iconSize:[44,58], iconAnchor:[22,58*(88+6)/100]})
        : L.divIcon({html:pinHtml(p), className:'', iconSize:[88,112], iconAnchor:[44,100]});
      var m = L.marker([p.lat, p.lng], {icon:icon, zIndexOffset:(p.id===selected ? 1000 : (p.score||0)), keyboard:false});
      m.on('click', function(ev){ L.DomEvent.stopPropagation(ev); post({type:'tap', id:p.id}); });
      m.addTo(map);
      overlays[p.id] = m;
    });
  }
  function selectPin(id){
    selected = id;
    setPins(pinsData);
  }
  function panTo(lat, lng, level){
    if(!map) return;
    if(level) map.setView([lat, lng], Math.max(3, Math.min(19, 20 - level)));
    else map.panTo([lat, lng]);
  }

  (function(){
    if (typeof L === 'undefined') return; // 스크립트를 못 받았으면 onerror/timeout 이 알린다
    window.__mapOk = true; clearTimeout(failTimer);
    map = L.map('map', {
      zoomControl:false, attributionControl:true,
      dragging:$i, touchZoom:$i, scrollWheelZoom:$i, doubleClickZoom:$i, boxZoom:$i, keyboard:$i
    }).setView([${widget.center.lat}, ${widget.center.lng}], $zoom);
    map.attributionControl.setPrefix(false);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom:19, attribution:'&copy; OpenStreetMap contributors'
    }).addTo(map);
    map.on('click', function(){ post({type:'mapTap'}); });
    var me = $me;
    if (me) {
      L.marker([me.lat, me.lng], {icon:L.divIcon({html:'<div class="me"><i></i></div>', className:'', iconSize:[120,120], iconAnchor:[60,60]}),
        interactive:false, keyboard:false, zIndexOffset:-1000}).addTo(map);
    }
    post({type:'ready'});
  })();
</script>
</body></html>
''';
  }


  @override
  Widget build(BuildContext context) => _host.build();
}
