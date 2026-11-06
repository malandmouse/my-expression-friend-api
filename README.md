# 표정친구 백엔드

Java 21 · Spring Boot 4.1.2 · PostgreSQL · Flyway · Spring Security · Swagger.

공통 기반·인증과 회원 승인·역할 관리, 아동 등록·프로필·치료사 연결·목적별 동의, 생성 콘텐츠 요약 검토와 학습 세션·완료·별·receipt·기록 관리를 구현했다. Core 콘텐츠의 불변 의존성 검증·승인·발행 증거와 아동 기기 인증·checkpoint·lease·여러 Day 실행 adapter도 포함한다. 운영 콘텐츠 권한과 사용 허가·미디어 제공은 기본 차단이며 합성 격리 검증 범위다. 신규 회원은 PENDING이며 이메일 확인 후 운영자가 신청 역할을 승인한다. 이메일 확인 메일은 설정 활성화 시 Resend HTTPS API로 전송한다. 치료사 초대·회원 변경 알림의 외부 전송은 후속 작업이다. 추가된 V2.1 실제 생성기·Typecast·iPad 연결과 잔여 인수 범위는 [프로토타입 연결 안내](docs/PROTOTYPE_CONNECTIONS.md)를 따른다. 최신 Dockerfile은 Java·Worker·Python을 포함하며 [배포 구성](docs/DEPLOY_PROTOTYPE.md)의 환경을 설정해야 한다. 실제 이미지 빌드/배포는 아직 별도 확인이다.

## 소스 구성

- `src/main`: 서버 코드, 설정, 마이그레이션, 오류 계약.
- `src/test`, `src/integrationTest`: 회귀 테스트. 테스트 소스는 Git에 보존하고 Docker 빌드에는 포함하지 않는다.
- `gradle`, `gradlew*`, `build.gradle`, `settings.gradle`: 재현 가능한 빌드 입력.
- `Dockerfile`, `.dockerignore`: Render 빌드 및 실행 이미지 구성.

배포 안내와 API 계약 문서 5개는 `docs/`에 포함한다. 나머지 로컬 문서와 `tools/`, `build/`, IDE 설정·키·로그는 Git 업로드 대상에서 제외한다. 루트 허용 목록을 쓰므로 새 최상위 빌드 파일이 필요해지면 `.gitignore`에도 추가한다.

## 실행 환경 변수

| 변수 | 값 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://HOST:5432/DATABASE` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL 사용자 |
| `SPRING_DATASOURCE_PASSWORD` | 해당 사용자 비밀번호 |
| `EF_IDEMPOTENCY_FINGERPRINT_KEY` | 무작위 32바이트의 Base64 문자열 |
| `EF_IDEMPOTENCY_ENCRYPTION_KEY` | 위 키와 다른 무작위 32바이트의 Base64 문자열 |
| `EF_JWT_SIGNING_KEY` | 위 두 키와 다른 무작위 32바이트의 Base64 문자열. JWT HS256 서명용 |
| `EF_API_DOCS_ENABLED` | Swagger 활성화 여부. 기본 `true` |
| `EF_BOOTSTRAP_ADMIN_ENABLED` | 최초 운영자 생성 시에만 `true`. 기본 `false` |
| `EF_BOOTSTRAP_ADMIN_EMAIL` | 최초 운영자 이메일. 공개 가입 계정과 겹치지 않는 주소 |
| `EF_BOOTSTRAP_ADMIN_PASSWORD` | 최초 운영자 비밀번호, 15~128자 |
| `EF_MAIL_ENABLED` | 인증 메일 전송 활성화. 기본 `false` |
| `EF_RESEND_API_KEY` | Resend에서 발급한 발송용 API 키. 백엔드에만 저장 |
| `EF_MAIL_FROM` | 인증된 발신 이메일. 예: `no-reply@notify.example.com` |
| `EF_FRONTEND_BASE_URL` | 리액트 웹 기본 주소. HTTPS 또는 개발용 loopback HTTP |
| `PORT` | HTTP 포트. 미지정 시 `8080` |

