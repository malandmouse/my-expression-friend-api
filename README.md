# 표정친구 백엔드

Java 21 · Spring Boot 4.1.2 · PostgreSQL · Flyway · Spring Security · Swagger.

현재 공통 오류·검증·인증 연결 구조·멱등 처리와 Swagger를 구현했다. 로그인 및 아동·학습 기능은 후속 구현 대상이다. 실제 자격 검증기를 연결하기 전에는 보호 API가 접근을 거부한다.

## 소스 구성

- `src/main`: 서버 코드, 설정, 마이그레이션, 오류 계약.
- `src/test`, `src/integrationTest`: 회귀 테스트. 테스트 소스는 Git에 보존하고 Docker 빌드에는 포함하지 않는다.
- `gradle`, `gradlew*`, `build.gradle`, `settings.gradle`: 재현 가능한 빌드 입력.
- `Dockerfile`, `.dockerignore`: Render 빌드 및 실행 이미지 구성.

로컬 `docs/`, `tools/`, `build/`, IDE 설정·키·로그는 Git 업로드 대상에서 제외한다. 루트 허용 목록을 쓰므로 새 최상위 빌드 파일이 필요해지면 `.gitignore`에도 추가한다.

## 실행 환경 변수

| 변수 | 값 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://HOST:5432/DATABASE` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | 해당 사용자 비밀번호 |
| `EF_IDEMPOTENCY_FINGERPRINT_KEY` | 무작위 32바이트의 Base64 문자열 |
| `EF_IDEMPOTENCY_ENCRYPTION_KEY` | 위 키와 다른 무작위 32바이트의 Base64 문자열 |
| `EF_API_DOCS_ENABLED` | Swagger 활성화 여부. 기본 `true` |
| `PORT` | HTTP 포트. 미지정 시 `8080` |

DB URL에는 `jdbc:` 접두사가 필요하다. PostgreSQL용 연결 URI를 그대로 JDBC URL에 넣지 않는다. 비밀번호와 키는 Git·Dockerfile에 쓰지 않고 실행 환경에 공급한다. 두 멱등 처리 키는 한 번 생성한 뒤 재배포에도 유지한다.

로컬에서는 `EF_DB_URL`, `EF_DB_USERNAME`, `EF_DB_PASSWORD` 별칭도 지원한다. 같은 항목의 Spring 표준 환경 변수(`SPRING_DATASOURCE_*`)가 있으면 그 값이 우선한다. `.env.example`은 변수 안내이며 Spring Boot는 `.env`를 자동으로 읽지 않는다.

## 로컬 빌드 및 테스트

Java 21 이상으로 Gradle을 실행한다. Windows에서는 `gradlew.bat`을 사용한다.

```sh
./gradlew test bootJar
./gradlew bootRun
```

DB 없이 실행할 수 있는 단위·웹 테스트와 별도로 실제 PostgreSQL 통합 테스트를 제공한다. loopback의 별도 `foundation_test` DB와 `foundation_test` 사용자를 준비하고 다음을 실행한다. 통합 테스트는 해당 DB의 합성 테스트 데이터를 초기화한다.

```sh
EF_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/foundation_test ./gradlew integrationTest
```

테스트 DB에 비밀번호가 있다면 `EF_TEST_DB_PASSWORD`도 지정한다. 개발·운영용 `emotion_friend` DB를 통합 테스트에 사용하지 않는다.

## Render 배포 설정

Render에서 GitHub 저장소를 연결하고 Web Service를 만든다. 실제 서비스 생성·배포는 별도로 수행한다.

| 항목 | 설정 |
| --- | --- |
| Language / Runtime | Docker |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context | 저장소 루트 |
| Health Check Path | `/actuator/health` |
| Environment | 위 DB 연결 정보 및 멱등 처리 키 |

Render PostgreSQL의 host/database/user/password를 환경 변수로 공급한다. Render에서 `localhost`는 개발 PC의 DB를 가리키지 않는다. 같은 Render 네트워크의 DB를 사용한다면 해당 DB의 내부 host로 JDBC URL을 구성한다.

서버는 Render의 `PORT`와 `0.0.0.0` 바인딩을 사용한다. Docker 빌드 stage에는 JDK·Gradle과 서버 소스만 전달하고, 최종 image에는 Java 21 JRE와 실행 jar만 남긴다. 테스트·로컬 문서·캐시·환경 변수 파일은 image에 복사하지 않는다.

배포 후 확인할 경로:

- `/actuator/health`: 서버·DB 상태. 정상 시 HTTP 200, `status=UP`.
- `/swagger-ui/index.html`: Swagger UI (`EF_API_DOCS_ENABLED=true`일 때).
- `/v3/api-docs`: 실행 서버의 OpenAPI JSON.
- `/api/v1/children`: 인증 없이 요청하면 HTTP 401. 현재 아동 API 구현 완료를 뜻하지 않는다.

참고: [Render Docker 배포](https://render.com/docs/docker), [Render 포트·바인딩](https://render.com/docs/troubleshooting-deploys).
