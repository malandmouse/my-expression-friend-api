# foundation → Agent 매핑

| Agent 입력 | foundation 원천/처리 |
|---|---|
| childId | 권한 검사한 URL child UUID |
| nickname | care.child.pseudonym, 서버 내부 조회 |
| ageBands | profile.ageBand 1개를 배열로 변환. 복수선택 확장은 후속 협의 |
| language | profile.primaryLanguage, 현재 ko |
| interests/preferredActivities/avoidTopics | profile 동일 필드, 부재는 [] |
| sensoryPreferences | profile 문자열 배열을 ;로 연결, 최대500자. 원본 프로필은 변경하지 않음 |
| experienceNarrative | foundation 지원 없음 → null |
| emotionExpression | 지원 없음 → null |
| understanding 3축 | 지원 없음 → 각각 null. UNKNOWN/성별/연령으로 능력을 추론하지 않음 |
| expressionExamples | foundation 원천 없음 → [] |
| characterPreference | 제작 화면 JUN/NARA/BOTH 설정 |
| priorityGoals | 제작 화면 요청의 최대2개. 현재 화면 기본 감정 맥락/자기 마음 |
| dailyEvents | 제작 화면에서 명시 입력한 보호자 보고. CHILD_REPLY_CANDIDATE_ONLY |
| recentExperienceRef | 참조를 실제 내용으로 해석하지 않음. resolver가 없으므로 자동으로 본문을 만들지 않음 |
| continuity | 관계·초대·장기 기억 원천 미연결 → 빈 목록. 첫 만남이라고 자동 단정하지 않음 |
| nextTraining | 자동 연결 미완료 → null. 별도 summary API는 수락 receipt만 제공 |
| sourceSnapshot | LearningCurrent의 profileVersion/CORE_PROFILE_LEARNING revision과 서버 생성 ID |

프로필의 그림 지원/의사소통 방식 legacy 필드는 신규 언어 수준 축에 자동 매핑하지 않았습니다. 설정 모델 확장은 웹 담당자와 별도 합의가 필요합니다.

DB는 care/identity/learning 기존 foundation이 정본입니다. Worker로부터 아동·권한·완료·별·기억 사실을 역으로 받지 않습니다. 요약의 인식 정답0은 능력0을 의미하지 않으며 observationDataAvailable=false와 한계를 함께 표시합니다.
