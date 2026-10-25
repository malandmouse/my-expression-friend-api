# MyExpressionFriend API

Render 배포 설정은 [배포 가이드](docs/render-deployment.md)와 [.env.example](.env.example)을 참고하세요.
생성은 OpenAI 호환 API, 임베딩은 별도 OpenAI API 설정을 사용하며 파일은 로컬 또는 R2/S3에 저장합니다.

MyExpressionFriend API는 아동의 표현 학습, 대화 학습, 오프라인 미션, 보호자/치료사 리포트 기능을 제공하는 Spring Boot 기반 백엔드 서버입니다. 보호자 앱, 치료사 화면, Unity 학습 클라이언트가 사용할 수 있는 REST API와 인증, 통계, 알림, RAG 기반 생성 기능을 포함합니다.

## 프로젝트 개요

이 서버는 다음 기능을 담당합니다.

- 이메일/비밀번호 기반 회원가입 및 로그인, JWT 인증, refresh token 쿠키 관리
- 보호자, 치료사, 관리자 역할 기반 접근 제어
- 아동 정보 등록, 수정, 삭제 및 보호자/치료사 권한 관리
- 표정 학습 및 대화 학습 결과 저장
- 아동별 홈 화면, 기록, 참여도, 주간 진행도, 통계 조회
- 오프라인 미션 생성, 제출, 검토
- 치료사 리포트 및 AI 리포트 생성/조회
- 알림 및 알림 설정 관리
- 시나리오 데이터 관리, 승인, Unity 클라이언트용 학습 데이터 제공
- OpenAI Realtime client secret 발급
- RAG 문서 색인, 검색, 프롬프트 기반 텍스트 생성 지원

## 기술 스택

- Java 17
- Spring Boot 3.5.12
- Gradle Wrapper
- Spring Security
- Spring Data JPA / JDBC
- PostgreSQL 17
- pgvector
- Spring AI
- Google GenAI Java SDK
- OpenAI Realtime API 연동 설정
- Springdoc OpenAPI / Swagger UI
- Lombok
- Caffeine Cache
- JUnit 5 / Spring Boot Test

## 소스 코드 구조

```text
src/main/java/myexpressionfriend_api
├── admin          관리자용 사용자/테스트 데이터 API
├── auth           회원가입, 로그인, 사용자, refresh token
├── child          아동 정보, 보호자/치료사 권한, PIN 관리
├── common         공통 설정, 예외 처리, 파일 저장, LLM 클라이언트
├── game           표정/대화 학습 결과 저장 및 학습 이력
├── homework       오프라인 미션 생성, 제출, 검토
├── notification   알림, 알림 이벤트, 알림 설정
├── player         게임 플레이어 선택 정보
├── rag            RAG 문서 색인, 검색, 생성 API
├── realtime       OpenAI Realtime client secret 발급
├── report         보호자/치료사용 리포트
├── scenario       대화 시나리오 데이터, 승인, Unity 제공 API
├── security       JWT 필터, 인증 실패/권한 실패 핸들러
└── statistics     홈/기록 화면용 통계, 대화 오류 패턴 분석
```

주요 리소스 파일은 다음과 같습니다.

```text
src/main/resources/application.properties       공통 설정
src/main/resources/application-dev.properties   로컬 개발 설정
src/main/resources/prompts/rag                  RAG/리포트/미션 생성 프롬프트
src/main/resources/data/scenarios               샘플 시나리오 JSON
docker/postgres/init                            PostgreSQL 확장 초기화 SQL
docs                                           추가 SQL 패치 문서
```

## 설치 방법

### 1. 필수 프로그램

다음 프로그램이 설치되어 있어야 합니다.

- JDK 17 이상
- Docker Desktop 또는 Docker Engine
- Git

Gradle은 별도로 설치하지 않아도 됩니다. 저장소에 포함된 Gradle Wrapper(`gradlew`, `gradlew.bat`)를 사용합니다.

### 2. 저장소 클론

```bash
git clone <repository-url>
cd myexpressionfriend-api
```

### 3. PostgreSQL 실행

Docker Compose로 PostgreSQL과 pgvector 환경을 실행합니다.

```bash
docker compose up -d
```

`compose.yaml`은 다음 DB를 생성합니다.

