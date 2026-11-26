# syntax=docker/dockerfile:1
# identity-service : image multi-étapes, exécution non root (docs/ci/requests/backend.md §3.1).
# Contexte de build : services/  →  docker build -f identity-service/Dockerfile services/

# ---------------------------------------------------------------- build
# JDK Temurin 25 + wrapper Maven 3.10.2 (l'image maven:3.10.2-eclipse-temurin-25 n'existe pas
# pour toutes les plateformes, ex. arm64).
FROM eclipse-temurin:25-jdk@sha256:ce2ef9d2d67a68de5e196d4de9d743327c44a10b57fe02ec39f572c652cacfd3 AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY identity-service/pom.xml identity-service/
COPY identity-service/src identity-service/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -ntp -q -pl identity-service -am package -DskipTests \
 && cp identity-service/target/identity-service-*.jar /build/app.jar \
 && java -Djarmode=tools -jar /build/app.jar extract --layers --launcher --destination /build/extracted

# ---------------------------------------------------------------- exécution
FROM eclipse-temurin:25-jre@sha256:4ed132f6832e9804b123e89a849a7b4502756ae64a4ee08ebab939d9d5d0a9fa
RUN groupadd --system --gid 10001 identity \
 && useradd --system --uid 10001 --gid identity --no-create-home --shell /usr/sbin/nologin identity
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/extracted/dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=10001:10001 /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=10001:10001 /build/extracted/application/ ./
USER 10001:10001
# 8080 : API (routée par nginx) ; 8091 : management (/actuator/health, jamais publié).
EXPOSE 8080 8091
# Clés JWT montées en lecture seule (volume jwt_keys) ; aucun secret dans l'image.
# B1 : --enable-native-access pour le moteur bcrypt natif (crypt_rn de libcrypt.so.1 via FFM).
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError --enable-native-access=ALL-UNNAMED" \
    JWT_PRIVATE_KEY_PATH=/run/taskora/jwt/private.pem \
    JWT_PUBLIC_KEY_PATH=/run/taskora/jwt/public.pem
# Sans curl dans l'image JRE : requête HTTP minimale par /dev/tcp (bash).
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8091 && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
