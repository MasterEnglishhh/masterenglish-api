FROM eclipse-temurin:21-jre-noble

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --home-dir /app --no-create-home --shell /usr/sbin/nologin app

WORKDIR /app

COPY build/libs/app.jar ./app.jar

USER app

EXPOSE 8080

ENTRYPOINT ["java", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
