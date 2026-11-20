package com.sbada.payment.auth;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.sbada.payment.common.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** /api/** 요청마다 'Authorization: Bearer 토큰' 을 검증하고 사용자 번호를 요청에 붙인다 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String USER_ID = "sbada.userId";

    private final JwtVerifier jwt;

    public AuthInterceptor(JwtVerifier jwt) {
        this.jwt = jwt;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(401, "AUTH_REQUIRED", "로그인이 필요합니다.");
        }
        request.setAttribute(USER_ID, jwt.verify(header.substring(7).trim()));
        return true;
    }
}
