-- 스바다 시연 상품 다시 만들기 (2026-10-21, 사용자가 보낸 상품 50개 이름 · 사진)
-- ① 지금 있는 물건과, 물건에 걸린 교환 요청 · 채팅방 · 채팅 메시지 · 물건/교환 알림 · 바꾸고 싶은 카테고리 · 물건 사진 행을 지운다
--    (계정 4명 · 취향 · 키워드 · 기기 토큰 · 물건에 안 붙은 업로드 사진은 그대로)
-- ② 상품 50개(주인 4명에게 나눔, 가격은 시연용 표본 값, ai_provider = 'seed') + 대표 사진 행을 넣는다
--    사진 파일은 SeedImageInstaller 가 classpath:seed/pNN.jpg → 업로드 폴더 seed-pNN.jpg 로 복사한다
-- ③ 시연 시작용 교환 요청 1건: 도현의 '모니터'(130,000원) → 민지의 '무선 이어폰'(120,000원) + 채팅방 · 제안 메시지 · 알림
-- 이미 적용된 뒤에는 이 파일을 고치지 말고 V5__ 로 새로 만든다.

-- 1) 지우기(외래 키 순서: 메시지 → 방 → 교환 → 알림 → 물건에 딸린 것 → 물건)
delete from chat_messages;
delete from chat_rooms;
delete from exchange_requests;
delete from notifications where ref_type in ('ITEM', 'EXCHANGE');
delete from item_wish_categories;
delete from ai_analyses where image_id in (select id from item_images where item_id is not null);
delete from item_images where item_id is not null;
delete from items;

-- 2) 상품 50개. hours_ago = 몇 시간 전에 올린 것처럼 보일지
create local temporary table seed_items (
    img varchar(3), username varchar(20), title varchar(60), description varchar(1000),
    category varchar(20), item_condition varchar(20), price int, price_min int, price_max int,
    confidence double, reason varchar(600), hours_ago int, views int, requests int, file_size int
);

