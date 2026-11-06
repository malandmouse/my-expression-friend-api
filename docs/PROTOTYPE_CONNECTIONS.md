## 최신 진행: 기능 마무리 수정본(2026-11-05)

기존 실제 GPT5편·Typecast105음원이 READY이고 서버 표본 파형도 정상이다. 사용자는 iPad 내용은 확인했지만 전체 무음을 보고했다. 새 수정본은 iOS playback 세션·기존 홈/Day 목록/별/글자 효과·비동기 카드/확장·완료 후 음성 대기 분리를 포함한다. 실제 기기 출력은 재설치 후 확인하며 성공으로 미리 표시하지 않는다. 기존04 LIVE 프로세스/키/DB/원고는 자동 중단·재생성하지 않는다. 최신 적용 방법은 출력 폴더의 `마무리 수정본 적용과 확인.md`를 따른다.

# V2.1 프로토타입 연결과 실행

2026-11-05 현재 통합 브랜치의 구현 안내다. 사용자 제공 `ExpressionFriend_Executable_Prototype_Blueprint_10criteria.zip`과 내부 V2.1 설계를 기준으로 한다. 실제 GPT/Typecast/iPad 최종 인수와 구현 상태는 루트의 `docs/prototype-feasibility-10criteria-20261105/IMPLEMENTATION_PLAN.md`에서 구분한다.

## 실행 구성

- 보호자 웹: `apps/adult-web`의 기존 React 화면·인증·아동 등록을 재사용한다.
- 백엔드: 이 `services/core`의 Spring Boot/PostgreSQL이 권한·설정·경험·승인·발행·완료·별의 단일 권위다.
- AI Worker: 이 폴더의 `worker`를 함께 전달해야 한다. Node24 이상과 Python3가 필요하다. vendored SillyTavern 카드 validator/요청 builder/전송 코드를 실제 사용하며 준·나래0.3.0을 유지한다.
- Unity: 기존 Foundation iPad 앱과 r7/r32 자산을 재사용한다. 아동 연결 전환과 최신 기능 복원 스크립트를 적용한 뒤 새06 Xcode 폴더로 내보내기가 한 번 필요하다. 같은Team/앱ID로 설치하며 기존 앱/DB를 삭제하지 않는다.

시작 순서는 **DB → Worker 내부 readiness → 백엔드 → 웹**이다. 배포용 단일 이미지는 `Dockerfile`과 `runtime/serve.py`로 이 순서를 구성한다(실제 image build/배포는 별도). 기존 음원 준비 복구 기능이 Worker보다 먼저 실행되지 않게 한다. 첫 배포는 API/Worker 각1개 프로세스를 기준으로 한다. 여러 인스턴스 간 복구 소유권·장시간 부하 검증은 이번 로컬 데모 인수가 아니다.

## 한 아동의 흐름

1. 기존 회원 인증으로 로그인하고 아동 기본 프로필을 저장한다.
2. 기본 학습 동의 뒤 `scenario-profile`과 `daily-events`를 저장한다. 설정 revision·선택 사건의 version을 포함한 서버 snapshot을 생성 작업에 고정한다.
3. 웹은 `agent-capabilities`에서 백엔드/Worker 모드 일치와 기존 모델·기본 요청 수를 확인한다. 실제 모드에는 생성 전 비용 안내를 표시한다.
4. `agent-jobs`에 하루5편을 요청한다. V2.1은 계획1회+노드40회이며 자동 의미 검수/재작성 모델을 추가하지 않는다. 실제 예약·정산·미확정 호출은 기존 Core 원장에 남는다.
5. 웹은 같은 작업의 **다섯 주제·요약이 모두 준비된 뒤** 확정 양식을 연다. 선택한 job과 요약/summaryId/dayDraftHash가 일치해야 한다. 최신 조회는 GET만 다시 시작하며 생성 POST를 대신 재전송하지 않는다.
6. 보호자가 확정하면 기존 문장별 음원/연기/해시 결속과5편 전체 발행을 사용한다. 웹은 저장된 release를 읽고 음원 진행/실패/불명/READY를 표시한다. 카드·표현 확장에 별도 보호자 승인 단계는 없다.
7. 기기 일회용 연결 코드 → iPad home/plan 권한 → package/문장 음원 → 대화/감정/원문 또는 카드 확정 → 원자 receipt/별 → 완료 뒤 캐릭터 반응 순서로 진행한다.
8. 날짜별 `prototype-dashboard`는 실제 완료와 관찰을 조회한다. 날짜에 제작 기록이 없으면 웹에서 기록 부재로 표시한다. 완료한 실제 경로의 관찰만 `nextTraining`과 다음 생성의 NPC별 continuity에 들어간다.

## 추가한 조회 계약

`GET /api/v1/children/{childId}/agent-capabilities`

