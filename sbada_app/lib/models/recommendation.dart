import 'item.dart';
import 'json.dart';

/// 점수 구성 요소: value(0~1) × weight 가 최종 점수에 더해진다.
class ScoreFactor {
  const ScoreFactor({required this.key, required this.label, required this.value, required this.weight});

  final String key;
  final String label;
  final double value;
  final double weight;

  double get contribution => value * weight;

  factory ScoreFactor.fromJson(Map<String, dynamic> j) => ScoreFactor(
        key: reqStr(j, 'key'),
        label: reqStr(j, 'label'),
        value: optDouble(j, 'value'),
        weight: optDouble(j, 'weight'),
      );
}

class Recommendation {
  const Recommendation({
    required this.item,
    required this.score,
    required this.priceDiffPercent,
    required this.factors,
    required this.reasons,
    this.requestStatus,
  });

  final Item item;

  /// 0~100
  final int score;
  final int priceDiffPercent;
  final List<ScoreFactor> factors;
  final List<String> reasons;

  /// 이미 요청을 보냈으면 REQUESTED
  final String? requestStatus;

  factory Recommendation.fromJson(Map<String, dynamic> j) => Recommendation(
        item: Item.fromJson(j['item'] as Map<String, dynamic>),
        score: reqInt(j, 'score'),
        priceDiffPercent: optInt(j, 'priceDiffPercent') ?? 0,
        factors: objList(j, 'factors', ScoreFactor.fromJson),
        reasons: strList(j, 'reasons'),
        requestStatus: optStr(j, 'requestStatus'),
      );
}

class MatchResult {
  const MatchResult({required this.base, required this.algorithm, required this.candidates, required this.matches});

  final Item base;
  final String algorithm;
  final int candidates;
  final List<Recommendation> matches;

  factory MatchResult.fromJson(Map<String, dynamic> j) => MatchResult(
        base: Item.fromJson(j['base'] as Map<String, dynamic>),
        algorithm: optStr(j, 'algorithm') ?? '',
        candidates: optInt(j, 'candidates') ?? 0,
        matches: objList(j, 'matches', Recommendation.fromJson),
      );
}

class FeedResult {
  const FeedResult({required this.algorithm, required this.candidates, required this.items});

  final String algorithm;
  final int candidates;
  final List<Recommendation> items;

  factory FeedResult.fromJson(Map<String, dynamic> j) => FeedResult(
        algorithm: optStr(j, 'algorithm') ?? '',
        candidates: optInt(j, 'candidates') ?? 0,
        items: objList(j, 'items', Recommendation.fromJson),
      );
}
