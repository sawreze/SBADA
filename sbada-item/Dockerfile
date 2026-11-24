# 스바다 물품 서버 이미지(물품 · 사진 · AI · 추천 · 찜 · GraphQL). 메인 · 결제 서버와 따로 배포 · 따로 확장
#  빌드 위치는 저장소 맨 위(세 서버가 같은 .proto 를 쓰므로): docker build -f sbada-item/Dockerfile .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY sbada-proto ./sbada-proto
COPY sbada-item ./sbada-item
WORKDIR /src/sbada-item
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/sbada-item/build/libs/sbada-item-0.1.0.jar app.jar
ENV JAVA_OPTS="-Xmx300m -XX:+UseSerialGC"
EXPOSE 8099 9099
CMD ["sh", "-c", "java $JAVA_OPTS -Dserver.address=0.0.0.0 -Dserver.port=${PORT:-8099} -jar app.jar"]