DB URL에는 `jdbc:` 접두사가 필요하다. PostgreSQL용 연결 URI를 그대로 JDBC URL에 넣지 않는다. 비밀번호와 키는 Git·Dockerfile에 쓰지 않고 실행 환경에 공급한다. 세 키는 각각 한 번 생성한 뒤 재배포에도 유지한다. 새 JWT 키가 없거나 멱등 키와 같으면 서버가 시작되지 않는다.

Windows PowerShell 5.1에서도 다음 명령으로 JWT 키 하나를 생성할 수 있다. 터미널에서 설정한 변수는 IntelliJ Run 설정에 자동으로 전달되지 않으므로 IntelliJ 실행 시 해당 설정에 저장한다.

```powershell
$jwtBytes = New-Object byte[] 32
$jwtRng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $jwtRng.GetBytes($jwtBytes); $env:EF_JWT_SIGNING_KEY = [Convert]::ToBase64String($jwtBytes) }
finally { $jwtRng.Dispose() }
```

## 회원가입·로그인 사용

Swagger에서 다음 순서로 확인한다. 응답은 envelope 없이 직접 반환하며, 회원·토큰 응답에는 `Cache-Control: no-store`를 적용한다.

1. `POST /api/v1/auth/signup`: 아래 본문으로 신청한다. 성공은 201, 승인 역할은 null, 상태는 PENDING이다. 공개 가입은 GUARDIAN/THERAPIST만 가능하다.
2. `POST /api/v1/auth/login`: `{"loginId":"guardian@example.com","password":"a sufficiently long passphrase"}`로 로그인한다.
3. 응답의 `credential.accessToken`을 Swagger Authorize의 adultBearer에 입력한다. `Bearer ` 접두사는 입력하지 않는다.
4. `GET /api/v1/me`: 본인 정보·신청 역할·승인 상태를 확인한다. PENDING/REJECTED는 이 조회·이메일 확인·로그아웃만 허용하며, 업무 API는 403 ACCOUNT_NOT_ACTIVE다.
5. `POST /api/v1/auth/refresh`: Bearer를 해제하고 `{"refreshToken":"현재 값"}`을 보낸다. 응답의 액세스·리프레시 토큰을 모두 교체한다.
6. `POST /api/v1/auth/logout`: 새 adultBearer와 같은 로그인 세션의 refreshToken 본문을 보낸다. 성공은 본문 없는 204이며 해당 세션의 모든 토큰이 무효화된다. 다른 기기의 로그인 세션은 유지한다.

```json
{
  "email": "guardian@example.com",
  "displayName": "보호자",
  "password": "a sufficiently long passphrase",
  "requestedRole": "GUARDIAN"
}
```

비밀번호는 15~128자이며 PBKDF2-HMAC-SHA256(600,000회, 무작위 salt) 해시로 저장한다. 이메일은 대소문자를 구분하지 않는다. 이메일 중복은 409 ACCOUNT_ALREADY_EXISTS, 유효하지 않은 본문은 422 INVALID_SCHEMA, 로그인 실패는 401 AUTH_REQUIRED다. 가입 직후 emailVerified=false이며 이메일 확인 API로 검증한다.

액세스 JWT는 10분, 로그인 세션은 로그인 시점부터 최대 30일이다. refresh를 사용해도 세션 종료 시각은 연장되지 않는다. 만료된 액세스 토큰은 refresh 본문만으로 재발급할 수 있다. refresh는 일회용이며 이미 소비된 값을 재사용하면 해당 세션 전체를 폐기한다. 클라이언트는 refresh 요청을 직렬화하고 자동 중복 재시도를 하지 않는다. 갱신 응답을 잃었다면 다시 로그인한다. 이 인증 API는 기존 Core 스냅샷의 Idempotency-Key 재생 계약을 적용하지 않는다.

현재 계정 상태·세션 폐기 여부는 매 요청 DB에서 확인한다. SUSPENDED는 로그인·갱신·기존 JWT 접근이 차단된다. 아동 접근은 현재 보호자·치료사 관계와 목적별 동의를 확인한다. 아래 이메일 확인·회원 승인 흐름을 사용한다.

## 회원 승인·역할 관리