인증: 기존 보호자 Bearer 및 해당 아동 MANAGE/기본 학습 동의. `Cache-Control: no-store`. 서버의 서비스 토큰으로 Worker 상태만 조회하며 GPT/음성 생성·목소리 목록 조회를 하지 않는다. 서비스 토큰과 제공자 키는 응답에 없다.

```json
{
  "schemaVersion": "EF_AGENT_CAPABILITIES_P1",
  "childId": "00000000-0000-4000-8000-000000000001",
  "generationMode": "MOCK",
  "engine": "EF_ST_AGENT_DRAFT_V2_1",
  "modelRef": "gpt-5.4",
  "configurationRef": "prototype-st-v2.1-1",
  "sessionsPerDay": 5,
  "expectedGptCalls": 41
}
```

`POST /api/v1/children/{childId}/agent-jobs`의 기존 `EF_GENERATE_DAY_P1` body에 선택 필드 `expectedGenerationMode: "MOCK" | "LIVE"`를 추가했다. 새 웹과 무료 검사기는 이 값을 보낸다. 서버 모드가 바뀌었으면 예약·제공자 호출 전에409/EXECUTION_MODE_MISMATCH로 거부한다. 기존 필수 필드와 멱등 키는 유지한다.

정확한 타입은 `src/main/resources/contracts/prototype-p1.schema.json`의 `AgentCapabilitiesP1`·`GenerateDayCommandP1`에 있다. Worker의 같은 계약 사본도 함께 갱신했다. 기존 ZIP을 덮어쓴 것이 아니라 현재 구현의 추가 계약이다. Spring OpenAPI가 실제 신규 경로를 노출한다.

내부 `GET /internal/v2/worker-status`는 기존32자 이상 서비스 토큰을 요구한다. 응답은 mode/engine 두 필드이며 제공자·작업 저장 포트 호출은 없다. 일반 사용자/iPad가 직접 호출하지 않는다.

## 재시작과 현재 작업

- 기존 음원 복구 진입점은 하나만 유지한다. 발행 기능이 꺼졌을 때는 복구를 실행하지 않는다.
- 활성 release의 미정산 RESERVED 음원은 OUTCOME_UNKNOWN으로 보존한다. 자동 합성하지 않는다.
- 이미 성공한 음원·원고/승인 hash는 유지한다. 아직 전송하지 않은 PENDING과 성공 음원만 가진 작업은 기존 큐로 이어간다.
- MEDIA_FAILED/HELD는 보호자 화면의 명시 동작으로 처리한다. 발행만 실패했다면 준비된 음원을 재사용하는 기존 재발행 경로를 쓴다.
- 서버 시작 시 앞서 확정한 미전송 음원 작업이 이어질 수 있으므로 LIVE 실행기가 입력 전에 비용을 알린다.

## 담당자에게 필요한 환경 설정

기존 `application.properties`와 운영 인증/DB/메일 설정에 다음 값을 함께 맞춘다. 실제 비밀값을 ZIP·Git·프론트·Unity에 넣지 않는다.

| 영역 | 환경 변수 |
|---|---|
| 백엔드에서 Worker | EF_AGENT_ENABLED, EF_AGENT_V2, EF_AGENT_URL, EF_AGENT_SERVICE_TOKEN |
| Worker에서 백엔드 저장 포트 | EF_AGENT_STORAGE_ORIGIN, 같은 EF_AGENT_SERVICE_TOKEN |
| 공통 생성 설정 | EF_AGENT_MODEL, EF_AGENT_CONFIGURATION_REF |
| 백엔드 LIVE/발행 | EF_PROTOTYPE_LIVE_ENABLED, EF_PROTOTYPE_PUBLICATION_ENABLED |
| Worker LIVE | EF_AGENT_MODE=LIVE, OPENAI_API_KEY, TYPECAST_API_KEY |
| 목소리 | TYPECAST_JUN_VOICE_ID, TYPECAST_NARA_VOICE_ID, TYPECAST_MODEL=ssfm-v30 |
| GPT 일일 예산 | EF_AGENT_MAX_CALLS, EF_AGENT_MAX_INPUT_TOKENS, EF_AGENT_MAX_OUTPUT_TOKENS, EF_AGENT_MAX_COST_MICROUSD, EF_AGENT_INPUT_USD_PER_MILLION, EF_AGENT_OUTPUT_USD_PER_MILLION |
| 운영 날짜 | EF_PROTOTYPE_TEST_CLOCK_ENABLED=false. 운영에서 테스트 날짜 전진을 사용하지 않는다. |

`EF_AGENT_CONFIGURATION_REF`는 현재 백엔드 snapshot/provenance의 설정 식별자다. Worker는 동일한 요청 설정을 받고 모델은 공통 EF_AGENT_MODEL을 사용한다. 서로 다른 모델을 묵시적으로 대체하지 않는다.

