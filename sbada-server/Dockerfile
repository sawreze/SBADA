# 스바다 서버를 클라우드(Render 등)에서 돌리는 이미지.
#  1단계: JDK 21 로 실행용 jar 만들기(테스트는 PC 에서 이미 돌렸으므로 건너뜀)
#  2단계: JRE 21 만 담은 가벼운 이미지에서 실행. 주소 0.0.0.0, 포트는 클라우드가 주는 PORT(없으면 8097)
#  비밀값(GEMMA_API_KEY 등)은 이미지에 넣지 않고 클라우드의 환경 변수로 넣는다
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/build/libs/sbada-server-0.1.0.jar app.jar
# 무료 플랜 메모리(512MB)에 맞춤. DB(H2)와 사진은 컨테이너 안 ./data — 다시 배포하거나 재시작하면 처음 시연 데이터로 돌아간다
ENV JAVA_OPTS="-Xmx350m -XX:+UseSerialGC"
EXPOSE 8097
CMD ["sh", "-c", "java $JAVA_OPTS -Dserver.address=0.0.0.0 -Dserver.port=${PORT:-8097} -jar app.jar"]
