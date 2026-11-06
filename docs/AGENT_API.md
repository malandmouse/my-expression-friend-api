# 새 HTTP 계약 EF_FOUNDATION_AGENT_1

## 성인 웹 → foundation

기존 성인 `Authorization: Bearer <adult JWT>`. 인증/관계/동의를 DB에서 매번 확인합니다. URL의 child는 해당 계정이 접근 가능한 UUID입니다. 작업 scope는 requester와 child에 결속됩니다.

`POST /api/v1/children/{childId}/agent-jobs` — `Idempotency-Key` 필수, 성공 202:

```json
{"programDay":1,"localDate":"2026-11-05","characterPreference":"BOTH","priorityGoals":["EMOTION_CONTEXT","SELF_FEELING"],"dailyEvents":[{"occurredOn":"2026-11-04","text":"보호자가 보고한 경험","reportedFeeling":null}]}
```

캐릭터 JUN/NARA/BOTH, Day 1~30, 목표 최대2, 보고 경험 최대5/각500자. 최근 경험은 아동 답변 후보에만 사용합니다. 서버 프로필/권한/동의 버전·snapshot ID를 클라이언트가 지정하지 않습니다. 지원 축은 새 foundation에 없으면 null입니다. 일차는 **제작 설정**이며 실제 Day 해금/배정 권한을 만들지 않습니다.

```json
{"jobId":"00000000-0000-4000-8000-000000000010","state":"QUEUED","version":1,"deadline":"2026-11-05T00:05:00Z","mode":"MOCK","paidCalls":0,"publicationAllowed":false}
```

- `GET .../agent-jobs/{jobId}`: 현재 state/version. 자동 실행/재시도 없음.
- `POST .../{jobId}/cancel` body `{"expectedVersion":2}`: QUEUED/RUNNING 현재 버전에 한함. 성공 200 같은 job view. 늦은 결과가 summary를 만들지 못하도록 admission/settlement를 한 트랜잭션에 묶음.
- `POST .../{jobId}/dispatch` body `{}`: 202. QUEUED 작업의 명시 실행. RUNNING/OUTCOME_UNKNOWN을 다시 전송하지 않음.
- `GET .../{jobId}/summaries`: RESULT_READY일 때 job + 5개의 `{slot,materialId,summaryId}`만. 저장한 결과 bytes hash를 다시 확인. 보호자 화면에 원고 전체/내부 prompt를 내보내지 않음.
- 기존 `GET .../learning-material-summaries`/`POST .../learning-material-confirmations`: 생성 요약 확인/보류. 확인했다고 자동 발행하지 않음.
- `GET /api/v1/children/{childId}/agent-training-summary`: 최근30일, 최대150개 **수락된 receipt**만 읽어 deterministic worker로 정리. no scoreable observations. 현재 지원하는 요약은 완료/별 근거이며 언어/감정 능력 평가가 아님.

상태: QUEUED/RUNNING/RESULT_READY/HELD/FAILED/CANCELLED/OUTCOME_UNKNOWN. 원문/키를 오류에 넣지 않습니다. timeout/전송불명은 새 job을 자동 예약하지 않습니다. HTTP idempotency는 기존 foundation encrypted replay를 사용합니다. 수정된 설정/프로필은 새 source fingerprint가 됩니다. 같은 입력 실패 이후의 명시 재제작 revision 관리 UI는 별도 후속 항목입니다.

## foundation → Node worker

고정 origin `EF_AGENT_URL`, 서버 전용 `EF_AGENT_SERVICE_TOKEN`. 성인/아동 JWT와 다릅니다. 토큰 32자 이상, redirect/retry0. Node 기본 `127.0.0.1:5198`.

`POST /internal/v1/daily-scenarios` body는 `worker/contracts/agent-api.proposed.openapi.json`의 **GenerateDayRequest** (`EF_ST_AGENT_DRAFT_V1`). foundation이 직전 DB에서 만든 profile/snapshot과 jobId를 넣습니다. 응답 200:

```json
{"contractVersion":"EF_FOUNDATION_AGENT_1","jobId":"00000000-0000-4000-8000-000000000010","sourceSnapshot":{"snapshotId":"00000000-0000-4000-8000-000000000011","inputRevision":1,"profileVersion":3,"authorityRevision":1},"result":{"contractVersion":"EF_ST_AGENT_DRAFT_V1","status":"DRAFT_READY","publicationAllowed":false,"program":{},"scenarios":[],"webPreview":[],"provenance":{}},"coreDrafts":[],"mode":"MOCK","paidCalls":0,"publicationAllowed":false}
```

위 응답은 필드 구성 설명용 축약본입니다. 빈 scenarios/coreDrafts는 유효하지 않습니다. **실제 유효한 5편 전체 응답**은 `src/testFixtures/resources/core/generated-st-day.json`에 있습니다. 그 자료는 합성 fixture이며 운영 아동 자료가 아닙니다.

coreDrafts의 각 항목: slot1~5, materialId UUID, scenarioVersionId UUID, subject≤40, summary≤300, payload=Core0.3 DRAFT, validationProfile=GENERATED_1_TO_3, dependenciesStatus=UNBOUND_NOT_PUBLISHABLE.

`POST /internal/v1/training-summary`: 기존 SummarizeRequest → SummarizeResult. HTTP401는 `{code:"AUTH_REQUIRED"}`, 422 등의 계약 오류는 기존 `errorBody` (`contractVersion,jobId,error{code,stage,providerAttempted,executionState,automaticRetryAllowed:false,issues}`), 404는 `{code:"RESOURCE_NOT_FOUND"}`. Node 로그에는 요청/토큰/원문을 출력하지 않습니다. HTTP 비정상/불명 응답을 성공 자료로 수락하지 않습니다.

현재 이 경로는 MOCK만 허용합니다. LIVE 공급자, Typecast, QA 반복 수정, 자동 발행을 구현했다고 해석하지 않습니다.