최초 운영자는 공개 가입할 수 없다. 처음 한 번 실행 환경에 `EF_BOOTSTRAP_ADMIN_ENABLED=true`, 운영자 이메일·비밀번호를 공급하고 서버를 시작한다. 이미 공개 가입한 이메일은 운영자로 승격하지 않는다. 다른 운영자가 존재하면 새 운영자를 추가하지 않는다. 같은 이메일로 재실행해도 비밀번호를 덮어쓰지 않는다. 생성이 끝나면 enabled를 false로 바꾸고 bootstrap 비밀번호를 실행 설정에서 제거한다. 이후 일반 로그인 API를 사용한다.

1. 가입자가 로그인 후 `POST /api/v1/auth/email-verifications`로 확인을 요청한다. 202 응답이며 본문에 secret을 반환하지 않는다.
2. 메일 설정을 활성화하면 발송 작업이 Resend API로 인증 링크를 전송한다. 링크는 리액트의 `/#/verify-email` 화면으로 연결되며 같은 가입 계정으로 로그인한 뒤 확인 버튼을 누른다. 화면이 `verificationId`와 `token`을 본인의 `POST /api/v1/auth/email-verifications/confirm`에 제출한다. 토큰은 요청 시점부터 15분·일회용이며 재요청 시 이전 토큰이 무효화된다. **가짜 Resend 서버·실제 PostgreSQL과 브라우저로 검증했으며, 실제 Resend 계정 발송 및 받은 편지함 도달은 아직 검증하지 않았다.**
3. 운영자가 `GET /api/v1/admin/members?status=PENDING&limit=20`으로 신청을 조회한다.
4. 운영자가 `POST /api/v1/auth/reauthenticate`에 `{"password":"본인 비밀번호"}`를 보내 재인증한다. 현재 세션에서 5분 유효하며 응답은 `verificationRef`, `expiresAt`이다.
5. `POST /api/v1/admin/members/{memberId}/approve`에 `Idempotency-Key`와 `{"reason":"검토 사유","reviewReference":"검토 근거 식별자"}`를 보낸다. 신청한 GUARDIAN/THERAPIST 역할로 승인되며 해당 회원은 다시 로그인해야 한다.

같은 방식으로 `/reject`, `/suspend`, `/role`을 제공한다. 역할 변경 본문에는 `role`(GUARDIAN/THERAPIST)도 필요하다. 운영자 본인·다른 운영자를 이 API로 변경할 수 없다. 정지·역할 변경은 기존 세션·미완료 초대·치료사 연결을 철회한다. 관리 중인 아동이 있는 보호자는 역할을 바꿀 수 없다. 정지 계정의 재승인은 기존 아동 소유 관계를 유지하지만 치료사 연결은 자동 복구하지 않는다. 모든 변경은 검토 사유와 전후 상태를 감사 기록에 남긴다.

## 아동 등록·치료사 연결·동의

아동 관련 쓰기 API에는 새 작업마다 고유한 `Idempotency-Key`를 보낸다. 동일 요청 재시도에는 같은 키를 사용한다. 조회·재생 시에도 현재 권한을 검사하므로 철회·정지·삭제 후에는 예전 성공 응답을 다시 얻을 수 없다.

승인된 보호자가 `POST /api/v1/children`에 다음을 보내 등록한다. 관리 보호자는 서버가 로그인 계정으로 지정한다. 이름·생년월일로 기존 아동을 검색해 가져오는 기능은 없다. 보호 관계 확인 값은 보호자의 진술이며 실물 관계를 자동 검증한 증거는 아니다.

```json
{
  "schemaVersion": "0.3.0",
  "pseudonym": "아동 별명",
  "guardianRelationshipConfirmed": true,
  "profile": {
    "schemaVersion": "0.3.0",
    "gender": "BOY",
    "ageBand": "TARGET_8_TO_13",
    "primaryLanguage": "ko",
    "receptiveSupport": "UNKNOWN",
    "recognitionSupport": "UNKNOWN",
    "expressiveSupport": "UNKNOWN",
    "communicationModes": ["UNKNOWN"],
    "supportPreferences": ["UNKNOWN"]
  }
}
```

201 응답에는 `childId`, `profileVersion`, `createdAt`과 프로필 필드가 있다. 목록은 `GET /api/v1/children?limit=20`, 프로필은 `GET /api/v1/children/{childId}/profile`이다. 프로필 수정은 `PUT`에 `expectedProfileVersion`, `pseudonym`, `profile`을 보내며 충돌 시 409다. 등록·수정은 각 버전의 감사 메타데이터를 기록한다. 이전 프로필 전문의 버전 보관은 제공하지 않는다.

