# gamehouse-crew

GameHouse의 **House(크루) 서비스**. 하우스 생성·가입·멤버 관리, House 채팅,
주간 퀘스트, 꾸미기 상점, 랭킹을 담당한다.

## 좌표

| 항목 | 값 |
|---|---|
| 앱 포트 | **8086** |
| 관리(actuator) 포트 | **8186** |
| DB 계정 / 스키마 | `duo_crew` / `crew_svc` |
| REST 접두어 | `/api/crew`, `/api/houses/{id}/quests`, `/api/shop` |
| STOMP 엔드포인트 | `/ws-house` (`/ws` 는 chat 서비스가 쓴다) |
| 패키지 | `gg.duo.crew.*` |

---

## 1. 준비물

| | 확인 명령 | 없으면 |
|---|---|---|
| JDK 17 | `java -version` | Temurin 17 설치 |
| Docker | `docker ps` | Docker Desktop 실행 |
| GitHub PAT | 아래 2-1 | `common` 을 못 받아 빌드 실패 |

Gradle 은 wrapper(`./gradlew`)를 쓰므로 따로 설치할 필요 없다.

---

## 2. 처음 한 번만 (clone 직후)

### 2-1. GitHub Packages 토큰 — 이걸 안 하면 빌드가 아예 안 된다

이 레포는 공용 모듈 `gg.duo:common` 을 **GitHub Packages** 에서 받아온다.
GitHub Packages 는 public 패키지도 읽기에 토큰을 요구한다.

1. GitHub → Settings → Developer settings → Personal access tokens →
   **Tokens (classic)** → Generate new token
2. 권한은 **`read:packages`** 하나만 체크
3. `~/.gradle/gradle.properties` 에 아래 두 줄을 넣는다 (파일이 없으면 만든다)

```properties
gpr.user=<GitHub 아이디>
gpr.key=<발급받은 토큰>
```

> ⚠️ 프로젝트 폴더의 `.gradle/` 이 아니라 **홈 디렉터리의 `~/.gradle/`** 이다.
> 이름이 비슷해서 자주 헷갈린다. 잘못 두면 `Could not find gg.duo:common:0.1.0` 이 난다.
>
> ⚠️ fine-grained token 은 동작하지 않는 경우가 있다. **classic** 을 쓴다.

### 2-2. 시크릿 파일

```bash
cp application-secret-example.yml application-secret.yml
```

`JWT_SECRET` 만 실제 값으로 채우면 된다. 나머지 기본값은 그대로 두면 동작한다.

> ⚠️ `JWT_SECRET` 은 **user/post/chat/match/riot 과 반드시 같은 값**이어야 한다.
> 다르면 로그인은 되는데 crew API 만 401 이 난다.
> **32바이트 이상**이어야 한다. 짧으면 부팅 때 `WeakKeyException` 으로 죽는다.

이 파일은 `.gitignore` 에 걸려 있어 커밋되지 않는다.

### 2-3. Postgres 와 RabbitMQ 띄우기

crew 혼자서는 못 돈다. 둘 다 `infra` 레포가 관리한다.

**이미 떠 있으면 건너뛴다:**

```bash
docker ps --format '{{.Names}}\t{{.Ports}}'
```

`gamehouse-db` 와 `gamehouse-rabbitmq` 가 보이면 끝.

**없으면 — Postgres:**

```bash
docker run -d --name gamehouse-db \
  -e POSTGRES_DB=duo -e POSTGRES_USER=duo -e POSTGRES_PASSWORD='아무거나' \
  -p 5432:5432 postgres:16
```

**없으면 — RabbitMQ:**

```bash
cd ~/github/gamehouse/infra
./scripts/local.sh up -d --build rabbitmq
```

공식 `rabbitmq` 이미지를 그냥 쓰면 안 된다. `infra/rabbitmq/Dockerfile` 이
**STOMP 플러그인**을 켜 둔 커스텀 이미지여야 House 채팅이 동작한다.
포트 3개가 열린다 — `5672`(AMQP), `61613`(STOMP), `15672`(관리 UI).

> RabbitMQ 가 없어도 앱은 뜬다. 5초마다 재접속 로그가 쌓이고 **채팅만** 동작하지 않는다.

### 2-4. DB 계정과 스키마 만들기

```bash
./db/init.sh
```

`duo_crew` 계정과 `crew_svc` 스키마를 만든다. `application-secret.yml` 에서
비밀번호를 읽어가므로 값을 따로 넘길 필요가 없다. 몇 번을 돌려도 안전하다.

postgres 가 Docker 안에 있으면 컨테이너 안에서 유닉스 소켓으로 접속하므로
관리 계정 비밀번호가 틀려도 동작한다.

**테이블은 만들지 않는다.** 앱이 처음 뜰 때 Hibernate(`ddl-auto: update`)가 만든다.

---

## 3. 실행

```bash
./gradlew bootRun
```

