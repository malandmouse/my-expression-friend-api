import { clone, need } from './base.mjs';

export const DIALOGUE_CONFIGURATION_REF = 'prototype-st-v2.1-ko4';
export const conversationIntents = ['SHARE_NEWS', 'SHARE_INTEREST', 'EXCHANGE_OPINIONS',
  'ASK_CHILD_STORY', 'WONDER_TOGETHER', 'SHARE_FEELING', 'FOLLOW_UP'];

// 학습용 예제 문제/정답은 이야기 씨드가 아니다. 날짜마다 대화 목적과 장소를 회전한다.
// 이것은 집필 계획이며 아직 아동이 듣거나 동의한 사건/기억으로 저장하지 않는다.
export function dailyDialogueSeeds(input, settings) {
  const preference = input.childProfile.characterPreference;
  const cast = preference === 'JUN' ? ['JungHoon'] : preference === 'NARA' ? ['MainPlayer'] : ['JungHoon', 'MainPlayer'];
  const day = input.program.programDay - 1, used = new Set();
  return Array.from({ length: 5 }, (_, i) => {
    const npcId = cast[(i + day) % cast.length];
    const places = settings.permittedPlaces.filter(place => place !== 'Home' ||
      input.continuity.placePermissions.some(permission => permission.place === place && permission.npcId === npcId));
    need(places.length > 0, 'INPUT_INVALID', '허용된 만남 장소가 없습니다. Home은 초대 근거가 필요합니다.');
    const hasMemory = input.continuity.knownFacts.some(fact => fact.heardByNpcIds.includes(npcId));
    const candidates = conversationIntents.map((_, j) => conversationIntents[(day + i + j) % conversationIntents.length]);
    const intent = candidates.find(value => !used.has(value) && (value !== 'FOLLOW_UP' || hasMemory));
    need(intent, 'CONFIG_UNAVAILABLE', '다섯 편에 사용할 서로 다른 대화 목적이 필요합니다.');
    used.add(intent);
    return { slot: i + 1, npcId, place: places[(i + day) % places.length], conversationIntent: intent,
      openingMode: intent === 'FOLLOW_UP' ? 'RECEIPT_BACKED_FOLLOW_UP' : 'ENTER_THE_TOPIC',
      emotionPolicy: 'MAY_REMAIN_STABLE', tensionPolicy: 'OPTIONAL_NOT_DEFAULT',
      endingPolicy: 'CLOSE_THIS_EXCHANGE_WITHOUT_FORCED_RESOLUTION' };
  });
}

// 같은 날 앞편은 예정된 노출일 뿐이다. 대사/선택/친밀함을 실제 기억으로 승격하지 않는다.
export function encounterFor(input, seeds, seed, stage = 'INTRO') {
  const relationship = input.continuity.relationships.find(value => value.npcId === seed.npcId);
  const facts = input.continuity.knownFacts.filter(value => value.heardByNpcIds.includes(seed.npcId));
  const prior = relationship?.meetingState === 'PRIOR_INTERACTION_CONFIRMED' || facts.length > 0;
  const meetingState = prior ? 'PRIOR_INTERACTION_CONFIRMED' : relationship?.meetingState ?? 'UNKNOWN';
  const earlierSlots = seeds.filter(value => value.slot < seed.slot && value.npcId === seed.npcId).map(value => value.slot);
  const evidenceRefs=[...new Set([...(relationship?.evidenceRefs??[]),...facts.map(fact=>fact.evidenceEventId)])].slice(0,10);
  const introductionAllowed = stage === 'INTRO' && !prior && meetingState === 'FIRST_MEETING_CONFIRMED' && evidenceRefs.length > 0 && earlierSlots.length === 0;
  return { npcId: seed.npcId, meetingState, evidenceRefs: clone(evidenceRefs),
    introductionAllowed, introductionPolicy: introductionAllowed ? 'OPTIONAL_BRIEF_FIRST_INTRODUCTION' : 'NO_SELF_INTRODUCTION',
    answerNameWhenAskedAllowed: true,
    earlierPlannedAppearanceSlots: earlierSlots, earlierAppearancesAreConfirmedMemories: false,
    cardFirstMeetingSettingIsConditional: true,
    guidance: introductionAllowed ? '처음 만남이 확인된 첫 등장이다. 필요하면 상황과 연결해 한 번만 짧게 소개한다.' :
      '먼저 자기소개를 반복하지 않고 이번 화제로 이어 간다. 이름을 물어본 선택에는 이름을 답할 수 있지만 첫 만남 인사로 다시 시작하지 않는다. UNKNOWN을 첫 만남으로 단정하지 않는다. 예정된 앞편을 실제로 들었다고 말하지 않는다.' };
}

export function dialogueCard(card, encounter) {
  const example = card.mes_example ?? '';
  // 원본0.3.0 카드는 보존한다. 이미 아는/불명 관계에는 첫 만남 예시를 재주입하지 않는다.
  const dialogueExamples = encounter.introductionAllowed ? example : example.split('<START>')
    .filter(block => block.trim() && !/처음|안녕|(?:난|나는|나)\s*(?:나래|준(?:이)?)(?:야|라고|이라고)/u.test(block))
    .map(block => '<START>' + block).join('');
  const description = encounter.introductionAllowed ? card.description : (card.description ?? '')
    .split(/(?=\[[^\]\n]+\])/u).filter(section => !section.startsWith('[정체성과 세계]')).join('');
  return { name: card.name, version: card.character_version, description, personality: card.personality,
    dialogueExamples, currentEncounterOverridesStaticOpening: true };
}