목적별 동의는 기본 거부다. 관리 보호자가 재인증 후 `GET /api/v1/consent-notices`에서 현재 안내 버전을 확인하고 다음 본문을 `POST /api/v1/children/{childId}/consents`에 보낸다. `guardianVerificationRef`는 같은 로그인 세션에서 방금 받은 `verificationRef`다. 조회는 같은 경로의 GET이다. 안내의 purpose 문구는 기술적인 처리 목적 요약이며 별도의 전체 동의 안내·보관 정책과 함께 게시해야 한다.

```json
{
  "schemaVersion": "0.3.0",
  "scope": "CORE_PROFILE_LEARNING",
  "granted": true,
  "noticeVersion": "2026-10-31.v1",
  "guardianVerificationRef": "재인증 응답의 UUID"
}
```

`SPEECH_STT`, `CARD_EXTERNAL_PROCESSING`, `CAMERA_LOCAL`, `OPTIONAL_RESEARCH_OR_MODEL_TRAINING`, `LOCAL_DRAFT_RESUME`는 각각 별도로 선택한다. 기본 프로필·학습 동의로 나머지 동의를 대신하지 않는다. 철회는 같은 요청에서 granted=false로 보낸다. 기본 프로필·학습 동의 철회는 치료사 연결과 미완료 초대를 함께 철회하며 재동의만으로 연결을 복구하지 않는다. 향후 STT·AI·기기 기능은 자신의 처리 목적 동의를 같은 트랜잭션에서 검사해야 한다.

치료사 연결 순서:

1. 보호자 재인증 및 기본 프로필·학습 동의 후 `POST /api/v1/children/{childId}/therapist-invitations`에 `{"therapistEmail":"therapist@example.com"}`을 보낸다. 202는 접수이며 연결 완료가 아니다.
2. 보호자는 같은 경로 GET으로 요청 목록을 확인하고, 특정 요청은 `DELETE /api/v1/children/{childId}/therapist-invitations/{invitationId}`로 취소한다. 가입 여부와 관계없이 동일한 요청 기록을 제공하며 적격 계정에만 알림을 생성한다. 이후 가입·승인된 계정에 자동 연결하지 않는다. 필요한 경우 취소 후 다시 초대한다.
3. 승인된 치료사는 `GET /api/v1/me/therapist-invitations`로 본인 초대를 확인한다. 수락 전에는 보호자 표시명·마스킹 연락처·요청 권한만 제공하고 아동 프로필은 공개하지 않는다.
4. 치료사가 기존 오프라인 연락 경로에서 초대 식별자와 대상 아동을 보호자와 대조한다. 재인증 후 `POST /api/v1/therapist-invitations/{invitationId}/accept`에 `{"treatmentRelationshipConfirmed":true}`를 보낸다. 거절은 같은 경로의 `/decline`이다. 초대는 24시간 만료다.
5. 수락 후 치료사에게 해당 아동의 VIEW_PROFILE/VIEW_SUMMARY만 부여한다. 보호자는 `GET /api/v1/children/{childId}/therapists`로 연결 목록을 본다. `DELETE /api/v1/children/{childId}/therapists/{therapistId}`는 관리 보호자 또는 해당 치료사 본인이 호출할 수 있다.

초대 ID는 비밀 토큰이 아니다. 로그인한 대상 계정과 결속된 앱 내 수락을 사용한다. 이메일 알림 링크나 ID만으로 권한을 얻을 수 없다. 치료사는 프로필 수정·동의 변경·기기 관리·다른 치료사 추가·전체 콘텐츠 발행을 할 수 없다. 운영자도 아동 프로필을 상시 열람할 수 없다.

`DELETE /api/v1/children/{childId}`는 재인증한 관리 보호자가 호출하며 아동 비활성화·연결 철회를 수행한다. 물리 삭제나 백업·감사 데이터의 삭제를 의미하지 않는다. 목록에서 제외되고 기존 프로필·멱등 재생 접근도 차단된다. 삭제·보관 기한에 따른 정리 작업은 별도 운영 범위다.

