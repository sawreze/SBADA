-- 스바다 알림 설정 · 찜 알림 (2026-10-22)
-- ① 알림 종류에 ITEM_LIKED(누가 내 물건을 찜함)를 더한다. 기존 값은 그대로.
-- ② 사람마다 알림 켜기/끄기 3가지(계정정보 > 알림 설정): 찜 · 채팅 · 교환. 기본은 모두 켜짐.
--    끄면 그 종류는 푸시(FCM/APNs)와 앱 배너를 보내지 않는다(알림함 기록은 남는다).
alter table notifications alter column noti_type
    enum ('CHAT_MESSAGE','EXCHANGE_ACCEPTED','EXCHANGE_CANCELED','EXCHANGE_REJECTED','EXCHANGE_REQUESTED','MATCH_SUGGESTED','ITEM_HELD','ITEM_LIKED') not null;
alter table app_users add column notify_like boolean default true not null;
alter table app_users add column notify_chat boolean default true not null;
alter table app_users add column notify_exchange boolean default true not null;
