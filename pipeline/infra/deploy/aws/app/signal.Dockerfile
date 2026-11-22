# 팀 시그널링 BE(BE_ScamShield 루트, 8080) 이미지. 팀 레포를 고치지 않으려고 Dockerfile 은 여기 둔다.
# 빌드 컨텍스트 = ~/src/BE_ScamShield (compose.yml 참고). pipeline/ 폴더는 복사하지 않는다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test && rm -f build/libs/*-plain.jar && cp build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
COPY --from=build /app.jar /app/app.jar
USER app
WORKDIR /app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
