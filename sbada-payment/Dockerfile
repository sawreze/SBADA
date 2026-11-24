# 스바다 결제 서비스 이미지(메인 서버와 따로 배포 · 따로 확장)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# 빌드 위치는 저장소 맨 위(세 서버가 같은 .proto 를 쓰므로): docker build -f sbada-payment/Dockerfile .
COPY sbada-proto ./sbada-proto
COPY sbada-payment ./sbada-payment
WORKDIR /src/sbada-payment
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/sbada-payment/build/libs/sbada-payment-0.1.0.jar app.jar
ENV JAVA_OPTS="-Xmx256m -XX:+UseSerialGC"
EXPOSE 8098 9098
CMD ["sh", "-c", "java $JAVA_OPTS -Dserver.address=0.0.0.0 -Dserver.port=${PORT:-8098} -jar app.jar"]
