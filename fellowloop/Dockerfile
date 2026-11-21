# Fellowloop server image: the Spring Boot server with the web UI baked in.
# Build: docker build -t fellowloop-server .
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY fellowloop-workflow fellowloop-workflow
COPY fellowloop-protocol fellowloop-protocol
COPY fellowloop-agent fellowloop-agent
COPY fellowloop-daemon fellowloop-daemon
COPY fellowloop-cli fellowloop-cli
COPY fellowloop-server fellowloop-server
RUN mvn -q -B package -DskipTests

FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web ./
RUN npm run build

FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --home-dir /var/lib/fellowloop fellowloop \
    && mkdir -p /var/lib/fellowloop/files && chown fellowloop:fellowloop /var/lib/fellowloop/files
WORKDIR /opt/fellowloop
COPY --from=build /src/fellowloop-server/target/fellowloop-server.jar /opt/fellowloop/fellowloop-server.jar
COPY --from=build /src/fellowloop-daemon/target/fellowloop-daemon-exec.jar /opt/fellowloop/downloads/fellowloop-daemon.jar
COPY --from=build /src/fellowloop-cli/target/fellowloop-cli-exec.jar /opt/fellowloop/downloads/fellowloop-cli.jar
COPY dist-scripts/install-runtime.sh /opt/fellowloop/downloads/install-runtime.sh
COPY LICENSE THIRD-PARTY-NOTICES.md /opt/fellowloop/
COPY --from=web /web/dist /opt/fellowloop/web
USER fellowloop
ENV FELLOWLOOP_WEB_DIR=file:/opt/fellowloop/web/ \
    FELLOWLOOP_DOWNLOADS_DIR=/opt/fellowloop/downloads \
    FELLOWLOOP_STORAGE_DIR=/var/lib/fellowloop/files \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/opt/fellowloop/fellowloop-server.jar"]