- host port: `5433`
- database: `MyExpressionFriend`
- username: `myuser`
- password: `mypassword`

초기 DB 생성 시 `docker/postgres/init/001_pgvector_extensions.sql`이 실행되어 `vector`, `hstore`, `uuid-ossp` 확장이 생성됩니다. 이미 기존 Docker 볼륨이 만들어진 뒤라면 init SQL이 다시 실행되지 않으므로, 확장이 필요한 경우 DB에서 직접 SQL을 실행하거나 볼륨을 새로 만들어야 합니다.

### 4. 환경 변수 설정

로컬에서 Docker DB를 사용할 때는 아래 환경 변수를 설정합니다.

Windows PowerShell:

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/MyExpressionFriend"
$env:SPRING_DATASOURCE_USERNAME="myuser"
$env:SPRING_DATASOURCE_PASSWORD="mypassword"
$env:JWT_SECRET="change-this-secret-to-a-long-random-value"
```

macOS/Linux:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/MyExpressionFriend"
export SPRING_DATASOURCE_USERNAME="myuser"
export SPRING_DATASOURCE_PASSWORD="mypassword"
export JWT_SECRET="change-this-secret-to-a-long-random-value"
```

선택 환경 변수:

| 변수 | 설명 | 기본값 |
| --- | --- | --- |
| `PORT` | 서버 포트 | `8080` |
| `SPRING_PROFILE` | Spring profile | `dev` |
| `APP_CORS_ALLOWED_ORIGIN_PATTERNS` | CORS 허용 origin | `http://localhost:5173` |
| `APP_STORAGE_BASE_PATH` | 업로드 파일 저장 경로 | `uploads` |
| `LLM_ENABLED` | LLM 기능 사용 여부 | `false` |
| `OPENAI_REALTIME_ENABLED` | OpenAI Realtime 발급 API 사용 여부 | `false` |
| `OPENAI_API_KEY` | OpenAI API key | 없음 |
| `LLM_BASE_URL` | 생성 프로바이더의 OpenAI 호환 API 주소 | `https://api.openai.com/v1` |
| `LLM_API_KEY` | 생성 프로바이더 API key | 없음 |
| `LLM_MODEL_FLASH` / `LLM_MODEL_PRO` | 생성 모델명 | 직접 지정 |
| `SPRING_AI_EMBEDDING_MODEL` | 임베딩 활성화 시 `openai` | `none` |
| `EMBEDDING_BASE_URL` | 임베딩 API 주소 | `https://api.openai.com/v1` |
| `EMBEDDING_API_KEY` | 임베딩 전용 API key | 없음 |
| `EMBEDDING_MODEL` | 임베딩 모델 | `text-embedding-3-small` |
| `SPRING_AI_VECTORSTORE_TYPE` | RAG 활성화 시 `pgvector` | `none` |
| `APP_STORAGE_TYPE` | 파일 저장소 (`local` / `s3`, R2는 `s3`) | `local` |

기본 `dev` 프로파일은 `spring.jpa.hibernate.ddl-auto=update`를 사용하므로, 로컬 개발 환경에서는 애플리케이션 실행 시 JPA 엔티티 기준으로 테이블이 생성/갱신됩니다.

## 실행 방법

Windows:

```powershell
.\gradlew.bat bootRun
```

macOS/Linux:

```bash
./gradlew bootRun
```

서버 실행 후 다음 주소를 사용할 수 있습니다.

- API 서버: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- Health check: `http://localhost:8080/actuator/health`

## 빌드 방법

Windows:

```powershell
.\gradlew.bat clean build
```

macOS/Linux:

```bash
./gradlew clean build
```

빌드 결과 JAR 파일은 `build/libs/` 아래에 생성됩니다.

테스트를 제외하고 실행 가능한 JAR만 생성하려면 다음 명령을 사용할 수 있습니다.

```bash
./gradlew clean bootJar -x test
```

Windows에서는 같은 명령을 `.\gradlew.bat`으로 실행하면 됩니다.

## 테스트 방법

전체 테스트:

```bash
./gradlew test
```

Windows:

```powershell
.\gradlew.bat test
```

테스트 리포트는 다음 경로에서 확인할 수 있습니다.

```text
build/reports/tests/test/index.html
```

