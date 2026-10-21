-- 스바다 콘텐츠 검사(보류) (2026-10-21)
-- 물건을 올리면 제목 · 설명의 욕설과 사진의 선정성 · 혐오 · 폭력을 검사한다(금칙어 목록 + Gemma).
-- 걸리면 물건을 HELD(보류)로 바꾸고 올린 사람에게 ITEM_HELD 알림을 보낸다.
-- 기존 값(AVAILABLE · HIDDEN · TRADED, 기존 알림 종류)은 그대로이고 새 값만 추가한다.
alter table items alter column status enum ('AVAILABLE','HIDDEN','TRADED','HELD') not null;
alter table items add column moderation_reason varchar(200);
alter table notifications alter column noti_type
    enum ('CHAT_MESSAGE','EXCHANGE_ACCEPTED','EXCHANGE_CANCELED','EXCHANGE_REJECTED','EXCHANGE_REQUESTED','MATCH_SUGGESTED','ITEM_HELD') not null;
