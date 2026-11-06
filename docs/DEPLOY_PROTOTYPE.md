# 프로토타입 배포 구성과 전달 범위

최신 소스는 Java21 API + Node24 비공개 Worker + Python3 Typecast bridge를 한 컨테이너에 담는다. DB는 외부 PostgreSQL이며 기존 Foundation의 계정·동의·승인·완료 원장을 그대로 사용한다. 이 문서는 **준비된 배포 코드**이며 실제 image build/호스팅 배포 완료 기록이 아니다.

## 최신 GPT 표정·전체 동작 — 2026-11-06

- 새 ko4는 같은 GPT 턴 응답에서 emotionUsed/emotionIntensity/faceCue/animationCue를 받는다. 대사 감정과 얼굴ID를 일치시키고 기본 포함16표정, Mixamo15종+ASK/NOD/NONE18선택을 사용한다. 자동 검수/수정·분류 요청을 추가하지 않으며 하루 GPT는 계획1+턴40이다.
- 학습 STORY 음원 준비 경로에서 Jev 호출을 제거했다. Typecast의 감정 연기는 그대로 사용한다. TYPESAFE_API_KEY/Jev 장부/별도 영속 디스크는 이 학습 기능의 배포 요건이 아니다. 과거 EF_JEV_ENABLED=true가 남아 있어도 학습 중 Jev를 호출하지 않는다. 아래 Jev 절은 이전 구현 이력이다.
- 설치 목록v2=`r7-r32-foundation-p1-v2-gpt`는 새 iPad 수정본35/새14를 요구한다. Backend→새 iPad 설치→새 ko4 생성/발행 순서로 맞춘다. 구버전 앱이 새 동작을 지원한다고 간주하지 않는다. 원래v1 카탈로그/저장 원고·음원·manifest는 재작성하지 않고 새 앱이 두 버전을 모두 읽는다.
- 실제 준비된 Mixamo Catalog가 있는 앱만 v2 목록을 소비한다. FBX/06 공유 엔진/기존meta와 r7/r32 자산을 바꾸지 않는다. 단발 클립이 음성보다 길면 생략하고, 한 NPC발화에서 동작을 문장마다 반복하지 않는다. WEIGHT_SHIFT는 발화 동안의 제자리 루프로 사용한다.
- 최신 선택/계약·컴파일은 모의 검사와 구분된다. 실제 GPT 생성 품질과 새 iPad 움직임은 별도 확인하며 기존 Day1/Day2를 재생성하지 않는다.

## 담당자가 적용할 순서

1. 대상 브랜치와 실제 Foundation DB의 백업을 확보한다. 보존된 legacy/Core DB에 이 migration을 적용하지 않는다.
2. 이 폴더 전체를 소스로 사용한다. `worker`, `runtime`, 라이선스/출처 자료까지 함께 전달한다. Worker만 별도 기존 실험실 서버로 실행하지 않는다.
3. `Dockerfile`을 빌드한다. root directory는 `services/core` 또는 전달 ZIP의 backend 루트다. API 빌드 뒤 런타임의 Java/Node/Python 버전을 확인하는 단계가 있다.
4. 비밀값은 호스팅 환경 설정에 넣는다. 기존 fingerprint/encryption/JWT 키는 재배포 때 유지한다. 신규 환경이라면 서로 다른32바이트 Base64 키3개와32자 이상 서비스 토큰을 담당자가 준비한다.
5. PostgreSQL 연결 후 시작한다. Flyway는 기존V1~V16 체크섬을 그대로 검증하고 추가형V17을 적용한다. 운영 시험 날짜 기능은 반드시 꺼 둔다.
6. `/actuator/health`의 UP, 회원 로그인, 동의, 설정/사건 저장을 확인한다. 정적 웹은 `/api/v1`을 이 API로 프록시한다. 기존 `vercel.json`의 Render 목적지가 이 배포 대상과 같은지 확인한다.
7. 새 유료 요청 전 사용자에게 비용을 알린 후 한 아동의 생성→5요약 확정→READY→같은 iPad→대시보드를 확인한다. 기존 승인 미전송 음원이 있으면 서버 시작 때 이어질 수 있다.

## 환경 변수