아동 목록과 운영자 회원 목록은 UUID cursor 및 limit(1~100)을 사용한다. 초대·연결 목록은 최근 또는 정렬된 최대 100건이다. 초대 제한은 계정 30회/일, 아동 20회/일, 대상 이메일 20회/일, peer IP 100회/일이다. 권한을 확인한 뒤 제한을 적용하고 실패 응답에도 횟수를 유지한다. 관계 작업은 초기 구현에서 PostgreSQL 공통 트랜잭션 잠금으로 직렬화한다. 처리량이 커지면 같은 보안 불변 조건을 유지하면서 자원별 잠금으로 분리해야 한다.

인증 요청 제한은 PostgreSQL에 저장하며 실패 응답에도 유지한다: 가입 IP당 10회/시간, 로그인 IP당 100회 및 계정당 20회/15분, 갱신 IP당 120회/15분. 초과 시 429다. IP는 서버의 실제 peer 주소를 사용하고 임의 X-Forwarded-For를 신뢰하지 않는다. 프록시 배포 시 사용자 IP 전달 정책에 맞춰 제한을 검토해야 한다. 만료 세션·소비 토큰·rate bucket의 정기 삭제 작업은 후속 운영 작업이다. 재사용 탐지를 위해 세션 만료 전 소비 토큰 해시는 삭제하지 않는다.

로컬에서는 `EF_DB_URL`, `EF_DB_USERNAME`, `EF_DB_PASSWORD` 별칭도 지원한다. 같은 항목의 Spring 표준 환경 변수(`SPRING_DATASOURCE_*`)가 있으면 그 값이 우선한다. `.env.example`은 변수 안내이며 Spring Boot는 `.env`를 자동으로 읽지 않는다.

## 콘텐츠 검토·학습 세션·결과·기록

관리 보호자와 연결된 치료사는 해당 아동의 기본 프로필·학습 동의가 유효할 때 주제·요약과 학습 기록을 조회한다. ADMIN은 아동 자료를 상시 조회할 수 없다. 모든 응답은 직접 DTO와 `Cache-Control: no-store`를 사용한다.

| 작업 | 경로 |
| --- | --- |
| 주제·요약 | `GET /api/v1/children/{childId}/learning-material-summaries` |
| 확인·보류 | `POST /api/v1/children/{childId}/learning-material-confirmations` |
| 세션 목록·시작 | `GET / POST /api/v1/children/{childId}/learning-sessions` |
| 세션 완료·별 상태 | `GET /api/v1/learning-sessions/{sessionId}` |
| 입력 확정 | `POST /api/v1/learning-sessions/{sessionId}/slots/{slot}/confirmed-expressions` |
| 완료·별·receipt | `POST /api/v1/learning-sessions/{sessionId}/slots/{slot}/finalize` |
| receipt 복구 | `GET /api/v1/learning-sessions/{sessionId}/slots/{slot}/receipt` |
| 결과·기록 | `GET /api/v1/children/{childId}/learning-results` 또는 `learning-records` |
| 기간 집계 | `GET /api/v1/children/{childId}/learning-summary` |

목록은 `limit` 1~100(기본 20)과 UUID `cursor`를 사용하고 `childId/items/nextCursor`를 반환한다. 주제·요약은 기본 미확인·보류 목록이며 `status=CONFIRMED`로 확인 완료 항목을 조회한다. 세션은 `status=ACTIVE` 또는 `COMPLETED`로 필터링한다. 기간 집계는 UTC `from` 포함·`to` 제외, 최대 366일이며 기본 최근 30일이다. 수집하지 않은 인식·도움 지표는 빈 배열과 limitations로 표시한다.

연결된 보호자·치료사는 `Idempotency-Key`와 다음 본문으로 1~5개 요약을 함께 검토한다. 중복 ID·다른 아동의 ID·폐기된 버전은 거부하고 전체 선택을 원자 처리한다. HOLD 후 재확인이 가능하다. 확인된 항목은 기본 대기 목록에서 숨기며 상태는 CONFIRMED로 유지한다.

```json
{"summaryIds":["요약 UUID"],"decision":"CONFIRM"}
```

