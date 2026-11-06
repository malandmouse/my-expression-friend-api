import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { generateStateful, transition, applyDialogueEmotion, koreanPeerStyle } from '../src/stateful-authoring.mjs';
import { defaultAuthoring } from '../src/authoring.mjs';

const input=JSON.parse(await fs.readFile(new URL('../examples/generate-request.json',import.meta.url)));
const state={npcId:'JungHoon',world:{place:'Class',establishedEvents:['도움을 기다리는 허구 사건']},beliefs:[],goal:{id:'g',description:'도움을 구하기'},emotion:{id:'FEAR',intensity:0.6,cause:'도움이 필요하다.'},unresolved:['도움']};
test('rules separate help/question states; respectful refusal preserves emotion; unmatched maps to wire UNCLEAR',()=>{
  const a=transition(state,'HELP_OFFER','도와줄게',{goalCategory:'NEEDS_HELP'});
  const b=transition(state,'INFORMATION_QUESTION','어떤 도움이 필요해?',{goalCategory:'NEEDS_HELP'});
  assert.equal(a.after.emotion.id,'RELIEF');assert.equal(b.after.emotion.id,'FEAR');assert.equal(state.emotion.id,'FEAR');
  a.after.world.establishedEvents.push('A only');assert.equal(b.after.world.establishedEvents.length,1);
  assert.equal(transition(state,'RESPECTFUL_DECLINE','지금은 어려워').after.emotion.id,'FEAR');
  assert.equal(transition(state,'HELP_OFFER','도와줄게',{goalCategory:'SHARE_EXPERIENCE'}).appraisal.eventCode,'UNCLEAR');
});
export function mockPort() {
  const captured=[];
  return {live:false,captured,requestIds:[],async complete(key,request,data) {
    captured.push({key,request,data:structuredClone(data)});
    assert.equal(request.response_format.type,'json_schema');
    if(key==='planner')return {scenarios:data.seeds.map(s=>({slot:s.slot,topic:`연결 검사 ${s.slot}`,summary:'도움이 필요한 캐릭터와 이야기하는 모의 원고다.',meetingReason:'같은 교실 친구가 쉬는 시간에 만난다.',event:'물건을 옮기는 데 도움이 필요한 허구 사건이다.',goal:'도움이 필요한 일을 이야기하기',goalCategory:'NEEDS_HELP',hasUnresolvedIssue:true,emotionPattern:'OPEN_TO_CHANGE',initialEmotion:'FEAR',cause:'혼자 하기 어려운 일이 있다.'}))};
    const npcLine=`${data.state.emotion.id==='RELIEF'?'네 말을 들으니 안심이 돼.':'어떻게 할지 걱정하고 있었어.'} ${key.includes('intro')?'내 이야기를 들려줄게.':'네가 한 말을 듣고 이야기하는 중이야.'}`;
    return {npcLine,emotionIntensity:0.5,emotionCause:'허구 사건의 현재 마음이다.',emotionEvidenceQuote:npcLine,goalOutcome:'UNCHANGED',
      emotionUsed:data.state.emotion.id,faceCue:data.state.emotion.id,animationCue:'NONE',choiceCandidates:data.output.choiceCount?[{choiceId:'a',text:'같이 도와줄게.',observationFocus:'CONTEXTUAL_RESPONSE',eventCode:'HELP_OFFER',evidenceText:'도움을 제안하는 표현이다.'},
        {choiceId:'b',text:'어떤 도움이 필요해?',observationFocus:'CLARIFICATION',eventCode:'INFORMATION_QUESTION',evidenceText:'도움의 내용을 확인하는 질문이다.'}]:[]};
  },async budget(){return {callsUsed:0,inputTokensUsed:0,outputTokensUsed:0,estimatedCostMicrousd:0,inputTokensReserved:0,outputTokensReserved:0,estimatedCostReservedMicrousd:0,isFinal:true};}};
}
test('five V2.1 graphs use ST request builder, eight nodes/four endings, actual branch histories and terminal quiz bindings',async()=>{
  const model=mockPort();const result=await generateStateful(input,defaultAuthoring,model);
  assert.equal(result.contractVersion,'EF_ST_AGENT_DRAFT_V2_1');assert.equal(result.scenarios.length,5);assert.equal(model.captured.length,41);
  for(const scenario of result.scenarios) {
    assert.equal(scenario.draft.nodes.length,8);assert.equal(scenario.draft.emotionQuestions.length,4);
    for(const q of scenario.draft.emotionQuestions)assert.equal(q.targetNodeId,q.terminalNodeId);
  }
  const a=model.captured.find(c=>c.key==='s1:moment_0'),b=model.captured.find(c=>c.key==='s1:moment_1');
  assert.equal(a.data.state.emotion.id,'RELIEF');assert.equal(b.data.state.emotion.id,'FEAR');
  assert.equal(a.data.history.at(-1).content,'같이 도와줄게.');assert.equal(b.data.history.at(-1).content,'어떤 도움이 필요해?');
  assert.ok(!b.data.history.some(h=>h.content==='같이 도와줄게.'));
  assert.equal(result.provenance.modelCalls,0);assert.equal(result.publicationAllowed,false);
  // Verify the actual ST-bound request, not a separate preview or a second writer/reviewer.
  const opening=model.captured.find(c=>c.key==='s1:intro');
  assert.equal(opening.data.dialogueStyle.version,koreanPeerStyle.version);
  assert.equal(opening.data.dialogueStyle.referenceOnly,true);
  assert.match(opening.request.messages[0].content,/한국어 말투 지침/);
  assert.ok(opening.request.messages.at(-1).content.includes('대회 안내문'));
  assert.ok(model.captured.find(c=>c.key==='s1:moment').data.dialogueStyle.example.some(e=>e.childReply==='무슨 대회인데?'));
});
test('a malformed paid candidate stops once without repair or automatic retry',async()=>{
  const model=mockPort();let calls=0;model.complete=async()=>{calls++;return {bad:true}};
  await assert.rejects(generateStateful(input,defaultAuthoring,model));assert.equal(calls,1);
});

