import 'json.dart';

/// 서버의 AI 가격 분석 결과(POST /api/ai/analyze)
class AiAnalysis {
  const AiAnalysis({
    required this.analysisId,
    required this.imageId,
    required this.suggestedTitle,
    required this.category,
    required this.categoryLabel,
    required this.condition,
    required this.conditionLabel,
    required this.price,
    required this.priceMin,
    required this.priceMax,
    required this.confidence,
    required this.reason,
    required this.tags,
    required this.provider,
    required this.model,
    required this.latencyMs,
    required this.marketSamples,
    this.marketMedian,
    required this.marketWeight,
  });

  final int analysisId;
  final int imageId;
  final String suggestedTitle;
  final String category;
  final String categoryLabel;
  final String condition;
  final String conditionLabel;
  final int price;
  final int priceMin;
  final int priceMax;
  final double confidence;
  final String reason;
  final List<String> tags;

  /// google(Gemma 4 클라우드) · ollama(Gemma 로컬) · fallback(AI 미연결 추정)
  final String provider;
  final String model;
  final int latencyMs;
  final int marketSamples;
  final int? marketMedian;
  final double marketWeight;

  bool get isAi => provider == 'google' || provider == 'ollama';

  String get providerLabel => switch (provider) {
        'google' => 'Gemma 4 (Google AI Studio)',
        'ollama' => 'Gemma (내 PC Ollama)',
        _ => '기준가 추정 (AI 미연결)',
      };

  factory AiAnalysis.fromJson(Map<String, dynamic> j) => AiAnalysis(
        analysisId: reqInt(j, 'analysisId'),
        imageId: reqInt(j, 'imageId'),
        suggestedTitle: optStr(j, 'suggestedTitle') ?? '',
        category: reqStr(j, 'category'),
        categoryLabel: optStr(j, 'categoryLabel') ?? '',
        condition: reqStr(j, 'condition'),
        conditionLabel: optStr(j, 'conditionLabel') ?? '',
        price: reqInt(j, 'price'),
        priceMin: reqInt(j, 'priceMin'),
        priceMax: reqInt(j, 'priceMax'),
        confidence: optDouble(j, 'confidence'),
        reason: optStr(j, 'reason') ?? '',
        tags: strList(j, 'tags'),
        provider: optStr(j, 'provider') ?? 'fallback',
        model: optStr(j, 'model') ?? '',
        latencyMs: optInt(j, 'latencyMs') ?? 0,
        marketSamples: optInt(j, 'marketSamples') ?? 0,
        marketMedian: optInt(j, 'marketMedian'),
        marketWeight: optDouble(j, 'marketWeight'),
      );
}
