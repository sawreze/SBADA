-- 스바다 프로필 사진 (2026-10-21)
-- 계정정보 위쪽 동그란 프로필에 사진을 넣는다. 사진 파일은 기존 사진 업로드(POST /api/images)를 그대로 쓰고,
-- 여기에는 그 사진 번호만 저장한다(없으면 null = 닉네임 첫 글자). 기존 행 · 데이터는 바뀌지 않는다.
alter table app_users add column profile_image_id bigint;
alter table app_users add constraint fk_app_users_profile_image foreign key (profile_image_id) references item_images;
