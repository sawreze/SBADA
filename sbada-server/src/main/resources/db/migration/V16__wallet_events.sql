-- MSA 1단계: 결제 서비스 분리 (2026-11-20)
-- ① 알림 종류에 WALLET(결제 서비스가 보낸 출금 · 결제 · 입금 알림)을 더한다. 기존 값은 그대로.
-- ② 결제 서비스가 보낸 이벤트를 한 번만 처리하기 위한 기록(같은 이벤트가 두 번 와도 알림은 한 번).
--    지갑 · 계좌 · 거래 데이터는 이 DB 가 아니라 결제 서비스 DB(sbada-payment/data/payment)에 있다.
alter table notifications alter column noti_type
    enum ('CHAT_MESSAGE','EXCHANGE_ACCEPTED','EXCHANGE_CANCELED','EXCHANGE_REJECTED','EXCHANGE_REQUESTED','MATCH_SUGGESTED','ITEM_HELD','ITEM_LIKED','WALLET') not null;

create table processed_events (
    event_id varchar(64) not null,
    event_type varchar(40) not null,
    processed_at timestamp(6) with time zone not null,
    primary key (event_id)
);