| 항목 | 값/책임 |
|---|---|
| PORT | 호스팅 제공 포트, 기본8080. 공용 포트는 API만 노출 |
| EF_DB_URL / EF_DB_USERNAME / EF_DB_PASSWORD | 담당자의 별도 Foundation PostgreSQL. JDBC URL 사용 |
| EF_IDEMPOTENCY_FINGERPRINT_KEY / EF_IDEMPOTENCY_ENCRYPTION_KEY / EF_JWT_SIGNING_KEY | 기존 서버3키 유지. ZIP/웹/Unity에 넣지 않음 |
| EF_AGENT_ENABLED / EF_AGENT_V2 / EF_AGENT_BUNDLE_WORKER | 모두true. 기본 단일 이미지 내부 Worker 실행 |
| EF_AGENT_SERVICE_TOKEN | 내부 서버 인증.32자 이상, 공개 응답에 없음 |
| EF_AGENT_PORT | 내부5198. API의 PORT와 겹치지 않게 설정 |
| EF_AGENT_URL | 생략하면 내부 `http://127.0.0.1:5198` 자동 설정 |
| EF_AGENT_STORAGE_ORIGIN | 생략하면 내부 API 포트로 자동 설정 |
| EF_AGENT_MODE / EF_PROTOTYPE_LIVE_ENABLED | 무료 MOCK/false 또는 실제 LIVE/true. 서로 다르면 시작 거부 |
| EF_PROTOTYPE_PUBLICATION_ENABLED | true. 확정 후 P1 발행 사용 |
| EF_CORE_LEASE_SECONDS | 30. 기존 기본0은 미디어 차단이므로 명시 설정 필요 |
| EF_PROTOTYPE_TEST_CLOCK_ENABLED | **false**. 이미지 실행기는true를 거부 |
| EF_AGENT_MODEL / EF_AGENT_CONFIGURATION_REF | gpt-5.4 유지 / 새 한국어 지침은 prototype-st-v2.1-ko4. 기존 원고의 설정 참조는 변경하지 않음 |
| OPENAI_API_KEY / TYPECAST_API_KEY | LIVE일 때만 서버 환경에 공급 |
| TYPECAST_JUN_VOICE_ID / TYPECAST_NARA_VOICE_ID / TYPECAST_MODEL | 준·나래의 기존 지원 목소리 / ssfm-v30 |
| EF_AGENT_INPUT_USD_PER_MILLION / EF_AGENT_OUTPUT_USD_PER_MILLION | 실제 사용하는 모델의 가격을 확인해 설정. 기본0 상태의 LIVE는 거부 |
| EF_AGENT_MAX_CALLS / MAX_INPUT_TOKENS / MAX_OUTPUT_TOKENS / MAX_COST_MICROUSD | 각각 EF_AGENT_ 접두사 포함. 배포 예제85 / 500000 / 32000 / 2000000. 실제 LIVE 데모 실행기와 입력 한도를 맞추고 USD2 상한은 유지한다. 예제 파일이 호스팅 환경을 자동 변경하지 않으므로 네 값을 명시 설정한다. 예약·불명 호출 포함 |
| EF_MAIL_* / EF_BOOTSTRAP_ADMIN_* | 기존 서비스의 메일/회원 승인 설정 유지. 배포 이미지가 데모 계정을 만들지 않음 |

`EF_AGENT_BUNDLE_WORKER=false`는 담당자가 별도 사설 Worker를 운영할 때만 사용한다. 이 경우 Worker의 loopback 포트를 공용에 열지 않고 인증된 사설 경로를 담당자가 제공한다. 단일 이미지의 기본 설정은 loopback만 허용한다.

## 이미지 동작

`runtime/serve.py`는 Worker의 인증된 메타데이터 readiness만 확인한 뒤 API를 실행한다. 이 조회는 GPT·Typecast를 호출하지 않는다. 한 프로세스가 종료되면 이 실행기가 만든 다른 프로세스도 종료하고 비정상 종료를 알린다. SIGTERM은 둘을 정상 종료한다. DB 생성·가상 계정 seed·제작 요청을 자동 실행하지 않는다.

