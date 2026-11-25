# docker build -f services/ml-service/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY machine-learning/pom.xml machine-learning/
COPY services/risk-engine/pom.xml services/risk-engine/
COPY services/core-api/pom.xml services/core-api/
COPY services/ml-service/pom.xml services/ml-service/
COPY services/threat-service/pom.xml services/threat-service/
COPY services/investigation-service/pom.xml services/investigation-service/
COPY machine-learning machine-learning
COPY services/ml-service services/ml-service
RUN mvn -B -q -pl services/ml-service -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/services/ml-service/target/ml-service-exec.jar app.jar
COPY machine-learning/models /opt/models
USER app
ENV PORT=8082 TZ=America/Sao_Paulo MODELS_DIR=/opt/models ML_WORK_DIR=/var/lib/contextshield/ml-work JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
EXPOSE 8082
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 CMD wget -qO- http://127.0.0.1:8082/actuator/health >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
