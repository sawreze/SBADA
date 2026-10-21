-- 스바다 '정든 물건에게 보내는 짧은 메시지' (2026-10-21)
-- 물건을 올릴 때 설명 아래 칸에 적는 한 줄(선택, 60자 이하). 목록에서 가격 아래에 "메시지" 로 굵게 보인다.
-- 기존 물건은 null(빈칸) 그대로.
alter table items add column farewell_message varchar(60);
