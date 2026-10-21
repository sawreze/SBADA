-- 스바다 카카오 로그인 (2026-10-21)
-- 카카오 회원번호(id)로 스바다 계정을 찾기 위한 열. 아이디·비밀번호로 가입한 계정은 null 로 둔다.
-- unique 인덱스: 같은 카카오 계정이 스바다 계정 두 개에 연결되지 않게 막는다(null 은 여러 개 있어도 된다).
alter table app_users add column kakao_id bigint;
create unique index ux_app_users_kakao_id on app_users (kakao_id);
