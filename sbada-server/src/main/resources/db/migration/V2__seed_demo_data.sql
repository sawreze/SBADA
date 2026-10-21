-- 쓰바다 시연·테스트용 초기 데이터 (예전 SeedDataRunner 가 코드로 넣던 것과 같은 내용).
-- 계정 4명 · 물건 16개(사진 포함) · 교환 요청 1건(도현 → 민지, 채팅방 · 제안 메시지 · 알림 포함)
-- 비밀번호는 모두 sbada1234 (로컬 시연 전용 공개 표본. 실제 비밀번호를 쓰지 않는다) → 아래는 그 BCrypt 해시
-- 사진 파일은 SQL 로 넣을 수 없어서, 서버가 켜질 때 SeedImageInstaller 가 classpath:seed/sNN.jpg 를
-- 업로드 폴더에 seed-sNN.jpg 로 복사한다. 시간은 '지금' 기준으로 몇 시간·며칠 전처럼 보이도록 DATEADD 를 쓴다.

-- 1) 사용자 · 온보딩(관심 카테고리 1.0, 키워드, 가격대)
insert into app_users (username, password_hash, nickname, region, onboarded, price_min, price_max, created_at) values
    ('minji',   '$2a$10$jVhnHJVAGjJ8h.q4KJH7D.cJBmkgkBlH8U8RqrUR4gH75an0VrgA2', '민지', '마포구 망원동',   true, 30000, 300000, current_timestamp),
    ('junho',   '$2a$10$jVhnHJVAGjJ8h.q4KJH7D.cJBmkgkBlH8U8RqrUR4gH75an0VrgA2', '준호', '마포구 합정동',   true, 20000, 150000, current_timestamp),
    ('seoyeon', '$2a$10$jVhnHJVAGjJ8h.q4KJH7D.cJBmkgkBlH8U8RqrUR4gH75an0VrgA2', '서연', '서대문구 연희동', true, 10000, 300000, current_timestamp),
    ('dohyun',  '$2a$10$jVhnHJVAGjJ8h.q4KJH7D.cJBmkgkBlH8U8RqrUR4gH75an0VrgA2', '도현', '마포구 서교동',   true, 50000, 500000, current_timestamp);

insert into user_preferences (user_id, category, weight, source, updated_at)
select u.id, p.category, 1.0, 'ONBOARDING', current_timestamp
from (values
        ('minji', 'DIGITAL'), ('minji', 'HOBBY'),
        ('junho', 'SPORTS'), ('junho', 'BOOK'), ('junho', 'DIGITAL'),
        ('seoyeon', 'FASHION'), ('seoyeon', 'BEAUTY'), ('seoyeon', 'ACCESSORY'),
        ('dohyun', 'DIGITAL'), ('dohyun', 'APPLIANCE')
     ) as p(username, category)
join app_users u on u.username = p.username;

insert into user_keywords (user_id, keyword)
select u.id, k.keyword
from (values
        ('minji', '닌텐도'), ('minji', '에어팟'),
        ('junho', '캠핑'), ('junho', '자전거'),
        ('seoyeon', '향수'), ('seoyeon', '가방'),
        ('dohyun', '아이패드'), ('dohyun', '모니터')
     ) as k(username, keyword)
join app_users u on u.username = k.username;

-- 2) 물건(가격은 시연용 표본 값, ai_provider = 'seed'). hours_ago = 몇 시간 전에 올린 것처럼 보일지
create local temporary table seed_items (
    img varchar(3), username varchar(20), title varchar(60), description varchar(1000),
    category varchar(20), item_condition varchar(20), price int, price_min int, price_max int,
    confidence double, reason varchar(600), hours_ago int, views int, requests int
);

