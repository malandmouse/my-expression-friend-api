import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { randomUUID } from 'node:crypto';
import { LabStore } from '../src/store.mjs';
import { createApp, GENERATE_PATH, SUMMARY_PATH } from '../src/app.mjs';
import { compile, generate, mockModel, assemble, inspectGraph } from '../src/authoring.mjs';
import { summarize } from '../src/learning.mjs';
import { readJson, ROOT, clone, writeJson } from '../src/base.mjs';
import { check } from '../src/schema.mjs';
import { checkVendor } from '../scripts/check-vendor.mjs';

async function fixture(t, generator) {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'st-agent-test-'));
  t.after(() => fs.rm(dir, {recursive:true,force:true}));
  const store = new LabStore(dir, generator), app = createApp({store});
  const settings = await store.settings(); settings.request.jobId = randomUUID();
  const headers = {host:'127.0.0.1:5197',authorization:`Bearer ${app.token}`};
  const dispatch = (method,pathname,body,extra={}) => app.dispatch({method,pathname,body,headers:{...headers,...extra}});
  return {dir,store,app,settings,dispatch};
}

test('고정한 SillyTavern 원본 블록·카드 v0.3.0의 실제 출처', async () => {
  assert.equal((await checkVendor()).reusedBlocks, 'PASS');
});
test('설정 저장 → ST 프롬프트/구조화 출력 미리보기', async t => {
  const {settings,dispatch} = await fixture(t);
  settings.request.childProfile.nickname='시험 가명';
  settings.request.childProfile.interests=['기차'];
  settings.authoring.systemInstruction+='\n실험 지침: 사건별로 말투를 바꿔 보기.';
  assert.equal((await dispatch('POST','/api/settings',settings)).body.revision,1);
  const p=await dispatch('POST','/api/preview',settings.request);
  assert.equal(p.status,200);assert.equal(p.body.externalProviderCalls,0);
  assert.ok(p.body.messages[1].content.includes('시험 가명'));
  assert.ok(p.body.messages[1].content.includes('기차'));
  assert.ok(p.body.messages[0].content.includes('사건별로'));
  assert.equal(p.body.cards.length,2);
  assert.equal(p.body.requestBody.response_format.type,'json_schema');
  assert.ok(!JSON.stringify(p.body.requestBody.response_format).includes('#/components/'));
});
test('생성 API → 5편·선택별 반응·고정 문제·JSON 다운로드; 외부 fetch 0', async t => {
  const {settings,dispatch}=await fixture(t);
  const old=globalThis.fetch;let calls=0;globalThis.fetch=()=>{calls++;throw Error('network forbidden');};
  try {
    const r=await dispatch('POST',GENERATE_PATH,settings.request,{'idempotency-key':settings.request.jobId});
    assert.equal(r.status,200,JSON.stringify(r.body));check('GenerateDayResult',r.body);
    assert.equal(r.body.scenarios.length,5);assert.equal(r.body.provenance.modelCalls,0);
    for(const s of r.body.scenarios){const g=inspectGraph(s.draft);assert.ok(g.endings.length);assert.ok(s.draft.nodes.some(n=>n.choices.length));assert.ok(s.draft.emotionQuestions.every(q=>q.options.length===4));}
    const d=await dispatch('GET',`/api/jobs/${settings.request.jobId}/download`);
    assert.deepEqual(d.body,r.body);assert.match(d.headers['Content-Disposition'],/attachment/);
    const job=await dispatch('GET',`/api/jobs/${settings.request.jobId}`);
    assert.equal(job.body.lab.externalProviderCalls,0);assert.equal(job.body.lab.localTransportCalls,1);assert.equal(calls,0);
  } finally {globalThis.fetch=old;}
});
test('캐릭터 선택·일차·보고 경험은 제한된 모의 출력과 제작 입력에 반영', async t=>{
  const {settings}=await fixture(t),r=settings.request;
  r.childProfile.characterPreference='NARA';r.program.programDay=9;
  r.dailyEvents[0].text='오늘 체육 시간에 줄의 순서가 바뀌었어.';
  const v=await generate(r,settings.authoring);
  assert.ok(v.result.scenarios.every(s=>s.draft.npcId==='MainPlayer'&&s.draft.topic.startsWith('Day 9')));
  assert.ok(v.result.scenarios[4].draft.nodes.some(n=>n.choices.some(c=>c.text===r.dailyEvents[0].text)));
  assert.ok(v.result.scenarios.every(s=>s.draft.nodes.every(n=>!n.text.includes(r.dailyEvents[0].text))));
});
test('동일 작업의 병렬·재시작 요청은 캐시, 변경된 멱등키 내용은 충돌',async t=>{
  let calls=0;const {dir,store,settings}=await fixture(t,async(...args)=>{calls++;return generate(...args);});
  const [a,b]=await Promise.all([store.run(settings.request,'same-key'),store.run(settings.request,'same-key')]);
  assert.equal(a.status,'DONE');assert.equal(b.cached,true);assert.equal(calls,1);
  assert.equal((await new LabStore(dir).run(settings.request,'same-key')).cached,true);
  const changed=clone(settings.request);changed.program.programDay++;
  await assert.rejects(store.run(changed,'same-key'),e=>e.code==='VERSION_CONFLICT');
});
test('중단된 RUNNING 예약은 재전송하지 않음',async t=>{
  const {dir,store,settings}=await fixture(t);
  await store.run(settings.request,'interrupted');
  const file=path.join(dir,'jobs',`${settings.request.jobId}.json`),record=await readJson(file);record.status='RUNNING';await writeJson(file,record);
  await assert.rejects(new LabStore(dir).run(settings.request,'interrupted'),e=>e.code==='OUTCOME_UNKNOWN');
});
test('존재하지 않는 분기는 구조 검사에서 오류; 자동 수정 없음',async t=>{
  const {settings}=await fixture(t),compiled=compile(settings.request,settings.authoring),raw=mockModel(settings.request,compiled);
  raw.scenarios[0].draft.nodes[0].choices[0].targetNodeId='missing';
  assert.throws(()=>assemble(settings.request,settings.authoring,compiled,raw,{attempted:false}),e=>e.code==='OUTPUT_INVALID');
});
test('실제 전송 경로도 테스트용 주입 transport 한 번만 사용 (외부 호출 아님)',async t=>{
  const {settings}=await fixture(t);let calls=0;
  const v=await generate(settings.request,settings.authoring,{paidEnabled:true,apiKey:'UNIT_TEST_FAKE_NOT_A_KEY',fetchImpl:async(url,options)=>{
    calls++;assert.equal(url,'https://api.openai.com/v1/chat/completions');
    const body=JSON.parse(options.body);assert.equal(body.n,1);assert.equal(body.stream,false);
    return new Response(JSON.stringify({id:'fake-response',usage:{prompt_tokens:10,completion_tokens:20,total_tokens:30},choices:[{finish_reason:'stop',message:{content:JSON.stringify(mockModel(settings.request,compile(settings.request,settings.authoring)))}}]}));
  }});
  assert.equal(calls,1);assert.equal(v.result.provenance.usage.totalTokens,30);
  // 위 LIVE 표시는 주입된 단위 시험 응답 안에만 존재하며 사용/비용 증거로 저장하지 않는다.
});
test('학습 기록: 도움·AI문장·장애 구분, 중복 제거, 다음 생성 참고',async t=>{
  const {dispatch}=await fixture(t),r=await readJson(path.join(ROOT,'examples/summarize-request.json'));
  r.observations.push(clone(r.observations[0]));r.receipts.push(clone(r.receipts[0]));
  const response=await dispatch('POST',SUMMARY_PATH,r),s=response.body;
  assert.equal(response.status,200);check('SummarizeResult',s);
  assert.equal(s.counts.completedSlots,3);assert.equal(s.counts.recognition.matched,2);
  assert.equal(s.counts.recognition.matchedWithReplayOrHint,1);assert.equal(s.counts.expression.aiComposed,1);
  assert.equal(s.counts.excluded.technicalFailure,1);assert.equal(s.counts.excluded.duplicateEvents,1);
  assert.equal(s.completionWrites+s.starWrites+s.modelCalls,0);assert.equal(s.nextTraining.profileChanged,false);
});
test('거절은 오답이 아님; 상충하는 영수증은 거절',async()=>{
  const r=await readJson(path.join(ROOT,'examples/summarize-request.json'));
  const row=clone(r.observations[3]);row.eventId='refusal';row.refused=true;row.responseRecorded=false;r.observations.push(row);
  assert.equal(summarize(r).counts.expression.refused,1);
  const conflict=clone(r.receipts[0]);conflict.starsAwarded=0;r.receipts.push(conflict);
  assert.throws(()=>summarize(r),e=>e.code==='VERSION_CONFLICT');
});
test('로컬 세션·Host/Origin 제한과 stale 설정 충돌',async t=>{
  const {settings,dispatch}=await fixture(t);
  assert.equal((await dispatch('GET','/api/settings',null,{authorization:''})).status,401);
  assert.equal((await dispatch('GET','/api/session',null,{host:'evil.example:5197'})).status,403);
  assert.equal((await dispatch('POST',GENERATE_PATH,settings.request,{origin:'https://evil.example'})).status,403);
  assert.equal((await dispatch('POST','/api/settings',settings)).status,200);
  assert.equal((await dispatch('POST','/api/settings',settings)).status,409);
});
test('Home은 초대 근거가 없으면 설정 단계에서 보류',async t=>{
  const {settings,dispatch}=await fixture(t);settings.authoring.permittedPlaces=['Home'];
  const r=await dispatch('POST','/api/settings',settings);assert.equal(r.status,422);assert.equal(r.body.error.code,'INPUT_INVALID');
});
