-- 스바다 AI 상품 요약 (2026-10-22)
-- 물건 상세의 '거래 희망 장소' 지도 위에 Gemma 가 사진 · 글을 보고 쓴 2~3문장 요약을 보여 준다.
-- 처음 볼 때 한 번 만들어 여기에 저장하고(다음부터는 바로), 주인이 제목 · 설명을 고치면 지운다(다음에 다시 만듦).
alter table items add column ai_summary varchar(500);
alter table items add column ai_summary_provider varchar(40);
