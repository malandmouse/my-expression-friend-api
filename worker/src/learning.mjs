import { check } from './schema.mjs';
import { VERSION, need, fingerprint } from './base.mjs';

export function summarize(input) {
  check('SummarizeRequest', input);
  need(Date.parse(input.period.startInclusive) < Date.parse(input.period.endExclusive), 'INPUT_INVALID', '조회 시작은 종료보다 앞서야 합니다.');
  const receipts = new Map(), slots = new Set();
  for (const r of input.receipts) {
    const previous = receipts.get(r.receiptId);
    if (previous) { need(fingerprint(previous) === fingerprint(r), 'VERSION_CONFLICT', '같은 영수증 ID의 내용이 다릅니다.', 409); continue; }
    const key = `${r.sessionId}/${r.slot}`;
    need(!slots.has(key), 'VERSION_CONFLICT', '같은 세션·슬롯에 서로 다른 완료 영수증이 있습니다.', 409);
    slots.add(key); receipts.set(r.receiptId, r);
  }
  const recognition = { validFirstResponses: 0, matched: 0, mismatched: 0, matchedWithoutRecordedSupport: 0, matchedWithReplayOrHint: 0, supportUnknown: 0 };
  const expression = { childOriginal: 0, preparedChoice: 0, cards: 0, aiComposed: 0, unknown: 0, refused: 0 };
  const excluded = { duplicateEvents: 0, invalidOrUnpresented: 0, technicalFailure: 0, noResponse: 0 };
  const events = new Map(), firsts = new Set(), evidence = [], failures = [];
  for (const row of input.observations) {
    const prior = events.get(row.eventId);
    if (prior) { need(fingerprint(prior) === fingerprint(row), 'VERSION_CONFLICT', '같은 이벤트 ID의 내용이 다릅니다.', 409); excluded.duplicateEvents++; continue; }
    events.set(row.eventId, row);
    if (row.technicalFailure) { excluded.technicalFailure++; continue; }
    if (!row.presented || !row.itemValid) { excluded.invalidOrUnpresented++; continue; }
    if (row.kind === 'SELF_EXPRESSION' && row.refused) {
      need(row.outcome === 'UNSCORED', 'INPUT_INVALID', '자기표현·거절은 정오로 채점하지 않습니다.'); expression.refused++; continue;
    }
    if (!row.responseRecorded) { excluded.noResponse++; continue; }
    if (row.kind === 'SELF_EXPRESSION') {
      need(row.outcome === 'UNSCORED', 'INPUT_INVALID', '아동 자신의 감정에는 정오 점수를 붙이지 않습니다.');
      const field = { CHILD_ORIGINAL: 'childOriginal', PREPARED_CHOICE: 'preparedChoice', CARDS: 'cards', AI_COMPOSED: 'aiComposed', UNKNOWN: 'unknown' }[row.authoredBy];
      expression[field]++; continue;
    }
    need(row.questionInstanceId !== null && row.outcome !== 'UNSCORED', 'INPUT_INVALID', '유효 감정 응답에는 문항과 backend 판정이 필요합니다.');
    if (!row.firstAttempt) continue;
    const firstKey = `${row.attemptId}/${row.questionInstanceId}`;
    need(!firsts.has(firstKey), 'INPUT_INVALID', '같은 문항의 첫 응답이 중복 선언됐습니다.'); firsts.add(firstKey);
    recognition.validFirstResponses++; evidence.push(row.eventId);
    if (row.support === 'UNKNOWN') recognition.supportUnknown++;
    if (row.outcome === 'MATCH') {
      recognition.matched++;
      if (row.support === 'NONE') recognition.matchedWithoutRecordedSupport++;
      else if (row.support !== 'UNKNOWN') recognition.matchedWithReplayOrHint++;
    } else { recognition.mismatched++; failures.push(...row.goalIds); }
  }
  const priorityGoals = [...new Set([failures[0] ?? 'EMOTION_CONTEXT', 'SELF_FEELING'])];
  const nextTraining = { priorityGoals, explanationHint: failures.length ? 'RECAP_CAUSE' : 'MAINTAIN',
    reasonCodes: [failures.length ? 'MAINTAIN_GOAL_WITH_NEW_EVENT' : evidence.length ? 'MAINTAIN_AND_VARY_EVENT' : 'INSUFFICIENT_EVIDENCE_MAINTAIN'],
    evidenceEventIds: evidence.slice(0, 100), repeatItemIds: [], profileChanged: false, publishedScenarioChanged: false };
  const count = receipts.size;
  const result = { contractVersion: VERSION, childId: input.childId, observationSnapshotId: input.observationSnapshotId,
    observationRevision: input.observationRevision,
    counts: { completedSlots: count, starsFromReceipts: [...receipts.values()].reduce((n, r) => n + r.starsAwarded, 0), recognition, expression, excluded },
    caregiverText: `입력된 완료 영수증 ${count}건입니다. 유효한 첫 감정 응답 ${recognition.validFirstResponses}건 중 ${recognition.matched}건이 문항의 허용 답과 일치했습니다. 일치한 응답 중 다시 듣기·힌트·명시 설명을 사용한 것은 ${recognition.matchedWithReplayOrHint}건입니다. 자기표현은 아동 원문 ${expression.childOriginal}건, 카드 ${expression.cards}건, AI 확장문 ${expression.aiComposed}건으로 구분했습니다. 기술 장애 ${excluded.technicalFailure}건은 응답 집계에서 제외했습니다.`,
    limitations: ['NOT_A_DIAGNOSTIC_SCORE', 'LAB_SUPPLIED_OBSERVATIONS_NOT_FOUNDATION_ACCEPTED', 'SUPPORT_CONDITIONS_MUST_BE_READ',
      'AI_COMPOSED_IS_NOT_INDEPENDENT_CHILD_LANGUAGE', 'FREE_TEXT_MEANING_NOT_AUTOMATICALLY_ANALYZED',
      ...(evidence.length > 100 ? ['EVIDENCE_REFS_CAPPED_AT_100_COUNTS_UNCHANGED'] : [])],
    nextTraining, schedulerMode: 'DISABLED_NO_FIXED_BANK', modelCalls: 0, completionWrites: 0, starWrites: 0 };
  return check('SummarizeResult', result, { stage: 'ASSEMBLY' });
}
