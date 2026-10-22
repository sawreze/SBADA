-- 시연용 계정 admin (2026-10-22) — 사용자 요청: 시연하기 편하게 채팅 목록과 내 물건(가격대별로 다양하게)이 미리 들어 있는 계정
--  - 아이디 admin / 비밀번호 sbada1234 (다른 시연 계정과 같음, 로컬 시연 전용). 이름만 admin 이고 관리자 권한은 없는 보통 계정이다
--  - 동네 서울 마포구 망원동, 관심: 디지털기기 · 취미/게임/음반 · 패션의류, 키워드 닌텐도 · 아이패드
--  - 내 물건 14개: 7천 원 ~ 34만 원(1만 이하 / 1~5만 / 5~20만 / 20~50만 고르게). 사진 seed/a01~a14.jpg = 같은 이름 전국 상품(V10) 사진의 복사본
--  - 채팅 7개: 받은 교환 요청 2(준호 · 서연) · 보낸 교환 요청 1(도현) · 그냥 채팅 2(민지 · 역삼동 이웃) · 끝난 요청 2(거절 · 취소)
--    안 읽은 메시지가 있는 방과 다 읽은 방을 섞어 둔다. 알림 4개(안 읽음 3)
-- 이미 적용된 뒤에는 이 파일을 고치지 말고 V13__ 으로 새로 만든다.

insert into app_users (username, password_hash, nickname, region, onboarded, price_min, price_max, created_at) values
    ('admin', '$2a$10$jVhnHJVAGjJ8h.q4KJH7D.cJBmkgkBlH8U8RqrUR4gH75an0VrgA2', '스바다', '서울 마포구 망원동', true,
     5000, 500000, dateadd('DAY', -60, current_timestamp));

insert into user_preferences (user_id, category, weight, source, updated_at)
select u.id, p.category, 1.0, 'ONBOARDING', current_timestamp
from (values ('DIGITAL'), ('HOBBY'), ('FASHION')) as p(category), app_users u
where u.username = 'admin';

insert into user_keywords (user_id, keyword)
select u.id, k.keyword
from (values ('닌텐도'), ('아이패드')) as k(keyword), app_users u
where u.username = 'admin';

-- 1) admin 의 물건 14개 (img = 사진 파일 seed/aNN.jpg, photo = 원본 전국 상품 사진 qNNN)
create local temporary table admin_items (
    img varchar(3), photo varchar(4), title varchar(60), description varchar(1000), category varchar(20), item_condition varchar(20),
    price int, confidence double, reason varchar(600), hours_ago int, views int, requests int, wish1 varchar(20), wish2 varchar(20)
);

