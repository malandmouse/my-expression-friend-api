# Render 배포: 외부 생성 API + OpenAI 임베딩 + R2

서버는 Render Docker Web Service, DB는 Render Postgres, 파일은 Cloudflare R2를 사용합니다.
이 구성은 Render 영구 디스크를 사용하지 않습니다. 유료 리소스를 자동 생성하는 설정도 없습니다.

## 배포 설정

1. Render에서 저장소를 연결하고 Web Service의 Language를 **Docker**로 선택합니다.
2. Dockerfile Path는 `./Dockerfile`, Docker Context는 저장소 루트로 설정합니다.
3. Docker Command는 비워 둡니다. Dockerfile이 Java 17로 실행하며 `prod` 프로파일을 기본으로 선택합니다.
4. Health Check Path를 `/actuator/health`로 설정합니다. `PORT`는 Render가 제공합니다.
5. `.env.example`의 값을 Render의 Environment에 등록합니다. `.env` 파일은 Spring Boot가 자동으로 읽지 않습니다.
6. 프론트엔드 API 주소를 Render 서버의 HTTPS 주소로 변경합니다.

`LLM_BASE_URL`과 `EMBEDDING_BASE_URL`은 API 버전 경로까지 포함한 주소입니다.
예를 들어 `https://provider.example/v1`을 넣으면 생성은 `/chat/completions`, 임베딩은 `/embeddings`를 뒤에 붙입니다.
전체 엔드포인트를 넣지 마세요. API 키와 모델은 각 서비스의 별도 설정을 사용합니다.

생성은 OpenAI 호환 Chat Completions API만 지원합니다. Gemini 고유 API, Anthropic 고유 API, Ollama `/api/generate` 주소는 그대로 사용할 수 없습니다.
프로바이더가 공개하는 OpenAI 호환 주소를 사용하세요. `LLM_MODEL_PRO`를 생략하면 `LLM_MODEL_FLASH`를 사용합니다.
`LLM_THINK`는 기본적으로 비워 두세요. 설정하면 `reasoning_effort`로 전송합니다.
기존 요청의 `think=true/false`는 각각 `medium/none`으로 매핑되며, 프로바이더·모델이 해당 값을 지원해야 합니다.

## 새 무료 DB 초기화

무료 DB는 **생성일부터 30일**, 용량은 **1GB**입니다. 준비 기간도 30일에 포함되므로 실제 사용 일정에 맞춰 생성하세요.
서버와 DB는 같은 리전으로 만들고 내부 호스트를 JDBC URL에 넣으세요.
Render가 주는 `postgresql://...` 주소 자체는 JDBC URL이 아닙니다. 사용자명과 비밀번호는 별도 변수에 넣습니다.

기존 DB를 이관할 경우 덤프에 개인정보가 포함될 수 있으므로 저장소에 커밋하지 마세요.
아래 절차는 기존 데이터가 없는 새 DB의 단기 시연 환경용입니다.

1. DB의 PSQL 접속 정보로 연결해 `docker/postgres/init/001_pgvector_extensions.sql`을 실행합니다.
2. 최초 배포에서만 `SPRING_JPA_HIBERNATE_DDL_AUTO=update`를 설정해 JPA 테이블을 생성합니다.
3. `SPRING_AI_EMBEDDING_MODEL=openai`, `SPRING_AI_VECTORSTORE_TYPE=pgvector`, `SPRING_AI_PGVECTOR_INITIALIZE_SCHEMA=true`를 설정합니다.
4. 서버가 정상 시작한 것을 확인한 뒤 `SPRING_JPA_HIBERNATE_DDL_AUTO=validate`로 변경하고 재배포합니다.
5. 필요한 사용자·시나리오 등 초기 데이터는 기존 관리자 API 또는 DB 이관으로 등록합니다. Dockerfile은 데이터를 자동으로 넣지 않습니다.

Flyway 마이그레이션은 현재 구성되어 있지 않습니다. 실제 운영의 스키마 변경은 별도 마이그레이션 도입이 필요합니다.
무료 DB에는 관리형 백업이 없으므로 종료·만료 전에 `pg_dump`로 필요한 데이터를 보관하세요.

## 기존 임베딩 데이터