보류는 `decision=HOLD`다. 이 검토는 주제·요약에 대한 확인이며 전체 원고·전 분기·미디어 검토나 발행 승인을 대신하지 않는다.

생성/발행 시스템은 서버 내부 `LearningCatalog.storeReviewed`와 `publish` port에 연결한다. 별도 독립 검수 근거, 불변 material/scenario 버전, 검증된 경로와 fictional effects, 정확히 5개의 확인된 자료, 발행 근거와 IANA timezone을 공급해야 한다. 이 port는 공개 REST API가 아니며 현재 실제 생성기·발행/미디어 시스템과 연결되지 않았다. 일반 클라이언트가 본문에 원고나 별 수를 보내 자료를 등록·발행·보상받는 경로는 없다. 준비되지 않은 운영 DB에서는 시작 요청이 `409 MATERIAL_NOT_READY`다.

`LearningService`는 기존 성인 인증·현재 아동 접근·멱등 재생을 담당하고, `LearningLedger`는 시작·입력 확정·완료·별·허구 효과·receipt의 단일 실행 경계를 담당한다. ledger는 호출자의 트랜잭션을 필수로 사용하며 별도의 완료 장부나 아동 registry를 만들지 않는다. 성인 REST 경로·DTO·오류·201 시작/200 복원·receipt 스냅샷은 유지한다. 새 runtime adapter도 같은 ledger에 연결한다.

새 Core 전체 원고는 내부 `CoreManuscriptStore.store(childId, Draft)`로 저장한다. Core 0.3.0 `ScenarioVersion`의 고정 JSON Schema와 그래프(순환·미도달·잘못된 참조·종단별 감정/근거)를 검증하고, 원천 문자열 material/scenario ID와 서버 UUID를 명시적으로 대응시킨다. `CORE_0_3`과 생성용 `GENERATED_1_TO_3` 검증 프로필은 별도이며 기존 `storeReviewed`의 3~8회 선택 계약을 완화하지 않는다. 내부 호출자는 현재 접근을 검사하는 신뢰된 adapter여야 하며 이 port를 클라이언트나 AI 서버에 공개하지 않는다.

V6는 기존 자료·계획·세션·보상·receipt·암호화된 멱등 응답을 보존하고 전체 원고와 검증 증거를 추가한다. 정규화 JSON의 SHA-256, 원천 contentHash, 검증 버전/프로필을 각각 보존한다. 원천 contentHash는 의존 자산까지 검증한 발행 hash로 인정하지 않는다. 저장된 원고와 증거는 DB에서도 수정·삭제를 차단하며, 같은 버전의 다른 본문·다른 아동 결속은 거절한다. 저장·요약 projection·검증 증거는 한 트랜잭션이다.

기존 요약 조회·확인/보류 API에서 Core 원고의 주제·요약을 검토할 수 있다. V6 검증 증거의 `structuralStatus=PASS`는 구조 검사만 의미하며 `dependenciesStatus=NOT_CHECKED`, `publicationStatus=NOT_APPROVED`를 유지한다. 이 report를 수정하지 않고 별도 의존성 검증·전체 검수·사용 허가·발행 증거를 추가한다. 확인만 된 Core 초안은 기존 legacy 발행 port와 실행 gate에서 계속 차단된다.

### Core 승인·발행 기반과 아동 runtime

`CorePublicationService`의 신뢰된 내부 artifact/validation port는 정책·persona·catalog의 고정 schema, 실제 bytes hash, source envelope와 recipe/cue/asset 결속을 검증한다. 전체 검수·아동 사용 허가·발행은 각각 별도 불변 증거이며 manifest hash·현재 동의 revision에 결속한다. V7/V8은 기존 V6 원고/report와 receipt·보상·암호화 replay를 재작성하지 않는다. 기존 데이터의 event/reward 불일치는 자동 보정하지 않고 migration을 차단한다.

전체 검수/발행은 별도 `ContentAuthority` adapter가 필요한 `/api/v1/core/validations/{validationId}/full-review`, `/publish`에서 처리한다. `/child-use`는 현재 관리 보호자의 명시 허가와 최근 재인증이 필요하다. `/api/v1/children/{childId}/core-plans`는 정확히 5개의 발행 버전을 결속한다. 모든 경로는 현재 권한·동의·요약·철회·hash를 검사한다. ADMIN/치료사/보호자 역할만으로 전체 검수·발행 권한을 얻지 않는다.

