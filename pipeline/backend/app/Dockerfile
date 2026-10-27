# 파이프라인 앱 (Spring Boot). OCI A1(arm64)·로컬(amd64) 둘 다 같은 파일로 빌드한다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test && rm -f build/libs/*-plain.jar && cp build/libs/*.jar /app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 app
COPY --from=build /app.jar /app/app.jar
USER app
WORKDIR /app
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/app.jar"]