일부 통합 테스트나 애플리케이션 컨텍스트 테스트는 PostgreSQL 연결 설정이 필요할 수 있습니다. 테스트 전에 `docker compose up -d`로 DB를 실행하고, 설치 방법의 환경 변수를 동일하게 설정합니다.

## 샘플 데이터

샘플 시나리오 데이터는 다음 위치에 포함되어 있습니다.

```text
src/main/resources/data/scenarios/Seoyeon_batch_fixed.json
```

이 파일은 대화 학습 시나리오 초기 데이터로 사용할 수 있는 JSON 데이터입니다. 애플리케이션의 시나리오 로더 또는 관리자용 시나리오 API를 통해 개발/테스트 환경에서 사용할 수 있습니다.

RAG, 리포트, 오프라인 미션 생성에 사용하는 프롬프트 템플릿은 다음 위치에 있습니다.

```text
src/main/resources/prompts/rag/
```

업로드 파일과 시나리오 export 파일은 기본적으로 다음 경로에 저장됩니다.

```text
uploads/
uploads/exports/scenarios/
```

## 데이터베이스

이 프로젝트는 PostgreSQL을 기본 데이터베이스로 사용합니다. 주요 데이터는 JPA 엔티티로 관리됩니다.

- 사용자, 역할, refresh token
- 아동 프로필 및 보호자/치료사 권한
- 표정 학습 세션과 시도 기록
- 대화 학습 세션과 턴 기록
- 시나리오, 대화 턴, 선택지
- 오프라인 미션과 제출/검토 기록
- 리포트
- 알림 및 알림 설정
- 통계 요약
- RAG 문서 메타데이터 및 vector store

`dev` 프로파일은 로컬 개발 편의를 위해 JPA `ddl-auto=update`를 사용합니다. 기본 프로파일의 공통 설정은 `ddl-auto=validate`이므로, 운영 또는 배포 환경에서는 DB 스키마가 엔티티와 일치해야 합니다.

추가 SQL 문서는 `docs/`에 있습니다.

```text
docs/add-statistics-trend-confidence-columns.sql
docs/fix-dialogue-offline-outcome-counts.sql
```

## 사용한 오픈소스

이 프로젝트의 주요 오픈소스 및 외부 라이브러리는 다음과 같습니다.

| 이름 | 용도 |
| --- | --- |
| Spring Boot | 웹 서버, 설정, 의존성 관리 |
| Spring Security | 인증/인가 |
| Spring Data JPA | ORM 및 repository |
| PostgreSQL JDBC Driver | PostgreSQL 연결 |
| pgvector | vector embedding 저장 및 검색 |
| Spring AI | OpenAI embedding, pgvector vector store 연동 |
| AWS SDK for Java v2 | R2/S3 파일 업로드·삭제·서명 URL |
| Springdoc OpenAPI | Swagger UI 및 OpenAPI 문서 |
| JJWT | JWT 생성 및 검증 |
| Lombok | DTO/엔티티 보일러플레이트 감소 |
| Caffeine | 로컬 캐시 |
| Apache PDFBox | PDF 문서 처리 |
| Google GenAI Java SDK | Gemini 계열 LLM 호출 |
| JUnit 5 / Spring Boot Test | 테스트 |

## 재현 절차 요약

저장소를 clone한 뒤 서버를 재생성하는 최소 절차는 다음과 같습니다.

```bash
git clone <repository-url>
cd myexpressionfriend-api
docker compose up -d
```

Windows PowerShell:

```powershell
$env:SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/MyExpressionFriend"
$env:SPRING_DATASOURCE_USERNAME="myuser"
$env:SPRING_DATASOURCE_PASSWORD="mypassword"
$env:JWT_SECRET="change-this-secret-to-a-long-random-value"
.\gradlew.bat clean build
.\gradlew.bat bootRun
```

macOS/Linux:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5433/MyExpressionFriend"
export SPRING_DATASOURCE_USERNAME="myuser"
export SPRING_DATASOURCE_PASSWORD="mypassword"
export JWT_SECRET="change-this-secret-to-a-long-random-value"
./gradlew clean build
./gradlew bootRun
```

실행 후 `http://localhost:8080/swagger-ui.html`에서 API 목록을 확인할 수 있습니다.
