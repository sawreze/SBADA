-- 2026-11-25 사용자 요청: 아이디 로그인 → 이메일 로그인
--  - 회원가입은 이메일로 하고, 로그인 칸도 이메일. username 은 앱 안의 @핸들 · 내부용으로 그대로 남긴다(자동으로 만들어짐)
--  - 이미 있는 계정(시연 · 테스트)은 '아이디@sbada.test' 로 채워 둔다 → admin@sbada.test / sbada1234 로 로그인
--    (.test 는 실제로 쓰이지 않는 예약 도메인이라 진짜 메일 주소와 겹치지 않는다). 카카오 계정은 비워 둔다
--  - 예전 앱 · 스크립트를 위해 로그인은 아이디(username)도 계속 받는다
alter table app_users add column email varchar(254);
update app_users set email = lower(username) || '@sbada.test' where kakao_id is null;
create unique index ux_app_users_email on app_users(email);
