import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { dailyDialogueSeeds, encounterFor, dialogueCard, DIALOGUE_CONFIGURATION_REF } from '../src/dialogue-planning.mjs';
import { generateStateful } from '../src/stateful-authoring.mjs';
import { defaultAuthoring } from '../src/authoring.mjs';
import { cards } from '../src/content.mjs';
import { mockCandidate } from '../integration/stateful-worker.mjs';

function mockPort() {
  const captured=[];
  return {live:false,captured,requestIds:[],async complete(key,request,data){captured.push({key,request,data:structuredClone(data)});return mockCandidate(key,data);},
    async budget(){return {callsUsed:0,inputTokensUsed:0,outputTokensUsed:0,estimatedCostMicrousd:0,inputTokensReserved:0,outputTokensReserved:0,estimatedCostReservedMicrousd:0,isFinal:true};}};
}

const base=JSON.parse(await fs.readFile(new URL('../examples/generate-request.json',import.meta.url)));
test('daily seeds vary intent/place without importing quiz answers and never invent a Home invitation or shared memory',()=>{
  const input=structuredClone(base), before=JSON.stringify(input), days=[];
  for(let day=1;day<=30;day++) {
    const request={...input,program:{...input.program,programDay:day}};
    const seeds=dailyDialogueSeeds(request,defaultAuthoring);days.push(seeds);
    assert.equal(new Set(seeds.map(x=>x.conversationIntent)).size,5);
    assert.ok(seeds.every(x=>!('targetEmotionId' in x)&&!('bankItemId' in x)&&x.place!=='Home'&&x.conversationIntent!=='FOLLOW_UP'));
  }
  assert.notDeepEqual(days[0],days[1]);assert.equal(JSON.stringify(input),before);
  assert.throws(()=>dailyDialogueSeeds(input,{...defaultAuthoring,permittedPlaces:['Home']}));
});

test('only an explicitly confirmed first encounter may introduce once in the planned day; UNKNOWN is not first meeting',()=>{
  const input=structuredClone(base), seeds=dailyDialogueSeeds(input,defaultAuthoring), first=seeds[0], repeated=seeds.find(x=>x.slot>1&&x.npcId===first.npcId);
  assert.equal(encounterFor(input,seeds,first).introductionAllowed,true);
  assert.equal(encounterFor(input,seeds,first,'CHOICE').introductionAllowed,false);
  const later=encounterFor(input,seeds,repeated);
  assert.equal(later.introductionAllowed,false);assert.deepEqual(later.earlierPlannedAppearanceSlots,[1]);
  assert.equal(later.earlierAppearancesAreConfirmedMemories,false);
  input.continuity.relationships=[];
  assert.equal(encounterFor(input,seeds,first).meetingState,'UNKNOWN');
  assert.equal(encounterFor(input,seeds,first).introductionAllowed,false);
  input.continuity.relationships=[{npcId:first.npcId,meetingState:'PRIOR_INTERACTION_CONFIRMED',evidenceRefs:['actual-receipt']}];
  const known=encounterFor(input,seeds,first);
  assert.equal(known.introductionAllowed,false);assert.deepEqual(known.evidenceRefs,['actual-receipt']);
  const raw=JSON.stringify(cards[first.npcId]);dialogueCard(cards[first.npcId].card.data,known);
  assert.equal(JSON.stringify(cards[first.npcId]),raw,'Projection never edits the version0.3.0 source card.');
});

test('real ST-bound requests carry encounter policy on all40 turns; a story may have no unresolved problem and retain the same feeling without repair',async()=>{
  const input=structuredClone(base);input.continuity.relationships=[];
  const model=mockPort(), original=model.complete.bind(model);
  model.complete=async(key,request,data)=>{
    const value=await original(key,request,data);
    if(key==='planner')for(const scene of value.scenarios)Object.assign(scene,{event:null,hasUnresolvedIssue:false,goalCategory:'SHARE_EXPERIENCE',goal:'친구에게 오늘 생각난 이야기를 들려주기',initialEmotion:'HAPPY',emotionPattern:'STABLE',cause:'편하게 이야기를 나누고 있다.'});
    else Object.assign(value,{npcLine:'난 그 장면이 계속 생각나서 웃고 있었어.',emotionUsed:'HAPPY',faceCue:'HAPPY',emotionIntensity:.2,emotionCause:'재미있는 이야기를 나누고 있다.',emotionEvidenceQuote:'그 장면이 계속 생각나서 웃고 있었어.',goalOutcome:'UNCHANGED'});
    return value;
  };
  const result=await generateStateful(input,defaultAuthoring,model);
  assert.equal(model.captured.length,41);assert.equal(result.provenance.automaticRepairCalls,0);
  for(const turn of model.captured.filter(x=>x.key!=='planner')) {
    assert.equal(turn.data.currentEncounter.meetingState,'UNKNOWN');
    assert.equal(turn.data.currentEncounter.introductionAllowed,false);
    assert.deepEqual(turn.data.state.unresolved,[]);assert.deepEqual(turn.data.state.world.establishedEvents,[]);
    assert.ok(turn.request.messages.at(-1).content.includes('NO_SELF_INTRODUCTION'));
  }
  for(const scene of result.scenarios) {
    assert.equal(scene.draft.nodes.length,8);
    assert.ok(scene.draft.emotionQuestions.every(q=>q.correctEmotionId==='HAPPY'));
    assert.ok(scene.stateTrace.nodeStates.every(n=>n.after.emotion.id==='HAPPY'&&n.after.unresolved.length===0));
  }
});

test('a changed live authoring policy refuses old partial jobs before any paid request',async()=>{
  const input=structuredClone(base);input.configurationRef='prototype-st-v2.1-ko2';
  const model=mockPort();model.live=true;
  await assert.rejects(generateStateful(input,defaultAuthoring,model),error=>error.code==='CONFIG_UNAVAILABLE');
  assert.equal(model.captured.length,0);assert.equal(DIALOGUE_CONFIGURATION_REF,'prototype-st-v2.1-ko4');
});
