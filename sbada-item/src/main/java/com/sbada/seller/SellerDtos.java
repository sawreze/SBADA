package com.sbada.seller;

/** 화면에 보여 줄 판매자 정보(메인 서버 UserDtos.PublicUser 와 같은 모양 → 앱은 그대로) */
public final class SellerDtos {

    private SellerDtos() {
    }

    public record PublicUser(long id, String nickname, String region) {
        public static PublicUser from(Seller s) {
            return new PublicUser(s.getId(), s.getNickname(), s.getRegion());
        }
    }
}
