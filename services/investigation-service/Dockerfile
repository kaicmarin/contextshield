# docker build -f services/investigation-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY machine-learning/pom.xml machine-learning/
COPY services/risk-engine/pom.xml services/risk-engine/
COPY services/core-api/pom.xml services/core-api/
COPY services/ml-service/pom.xml services/ml-service/
COPY services/threat-service/pom.xml services/threat-service/
COPY services/investigation-service/pom.xml services/investigation-service/
COPY services/investigation-service services/investigation-service
RUN mvn -B -q -pl services/investigation-service -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/services/investigation-service/target/investigation-service.jar app.jar
USER app
ENV PORT=8084 TZ=America/Sao_Paulo JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8084
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 CMD wget -qO- http://127.0.0.1:8084/actuator/health >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
