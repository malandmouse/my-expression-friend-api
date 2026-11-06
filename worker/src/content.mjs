import fs from 'node:fs/promises';
import path from 'node:path';
import { TavernCardValidator } from '../vendor/sillytavern/TavernCardValidator.js';
import { ROOT, NPCS, readJson, sha256, need, clone } from './base.mjs';

export const cards = {};
for (const [npcId, name] of Object.entries(NPCS)) {
  const raw = await fs.readFile(path.join(ROOT, 'cards', `${name}.json`));
  const card = JSON.parse(raw);
  // 실제 SillyTavern 원본 검증기를 이 경로에서 실행한다.
  need(new TavernCardValidator(card).validateV2() && card.data.character_version === '0.3.0' && card.data.name === name,
    'CONFIG_UNAVAILABLE', '준·나래 v0.3.0 카드가 필요합니다.');
  cards[npcId] = { card, fileSha256: sha256(raw) };
}
const example = await readJson(path.join(ROOT, 'examples/generate-response.json'));
export const templates = example.scenarios;
// 임상/교육 검토된 문제은행이라는 표시는 하지 않는다. 로컬 실험용 고정 보기·문구다.
export const quizBank = templates.map(s => ({
  id: `lab-${s.draft.emotionQuestions[0].correctEmotionId.toLowerCase()}`, version: 'lab-fixture-0.1',
  emotionId: s.draft.emotionQuestions[0].correctEmotionId,
  options: clone(s.draft.emotionQuestions[0].options), reviewStatus: 'NOT_REVIEWED_FOR_CHILD_USE'
}));
const catalog = await readJson(path.join(ROOT, 'rag/sources.json'));
const rag = await Promise.all(catalog.map(async row => {
  need(path.basename(row.file) === row.file && row.file.endsWith('.md'), 'CONFIG_UNAVAILABLE', '등록된 RAG 파일만 사용할 수 있습니다.');
  return { ...row, text: await fs.readFile(path.join(ROOT, 'rag', row.file), 'utf8') };
}));
export function retrieve(input, enabled = true) {
  if (!enabled) return [];
  const query = JSON.stringify([input.childProfile.priorityGoals, input.childProfile.interests, input.continuity.openThreads]).toLowerCase();
  return rag.map((r, i) => ({ r, i, score: r.tags.reduce((n, t) => n + (query.includes(t.toLowerCase()) ? 1 : 0), 0) }))
    .sort((a, b) => b.score - a.score || a.i - b.i).slice(0, 3).map(x => x.r);
}
export const cardVersions = ids => [...new Set(ids)].map(npcId => ({ npcId, version: cards[npcId].card.data.character_version, fileSha256: cards[npcId].fileSha256 }));
