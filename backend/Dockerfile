FROM eclipse-temurin:25.0.4_7-jdk-alpine-3.24 AS jdk

FROM postgres:17.10-alpine3.24 AS build

COPY --from=jdk /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH=$JAVA_HOME/bin:$PATH
WORKDIR /workspace/backend
COPY schema/ /workspace/schema/
COPY backend/ ./
RUN set -eux; \
    mkdir -p /tmp/pgdata; \
    chown postgres:postgres /tmp/pgdata; \
    gosu postgres initdb -D /tmp/pgdata; \
    gosu postgres pg_ctl -D /tmp/pgdata -o "-c listen_addresses=127.0.0.1" -w start; \
    ./gradlew --no-daemon :bootstrap:bootJar \
      -Pfinds.codegen.jdbcUrl=jdbc:postgresql://127.0.0.1:5432/postgres \
      -Pfinds.codegen.user=postgres; \
    gosu postgres pg_ctl -D /tmp/pgdata -m fast -w stop

FROM eclipse-temurin:25.0.4_7-jre-alpine-3.24 AS runtime

RUN addgroup -S finds && adduser -S -G finds -u 10001 finds
WORKDIR /app
COPY --from=build --chown=finds:finds /workspace/backend/bootstrap/build/libs/bootstrap-0.1.0-SNAPSHOT.jar ./app.jar

USER finds
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