insert into seed_items values
    ('s01', 'minji', '닌텐도 스위치 OLED 화이트', '작년에 산 OLED 모델이에요. 액정 필름 붙여서 써서 흠집 없어요. 독·조이콘 모두 있어요.',
        'HOBBY', 'LIKE_NEW', 280000, 240000, 320000, 0.86, 'OLED 모델의 중고 시세(25~32만원)와 거의 새것 상태를 반영했어요.', 72, 41, 0),
    ('s02', 'minji', '에어팟 프로 2세대', '배터리 성능 좋아요. 이어팁 새 걸로 같이 드려요.',
        'DIGITAL', 'GOOD', 150000, 125000, 175000, 0.82, '2세대 프로의 중고 거래가와 사용감 적은 상태를 반영했어요.', 24, 27, 1),
    ('s03', 'minji', '원목 무드등 스탠드', '따뜻한 전구색 무드등이에요. 이사하면서 정리해요.',
        'FURNITURE', 'GOOD', 25000, 18000, 32000, 0.70, '원목 소품 조명의 일반적인 중고가예요.', 216, 8, 0),
    ('s04', 'junho', '캠핑 의자 2개 세트', '두 번 쓴 캠핑 의자예요. 수납 가방 포함.',
        'SPORTS', 'LIKE_NEW', 45000, 36000, 55000, 0.78, '접이식 캠핑 의자 2개의 중고 시세를 반영했어요.', 10, 15, 0),
    ('s05', 'junho', '로드 자전거 헬멧', '사이즈 M, 사용감 조금 있어요.',
        'SPORTS', 'GOOD', 30000, 22000, 38000, 0.72, '헬멧은 안전용품이라 중고가가 낮게 형성돼요.', 144, 6, 0),
    ('s06', 'junho', '해리포터 전권 세트', '1~7권 전권, 책장에 꽂아만 뒀어요.',
        'BOOK', 'GOOD', 60000, 45000, 75000, 0.80, '전집 세트는 낱권보다 높게 거래돼요.', 96, 19, 0),
    ('s07', 'junho', '갤럭시 버즈2 프로', '케이스에 잔기스 조금, 음질 문제없어요.',
        'DIGITAL', 'GOOD', 85000, 70000, 100000, 0.80, '버즈2 프로의 최근 중고 거래가를 반영했어요.', 48, 22, 0),
    ('s08', 'seoyeon', '조말론 향수 100ml', '선물 받고 몇 번만 뿌렸어요. 잔량 90% 이상.',
        'BEAUTY', 'LIKE_NEW', 110000, 90000, 130000, 0.77, '정품 100ml 신품가의 약 55%로 추정했어요.', 24, 33, 0),
    ('s09', 'seoyeon', '가죽 크로스백 브라운', '소가죽, 모서리 사용감 조금 있어요.',
        'ACCESSORY', 'GOOD', 70000, 55000, 85000, 0.70, '브랜드 없는 소가죽 가방의 중고가예요.', 288, 9, 0),
    ('s10', 'seoyeon', '울 니트 가디건', '한 시즌 입었어요. 보풀 제거해 둠.',
        'FASHION', 'GOOD', 20000, 14000, 26000, 0.68, '니트류 중고 시세를 반영했어요.', 120, 4, 0),
    ('s11', 'seoyeon', '다이슨 에어랩 (구형)', '구형 모델, 배럴 3종 포함. 작동 잘 돼요.',
        'APPLIANCE', 'GOOD', 290000, 250000, 340000, 0.75, '구형 에어랩의 중고 시세와 구성품을 반영했어요.', 30, 52, 0),
    ('s12', 'dohyun', '아이패드 9세대 64GB', '학교 필기용으로 썼어요. 케이스·펜슬 호환 필름 포함.',
        'DIGITAL', 'GOOD', 260000, 230000, 300000, 0.84, '9세대 64GB Wi-Fi 모델의 중고가를 반영했어요.', 48, 37, 0),
    ('s13', 'dohyun', '27인치 QHD 모니터', '데드픽셀 없어요. 받침대 포함.',
        'DIGITAL', 'GOOD', 120000, 95000, 145000, 0.76, '27인치 QHD 보급형 모니터의 중고 시세예요.', 192, 14, 0),
    ('s14', 'dohyun', '레고 테크닉 스포츠카', '미개봉 새 상품이에요.',
        'HOBBY', 'NEW', 140000, 120000, 160000, 0.83, '미개봉 테크닉 세트는 정가의 80~90%에 거래돼요.', 5, 18, 0),
    ('s15', 'dohyun', '몬스테라 화분 (중형)', '잎 7장, 분갈이 한 달 전에 했어요.',
        'PLANT', 'GOOD', 15000, 10000, 20000, 0.65, '중형 몬스테라의 동네 거래가예요.', 72, 7, 0),
    ('s16', 'dohyun', 'PS5 듀얼센스 컨트롤러', '화이트, 스틱 쏠림 없어요.',
        'HOBBY', 'LIKE_NEW', 55000, 45000, 65000, 0.80, '듀얼센스 중고 시세를 반영했어요.', 24, 12, 0);

insert into items (owner_id, title, description, category, item_condition, status, ai_price, price_min, price_max,
                   ai_confidence, ai_reason, ai_provider, view_count, request_count, created_at, updated_at, version)
select u.id, s.title, s.description, s.category, s.item_condition, 'AVAILABLE', s.price, s.price_min, s.price_max,
       s.confidence, s.reason, 'seed', s.views, s.requests,
       dateadd('HOUR', -s.hours_ago, current_timestamp), dateadd('HOUR', -s.hours_ago, current_timestamp), 0