기본 모델은 `text-embedding-3-small`, 차원은 1536입니다.
기존 Ollama 4096차원 벡터와 섞이지 않도록 기본 테이블명을 `vector_store_openai_1536`으로 분리했습니다.
기존 RAG 문서는 새 모델로 다시 색인해야 합니다. 모델이나 차원을 바꿀 때도 새 테이블명과 재색인을 사용하세요.
`EMBEDDING_DIMENSIONS`는 API 요청과 pgvector 설정에 함께 적용됩니다.
임베딩·검색에는 텍스트만 사용하며 이미지·동영상의 원본 파일은 R2에 보관합니다.

RAG를 사용하지 않으면 `SPRING_AI_EMBEDDING_MODEL=none`, `SPRING_AI_VECTORSTORE_TYPE=none`으로 함께 설정하세요.
생성만 끄려면 `LLM_ENABLED=false`를 사용합니다. OpenAI Realtime은 별도의 기능이며 기본 비활성화입니다.
유료 API 호출을 실행할 수 있는 `/api/llm/health-check`, `/api/llm/error-pattern/run`, `/api/llm/dialogue/rebuild`는 ADMIN JWT가 필요합니다.
Render 헬스체크는 외부 AI를 호출하지 않는 `/actuator/health`를 사용하세요.

## R2 설정과 파일 주소

1. R2 **Standard** 버킷을 생성합니다. 버킷을 공개하거나 유료 Workers·Images·Stream 상품을 추가할 필요는 없습니다.
2. 해당 버킷에만 Object Read & Write 권한이 있는 R2 S3 API 자격 증명을 발급합니다.
3. `.env.example`의 `APP_STORAGE_TYPE=s3`, `S3_*` 값을 Render에 등록합니다.
4. API를 통해 파일을 업로드한 후 `/uploads/...` 주소로 다운로드되는지 확인합니다.

DB에는 만료되지 않는 `/uploads/{object-key}` 상대 주소를 저장합니다.
이 주소를 요청하면 서버가 5분짜리 S3 서명 URL로 302 이동시킵니다. 파일 본문은 R2에서 직접 내려받습니다.
클라이언트는 상대 주소를 백엔드 주소 기준으로 해석하고 HTTP 리다이렉트를 따라야 합니다.
프로필 이미지와 시나리오 JSON 백업이 저장소 설정을 따릅니다. 기존 RAG PDF API는 PDF 텍스트를 추출·색인하며 원본 PDF를 영구 보관하지 않습니다.
이 변경은 새로운 범용 동영상 업로드 API를 추가하지 않습니다. 기존 시나리오의 외부 이미지·동영상 URL은 유지됩니다.

`/uploads/**`는 기존처럼 로그인 없이 주소를 아는 사용자가 접근할 수 있습니다.
비공개 버킷 자체가 사용자별 조회 권한을 보장하지는 않습니다. 기존 애플리케이션의 파일 공유 방식은 유지됩니다.
기존 서버 디스크나 S3 파일은 자동으로 복사하지 않습니다. 기존 `/uploads/...` 파일은 같은 object key로 R2에 이관해야 합니다.
기존 S3 절대 URL은 자동으로 R2 URL로 바뀌지 않습니다.

AWS S3를 재사용하려면 `APP_STORAGE_TYPE=s3`, 실제 `S3_REGION`과 버킷·키를 설정하고 `S3_ENDPOINT`는 비워 두세요.
로컬 개발에서는 `APP_STORAGE_TYPE=local`로 두면 기존 `uploads/` 디렉터리에 저장합니다.

## 비용 범위

Render 무료 DB와 R2 무료 사용량 범위에서 DB·파일 저장 비용을 줄이는 구성입니다.
R2는 무료 한도를 넘으면 과금되는 상품이고, Render 트래픽·빌드 사용량과 생성·임베딩 API 비용은 별도입니다.
이 코드가 전체 비용을 $25로 제한하거나 외부 서비스의 초과 과금을 차단하지는 않습니다.

- Render 무료 DB: https://render.com/docs/free
- R2 요금: https://developers.cloudflare.com/r2/pricing/
- OpenAI 임베딩: https://developers.openai.com/api/docs/models/text-embedding-3-small