readiness는 실행 모드·V2.1 엔진과 함께 집필 버전 `prototype-st-v2.1-ko4`도 대조한다. API의 제작 가능 조회도 같은 버전을 요구한다. 구버전 설정/Worker를 혼용하면 새 제작 전에 거부한다. 기존에 완성된 원고·음원·receipt의 버전과 내용을 일괄 변경하지 않는다. 별도 사설 Worker 운영도 API와 동일 버전 소스로 함께 갱신한다.

API 재시작 시 성공한 음원/승인 해시는 재사용하고 미전송 음원만 이어간다. 실행 불명은 자동 재전송하지 않는다. 카드 작업도 RUNNING은OUTCOME_UNKNOWN, 미전송QUEUED는FAILED/NOT_DISPATCHED로 보존한다. 카드/확장 원문은 암호화·15분 TTL이며 별도 보호자 승인 단계가 없다.

## 비용·검증·제한

- 하루5편의 V2.1은 계획1+턴40 GPT 요청이다. 확정 후 가지 전체의 문장별 Typecast, 실시간 카드/확장, 완료 후 반응 음성 비용은 추가다. 동일 완료/별을 음성 실패 때문에 다시 지급하지 않는다.
- 카드 요청202/상태GET·Day 메뉴는 새 앱과 같은 변경 묶음으로 배포한다. 기존 동기 카드 API는 유지한다. 설치된 cue v1과105개 승인 음원은 바꾸지 않았다.
- 첫 배포는 API/Worker 한 인스턴스다. 여러 인스턴스의 복구 소유권·확장 배포는 이번 데모 범위가 아니다.
- 현재 환경에 Docker가 없어 이미지 실제 빌드/실행은 **NOT_RUN**이다. Python supervisor의 모의 검사와 소스 컴파일 성공을 이미지 실행 성공으로 표시하지 않는다.
- 실제 iPad 무음 수정, 홈/별/글자 효과, 두 날짜 각5편·웹5/5 확인은 사용자 인수 항목이다. 웹/백엔드 source ZIP에는 DB·실제 입력·키·node_modules·빌드 캐시가 없다.

SillyTavern 재사용 라이선스/변경 출처는 `worker/vendor/sillytavern`에 보존했다. 공용 서비스의 파생 소스 제공 등 라이선스 처리는 담당자가 포함한다.

## 전환·감정·Jev 품질 수정 묶음 (2026-11-05)

새 Worker는 같은 턴 요청에서 현재 NPC 감정·강도·원인·대사 근거를 받는다. 기존 결과/209개 음원을 수정하거나 자동 검수·수정 호출을 추가하지 않는다. 새 Typecast 구성 해시에는 `EF_NPC_TYPECAST_ACTING_V2`를 포함한다. 다른 구성으로 이미 예약한 작업을 같은 합성으로 위장하지 않는다.

선택적 Jev는 기본 OFF다. 사용 시 `EF_JEV_ENABLED=true`, 서버 비밀값 `TYPESAFE_API_KEY`, 명시적 `EF_JEV_MAX_REQUESTS`(첫 확인3), `EF_JEV_MODEL=jev-latest`, 절대 경로 `EF_JEV_LEDGER_DIRECTORY`가 필요하다. 이 경로는 재시작/재배포에도 유지되는 전용 볼륨이며 컨테이너 UID10001에만 읽기/쓰기를 허용한다. 단일 인스턴스에서 사용한다. 경로를 바꾸어 같은 확인의 시도 한도를 초기화하지 않는다.

Jev에는 승인된 이야기의 NPC 한 문장·화자·허구 감정만 보낸다. 아동 원문/프로필을 보내지 않는다. 새 STORY 음원 준비 중 최대 한 번 시도하고, 성공한 결과는 화자·문장 해시로 결속해 P1 `typecastActing.npcStyle`에 담는다. 완료 뒤 아동 반응 음성에는 호출하지 않는다. 2.5초 응답 제한/실패/불명/한도 소진이면 기존 로컬 동작을 사용하고 자동 재전송하지 않는다. iPad 재생은 추가 Jev 요청 없이 기존 AudioSource 시간으로 동기화한다. 이 경로의 실제 API 성공과 동작 자연스러움은 별도 인수 항목이다.

