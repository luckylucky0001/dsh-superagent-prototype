# Orca server image: the Spring Boot server with the web UI baked in.
# Build: docker build -t orca-server .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY orca-workflow orca-workflow
COPY orca-protocol orca-protocol
COPY orca-agent orca-agent
COPY orca-daemon orca-daemon
COPY orca-server orca-server
RUN mvn -q -B package -DskipTests

FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web ./
RUN npm run build

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --home-dir /var/lib/orca orca
WORKDIR /opt/orca
COPY --from=build /src/orca-server/target/orca-server.jar /opt/orca/orca-server.jar
COPY --from=build /src/orca-daemon/target/orca-daemon-exec.jar /opt/orca/downloads/orca-daemon.jar
COPY --from=web /web/dist /opt/orca/web
USER orca
ENV ORCA_WEB_DIR=file:/opt/orca/web/ \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/orca/orca-server.jar"]
