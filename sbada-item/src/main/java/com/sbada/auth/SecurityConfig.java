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
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.sbada.common.ApiException;

/**
 * 물품 서버의 공개 / 보호 주소 (2026-11-25 MSA 2단계, 메인 서버 규칙에서 물품 부분만 옮김)
 *  공개: GET 물건 목록 · 상세 · 카테고리 · 사진 · AI 상태, POST /graphql(목록 조회. 로그인하면 '내가 찜했는지'까지)
 *  보호: 그 밖의 /api/** (등록 · 수정 · 삭제 · 찜 · 추천 · AI 분석)
 * 토큰 검증은 메인 서버와 같은 키 파일로(발급은 메인 서버만). 앱은 메인 서버(게이트웨이)를 거쳐 여기로 온다.
 */
@Configuration
public class SecurityConfig {

    /** 폼 로그인을 쓰지 않으므로 기본 사용자(생성 비밀번호 로그)를 만들지 않게 한다 */
    @Bean
    UserDetailsService noFormLoginUsers() {
        return username -> {
            throw new UsernameNotFoundException(username);
        };
    }

    @Bean
    SecurityFilterChain api(HttpSecurity http, JwtService jwt,
                            @Value("${sbada.cors.allowed-origins:}") String corsOrigins) throws Exception {
        List<String> origins = Arrays.stream(corsOrigins.split(",")).map(String::trim).filter(o -> !o.isEmpty()).toList();
        if (!origins.isEmpty()) {
            CorsConfiguration cors = new CorsConfiguration();
            cors.setAllowedOriginPatterns(origins);
            cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
            cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
            cors.setMaxAge(3600L);
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", cors);
            http.cors(c -> c.configurationSource(source));
        }
        http
                .csrf(c -> c.disable())
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/error", "/api/item-service/health", "/graphql").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/items", "/api/items/*", "/api/items/count", "/api/categories",
                                "/api/images/*", "/api/ai/status").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> JwtAuthenticationFilter.writeError(res,
                                new ApiException(401, "AUTH_REQUIRED", "로그인이 필요합니다.")))
                        .accessDeniedHandler((req, res, ex) -> JwtAuthenticationFilter.writeError(res,
                                new ApiException(403, "FORBIDDEN", "이 요청을 할 권한이 없습니다."))))
                .addFilterBefore(new JwtAuthenticationFilter(jwt), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