insert into admin_items values
    ('a01', 'q019', 'USB 메모리', '64GB USB 메모리예요. 포맷해 두었고 잘 작동해요. 작은 물건이랑 바꿔요.', 'DIGITAL', 'GOOD', 7000, 0.78, '''USB 메모리''의 국내 중고 거래가와 사용감 적음 상태를 반영한 가격이에요.', 5, 12, 0, 'BOOK', 'ETC'),
    ('a02', 'q023', '보조배터리', '10000mAh 보조배터리예요. 거의 안 썼고 케이블 같이 드려요.', 'DIGITAL', 'LIKE_NEW', 12000, 0.74, '''보조배터리''의 국내 중고 거래가와 거의 새것 상태를 반영한 가격이에요.', 30, 18, 0, 'ACCESSORY', null),
    ('a03', 'q010', '무선 마우스', '조용한 클릭 무선 마우스예요. 수신기 포함이에요.', 'DIGITAL', 'LIKE_NEW', 15000, 0.72, '''무선 마우스''의 국내 중고 거래가와 거의 새것 상태를 반영한 가격이에요.', 52, 9, 0, 'HOBBY', null),
    ('a04', 'q013', '웹캠', '화상 회의용 풀HD 웹캠이에요. 상태 양호해요.', 'DIGITAL', 'GOOD', 22000, 0.7, '''웹캠''의 국내 중고 거래가와 사용감 적음 상태를 반영한 가격이에요.', 76, 21, 1, 'FASHION', null),
    ('a05', 'q012', '모니터 암', '싱글 모니터 암이에요. 책상이 넓어져요. 나사 다 있어요.', 'DIGITAL', 'GOOD', 30000, 0.69, '''모니터 암''의 국내 중고 거래가와 사용감 적음 상태를 반영한 가격이에요.', 100, 14, 0, 'FURNITURE', null),
    ('a06', 'q017', '외장 SSD', '1TB 외장 SSD예요. 몇 번 안 썼어요.', 'DIGITAL', 'LIKE_NEW', 52000, 0.76, '''외장 SSD''의 국내 중고 거래가와 거의 새것 상태를 반영한 가격이에요.', 120, 33, 0, 'HOBBY', 'DIGITAL'),
    ('a07', 'q416', '오디오 인터페이스', '홈레코딩용 오디오 인터페이스예요. 케이블 포함, 거의 새것이에요.', 'DIGITAL', 'LIKE_NEW', 75000, 0.73, '''오디오 인터페이스''의 국내 중고 거래가와 거의 새것 상태를 반영한 가격이에요.', 150, 27, 1, 'HOBBY', null),
    ('a08', 'q078', '차량용 블랙박스', '2채널 블랙박스예요. 메모리카드 포함, 상태 양호해요.', 'DIGITAL', 'GOOD', 80000, 0.71, '''차량용 블랙박스''의 국내 중고 거래가와 사용감 적음 상태를 반영한 가격이에요.', 200, 16, 0, 'SPORTS', null),
    ('a09', 'q043', '빔프로젝터', '캠핑 갈 때 쓰던 미니 빔프로젝터예요. 리모컨 있어요.', 'DIGITAL', 'GOOD', 130000, 0.74, '''빔프로젝터''의 국내 중고 거래가와 사용감 적음 상태를 반영한 가격이에요.', 240, 41, 0, 'SPORTS', 'HOBBY'),
    ('a10', 'q361', '전자 피아노', '88건반 전자 피아노예요. 사용감은 있지만 소리 깨끗해요. 직거래만 돼요.', 'HOBBY', 'FAIR', 200000, 0.68, '''전자 피아노''의 국내 중고 거래가와 사용감 많음 상태를 반영한 가격이에요.', 300, 38, 0, 'FURNITURE', 'DIGITAL'),
    ('a11', 'q357', '일렉 기타', '연습용 일렉 기타예요. 줄 새로 갈았고 케이스 드려요.', 'HOBBY', 'GOOD', 220000, 0.72, '''일렉 기타''의 국내 중고 거래가와 사용감 적음 상태를 반영한 가격이에요.', 360, 57, 0, 'DIGITAL', null),
    ('a12', 'q048', 'VR 헤드셋', 'VR 헤드셋이에요. 컨트롤러 2개 포함, 몇 번 안 썼어요.', 'HOBBY', 'LIKE_NEW', 275000, 0.75, '''VR 헤드셋''의 국내 중고 거래가와 거의 새것 상태를 반영한 가격이에요.', 420, 62, 0, 'DIGITAL', 'HOBBY'),
    ('a13', 'q038', '데스크톱 본체', '사무 · 가벼운 게임용 데스크톱 본체예요. 윈도우 정품, 상태 양호해요.', 'DIGITAL', 'GOOD', 320000, 0.7, '''데스크톱 본체''의 국내 중고 거래가와 사용감 적음 상태를 반영한 가격이에요.', 480, 45, 0, 'DIGITAL', 'APPLIANCE'),
    ('a14', 'q045', '게임 콘솔', '게임 콘솔이에요. 패드 2개랑 게임 1개 같이 드려요. 거의 새것이에요.', 'HOBBY', 'LIKE_NEW', 340000, 0.77, '''게임 콘솔''의 국내 중고 거래가와 거의 새것 상태를 반영한 가격이에요.', 540, 88, 1, 'DIGITAL', 'HOBBY');

insert into items (owner_id, title, description, category, item_condition, status, ai_price, price_min, price_max,
                   ai_confidence, ai_reason, ai_provider, view_count, request_count, created_at, updated_at, version)
select u.id, s.title, s.description, s.category, s.item_condition, 'AVAILABLE', s.price,
       round(s.price * 0.82 / 500) * 500, round(s.price * 1.18 / 500) * 500,
       s.confidence, s.reason, 'seed', s.views, s.requests,
       dateadd('HOUR', -s.hours_ago, current_timestamp), dateadd('HOUR', -s.hours_ago, current_timestamp), 0
from admin_items s, app_users u
where u.username = 'admin'
order by s.hours_ago desc;

insert into item_wish_categories (item_id, category)
select i.id, w.category
from (select title, wish1 as category from admin_items union select title, wish2 from admin_items where wish2 is not null) w
join items i on i.title = w.title
join app_users u on u.id = i.owner_id and u.username = 'admin';

-- 사진: 같은 이름 전국 상품의 사진(seed-qNNN.jpg)을 복사한 seed-aNN.jpg (stored_name 은 겹치면 안 돼서 파일을 따로 둔다)
insert into item_images (uploader_id, item_id, stored_name, original_name, content_type, size, width, height, sort_order, created_at)
select i.owner_id, i.id, 'seed-' || s.img || '.jpg', s.img || '.jpg', src.content_type, src.size, src.width, src.height, 0, i.created_at
from admin_items s
join items i on i.title = s.title
join app_users u on u.id = i.owner_id and u.username = 'admin'
join item_images src on src.stored_name = 'seed-' || s.photo || '.jpg';

drop table admin_items;

-- 2) 채팅 · 교환. (주인, 물건 이름)으로 물건을 찾는 보조 뷰
create local temporary table owned (username varchar(20), title varchar(60), item_id bigint, owner_id bigint);
insert into owned
select u.username, i.title, i.id, u.id from items i join app_users u on u.id = i.owner_id
where u.username in ('admin', 'junho', 'seoyeon', 'dohyun', 'minji', 'nw003') and i.status = 'AVAILABLE';

-- 채팅방: item = 이야기하는 물건(주인 = owner), partner = 상대. minutes_ago = 마지막 메시지 시각
create local temporary table demo_rooms (
    k varchar(2), owner_name varchar(20), item_title varchar(60), partner_name varchar(20), minutes_ago int
);
insert into demo_rooms values
    ('A', 'admin', '게임 콘솔', 'junho', 8),           -- 받은 요청: 준호 '디지털 카메라' → admin '게임 콘솔'
    ('B', 'admin', '오디오 인터페이스', 'seoyeon', 35), -- 받은 요청: 서연 '커피머신' → admin '오디오 인터페이스'
    ('C', 'dohyun', '휴대용 게임기', 'admin', 52),     -- 보낸 요청: admin 'VR 헤드셋' → 도현 '휴대용 게임기'
    ('D', 'admin', '일렉 기타', 'minji', 95),          -- 그냥 채팅(민지가 물어봄)
    ('E', 'admin', '데스크톱 본체', 'nw003', 180),     -- 그냥 채팅(역삼동 이웃)
    ('F', 'seoyeon', '손목시계', 'admin', 1500),       -- 끝남: admin '웹캠' → 서연 '손목시계' 거절
    ('G', 'junho', '자전거', 'admin', 2900);           -- 끝남: admin '빔프로젝터' → 준호 '자전거' admin 이 취소

insert into chat_rooms (item_id, owner_id, partner_id, created_at, last_message_at, last_message_preview,
                        owner_last_read_id, partner_last_read_id)
select o.item_id, o.owner_id, p.id, dateadd('MINUTE', -r.minutes_ago - 30, current_timestamp),
       dateadd('MINUTE', -r.minutes_ago, current_timestamp), null, 0, 0
from demo_rooms r
join owned o on o.username = r.owner_name and o.title = r.item_title
join app_users p on p.username = r.partner_name;

-- 교환 요청(방 A · B · C · F · G)
create local temporary table demo_exchanges (
    k varchar(2), requester varchar(20), offered varchar(60), message varchar(200), status varchar(20), close_reason varchar(120)
);
insert into demo_exchanges values
    ('A', 'junho', '디지털 카메라', '카메라랑 콘솔 바꾸실래요? 렌즈 캡이랑 가방도 드려요!', 'REQUESTED', null),
    ('B', 'seoyeon', '커피머신', '홈카페용 커피머신이랑 바꾸고 싶어요. 캡슐도 몇 개 남았어요.', 'REQUESTED', null),
    ('C', 'admin', 'VR 헤드셋', 'VR 헤드셋이랑 게임기 바꾸실래요? 컨트롤러 2개 다 드려요.', 'REQUESTED', null),
    ('F', 'admin', '웹캠', '웹캠이랑 손목시계 바꾸실래요?', 'REJECTED', null),
    ('G', 'admin', '빔프로젝터', '빔프로젝터랑 자전거 바꿔요!', 'CANCELED', '요청한 사람이 취소');

insert into exchange_requests (requester_id, target_owner_id, offered_item_id, target_item_id, message, status,
                               close_reason, chat_room_id, created_at, responded_at)
select q.id, room.owner_id, off.item_id, room.item_id, e.message, e.status, e.close_reason, room.id,
       dateadd('MINUTE', -r.minutes_ago - 20, current_timestamp),
       case when e.status = 'REQUESTED' then null else dateadd('MINUTE', -r.minutes_ago, current_timestamp) end
from demo_exchanges e
join demo_rooms r on r.k = e.k
join app_users q on q.username = e.requester
join owned off on off.username = e.requester and off.title = e.offered
join owned tgt on tgt.username = r.owner_name and tgt.title = r.item_title
join chat_rooms room on room.item_id = tgt.item_id and room.partner_id =
     (select id from app_users where username = r.partner_name);

-- 메시지: who = 'O'(물건 주인) · 'P'(상대) · 'S'(시스템), seq = 방 안 순서, minutes_ago = 보낸 시각
create local temporary table demo_messages (
    k varchar(2), seq int, who varchar(1), msg_type varchar(10), content varchar(1000), minutes_ago int
);
insert into demo_messages values
    ('A', 1, 'P', 'PROPOSAL', '''디지털 카메라''(으)로 ''게임 콘솔''을(를) 바꾸고 싶어요.' || char(10) || '카메라랑 콘솔 바꾸실래요? 렌즈 캡이랑 가방도 드려요!', 20),
    ('A', 2, 'P', 'TEXT', '카메라 사진 더 필요하시면 보내 드릴게요 😊', 8),
    ('B', 1, 'P', 'PROPOSAL', '''커피머신''(으)로 ''오디오 인터페이스''을(를) 바꾸고 싶어요.' || char(10) || '홈카페용 커피머신이랑 바꾸고 싶어요. 캡슐도 몇 개 남았어요.', 35),
    ('C', 1, 'P', 'PROPOSAL', '''VR 헤드셋''(으)로 ''휴대용 게임기''을(를) 바꾸고 싶어요.' || char(10) || 'VR 헤드셋이랑 게임기 바꾸실래요? 컨트롤러 2개 다 드려요.', 70),
    ('C', 2, 'O', 'TEXT', '오 VR 좋네요! 저녁에 확인하고 답드릴게요.', 52),
    ('D', 1, 'P', 'TEXT', '안녕하세요! 일렉 기타 아직 있나요?', 130),
    ('D', 2, 'O', 'TEXT', '네 있어요! 줄도 지난주에 새로 갈았어요.', 120),
    ('D', 3, 'P', 'TEXT', '혹시 제 필름 카메라랑 바꾸실 생각 있으세요?', 95),
    ('E', 1, 'P', 'TEXT', '본체 사양 알 수 있을까요? 그래픽카드가 궁금해요.', 180),
    ('F', 1, 'P', 'PROPOSAL', '''웹캠''(으)로 ''손목시계''을(를) 바꾸고 싶어요.' || char(10) || '웹캠이랑 손목시계 바꾸실래요?', 1530),
    ('F', 2, 'S', 'SYSTEM', '교환 요청이 거절되었어요.', 1500),
    ('G', 1, 'P', 'PROPOSAL', '''빔프로젝터''(으)로 ''자전거''을(를) 바꾸고 싶어요.' || char(10) || '빔프로젝터랑 자전거 바꿔요!', 2950),
    ('G', 2, 'S', 'SYSTEM', '교환 요청이 취소되었어요.', 2900);

insert into chat_messages (room_id, sender_id, msg_type, content, exchange_id, created_at)
select room.id,
       case m.who when 'O' then room.owner_id when 'P' then room.partner_id else null end,
       m.msg_type, m.content,
       case when m.msg_type in ('PROPOSAL', 'SYSTEM') then (select x.id from exchange_requests x where x.chat_room_id = room.id) end,
       dateadd('MINUTE', -m.minutes_ago, current_timestamp)
from demo_messages m
join demo_rooms r on r.k = m.k
join owned tgt on tgt.username = r.owner_name and tgt.title = r.item_title
join chat_rooms room on room.item_id = tgt.item_id and room.partner_id = (select id from app_users where username = r.partner_name)
order by m.minutes_ago desc;

-- admin 이 들어 있는 방(A~G)
create local temporary table admin_rooms (room_id bigint);
insert into admin_rooms
select c.id from chat_rooms c join app_users u on u.username = 'admin' where c.owner_id = u.id or c.partner_id = u.id;

-- 마지막 메시지 미리보기(100자)
update chat_rooms c set last_message_preview = (
    select left(m.content, 100) from chat_messages m where m.room_id = c.id order by m.id desc limit 1)
where c.id in (select room_id from admin_rooms);

-- 읽음: 일단 모두 다 읽음 → admin 이 안 읽은 메시지가 보이게 몇 방만 되돌린다
--  A(준호 받은 요청): 메시지 2개 다 안 읽음 · D(민지) · E(역삼동 이웃): 마지막 메시지 1개 안 읽음 · C(도현 답장): 마지막 1개 안 읽음
--  B · F · G 는 다 읽음. 안 읽은 수 = 마지막으로 읽은 번호보다 큰 메시지 수
update chat_rooms c set
    owner_last_read_id = (select max(m.id) from chat_messages m where m.room_id = c.id),
    partner_last_read_id = (select max(m.id) from chat_messages m where m.room_id = c.id)
where c.id in (select room_id from admin_rooms);

update chat_rooms c set owner_last_read_id = (select min(m.id) - 1 from chat_messages m where m.room_id = c.id)
where c.id in (select room_id from admin_rooms)
  and c.item_id in (select item_id from owned where username = 'admin' and title = '게임 콘솔');
update chat_rooms c set owner_last_read_id = (select max(m.id) - 1 from chat_messages m where m.room_id = c.id)
where c.id in (select room_id from admin_rooms)
  and c.item_id in (select item_id from owned where username = 'admin' and title in ('일렉 기타', '데스크톱 본체'));
update chat_rooms c set partner_last_read_id = (select max(m.id) - 1 from chat_messages m where m.room_id = c.id)
where c.id in (select room_id from admin_rooms)
  and c.item_id in (select item_id from owned where username = 'dohyun' and title = '휴대용 게임기');

-- 상대 물건의 교환 요청 수(admin 이 보낸 요청 3건: 도현 · 서연 · 준호 물건)
update items i set request_count = request_count + 1
where i.id in (select x.target_item_id from exchange_requests x
               where x.requester_id = (select id from app_users where username = 'admin'));
drop table admin_rooms;

-- 3) 알림: 받은 교환 요청 2(안 읽음) · 거절 1(읽음) · 비슷한 가격대 새 물건 1(안 읽음)
insert into notifications (user_id, noti_type, title, body, ref_type, ref_id, is_read, created_at)
select x.target_owner_id, 'EXCHANGE_REQUESTED', '교환 요청이 왔어요',
       q.nickname || '님이 ''' || off.title || '''(으)로 ''' || tgt.title || '''을(를) 바꾸고 싶어해요. 수락하면 교환이 완료돼요.',
       'EXCHANGE', x.id, false, x.created_at
from exchange_requests x
join app_users q on q.id = x.requester_id
join items off on off.id = x.offered_item_id
join items tgt on tgt.id = x.target_item_id
where x.status = 'REQUESTED' and x.target_owner_id = (select id from app_users where username = 'admin');

insert into notifications (user_id, noti_type, title, body, ref_type, ref_id, is_read, created_at)
select x.requester_id, 'EXCHANGE_REJECTED', '교환 요청이 거절되었어요',
       '''' || tgt.title || ''' 교환 요청이 거절되었어요. 다른 물건을 찾아볼까요?', 'EXCHANGE', x.id, true, x.responded_at
from exchange_requests x join items tgt on tgt.id = x.target_item_id
where x.status = 'REJECTED' and x.requester_id = (select id from app_users where username = 'admin');

insert into notifications (user_id, noti_type, title, body, ref_type, ref_id, is_read, created_at)
select u.id, 'MATCH_SUGGESTED', '비슷한 가격대 새 물건', '''' || o.title || '''이(가) 올라왔어요. 내 물건과 바꿔 볼까요?',
       'ITEM', o.item_id, false, dateadd('MINUTE', -15, current_timestamp)
from app_users u, owned o
where u.username = 'admin' and o.username = 'minji' and o.title = '필름 카메라';

drop table demo_messages;
drop table demo_exchanges;
drop table demo_rooms;
drop table owned;
