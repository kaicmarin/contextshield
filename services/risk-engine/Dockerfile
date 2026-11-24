# Build a partir da raiz do repositório:  docker build -f services/risk-engine/Dockerfile .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY services/risk-engine/pom.xml services/risk-engine/
COPY services/core-api/pom.xml services/core-api/
# baixa dependências numa camada própria: mudar código não baixa tudo de novo
RUN mvn -B -q -pl services/risk-engine -am dependency:go-offline -DexcludeArtifactIds=risk-engine || true
COPY services services
RUN mvn -B -q -pl services/risk-engine -am package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /src/services/risk-engine/target/risk-engine-exec.jar app.jar
USER app
ENV PORT=8081 TZ=America/Sao_Paulo JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=3s --start-period=40s --retries=5 CMD wget -qO- http://localhost:${PORT}/actuator/health >/dev/null || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