insert into seed_items values
    ('p01', 'dohyun', '스마트폰', '중고 휴대폰이에요. 액정 깨짐 없고 배터리 효율 88%예요. 케이스 같이 드려요.',
        'DIGITAL', 'GOOD', 320000, 260000, 380000, 0.80, '''스마트폰''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 30, 44, 0, 20960),
    ('p02', 'dohyun', '태블릿', '필기용으로 쓰던 태블릿이에요. 화면 보호 필름 붙어 있어요.',
        'DIGITAL', 'GOOD', 260000, 220000, 300000, 0.82, '''태블릿''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 48, 37, 0, 15171),
    ('p03', 'dohyun', '노트북', '업무용으로 쓰던 14인치 노트북이에요. 충전기 포함, 윈도우 정품이에요.',
        'DIGITAL', 'GOOD', 480000, 400000, 560000, 0.78, '''노트북''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 96, 29, 0, 29699),
    ('p04', 'junho', '기계식 키보드', 'RGB 기계식 키보드, 갈축이에요. 키캡 빠진 것 없어요.',
        'DIGITAL', 'LIKE_NEW', 70000, 55000, 85000, 0.80, '''기계식 키보드''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 20, 21, 0, 23642),
    ('p05', 'minji', '마우스', '무선 마우스예요. 수신기 포함, 건전지 새로 넣었어요.',
        'DIGITAL', 'GOOD', 15000, 10000, 20000, 0.72, '''마우스''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 120, 6, 0, 20067),
    ('p06', 'minji', '헤드폰', '노이즈 캔슬링 블루투스 헤드폰이에요. 이어패드 깨끗해요.',
        'DIGITAL', 'LIKE_NEW', 85000, 70000, 100000, 0.79, '''헤드폰''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 26, 33, 0, 20360),
    ('p07', 'minji', '무선 이어폰', '무선 이어폰이에요. 이어팁 새 걸로 같이 드려요.',
        'DIGITAL', 'GOOD', 120000, 100000, 140000, 0.82, '''무선 이어폰''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 24, 27, 1, 18011),
    ('p08', 'junho', '디지털 카메라', '콤팩트 디지털 카메라, 여행 때 몇 번 들고 다녔어요. 배터리 2개 드려요.',
        'DIGITAL', 'GOOD', 230000, 190000, 270000, 0.77, '''디지털 카메라''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 72, 31, 0, 31243),
    ('p09', 'dohyun', '모니터', '27인치 QHD 모니터예요. 데드픽셀 없고 받침대 포함이에요.',
        'DIGITAL', 'GOOD', 130000, 105000, 155000, 0.78, '''모니터''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 8, 19, 0, 29250),
    ('p10', 'junho', '블루투스 스피커', '캠핑 갈 때 쓰던 블루투스 스피커예요. 생활 방수 돼요.',
        'DIGITAL', 'GOOD', 55000, 45000, 65000, 0.76, '''블루투스 스피커''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 50, 14, 0, 29174),
    ('p11', 'seoyeon', '청바지', '스트레이트 핏 청바지, 허리 28이에요.',
        'FASHION', 'GOOD', 25000, 18000, 32000, 0.70, '''청바지''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 100, 9, 0, 32790),
    ('p12', 'seoyeon', '후드티', '회색 오버핏 후드티, 한 시즌 입었어요.',
        'FASHION', 'GOOD', 22000, 16000, 28000, 0.70, '''후드티''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 60, 11, 0, 16269),
    ('p13', 'seoyeon', '운동화', '흰색 스니커즈 240mm, 두 번 신었어요.',
        'FASHION', 'LIKE_NEW', 45000, 35000, 55000, 0.74, '''운동화''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 15, 23, 0, 10876),
    ('p14', 'junho', '백팩', '노트북 수납되는 백팩이에요. 지퍼 고장 없어요.',
        'ACCESSORY', 'GOOD', 40000, 30000, 50000, 0.73, '''백팩''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 130, 12, 0, 19749),
    ('p15', 'seoyeon', '에코백', '캔버스 에코백, 거의 안 들었어요.',
        'ACCESSORY', 'LIKE_NEW', 8000, 5000, 11000, 0.66, '''에코백''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 160, 4, 0, 15700),
    ('p16', 'seoyeon', '모자', '네이비 볼캡이에요. 사이즈 조절 돼요.',
        'ACCESSORY', 'GOOD', 15000, 10000, 20000, 0.68, '''모자''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 90, 5, 0, 12460),
    ('p17', 'seoyeon', '손목시계', '메탈 밴드 손목시계, 배터리 교체했어요.',
        'ACCESSORY', 'GOOD', 90000, 70000, 110000, 0.75, '''손목시계''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 40, 26, 0, 26152),
    ('p18', 'minji', '선글라스', '검은 테 선글라스, 케이스 있어요.',
        'ACCESSORY', 'LIKE_NEW', 35000, 25000, 45000, 0.72, '''선글라스''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 200, 8, 0, 20349),
    ('p19', 'seoyeon', '패딩', '검정 숏패딩 M 사이즈, 드라이클리닝 했어요.',
        'FASHION', 'GOOD', 80000, 60000, 100000, 0.74, '''패딩''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 30, 38, 0, 17775),
    ('p20', 'minji', '지갑', '소가죽 반지갑, 모서리 사용감 조금 있어요.',
        'ACCESSORY', 'GOOD', 30000, 20000, 40000, 0.70, '''지갑''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 170, 7, 0, 21763),
    ('p21', 'dohyun', '게임 컨트롤러', '유선 게임 컨트롤러, 스틱 쏠림 없어요.',
        'HOBBY', 'LIKE_NEW', 45000, 38000, 55000, 0.80, '''게임 컨트롤러''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 22, 15, 0, 21894),
    ('p22', 'dohyun', '휴대용 게임기', '휴대용 게이밍 PC예요. 박스와 충전기 모두 있어요.',
        'HOBBY', 'LIKE_NEW', 450000, 380000, 520000, 0.81, '''휴대용 게임기''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 12, 58, 0, 18072),
    ('p23', 'junho', '보드게임', '구성품 다 있는 보드게임, 두 번 했어요.',
        'HOBBY', 'LIKE_NEW', 28000, 20000, 35000, 0.72, '''보드게임''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 80, 10, 0, 39403),
    ('p24', 'minji', '블록 장난감', '대용량 블록 장난감, 상자째 드려요.',
        'KIDS', 'GOOD', 35000, 25000, 45000, 0.70, '''블록 장난감''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 140, 6, 0, 36190),
    ('p25', 'minji', '피규어', '미개봉 애니메이션 피규어예요.',
        'HOBBY', 'NEW', 65000, 50000, 80000, 0.76, '''피규어''의 최근 중고 거래가와 새 상품 상태를 반영한 시연용 표본 가격이에요.', 36, 24, 0, 38517),
    ('p26', 'junho', '기타', '통기타예요. 줄 새로 갈았고 소프트 케이스 포함이에요.',
        'HOBBY', 'GOOD', 150000, 120000, 180000, 0.75, '''기타''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 60, 17, 0, 12782),
    ('p27', 'junho', '스케이트보드', '컴플리트 스케이트보드, 베어링 잘 굴러요.',
        'SPORTS', 'GOOD', 60000, 45000, 75000, 0.72, '''스케이트보드''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 110, 9, 0, 12205),
    ('p28', 'junho', '농구공', '7호 농구공, 공기 잘 차 있어요.',
        'SPORTS', 'GOOD', 18000, 12000, 24000, 0.68, '''농구공''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 150, 4, 0, 27214),
    ('p29', 'seoyeon', '요가 매트', '10mm 요가 매트, 몇 번 안 썼어요.',
        'SPORTS', 'LIKE_NEW', 15000, 10000, 20000, 0.70, '''요가 매트''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 70, 8, 0, 19714),
    ('p30', 'minji', '필름 카메라', '수동 필름 카메라예요. 셔터 정상 작동해요.',
        'HOBBY', 'GOOD', 140000, 110000, 170000, 0.74, '''필름 카메라''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 55, 41, 0, 23514),
    ('p31', 'minji', '스탠드 조명', '원목 받침 스탠드 조명, 전구색 LED예요.',
        'FURNITURE', 'GOOD', 25000, 18000, 32000, 0.70, '''스탠드 조명''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 216, 8, 0, 8706),
    ('p32', 'seoyeon', '커피머신', '드립 커피머신, 깨끗이 세척해서 드려요.',
        'APPLIANCE', 'GOOD', 40000, 30000, 50000, 0.72, '''커피머신''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 45, 13, 0, 24775),
    ('p33', 'dohyun', '전기포트', '스테인리스 전기포트 1.7L, 물때 없어요.',
        'APPLIANCE', 'GOOD', 20000, 14000, 26000, 0.70, '''전기포트''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 180, 5, 0, 25345),
    ('p34', 'seoyeon', '머그컵 세트', '머그컵 4개 세트, 새 상품이에요.',
        'ETC', 'NEW', 12000, 8000, 16000, 0.66, '''머그컵 세트''의 최근 중고 거래가와 새 상품 상태를 반영한 시연용 표본 가격이에요.', 190, 3, 0, 20304),
    ('p35', 'junho', '텀블러', '보온 텀블러 600ml, 한 번 썼어요.',
        'ETC', 'LIKE_NEW', 15000, 10000, 20000, 0.68, '''텀블러''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 66, 7, 0, 15108),
    ('p36', 'dohyun', '공기청정기', '원룸용 공기청정기, 필터 교체한 지 한 달 됐어요.',
        'APPLIANCE', 'GOOD', 85000, 70000, 100000, 0.77, '''공기청정기''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 18, 22, 0, 16696),
    ('p37', 'junho', '선풍기', '탁상 선풍기, 3단 바람 잘 나와요.',
        'APPLIANCE', 'GOOD', 30000, 22000, 38000, 0.70, '''선풍기''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 210, 6, 0, 19799),
    ('p38', 'dohyun', '청소기', '유선 진공청소기, 흡입력 좋아요.',
        'APPLIANCE', 'GOOD', 55000, 45000, 65000, 0.74, '''청소기''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 84, 11, 0, 18525),
    ('p39', 'minji', '수납함', '원목 수납함, 책상 정리용으로 좋아요.',
        'FURNITURE', 'GOOD', 18000, 12000, 24000, 0.68, '''수납함''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 230, 4, 0, 26566),
    ('p40', 'seoyeon', '화분', '작은 관엽식물 화분, 잘 자라고 있어요.',
        'PLANT', 'GOOD', 15000, 10000, 20000, 0.66, '''화분''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 52, 10, 0, 9393),
    ('p41', 'junho', '중고 도서', '소설책 5권 묶음이에요.',
        'BOOK', 'GOOD', 5000, 3000, 8000, 0.66, '''중고 도서''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 260, 3, 0, 30320),
    ('p42', 'junho', '문제집', '수능 문제집, 필기 거의 없어요.',
        'BOOK', 'LIKE_NEW', 8000, 5000, 11000, 0.66, '''문제집''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 280, 2, 0, 31005),
    ('p43', 'seoyeon', '문구 세트', '노트 · 펜 · 형광펜 문구 세트, 새 상품이에요.',
        'ETC', 'NEW', 12000, 8000, 16000, 0.66, '''문구 세트''의 최근 중고 거래가와 새 상품 상태를 반영한 시연용 표본 가격이에요.', 150, 3, 0, 39270),
    ('p44', 'minji', '계산기', '공학용 계산기, 케이스 포함이에요.',
        'DIGITAL', 'GOOD', 20000, 14000, 26000, 0.68, '''계산기''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 250, 5, 0, 26656),
    ('p45', 'dohyun', '의자', '원목 회전 의자, 바퀴 잘 굴러요.',
        'FURNITURE', 'GOOD', 70000, 55000, 85000, 0.72, '''의자''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 100, 12, 0, 20278),
    ('p46', 'junho', '책상', '원목 1인용 책상이에요. 직접 가져가셔야 해요.',
        'FURNITURE', 'GOOD', 90000, 70000, 110000, 0.73, '''책상''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 120, 14, 0, 22410),
    ('p47', 'junho', '자전거', '26인치 산악자전거, 기어 변속 잘 돼요.',
        'SPORTS', 'GOOD', 180000, 150000, 210000, 0.76, '''자전거''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 33, 30, 0, 31218),
    ('p48', 'seoyeon', '우산', '3단 자동 우산, 거의 안 썼어요.',
        'ETC', 'LIKE_NEW', 10000, 6000, 14000, 0.64, '''우산''의 최근 중고 거래가와 거의 새것 상태를 반영한 시연용 표본 가격이에요.', 300, 2, 0, 14275),
    ('p49', 'minji', '반려동물 이동 가방', '고양이 · 소형견 이동 가방, 세탁했어요.',
        'PET', 'GOOD', 38000, 28000, 48000, 0.70, '''반려동물 이동 가방''의 최근 중고 거래가와 사용감 적음 상태를 반영한 시연용 표본 가격이에요.', 64, 9, 0, 28207),
    ('p50', 'dohyun', '장바구니', '튼튼한 접이식 장바구니, 새 상품이에요.',
        'ETC', 'NEW', 7000, 4000, 10000, 0.62, '''장바구니''의 최근 중고 거래가와 새 상품 상태를 반영한 시연용 표본 가격이에요.', 320, 2, 0, 20017);

insert into items (owner_id, title, description, category, item_condition, status, ai_price, price_min, price_max,
                   ai_confidence, ai_reason, ai_provider, view_count, request_count, created_at, updated_at, version)
select u.id, s.title, s.description, s.category, s.item_condition, 'AVAILABLE', s.price, s.price_min, s.price_max,
       s.confidence, s.reason, 'seed', s.views, s.requests,
       dateadd('HOUR', -s.hours_ago, current_timestamp), dateadd('HOUR', -s.hours_ago, current_timestamp), 0
from seed_items s
join app_users u on u.username = s.username
order by s.img;

-- 대표 사진(512x512 JPEG, 크기는 classpath:seed/pNN.jpg 와 같다)
insert into item_images (uploader_id, item_id, stored_name, original_name, content_type, size, width, height, sort_order, created_at)
select i.owner_id, i.id, 'seed-' || s.img || '.jpg', s.img || '.jpg', 'image/jpeg', s.file_size, 512, 512, 0, i.created_at
from seed_items s
join items i on i.title = s.title
order by s.img;

-- 이 물건과 바꾸고 싶은 카테고리
insert into item_wish_categories (item_id, category)
select i.id, w.category
from (values
        ('p01', 'HOBBY'), ('p01', 'FASHION'), ('p02', 'HOBBY'), ('p03', 'HOBBY'), ('p03', 'APPLIANCE'), ('p04', 'HOBBY'),
        ('p04', 'SPORTS'), ('p05', 'ACCESSORY'), ('p06', 'HOBBY'), ('p06', 'SPORTS'), ('p07', 'HOBBY'), ('p07', 'SPORTS'),
        ('p08', 'SPORTS'), ('p08', 'HOBBY'), ('p09', 'DIGITAL'), ('p09', 'HOBBY'), ('p10', 'SPORTS'), ('p11', 'ACCESSORY'),
        ('p11', 'BEAUTY'), ('p12', 'BEAUTY'), ('p13', 'ACCESSORY'), ('p13', 'SPORTS'), ('p14', 'DIGITAL'), ('p15', 'BOOK'),
        ('p16', 'FASHION'), ('p17', 'DIGITAL'), ('p17', 'BEAUTY'), ('p18', 'FASHION'), ('p19', 'DIGITAL'), ('p19', 'HOBBY'),
        ('p20', 'BEAUTY'), ('p20', 'FASHION'), ('p21', 'SPORTS'), ('p22', 'DIGITAL'), ('p23', 'BOOK'), ('p24', 'HOBBY'),
        ('p25', 'DIGITAL'), ('p26', 'DIGITAL'), ('p27', 'FASHION'), ('p28', 'ACCESSORY'), ('p29', 'BEAUTY'), ('p30', 'DIGITAL'),
        ('p31', 'PLANT'), ('p31', 'BOOK'), ('p32', 'DIGITAL'), ('p33', 'FURNITURE'), ('p34', 'PLANT'), ('p35', 'SPORTS'),
        ('p36', 'HOBBY'), ('p37', 'DIGITAL'), ('p38', 'FURNITURE'), ('p39', 'PLANT'), ('p40', 'FURNITURE'), ('p41', 'HOBBY'),
        ('p42', 'ETC'), ('p43', 'BOOK'), ('p44', 'BOOK'), ('p45', 'DIGITAL'), ('p46', 'DIGITAL'), ('p47', 'DIGITAL'),
        ('p47', 'HOBBY'), ('p48', 'BEAUTY'), ('p49', 'ACCESSORY'), ('p50', 'PLANT')
     ) as w(img, category)
join seed_items s on s.img = w.img
join items i on i.title = s.title;

drop table seed_items;

-- 3) 시연 시작용 교환 요청: 도현의 '모니터' → 민지의 '무선 이어폰' (이어폰 request_count = 1 은 위에서 넣음)
insert into chat_rooms (item_id, owner_id, partner_id, created_at, last_message_at, last_message_preview,
                        owner_last_read_id, partner_last_read_id)
select i.id, i.owner_id, d.id, current_timestamp, current_timestamp,
       '''모니터''(으)로 ''무선 이어폰''을(를) 바꾸고 싶어요.' || char(10) || '이어폰이랑 모니터 바꾸실래요? 받침대도 드려요!',
       0, 0
from items i, app_users d
where i.title = '무선 이어폰' and d.username = 'dohyun';

insert into exchange_requests (requester_id, target_owner_id, offered_item_id, target_item_id, message, status,
                               chat_room_id, created_at)
select mon.owner_id, ear.owner_id, mon.id, ear.id, '이어폰이랑 모니터 바꾸실래요? 받침대도 드려요!', 'REQUESTED',
       r.id, current_timestamp
from items mon, items ear, chat_rooms r
where mon.title = '모니터' and ear.title = '무선 이어폰'
  and r.item_id = ear.id and r.partner_id = mon.owner_id;

insert into chat_messages (room_id, sender_id, msg_type, content, exchange_id, created_at)
select e.chat_room_id, e.requester_id, 'PROPOSAL',
       '''모니터''(으)로 ''무선 이어폰''을(를) 바꾸고 싶어요.' || char(10) || '이어폰이랑 모니터 바꾸실래요? 받침대도 드려요!',
       e.id, current_timestamp
from exchange_requests e;

-- 보낸 사람(도현 = partner)은 자기 메시지를 읽은 것으로 둔다(ChatService.post 와 같음)
update chat_rooms r set partner_last_read_id = (select max(m.id) from chat_messages m where m.room_id = r.id);

insert into notifications (user_id, noti_type, title, body, ref_type, ref_id, is_read, created_at)
select e.target_owner_id, 'EXCHANGE_REQUESTED', '교환 요청이 왔어요',
       '도현님이 ''모니터''(으)로 ''무선 이어폰''을(를) 바꾸고 싶어해요. 수락하면 교환이 완료돼요.',
       'EXCHANGE', e.id, false, current_timestamp
from exchange_requests e;
