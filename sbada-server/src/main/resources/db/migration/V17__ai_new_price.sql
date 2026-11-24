-- AI 가격 분석: 새 제품 정가(원)를 함께 저장한다 (2026-11-24)
-- 화면의 '가격 분석 결과 · 정가: xxx원 (AI 추정)'에 쓴다. 예전 분석은 값이 없어(null) 정가 줄이 숨겨진다.
alter table ai_analyses add column new_price integer;