IntelliJ 를 쓰면 이 폴더를 **별도 프로젝트로** 열고 `CrewApplication` 을 실행한다.
(backend 모노레포와 같은 창에서 열면 Gradle 프로젝트가 충돌한다)

포트 8086 이 이미 쓰이고 있으면 `backend` 레포의 crew 모듈이 떠 있는 것이다. 하나만 띄운다.

---

## 4. 확인

```bash
curl -s localhost:8186/actuator/health
# {"status":"UP"} 이어야 한다

curl -s localhost:8086/api/crew/houses
# [] 또는 하우스 목록
```

상점 상품이 필요하면 backend 레포의 seed 를 한 번 돌린다
(마이그레이션 파일은 이 레포로 옮기지 않았다):

```bash
cd ~/github/gamehouse/backend
docker exec -i gamehouse-db psql -U duo_crew -d duo -v ON_ERROR_STOP=1 \
  < db/migration/V7__seed_profile_frame_catalog.sql
docker exec -i gamehouse-db psql -U duo_crew -d duo -v ON_ERROR_STOP=1 \
  < db/migration/V10__seed_full_customization_catalog.sql
```

---

## 5. 의존하는 것들

| 대상 | 주소 | 없으면 |
|---|---|---|
| PostgreSQL | `localhost:5432` | **부팅 실패** |
| user 서비스 | `localhost:8081` | 앱은 뜨고 **닉네임만 `null`** |
| RabbitMQ AMQP | `localhost:5672` | 앱은 뜨고 이벤트 발행 실패 |
| RabbitMQ STOMP | `localhost:61613` | 앱은 뜨고 **채팅만** 안 됨 |
| GitHub Packages | — | **빌드 실패** |

crew 는 닉네임을 user 서비스의 `/internal/users?ids=` 로 물어본다.
예전에는 `user_svc.users` 를 직접 SELECT 했는데, `duo_crew` 계정에 그 스키마 권한이
없어서 House API 가 전부 500 이었다. **서비스 경계를 넘는 SQL 을 다시 넣지 말 것.**

---

## 6. 자주 나는 에러

| 증상 | 원인 | 해결 |
|---|---|---|
| `Could not find gg.duo:common:0.1.0` | GitHub PAT 없음/위치 틀림 | 2-1. `~/.gradle/gradle.properties` |
| `401 Unauthorized` (Gradle) | fine-grained token | classic token + `read:packages` |
| `password authentication failed for user "duo_crew"` | 계정 없음 **또는** 비밀번호 불일치 | `./db/init.sh` 재실행 |
| `permission denied for schema crew_svc` | 스키마 소유자가 아님 | `./db/init.sh` 재실행 (`AUTHORIZATION` 이 핵심) |
| `WeakKeyException ... 248 bits` | JWT_SECRET 이 짧음 | 32바이트 이상으로 |
| crew API 만 401 | JWT_SECRET 이 다른 서비스와 다름 | 값을 맞춘다 |
| `Port 8086 already in use` | backend 의 crew 모듈이 떠 있음 | 하나만 띄운다 |
| `column ... does not exist` | 기존 DB 스키마가 엔티티보다 낡음 | backend `db/migration/` 의 해당 SQL 실행 |
| `Connection refused ...:61613` | RabbitMQ STOMP 미기동 | 2-3. 앱은 계속 돈다 |

> ⚠️ `ddl-auto: update` 의 DDL 실패는 **ERROR 가 아니라 WARN 으로** 찍히고 부팅이 계속된다.
> `column ... does not exist` 를 만나면 로그 위쪽에서
> `GenerationTarget encountered exception` 을 먼저 찾을 것. 그게 진짜 원인이다.

---

## 7. 빌드와 배포

```bash
./gradlew build          # 테스트 포함
./gradlew bootJar        # 실행 가능한 jar
```

Docker 이미지 — 빌드 컨텍스트는 이 레포 루트다. 토큰은 BuildKit secret mount 로
넘긴다(ARG 로 넘기면 이미지 레이어 히스토리에 평문으로 남는다).

```bash
DOCKER_BUILDKIT=1 docker build \
  --platform linux/amd64 \
  --secret id=gpr,src=$HOME/.gradle/gradle.properties \
  -t gamehouse:crew-develop .
```

`--platform linux/amd64` 는 Apple Silicon 에서 필수다. 빼면 arm64 이미지가 만들어져
배포 대상(amd64)에서 `no matching manifest` 로 실패한다.

CI 는 `.github/workflows/ci-cd.yml` — `feature/*` → `develop` PR 에서 검증,
`develop` 머지에서 이미지 publish.

---

## 관련 레포

| 레포 | 내용 |
|---|---|
| `gamehouse-common` | 공용 모듈(이벤트 계약, 예외, JWT). GitHub Packages 로 배포 |
| `backend` | 모노레포. `db/migration/` 아카이브가 여기 있다 |
| `infra` | compose, RabbitMQ 이미지, k8s 매니페스트 |
| `frontend` | React. `/api/crew` 를 :8086 으로 프록시 |