Unity는 현재 문장 음원만 준비하고 유효한30초 미디어 권한을 재사용한다. 권한 만료/화자·음원 해시 검사는 유지한다. 새 `presentation.place`는 승인한 원고의 장소에서 읽기 전용으로 투영한다. 기존 저장 package/완료/별/음원을 다시 쓰지 않는다. 예전 앱과 함께 배포할 때에도 기존 필드는 유지된다.

새 Unity 적용 소스에는 기존 r7/r32 장면·FBX/클립 교체가 없다. 실행 프로젝트의 검증된 Mixamo 카탈로그를 재사용한다. 새 실행 프로젝트를 만드는 담당자는 기존 Mixamo 준비 도구/자산을 함께 이관해야 하며, 소스 컴파일 성공을 리타기팅·iPad 연출 성공으로 보고하지 않는다.

## 배포 대상 확인과 유료 호출 없는 사전 검사 — 2026-11-06

GitHub의 웹 `main/vercel.json`은 기존 Render API를 외부 rewrite 대상으로 지정한다. 백엔드 `feat/backend-foundation/README.md`는 Render Docker 배포를 명시한다. 이 구성으로 준비하되 실제 배포 commit, auto-deploy 대상 브랜치, DB 연결값과 비밀값·영속 디스크의 현재 설정은 GitHub 파일만으로 확정하지 않는다. 기존 서비스나 환경 값을 자동 변경하지 않는다.

- 웹 근거: https://github.com/malandmouse/emotion-friend-web/blob/main/vercel.json
- 백엔드 근거: https://github.com/malandmouse/my-expression-friend-api/blob/feat/backend-foundation/README.md#render-배포-설정
- 플랫폼 안내: https://render.com/docs/docker , https://vercel.com/docs/routing/rewrites

담당자가 Docker를 사용할 수 있는 환경에서 다음 두 명령으로 이미지 자체만 먼저 확인한다. 이 명령은 컨테이너의 서버를 시작하지 않고 DB·GPT·Typecast·Jev에도 연결하지 않는다. 첫 image build는 공식 base image/Gradle 패키지를 내려받을 수 있다.

```sh
docker build -t expression-friend-prototype:review .
docker run --rm --entrypoint python3 expression-friend-prototype:review /app/runtime/preflight.py --image
```

동일 작업을 `sh runtime/check-image.sh` 한 번으로 실행할 수도 있다. 빌드 중에는 공식 이미지/의존 패키지를 다운로드할 수 있지만 검사 컨테이너는 `--network none`으로 실행한다. 환경 파일·키·DB 볼륨을 전달하지 않는다. Dockerfile 자체에도 이미지의 새 집필 모듈·P1 스키마·프롬프트·라이선스 포함 검사를 넣었다. 실제 호스트의 image build 결과는 따로 기록해야 한다.

그 뒤 호스팅에 **값을 이미 입력한 환경**에서 `python3 /app/runtime/preflight.py --environment`를 실행한다. 이 검사는 설정 존재·포트/모드·서로 다른 서버3키·비용 한도·미디어 lease·선택적 Jev 볼륨만 읽는다. 값 자체를 출력하지 않으며 DB 마이그레이션이나 제공자 요청을 실행하지 않는다. PASS는 설정 검사이며 LIVE/iPad 인수나 실제 DB 연결 성공을 뜻하지 않는다. `--image` PASS 또한 HTTPS·배포 성공의 대체가 아니다.

기존 DB 백업은 담당자가 기존 PostgreSQL 설정으로 `pg_dump -Fc`를 실행해 비공개 위치에 보관하고, 별도 복원 DB에서 마이그레이션부터 확인한다. 실제 비밀번호를 명령행 인자/ZIP에 넣지 않는다. V1~V17 원본 migration은 수정하지 않았다. 현재 Mac 실행기와 함께 실행 중인 demo DB를 배포 DB로 사용하지 않는다.

현재 작성 환경에는 Docker CLI가 없어 실제 이미지 빌드/실행은 여전히 NOT_RUN이다. 환경 guard의 무료 검사 통과를 이미지 실행으로 승격하지 않는다. 배포 담당자의 호스트에서 위 검사와 기존 계정 로그인·설정 저장·확정·기기 연결·완료 조회를 확인한 후 실제 배포 인수로 기록한다.
