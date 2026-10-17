FROM maven:3.9-eclipse-temurin-8 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:8-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ffmpeg \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system tenx \
    && useradd --system --gid tenx --home-dir /app tenx \
    && mkdir -p /data/frames /data/results \
    && chown -R tenx:tenx /data

WORKDIR /app
COPY --from=build /workspace/target/tenx-ai-media-service-*.jar app.jar

USER tenx
EXPOSE 8092

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
