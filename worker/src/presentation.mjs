import path from 'node:path';
import { ROOT, readJson, need, clone } from './base.mjs';

// 같은 GPT 턴에서 고릅니다. 별도 감정 분류/Jev/추가 모델 요청은 없습니다.
export const presentationCatalog = await readJson(path.join(ROOT, 'contracts/gpt-presentation-v2.json'));
export const faceIds = Object.keys(presentationCatalog.faces);
export const animationIds = presentationCatalog.motions.map(motion => motion.id);
export function presentationFor(turn) {
  need(faceIds.includes(turn.faceCue) && turn.faceCue === turn.emotionUsed,
    'OUTPUT_INVALID', '대사 감정과 학습용 얼굴 표정이 다릅니다. 자동 재호출하지 않습니다.');
  need(animationIds.includes(turn.animationCue), 'OUTPUT_INVALID', '설치되지 않은 동작입니다.');
  return { faceCue: turn.faceCue, animationCue: turn.animationCue };
}
export function presentationContext(previous = 'NONE') {
  return { ...clone(presentationCatalog), previousAnimationCue: previous,
    selectionPolicy: '목록 전체를 선택할 수 있지만 모든 종류를 억지로 쓰지 않는다. 말과 의도가 맞을 때 하나만 고르고 작은 반응에는 NONE. 이전 동작을 기계적으로 반복하지 않는다. 비꼼/거절/화남은 대사에 그 뜻이 있을 때만 사용하며 아동을 평가하거나 비웃는 동작으로 쓰지 않는다.',
    timingPolicy: 'Typecast 길이는 아직 모른다. 짧은 말에는 긴 동작을 고르지 않는다. 실제 음성보다 긴 단발 동작은 Unity가 생략하며 배속/중간 절단하지 않는다. WEIGHT_SHIFT는 체중 이동이며 걷기/장소 이동이 아니다.' };
}
