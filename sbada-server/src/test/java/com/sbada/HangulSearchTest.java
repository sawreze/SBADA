package com.sbada;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.sbada.common.HangulSearch;

/** 검색 규칙: 이름 · 동네 · 초성 · 여러 단어 */
class HangulSearchTest {

    private static boolean find(String q) {
        return HangulSearch.matches(q, "블루투스 헤드폰", "마포구 망원동");
    }

    @Test
    void titleAndSpacesAndCase() {
        assertThat(find("헤드폰")).isTrue();
        assertThat(find("블루투스헤드폰")).isTrue();
        assertThat(HangulSearch.matches("qhd", "27인치 QHD 모니터", "마포구 서교동")).isTrue();
        assertThat(find("이어폰")).isFalse();
    }

    @Test
    void choseong() {
        assertThat(find("ㅎㄷㅍ")).isTrue();
        assertThat(find("ㅂㄹㅌㅅ")).isTrue();
        assertThat(find("헤ㄷ")).isTrue();
        assertThat(find("ㅎㅎ")).isFalse();
        assertThat(HangulSearch.matches("ㅅㅁㅌㅍ", "스마트폰", "마포구 서교동")).isTrue();
    }

    @Test
    void regionAndAddress() {
        assertThat(find("망원동")).isTrue();
        assertThat(find("망원")).isTrue();
        assertThat(find("마포구")).isTrue();
        assertThat(find("마포구 망원동")).isTrue();
        assertThat(find("서울 마포구 망원동")).isTrue();
        assertThat(find("ㅁㅇㄷ")).isTrue();
        assertThat(find("합정동")).isFalse();
    }

    @Test
    void everyWordMustMatchTitleOrRegion() {
        assertThat(find("망원 헤드폰")).isTrue();
        assertThat(find("망원동 ㅎㄷㅍ")).isTrue();
        assertThat(find("합정 헤드폰")).isFalse();
        assertThat(find("  ")).isTrue();
    }
}
