# =============================================================================
# AutoClick — imagem única pro EasyPanel
#
#  Etapa 1 (apk): instala o SDK do Android e compila o APK assinado a partir
#                 do código em app/. Roda de novo SÓ quando app/ muda (cache
#                 de camadas do Docker); mudar macro/config não recompila.
#  Etapa 2 (server): Node sem dependências servindo o APK, os macros e o
#                    config pros celulares, mais o painel de status.
# =============================================================================

# ---------- Etapa 1: APK ----------
FROM eclipse-temurin:17-jdk-jammy AS apk

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    GRADLE_USER_HOME=/opt/gradle-home \
    GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.console=plain"

RUN apt-get update \
 && apt-get install -y --no-install-recommends unzip curl ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# cmdline-tools 12.0 (build 11076708) exige JDK 17: é o que a imagem tem.
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
 && cd "$ANDROID_HOME/cmdline-tools" \
 && curl -fsSL -o tools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q tools.zip && rm tools.zip \
 && mv cmdline-tools latest

ENV PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true \
 && sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0" > /dev/null

WORKDIR /src

# Primeiro só os arquivos de build: baixa o Gradle e os plugins numa camada
# que fica em cache enquanto esses arquivos não mudarem.
COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle.kts settings.gradle.kts gradle.properties ./
COPY app/build.gradle.kts ./app/build.gradle.kts
RUN chmod +x gradlew && ./gradlew --no-daemon -q help > /dev/null

# Dependências do app (AndroidX, Material): outra camada em cache.
RUN ./gradlew --no-daemon -q :app:dependencies --configuration releaseRuntimeClasspath > /dev/null 2>&1 || true

# Agora o código. A chave de assinatura tem que ser a MESMA de sempre, senão
# o Android recusa instalar por cima da versão que já está nos celulares.
COPY autoclick-keystore.jks ./autoclick-keystore.jks
COPY app ./app

# Opcionais (variáveis de ambiente do EasyPanel chegam aqui como build args):
#   SERVER_URL  = endereço público do servidor, gravado dentro do APK
#   SYNC_TOKEN  = token que o app manda em cada pedido (o servidor confere)
# Sem eles, valem os valores de gradle.properties.
ARG SERVER_URL=""
ARG SYNC_TOKEN=""
ARG VERSION_NAME=""

# versionCode = segundos desde 2023-11-14 (cresce a cada build; sempre maior
# que o instalado). Só roda quando app/ mudou, então macro novo não gera APK
# novo nem atualização à toa nos celulares.
RUN set -e; \
    CODE=$(( $(date +%s) - 1700000000 )); \
    EXTRA="-Pautoclick.versionCode=$CODE"; \
    if [ -n "$SERVER_URL" ];   then EXTRA="$EXTRA -Pautoclick.serverUrl=$SERVER_URL"; fi; \
    if [ -n "$SYNC_TOKEN" ];   then EXTRA="$EXTRA -Pautoclick.syncToken=$SYNC_TOKEN"; fi; \
    if [ -n "$VERSION_NAME" ]; then EXTRA="$EXTRA -Pautoclick.versionName=$VERSION_NAME"; fi; \
    ./gradlew --no-daemon :app:assembleRelease $EXTRA; \
    APK=app/build/outputs/apk/release/app-release.apk; \
    test -f "$APK"; \
    BADGING=$("$ANDROID_HOME/build-tools/34.0.0/aapt2" dump badging "$APK" | grep "^package:"); \
    VC=$(echo "$BADGING" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p"); \
    VN=$(echo "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p"); \
    mkdir -p /out && cp "$APK" /out/AutoClick.apk; \
    printf '{"versionCode":%s,"versionName":"%s","builtAt":"%s"}\n' "$VC" "$VN" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" > /out/version.json; \
    cat /out/version.json

# ---------- Etapa 2: servidor ----------
FROM node:22-alpine AS server

WORKDIR /app
ENV NODE_ENV=production \
    PORT=3000 \
    DATA_DIR=/app/data

COPY server ./server
COPY macros ./macros
COPY config.json ./config.json
COPY gradle.properties ./gradle.properties
COPY --from=apk /out /app/apk

RUN mkdir -p /app/data

EXPOSE 3000
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3000/health > /dev/null || exit 1

CMD ["node", "server/index.js"]
