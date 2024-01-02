FROM ghcr.io/navikt/baseimages/temurin:11
ENV JAVA_OPTS="-Dlogback.configurationFile=logback-remote.xml -Xms512M -Xmx2048M"
COPY build/libs/app*.jar app.jar