from seed_items s
join app_users u on u.username = s.username
order by s.img;

-- 대표 사진(파일 크기는 classpath:seed/sNN.jpg 와 같다. 모두 800×800 JPEG)
insert into item_images (uploader_id, item_id, stored_name, original_name, content_type, size, width, height, sort_order, created_at)
select i.owner_id, i.id, 'seed-' || s.img || '.jpg', s.img || '.jpg', 'image/jpeg', f.size, 800, 800, 0, i.created_at
from seed_items s
join items i on i.title = s.title
join (values
        ('s01', 39027), ('s02', 39797), ('s03', 39482), ('s04', 41132), ('s05', 42814), ('s06', 44733),
        ('s07', 37181), ('s08', 36417), ('s09', 36764), ('s10', 46358), ('s11', 42134), ('s12', 40665),
        ('s13', 39903), ('s14', 38782), ('s15', 44378), ('s16', 37589)
     ) as f(img, size) on f.img = s.img
order by s.img;

-- 이 물건과 바꾸고 싶은 카테고리
insert into item_wish_categories (item_id, category)
select i.id, w.category
from (values
        ('s01', 'DIGITAL'),
        ('s02', 'HOBBY'), ('s02', 'SPORTS'),
        ('s03', 'PLANT'), ('s03', 'BOOK'),
        ('s04', 'DIGITAL'),
        ('s05', 'ACCESSORY'),
        ('s06', 'HOBBY'),
        ('s07', 'SPORTS'), ('s07', 'HOBBY'),
        ('s08', 'FASHION'), ('s08', 'DIGITAL'),
        ('s09', 'BEAUTY'),
        ('s10', 'BEAUTY'), ('s10', 'ACCESSORY'),
        ('s11', 'HOBBY'), ('s11', 'DIGITAL'),
        ('s12', 'HOBBY'),
        ('s13', 'FURNITURE'), ('s13', 'HOBBY'),
        ('s14', 'DIGITAL'),
        ('s15', 'FURNITURE'),
        ('s16', 'SPORTS')
     ) as w(img, category)
join seed_items s on s.img = w.img
join items i on i.title = s.title;

drop table seed_items;

-- 3) 시연 시작 시점에 민지에게 '받은 교환 요청'이 하나 있게 한다
--    도현이 '레고 테크닉 스포츠카'를 내놓고 민지의 '에어팟 프로 2세대'를 요청(에어팟 request_count = 1 은 위에서 넣음)
insert into chat_rooms (item_id, owner_id, partner_id, created_at, last_message_at, last_message_preview,
                        owner_last_read_id, partner_last_read_id)
select i.id, i.owner_id, d.id, current_timestamp, current_timestamp,
       '''레고 테크닉 스포츠카''(으)로 ''에어팟 프로 2세대''을(를) 바꾸고 싶어요.' || char(10) || '에어팟이랑 레고 바꾸실래요? 미개봉이에요!',
       0, 0
from items i, app_users d
where i.title = '에어팟 프로 2세대' and d.username = 'dohyun';

insert into exchange_requests (requester_id, target_owner_id, offered_item_id, target_item_id, message, status,
                               chat_room_id, created_at)
select lego.owner_id, air.owner_id, lego.id, air.id, '에어팟이랑 레고 바꾸실래요? 미개봉이에요!', 'REQUESTED',
       r.id, current_timestamp
from items lego, items air, chat_rooms r
where lego.title = '레고 테크닉 스포츠카' and air.title = '에어팟 프로 2세대'
  and r.item_id = air.id and r.partner_id = lego.owner_id;

insert into chat_messages (room_id, sender_id, msg_type, content, exchange_id, created_at)
select e.chat_room_id, e.requester_id, 'PROPOSAL',
       '''레고 테크닉 스포츠카''(으)로 ''에어팟 프로 2세대''을(를) 바꾸고 싶어요.' || char(10) || '에어팟이랑 레고 바꾸실래요? 미개봉이에요!',
       e.id, current_timestamp
from exchange_requests e;

-- 보낸 사람(도현 = partner)은 자기 메시지를 읽은 것으로 둔다(ChatService.post 와 같음)
update chat_rooms r set partner_last_read_id = (select max(m.id) from chat_messages m where m.room_id = r.id);

insert into notifications (user_id, noti_type, title, body, ref_type, ref_id, is_read, created_at)
select e.target_owner_id, 'EXCHANGE_REQUESTED', '교환 요청이 왔어요',
       '도현님이 ''레고 테크닉 스포츠카''(으)로 ''에어팟 프로 2세대''을(를) 바꾸고 싶어해요. 수락하면 교환이 완료돼요.',
       'EXCHANGE', e.id, false, current_timestamp
from exchange_requests e;
