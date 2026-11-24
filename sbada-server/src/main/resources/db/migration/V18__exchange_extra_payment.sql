-- 교환 추가 금액 + 안전결제 상태 (2026-11-25 금융 백엔드)
--  extra_amount  : 추가 금액(원). 0 이면 추가 금액 없음
--  extra_payer   : 누가 내는지 REQUESTER(요청 보낸 사람) · OWNER(물건 주인). 추가 금액이 없으면 null
--  payment_status: NONE(아직 안 냄) · PAID_HELD(결제되어 보관 중) · RELEASED(교환 완료, 상대 지갑으로 정산)
--                  · REFUND_PENDING(교환이 깨져 환불 요청 중) · REFUNDED(환불 완료)
--  requester_received_at · owner_received_at: 양쪽 수령 확인('물건 받았어요'). 둘 다 확인해야 정산
--  돈 자체(원장 · 보관 · 정산)는 결제 서비스 DB 에 있다. 여기에는 교환 화면에 보여 줄 '상태'만 둔다.
alter table exchange_requests add column extra_amount bigint default 0 not null;
alter table exchange_requests add column extra_payer varchar(20);
alter table exchange_requests add column payment_status varchar(20) default 'NONE' not null;
alter table exchange_requests add column requester_received_at timestamp(6) with time zone;
alter table exchange_requests add column owner_received_at timestamp(6) with time zone;