test('dialogue-grounded emotion can resolve previous fear without changing another branch',()=>{
  const turn={npcLine:'빗자루가 움직인 거였네. 이제 마음이 놓여.',emotionUsed:'RELIEF',emotionIntensity:.3,emotionCause:'소리의 원인을 알았다.',emotionEvidenceQuote:'이제 마음이 놓여.',goalOutcome:'SUCCESS'};
  const after=applyDialogueEmotion(state,turn);assert.equal(after.emotion.id,'RELIEF');assert.equal(after.emotion.intensity,.3);assert.deepEqual(after.unresolved,[]);assert.equal(state.emotion.id,'FEAR');
  assert.throws(()=>applyDialogueEmotion(state,{...turn,emotionEvidenceQuote:'원문에 없는 근거'}));
});
test('the currently spoken emotion drives the face and terminal question after a resolution',async()=>{
  const model=mockPort(),complete=model.complete.bind(model);
  model.complete=async(key,request,data)=>{
    const turn=await complete(key,request,data);
    if(key.includes(':moment_1_'))Object.assign(turn,{npcLine:'불빛이 켜졌네. 이제 마음이 놓여.',emotionUsed:'RELIEF',faceCue:'RELIEF',emotionIntensity:.333333,emotionCause:'원인을 확인했다.',emotionEvidenceQuote:'이제 마음이 놓여.',goalOutcome:'SUCCESS'});
    return turn;
  };
  const result=await generateStateful(input,defaultAuthoring,model);
  for(const s of result.scenarios)for(const n of s.draft.nodes.filter(n=>n.nodeId.startsWith('moment_1_'))){
    assert.equal(n.emotionId,'RELIEF');assert.equal(n.faceCue,'RELIEF');assert.equal(n.emotionIntensity,.3333);
    assert.equal(s.draft.emotionQuestions.find(q=>q.targetNodeId===n.nodeId).correctEmotionId,'RELIEF');
  }
});
