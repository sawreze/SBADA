package com.sbada.auth;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.sbada.common.ApiException;
import com.sbada.user.AppUserRepository;

/**
 * 공개 요청과 보호 요청을 나눈다(7장 7.2의 표).
 *  공개: 회원가입·로그인, 상품 목록·상세 GET, 카테고리, 사진 보기, 상태 확인, WebSocket 연결(토큰은 핸드셰이크에서 검사)
 *  보호: 그 밖의 /api/** (등록·수정·삭제·교환·채팅·알림·추천)
 *
 * [프로그래밍 설명 — 어떤 API 를 로그인 없이 부를 수 있는지 정하는 곳]
 *  - @Configuration: @Bean 메서드로 직접 빈을 만들어 등록하는 설정 클래스.
 *    @Bean 메서드가 돌려준 객체가 스프링 빈이 되고, 다른 클래스의 생성자에 주입된다
 *    (예: passwordEncoder() → AuthService 의 PasswordEncoder 파라미터).
 *  - SecurityFilterChain: Spring Security 6+ 방식의 보안 설정. HttpSecurity 빌더로 규칙을 쌓고 build() 한다.
 *  - 람다 DSL: .csrf(c -> c.disable()) 처럼 '설정 객체를 받아 바꾸는 함수'를 넘겨 설정한다.
 *
 *  ▶ 주소별 접근 규칙 (위에서부터 처음 맞는 규칙이 적용된다)
 *    공개(permitAll)     /, /error, /api/health, /api/auth/signup·login·kakao, GET /api/geo/reverse(로그인 전 온보딩의 GPS 동네 찾기), /ws(WebSocket — 토큰은 JwtHandshakeInterceptor 가 따로 검사)
 *                        POST /api/auth/signup, POST /api/auth/login
 *                        GET  /api/items, /api/items/{id}, /api/categories, /api/images/{id},
 *                             /api/ai/status, /api/config/map
 *    로그인 필요         그 밖의 /api/**  → 토큰이 없으면 401 AUTH_REQUIRED
 *    전부 거절(denyAll)  /api 로 시작하지 않는 나머지 주소
 *    ※ "/api/items/*" 의 * 는 경로 한 칸만 뜻한다 → /api/items/5 는 공개, /api/items/5/matches 는 보호.
 *    ※ 같은 /api/items 라도 GET 만 공개다. POST(등록)·PUT·DELETE 는 로그인 필요.
 */
@Configuration
public class SecurityConfig {

    /** 비밀번호 해시 도구. BCrypt 는 기본 강도 10(2^10번 반복)으로 해시한다. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /** 폼 로그인을 쓰지 않으므로 기본 사용자(생성 비밀번호 로그)를 만들지 않게 한다. */
    @Bean
    UserDetailsService noFormLoginUsers() {
        // 이 빈이 없으면 Spring Boot 가 'user / 임의 비밀번호' 기본 계정을 만들고 로그에 비밀번호를 찍는다
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    /**
     * 보안 필터 체인 설정. 파라미터(JwtService, AppUserRepository, h2Console)는 스프링이 주입한다.
     */
    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt, AppUserRepository users,
                            @Value("${spring.h2.console.enabled:false}") boolean h2Console,
                            @Value("${sbada.cors.allowed-origins:}") String corsOrigins) throws Exception {
        // 다른 주소의 웹 화면(예: Vercel 에 올린 https://sbada.vercel.app)이 이 서버 API 를 부를 수 있게 허용(CORS).
        // 비어 있으면(기본, 내 PC) 끈다 — 웹 화면을 같은 서버(/app/)에서 내려 주므로 필요 없음.
        // 예) sbada.cors.allowed-origins=https://*.vercel.app (쉼표로 여러 개, * 는 그 자리 아무 글자)
        List<String> origins = Arrays.stream(corsOrigins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (!origins.isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOriginPatterns(origins);
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            cors.setMaxAge(3600L);
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/api/**", cors);
            http.cors(c -> c.configurationSource(source));
        }
        http
                // CSRF 보호 끔: 쿠키 대신 Authorization 헤더(Bearer 토큰)로 인증하므로
                // 브라우저가 쿠키를 자동으로 붙이는 것을 노리는 CSRF 공격이 성립하지 않는다.
                .csrf(c -> c.disable())
                .formLogin(f -> f.disable())   // 스프링 기본 로그인 HTML 페이지를 쓰지 않음(로그인은 JSON API)
                .httpBasic(b -> b.disable())   // 'Authorization: Basic 아이디:비밀번호' 방식도 쓰지 않음
                // 세션(JSESSIONID 쿠키)을 만들지 않음 → 매 요청을 토큰만으로 판단(STATELESS)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // H2 콘솔은 <frame> 을 쓰므로 같은 출처(sameOrigin)에서는 frame 을 허용
                .headers(h -> h.frameOptions(f -> f.sameOrigin()))
                .authorizeHttpRequests(a -> {
                    if (h2Console) { // dev 프로필에서만 켜지는 DB 확인 화면(http://127.0.0.1:8097/h2-console)
                        a.requestMatchers("/h2-console/**").permitAll();
                    }
                    // /test.html(폰 비율 테스트 화면) · /api-test.html(API 테스트) · /app/**(Flutter 웹 빌드):
                    // 정적 파일만 공개. 데이터는 아래 규칙 그대로 API 로 받는다.
                    a.requestMatchers("/", "/error", "/favicon.ico", "/test.html", "/api-test.html", "/app", "/app/**",
                                    "/api/health", "/ws", "/ws/**").permitAll()
                            .requestMatchers("/api/auth/signup", "/api/auth/login", "/api/auth/kakao").permitAll()
                            .requestMatchers(HttpMethod.GET, // ← 이 줄의 주소들은 GET 방식일 때만 공개
                                    "/api/items", "/api/items/*", "/api/categories",
                                    "/api/images/*", "/api/ai/status", "/api/config/map", "/api/geo/reverse").permitAll()
                            .requestMatchers("/api/**").authenticated() // 나머지 API 는 로그인(토큰) 필요
                            .anyRequest().denyAll();                    // 그 밖의 주소는 모두 거절
                })
                // 필터 단계에서 거절될 때의 응답 모양을 우리 오류 JSON 으로 통일
                .exceptionHandling(e -> e
                        // 인증이 필요한데 토큰이 없음 → 401
                        .authenticationEntryPoint((req, res, ex) -> JwtAuthenticationFilter.writeError(res,
                                new ApiException(401, "AUTH_REQUIRED", "로그인이 필요합니다.")))
                        // 인증은 됐지만 그 주소에 접근 권한이 없음(denyAll 등) → 403
                        .accessDeniedHandler((req, res, ex) -> JwtAuthenticationFilter.writeError(res,
                                new ApiException(403, "FORBIDDEN", "이 요청을 할 권한이 없습니다."))))
                // 우리가 만든 JWT 필터를 스프링 기본 인증 필터보다 앞에 끼워 넣는다
                .addFilterBefore(new JwtAuthenticationFilter(jwt, users), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