기본 API/Worker의 내부 HTTP는 loopback으로만 사용한다. 별도 호스트로 나누면 기존 어댑터가 허용하는 HTTPS 주소·서비스 인증으로 구성한다. Worker는 브라우저에 직접 공개하지 않는다. 웹 배포와 새 백엔드는 이 추가 조회 계약을 포함한 같은 변경 묶음으로 반영한다.

## 확인한 범위

2026-11-05 무료 검사: 웹10/Worker13/백엔드4=27개, TypeScript/lint/웹 빌드·백엔드 컴파일 통과. Mac 호스트12에서 임시 DB의 실제 복구 SQL 검사, 새 가상 아동의 하루5편 생성·확정·원문4/카드1·완료5/별5·대시보드 통과. 데모 날짜2026-11-07 유지. 기존 앱·DB와 사용 중인 아동의 원고를 덮어쓰지 않았다.

복구 이벤트는 검증 후 기존 진입점 하나로 정리했다. 최종 소스의 컴파일과 이후04 실행 시 전체 빌드로 반영한다. 실제 GPT/Typecast 한 묶음과 같은 iPad의 두 날짜 완주는 별도 잔여 인수다. 디자인 변경은 후속 작업이다.

## 공식 API 확인 근거

- [OpenAI GPT-5.4](https://developers.openai.com/api/docs/models/gpt-5.4): 기존 모델의 Chat Completions/구조화 출력 지원과 가격을 확인했다.
- [OpenAI Chat Completions](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create): response_format/json_schema와 max_completion_tokens 계약. 이 한도에는 추론 토큰도 포함하므로 잘린 응답을 자동 재생성하지 않는다.
- [Typecast API](https://typecast.ai/developers/api/): 기존 ssfm-v30/텍스트 음성·감정 연기 경로를 유지한다. 실제 목소리별 지원 프리셋은 LIVE metadata 조회로 확인한다.


## 비동기 표현과 Day 메뉴 추가 계약

- `GET /api/v1/child-sessions/day-index`: 현재 기기에 연결된 아동의 서버 Day·별·시작 가능 여부만 조회한다. 조회 자체로 해금/별을 만들지 않는다. 과거 Day는 기록 보기이며 과거 회기를 정규 완료로 다시 실행하지 않는다.
- `POST /api/v1/sessions/{session}/slots/{slot}/expression-jobs` →202: `EF_EXPRESSION_JOB_COMMAND_P1`, `operation: OPTIONS | EXPANSION`, `request`는 기존 카드/확장 body다. 기존 아동 Bearer·Idempotency-Key·동의·현재 checkpoint/원문 hash를 그대로 사용한다.
- `GET .../expression-jobs/{jobId}` → `EF_EXPRESSION_JOB_P1`: QUEUED/RUNNING/COMPLETED/FAILED/OUTCOME_UNKNOWN/EXPIRED/DISCARDED와 결속된 결과를 반환한다. 원문/버전이 달라지면 이전 결과를 사용하지 않는다. 완료된 동일 요청은 TTL 안에서 재사용하며 불명 실행은 재전송하지 않는다.
- 별도 보호자 승인/카드 수락은 없다. 아동의 최종 표현 확정·기존 receipt는 그대로다. 기존 동기 카드 API도 호환을 위해 유지한다. DB는 추가형V17만 적용하며 기존 migration checksum은 바꾸지 않는다.
- 앱의 대기 취소는 **결과 기다리기 중지**다. 전송된 제공자 요청의 취소·환불을 의미하지 않는다. 새 입력/다음 이야기에서 늦은 결과나 음성을 재생하지 않는다.

정확한 스키마는 공통 `prototype-p1.schema.json`의 ExpressionJobCommandP1/ExpressionJobP1/DayIndexP1이다. 배포 구성과 환경은 [DEPLOY_PROTOTYPE.md](DEPLOY_PROTOTYPE.md)를 따른다.


## 완료 뒤에도 가능한 기존 목소리 확인

`GET /api/v1/child-sessions/voice-check`는 현재 연결 아동의 이미 READY로 발행된 NPC 한 문장만 `EF_VOICE_CHECK_P1`로 반환한다. 이어서 `GET /api/v1/child-sessions/voice-check/{assetId}/audio`에 `X-Expected-SHA256`을 보낸다. 기존 기기 Bearer·현재 동의/철회·Core 발행 실행 권한과 PCM hash를 재확인한다. 다른 아동의 asset은 조회할 수 없다.

두 경로 모두 no-store/read-only다. 세션 시작·관찰·완료·별·제공자 호출을 만들지 않으며, 정규/복습 학습 완료 경로를 대체하지 않는다. 이미5편을 마친 iPad에서도 기존 음원으로 새 재생 설정을 확인하기 위한 기능이다. UI는 LIVE Typecast와 MOCK 확인 음원을 구분한다.
