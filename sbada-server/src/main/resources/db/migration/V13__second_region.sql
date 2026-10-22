-- 스바다 두 번째 동네 (2026-10-22)
-- 내 동네는 최대 2개: region = 지금 보고 있는 동네(홈 · 지도 · 물건 위치 기준), second_region = 하나 더 추가한 동네(없으면 null).
-- 홈 왼쪽 위 동네 이름을 눌러 두 동네를 서로 바꾼다(POST /api/me/region/swap). 기존 행 · 데이터는 바뀌지 않는다.
alter table app_users add column second_region varchar(20);
