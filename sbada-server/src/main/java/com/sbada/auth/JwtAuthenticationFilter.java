package com.sbada.auth;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.sbada.common.ApiException;
import com.sbada.common.ErrorResponse;
import com.sbada.common.Json;
import com.sbada.user.AppUserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Authorization: Bearer <토큰> 을 읽어 '누구인가'를 확인한다(7장 7.4).
 *  - 헤더가 없으면 그냥 통과 → 공개 GET은 200, 보호 API는 401(AUTH_REQUIRED)
 *  - 헤더가 있는데 토큰이 만료·변조되었으면 공개 GET이라도 401
 * '이 자료를 바꿀 수 있는가'(403)는 Service에서 따로 검사한다.
 *
 * [프로그래밍 설명 — 모든 API 요청이 Controller 에 닿기 전에 지나가는 관문]
 *  - 서블릿 필터(Filter): 요청이 DispatcherServlet(→ Controller)에 가기 전에 끼어드는 코드.
 *    Spring Security 는 여러 필터를 줄 세운 '필터 체인'으로 동작하고, SecurityConfig 에서 이 필터를
 *    UsernamePasswordAuthenticationFilter 앞에 끼워 넣었다(addFilterBefore).
 *  - OncePerRequestFilter: 한 요청에 정확히 한 번만 실행되도록 보장하는 부모 클래스.
 *  - 요청 1번의 흐름:
 *      앱 ─(Authorization: Bearer eyJ…)→ [이 필터: 토큰 검증 → SecurityContext 에 사용자 번호 저장]
 *         → [권한 검사: SecurityConfig 의 authorizeHttpRequests 규칙] → Controller(Authentication auth)
 *  - SecurityContextHolder: 지금 요청(스레드)의 '로그인한 사용자'를 담아 두는 곳.
 *    Controller 에서 파라미터로 Authentication auth 를 받으면 여기 넣은 객체가 들어오고,
 *    (Long) auth.getPrincipal() 로 사용자 번호를 꺼낸다.
 *  - 필터는 @Component 가 아니라 SecurityConfig 에서 new 로 만든다 → 일반 서블릿 필터로 중복 등록되는 것을 피한다.
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwt;
    private final AppUserRepository users;

    public JwtAuthenticationFilter(JwtService jwt, AppUserRepository users) {
        this.jwt = jwt;
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        // ① 토큰이 없다 → '익명 사용자'로 다음 단계로 보낸다.
        //    공개 주소면 그대로 처리되고, 보호 주소면 뒤의 권한 검사에서 401 AUTH_REQUIRED 가 된다.
        if (header == null || header.isBlank()) {
            chain.doFilter(request, response); // 체인의 다음 필터(또는 Controller)로 넘기기
            return;
        }
        // ② 형식이 틀렸다(예: "Token abc") → 바로 401 응답을 쓰고 끝낸다(chain.doFilter 를 부르지 않음 = 요청 차단)
        if (!header.startsWith("Bearer ")) {
            writeError(response, new ApiException(401, "TOKEN_INVALID", "Authorization 헤더는 'Bearer 토큰' 형식이어야 합니다."));
            return;
        }
        try {
            // ③ "Bearer " 7글자를 잘라낸 나머지가 토큰. 서명·만료를 검사하고 사용자 번호를 얻는다.
            long userId = jwt.verify(header.substring(7).trim());
            // ④ 토큰은 맞는데 그 사이 사용자가 지워졌을 수도 있으니 DB 에 있는지 확인
            if (!users.existsById(userId)) {
                throw new ApiException(401, "TOKEN_INVALID", "사용자를 확인할 수 없습니다.");
            }
            // ⑤ '인증됨' 표시를 만들어 SecurityContext 에 넣는다.
            //    principal = 사용자 번호(Long), credentials = null(비밀번호는 다시 쓰지 않음), 권한 목록 = 비어 있음(역할 구분 없음)
            var authentication = UsernamePasswordAuthenticationToken.authenticated(userId, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ApiException e) {
            // 만료(TOKEN_EXPIRED)·변조(TOKEN_INVALID) → 혹시 남은 인증 정보를 지우고 401 응답
            SecurityContextHolder.clearContext();
            writeError(response, e);
            return;
        }
        // ⑥ 인증 성공 → 다음 단계로
        chain.doFilter(request, response);
    }

    /**
     * 필터 단계에서는 GlobalExceptionHandler(@RestControllerAdvice)가 동작하지 않으므로(아직 Controller 전)
     * 응답(상태 코드 + JSON 본문)을 직접 쓴다. 모양은 다른 오류와 같은 {code, message, errors}.
     */
    static void writeError(HttpServletResponse response, ApiException e) throws IOException {
        response.setStatus(e.getStatus());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(Json.write(ErrorResponse.of(e.getCode(), e.getMessage())));
    }
}
