package com.sbada.ai;

import org.springframework.data.jpa.repository.JpaRepository;

/** ai_analyses 테이블 접근(분석 결과 저장, 물건 등록 때 analysisId 로 다시 읽기). 기본 메서드만 쓴다. */
public interface AiAnalysisRepository extends JpaRepository<AiAnalysis, Long> {
}
