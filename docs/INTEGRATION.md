# Foundation · Agent · Unity 이관

**현재 연결 안내:** 아래는 최초 이관 당시의 V1/MOCK 범위를 보존한 기록입니다. 이후 구현한 V2.1 제작·문장 음원·P1 iPad·표현 카드·날짜별 대시보드와 신규 모드 조회 계약은 [PROTOTYPE_CONNECTIONS.md](PROTOTYPE_CONNECTIONS.md)를 먼저 읽으세요. 실제 유료/iPad 최종 인수는 별도 체크리스트로 구분합니다.

이 패키지는 `feat/backend-foundation` b4391cd9와 웹 main d7c15059를 기준으로 한 **미배포 소스**입니다. 운영 발행·실제 GPT·Typecast 호출은 실행하지 않았습니다.

## 읽는 순서

1. 이 문서 → `AGENT_API.md` → `PROFILE_MAPPING.md`.
2. `src/main/java/.../agent/`: 새 HTTP 연결, foundation 권한/작업/원고 저장 연결.
3. `worker/integration/`: 실제 SillyTavern 코드·준/나래 v0.3.0을 재사용한 서버 전용 Node worker.
4. 기존 foundation `README.md`: 인증·동의·콘텐츠 발행·runtime의 운영 전제.

## 소스 배치

- 메인 `apps/adult-web`: 새 React 웹. 기존 로그인·아동·동의·요약 확인·기록 화면 유지, 하루 제작·기기 연결 추가.
- 메인 `services/core`: 이 foundation 프로젝트. Java 21 / Gradle. 과거 `learning/pom.xml`을 실행하지 않습니다.
- 메인 `services/core/worker`: 외부 API를 호출하지 않는 연결 검사용 worker. `node >=24`, `npm run start:foundation`.
- 메인 `integrations/core-legacy`: 과거 Core 추적 소스의 보존/회귀 검사본. 새 백엔드 ZIP에는 들어가지 않습니다.
- 메인 Unity: `FoundationRuntimeConnection`, `FoundationDialogue`, 새 Foundation 전용 Scene 생성 도구. 기존 r7/r32 코드·Scene·GUID·표정/동작 자산은 수정하지 않습니다.

## 이번 연결 범위

웹 요청 → foundation 성인 JWT·아동 권한·동의 확인 → 불변 입력 snapshot → 기존 generation_job 예약 → DB 트랜잭션 밖에서 worker 한 번 호출 → 동일 작업/현재 권한/취소/기한 확인 → 다섯 DRAFT 원고와 요약 원자 저장. 기존 주제·요약 화면에서 확인/보류합니다.

Node worker는 `EF_FOUNDATION_AGENT_1` HTTP adapter를 제공합니다. 기존 SillyTavern에서 가져온 card validation/request/transport 코드, 준·나래 카드, RAG, Prepared V1 출력은 보존했습니다. **MOCK 고정**이며 실제 모델 생성 서비스로 표시하지 않습니다. worker 응답의 원고는 foundation `GENERATED_1_TO_3` 구조 검사 대상으로 변환됩니다.

요약 API는 **foundation에서 수락한 receipt만** DB에서 읽습니다. 아동의 인식 정오/도움 수준 관측은 현재 foundation에 없으므로 생성하지 않습니다. 관계 기억·장소 초대·언어 능력을 완료 횟수로 추정하지 않습니다. 다음 제작의 장기 기억/관측 원천 연결은 미완료입니다.

## Unity

새 adapter는 foundation의 실제 nested session/pin/checkpoint, 개별 asset lease 및 raw bytes, ORIGINAL proof/완료/receipt 형식을 사용합니다. 이전 Core 0.4/0.5 endpoint로 요청하지 않습니다. 새 `H03 > Foundation > Create separate foundation connection scene`은 기존 shell을 재사용하는 별도 Scene만 만들며 r7/r32와 기존 build settings를 덮어쓰지 않습니다.

