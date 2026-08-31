# syntax=docker/dockerfile:1
#
# 빌드 컨텍스트는 이 레포 루트다.
#   DOCKER_BUILDKIT=1 docker build \
#     --secret id=gpr,src=$HOME/.gradle/gradle.properties \
#     -t gamehouse:crew .
#
# [모노레포 때와 달라진 것]
#   before  컨텍스트가 backend/ 루트, 모듈 7개의 build.gradle 을 전부 COPY,
#           common 을 project(':common') 으로 함께 빌드
#   after   컨텍스트가 이 레포, common 은 GitHub Packages 에서 받아온다
#
# ⚠️ 토큰을 ARG 로 넘기지 말 것. 이미지 레이어 히스토리에 평문으로 남는다.
#    BuildKit secret mount 는 그 RUN 동안만 존재하고 레이어에 남지 않는다.

FROM eclipse-temurin:17-jdk AS build
WORKDIR /app

# 의존성 정의 먼저 복사 → 소스만 바뀔 때 이 레이어 재사용
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew

RUN --mount=type=secret,id=gpr,target=/root/.gradle/gradle.properties \
    ./gradlew --no-daemon dependencies

COPY src ./src
RUN --mount=type=secret,id=gpr,target=/root/.gradle/gradle.properties \
    ./gradlew --no-daemon bootJar
RUN cp build/libs/*.jar /app/app.jar

# 최소 JRE 생성. 새 의존성이 다른 JDK 모듈을 요구하면 여기 추가 (빠지면 런타임 NoClassDefFound)
RUN jlink \
      --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.prefs,java.rmi,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,java.transaction.xa,java.xml,java.xml.crypto,jdk.crypto.cryptoki,jdk.crypto.ec,jdk.jfr,jdk.management,jdk.unsupported,jdk.httpserver \
      --strip-debug --no-header-files --no-man-pages --compress=2 \
      --output /javaruntime

FROM debian:bookworm-slim AS run
ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"
WORKDIR /app

RUN groupadd -r spring && useradd -r -g spring spring

COPY --from=build /javaruntime ${JAVA_HOME}
COPY --from=build /app/app.jar /app/app.jar

# crew 는 업로드 파일을 다루지 않는다. 다른 서비스에 있는 /app/uploads 볼륨 준비를
# 여기서는 뺐다 — 쓰지 않는 빈 디렉터리를 만들면 나중에 누가 왜 있는지 되묻는다.

USER spring
EXPOSE 8086
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
