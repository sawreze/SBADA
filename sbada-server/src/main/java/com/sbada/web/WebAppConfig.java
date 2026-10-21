package com.sbada.web;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 브라우저 테스트용: 스바다 Flutter 앱의 웹 빌드(sbada_app/build/web)를 /app/ 주소로 내려 준다.
 * 에뮬레이터와 같은 앱 코드가 브라우저에서 돈다 → http://127.0.0.1:8097/test.html 이 폰 비율 화면 안에 띄운다.
 *
 * 빌드: scripts\build-web.bat (flutter build web --base-href /app/ --dart-define=API_BASE_URL=http://127.0.0.1:8097)
 * 폴더가 없으면(빌드 전) /app/ 은 404 이고, 나머지 서버 기능에는 영향이 없다.
 *
 * [프로그래밍 설명]
 *  - WebMvcConfigurer: 스프링 MVC 설정을 덧붙이는 인터페이스. 필요한 메서드만 골라 재정의한다.
 *  - addResourceHandlers: "/app/**" 요청을 디스크 폴더의 파일로 바로 응답(정적 파일 서버).
 *  - addViewControllers: 컨트롤러 없이 주소만 연결. "/app" → "/app/" 로 이동, "/app/" → index.html 을 보여 준다.
 */
@Configuration
public class WebAppConfig implements WebMvcConfigurer {

    private final String location;

    public WebAppConfig(@Value("${sbada.webapp.dir:../sbada_app/build/web}") String dir) {
        String uri = Path.of(dir).toAbsolutePath().normalize().toUri().toString();
        this.location = uri.endsWith("/") ? uri : uri + "/";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/app/**").addResourceLocations(location);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/app").setViewName("redirect:/app/");
        registry.addViewController("/app/").setViewName("forward:/app/index.html");
    }
}