현재 새 Scene은 **대화 텍스트·선택·감정 확인→직접 자기표현→ORIGINAL 완료** 연결용입니다. HTTP 복구/완료는 새 SDK를 사용합니다. **새 foundation 경로의 음성/표정/동작 playback binding, 카드/NATURAL, 마지막 반응 음성은 아직 연결 완료가 아닙니다.** 기존 r7/r32의 로컬 미리보기·카드·음성 기능은 별도로 그대로 보존됩니다. 이를 새 foundation에서 카드까지 회귀 통과했다고 해석하면 안 됩니다.

## 발행 전제와 미완료 연결

자동으로 발행하지 않습니다. 새 원고에는 `unbound-*` 참조를 넣고 `UNBOUND_NOT_PUBLISHABLE`로 명시합니다. `contentHash`는 임시 DRAFT 식별값이며 Core dependency envelope를 계산한 발행 해시가 아닙니다. 기존 foundation dependency 검증은 이를 승인된 자료로 받아들이지 않습니다.

아동 앱에서 실제 시나리오를 보려면 담당자가 실제 persona/policy/catalog/media bytes를 연결하고 **새로 해시를 계산한 원고 버전**을 등록해야 합니다. 그 뒤 전체 분기·음성 검수, 요약 확인, child-use evidence, 발행 권한, 발행/plan, media provider/lease 설정이 필요합니다. 예제/구조 통과를 이 증거로 대체하지 않았습니다.

- ContentAuthority와 CoreMediaDelivery는 기존 default deny를 유지합니다.
- `emotion-friend.core.child-use-enabled=false`, lease 0 기본값도 유지합니다.
- 시나리오 발행과 카드 생성/표현 확장에 별도 승인을 요구하는 것은 다릅니다. 이번에 카드/확장 승인 단계를 추가하지 않았습니다.
- 실제 기존 DB의 계정·아동을 새 foundation 스키마로 변환/적용하지 않았습니다. **보존한 기존 DB를 새 Flyway URL에 넣지 마세요.** 운영 DB snapshot/소유자와 별도로 합의한 뒤 수행해야 합니다.

## 설정과 검사

`.env.integration.example`은 빈 설정 예제입니다. 서버 토큰은 OpenAI/Typecast 키와 다른 32자 이상의 값이며 브라우저/Unity에 보내지 않습니다. 기본 `EF_AGENT_ENABLED=false`. worker는 loopback 바인딩, Java는 loopback HTTP 또는 명시 HTTPS origin만 허용하고 redirect/retry를 하지 않습니다.

다운로드와 실행은 사용자가 수행합니다. 이번 소스 정리 이후 자동 다운로드는 하지 않습니다.

```sh
# backend root — Java 21, Gradle wrapper 및 Maven 의존성 다운로드 필요
./gradlew test bootJar
# 실제 DB 검사는 기존/운영 DB가 아닌 별도 foundation_test DB만
EF_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55432/foundation_test ./gradlew integrationTest
# worker — 추가 npm 패키지 다운로드 없음, 실제 유료 호출 없음
cd worker
node --test test/*.test.mjs
```

전체 backend/신규 Java test/실서버 통합 검사는 이 환경의 Gradle 다운로드 차단 때문에 **미실행**입니다. 전체 CI 통과본으로 배포하지 마세요. 웹도 lockfile에 지정된 정확한 npm 버전 세트의 `npm ci && npm run build`는 사용자 다운로드 후 검사해야 합니다. 기존 설치 패키지로 한 보조 타입검사/번들링은 별도로 기록합니다.

원고/보고 경험은 `learning.agent_source`/`agent_artifact`의 보호된 DB에 보관됩니다. 운영 사용 전 접근·보관 기간·삭제 정책을 담당자가 정해야 합니다. worker는 아동/별/완료 DB를 보유하거나 직접 쓰지 않습니다.
