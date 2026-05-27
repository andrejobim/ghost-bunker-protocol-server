# Multi-stage build for Ghost Bunker reference server (staging).
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system ghostbunker \
    && useradd --system --gid ghostbunker ghostbunker
COPY --from=build /build/target/ghost-bunker-protocol-server-*.jar /app/app.jar
USER ghostbunker
EXPOSE 8080 8081
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