운영 기본값은 **전체 검수/발행 capability 전부 거절, `emotion-friend.core.child-use-enabled=false`, `emotion-friend.core.lease-seconds=0`, 미디어 provider 거절**이다. 합성 권한과 byte provider는 통합 테스트에만 존재한다. 실제 검수자·발행자/권한 운영, 보호자 허가 화면·고지, 미디어 제공자·키·기간·보관 정책은 사용자 결정 전까지 미정으로 둔다.

현재 관리 보호자는 최근 재인증 후 `POST /api/v1/children/{childId}/device-grants`로 5분·일회용 연결 코드를 발급한다. `POST /api/v1/child-sessions/exchange`는 device에 결속된 10분 runtime credential로 교환한다. `/persist`·`/refresh`는 30일 고정 만료의 회전 secret을 사용하며 hash만 저장한다(재시도 응답은 기존 foundation replay에서 암호화). 재사용 탐지·기기 해제·동의 revision/보호자 상태 변경은 generation을 증가시켜 credential·미완료 grant·lease를 차단한다. 성인 JWT와 runtime 인증 chain은 분리한다.

아동은 `GET /api/v1/children/{childId}/learning-home`, `POST .../mission-entries`, `POST /api/v1/daily-plans/{planId}/sessions`, `GET /api/v1/sessions/{sessionId}`, `PUT .../checkpoint`를 사용한다. 시작은 불변 plan/publication pin을 만들고 재접속은 같은 pin/checkpoint를 복구한다. checkpoint의 path/draft/attempt/revision/hash를 검사하며 확정 발화 원문은 영속화하지 않는다. proof 만료·서버 재시작 뒤에는 재확정이 필요하다.

Day 1은 첫 시작 날짜를 고정한다. 다음 Day는 더 뒤 현지 날짜의 새 명시 진입과 바로 다음 계획 준비가 필요하다. GET·refresh·자정·같은 event 재생으로 해금하지 않는다. Day당 순서대로 5별이며 자정 뒤 신규 정규 완료는 차단한다. `child_world`가 누적 revision 정본이고 과거 receipt는 수락 당시 값을 유지한다. runtime 완료도 기존 ledger의 receipt·별·effects·checkpoint·replay 트랜잭션을 사용한다.

슬롯 콘텐츠는 `GET /api/v1/sessions/{sessionId}/slots/{slot}/content`, 개별 asset lease는 `POST .../playback-leases` 및 `POST /api/v1/playback-leases/{leaseId}/renew`로 공급한다. 실제 bytes는 runtime Bearer와 `X-Playback-Lease` 헤더를 요구하며 기기/generation/session/pin/asset/hash·현재 승인·최대30초 TTL을 검사한다. provider 호출 동안 DB 잠금을 유지하지 않고 I/O 전후 재검사한다. 기본 미설정 상태에서는 실제 공급이 차단된다.

새 runtime은 Core 공용 DTO 전체와 동일하지 않다. 확정 원문 snapshot을 받지 않는 proof 기반 Commit/Checkpoint, 기존 ledger Receipt, 고정 5슬롯 계획·개별 asset lease adapter다. 공유 생성 DTO와 기존 성인 React parser는 변경하지 않았다. 상세 경로/DTO/기간·차이·검증 명령은 로컬 `docs/backend/CORE_MIGRATION_PHASE2_3.md` 및 `CORE_MIGRATION_PHASE2_3_VERIFICATION.json`을 따른다. 실제 AI·사용자 발행·미디어 품질/기기·Unity·운영 DB 변경·배포는 검증 범위 밖이다.

현재 실행 adapter는 관리 보호자의 성인 인증을 사용하는 **Day 1·직접 입력·ORIGINAL** 범위다. 치료사는 실행·입력·완료 요청을 할 수 없다. 아동 기기 credential, 학습 재생 화면·콘텐츠/lease·캐릭터 반응, STT·카드·복습·Day 2는 별도다. 이 API를 실기기 학습 완료로 해석하지 않는다.

1. 신뢰된 발행 연결 후 `POST .../learning-sessions`에 `{"schemaVersion":"0.3.0","deviceId":"device-1"}`과 멱등 키를 보낸다. 신규 201, 동일 기기의 기존 세션 복원 200이다. 첫 시작 시 서버의 현지 날짜를 고정한다.
2. 다음 슬롯의 `confirmed-expressions`에 `{"text":"합성 직접 입력","draftVersion":1,"inputSource":"TYPED","childConfirmed":true}`를 보내 15분 유효 참조를 받는다. 원문은 즉시 버리며 DB·로그·멱등 응답에 저장하지 않는다. 재시작·다른 인스턴스·참조 만료 시 다시 확정해야 한다.
3. `finalize`는 확정 참조·draftVersion·selectedChoiceIds·terminalSceneId·selectedEmotionId·expressionChoice=ORIGINAL·expectedSessionRevision·expectedWorldRevision·clientCommitId를 받는다. 서버의 고정 경로·revision을 검사하고 완료/별/fictional story event/receipt/멱등 응답을 함께 커밋한다.
4. 슬롯당 최초 완료에 별 1개, 최대 5개다. 슬롯은 순서대로 완료한다. 해금 날짜 종료 후 신규 완료는 `REWARD_WINDOW_EXPIRED`, `nextAction=START_REVIEW`로 거절한다. 기존 receipt 복구는 가능하다.

같은 key/body는 최초 성공 응답을 재생한다. 같은 슬롯의 같은 완료 본문은 다른 키로 다시 보내도 기존 receipt를 반환하고, 다른 본문은 충돌한다. receipt에 기록된 stars/completedCount는 수락 당시 스냅샷이므로 최신 상태는 세션 GET을 사용한다. 재생 실패로 이미 커밋된 완료·별을 취소하지 않는다. 모든 재조회·멱등 재생 전에 현재 계정·아동·관계·동의를 검사한다. 기록 수정·별 수동 지급·수락된 receipt 삭제 API는 없다.

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

V5→V6 보존 검사는 같은 격리 클러스터 안에 UUID 이름의 합성 DB를 별도 생성하고 검사 후 제거하므로 테스트 사용자에 `CREATEDB` 권한이 필요하다. 로컬 `tools/Test-Foundation.ps1`은 새 임시 클러스터와 해당 권한을 가진 합성 사용자를 준비하고 전체 검사가 끝나면 서버를 종료한다. 이 권한을 운영 애플리케이션 계정에 추가하지 않는다.

## Render 배포 설정

Render에서 GitHub 저장소를 연결하고 Web Service를 만든다. 실제 서비스 생성·배포는 별도로 수행한다.

| 항목 | 설정 |
| --- | --- |
| Language / Runtime | Docker |
| Dockerfile Path | `./Dockerfile` |
| Docker Build Context | 저장소 루트 |
| Health Check Path | `/actuator/health` |
| Environment | 위 DB 연결 정보, 멱등 처리 키 2개 및 JWT 서명 키 |

Render PostgreSQL의 host/database/user/password를 환경 변수로 공급한다. Render에서 `localhost`는 개발 PC의 DB를 가리키지 않는다. 같은 Render 네트워크의 DB를 사용한다면 해당 DB의 내부 host로 JDBC URL을 구성한다.

서버는 Render의 `PORT`와 `0.0.0.0` 바인딩을 사용한다. Docker 빌드 stage에는 JDK·Gradle과 서버 소스만 전달하고, 최종 image에는 Java 21 JRE와 실행 jar만 남긴다. 테스트·로컬 문서·캐시·환경 변수 파일은 image에 복사하지 않는다.

배포 후 확인할 경로:

- `/actuator/health`: 서버·DB 상태. 정상 시 HTTP 200, `status=UP`.
- `/swagger-ui/index.html`: Swagger UI (`EF_API_DOCS_ENABLED=true`일 때).
- `/v3/api-docs`: 실행 서버의 OpenAPI JSON.
- `/api/v1/children`: 인증 없이 요청하면 HTTP 401. 현재 아동 API 구현 완료를 뜻하지 않는다.

참고: [Render Docker 배포](https://render.com/docs/docker), [Render 포트·바인딩](https://render.com/docs/troubleshooting-deploys).
