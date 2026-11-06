import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { generateStateful } from '../src/stateful-authoring.mjs';
import { defaultAuthoring } from '../src/authoring.mjs';
import { animationIds, faceIds, presentationCatalog } from '../src/presentation.mjs';
import { mockCandidate } from '../integration/stateful-worker.mjs';
import { mediaWorker } from '../integration/media-worker.mjs';
import { toCoreDraft } from '../integration/foundation.mjs';

const input=JSON.parse(await fs.readFile(new URL('../examples/generate-request.json',import.meta.url)));
function port(mismatch=false){let turns=0;const requests=[];return {live:false,requests,requestIds:[],async complete(key,request,data){requests.push({key,request,data});const value=mockCandidate(key,data);if(key!=='planner'){value.animationCue=animationIds[turns++%animationIds.length];if(mismatch)value.faceCue='ANGRY';}return value;},async budget(){return {callsUsed:0,inputTokensUsed:0,outputTokensUsed:0,estimatedCostMicrousd:0,inputTokensReserved:0,outputTokensReserved:0,estimatedCostReservedMicrousd:0,isFinal:true};}};}
test('all installed motions travel through the actual GPT schema, five graphs and Core adapter without extra calls',async()=>{
  const model=port(),result=await generateStateful(input,defaultAuthoring,model),selected=new Set();
  assert.equal(model.requests.length,41);assert.equal(result.provenance.automaticRepairCalls,0);
  for(const call of model.requests.filter(x=>x.key!=='planner')){
    const schema=call.request.response_format.json_schema.schema;
    assert.deepEqual(schema.properties.animationCue.enum,animationIds);
    assert.deepEqual(schema.properties.faceCue.enum,faceIds);
    assert.equal(call.data.installedPresentation.jevCalls,0);
  }
  for(const scene of result.scenarios){
    const core=toCoreDraft(scene,input.jobId);
    for(const node of scene.draft.nodes){selected.add(node.animationCue);assert.equal(node.faceCue,node.emotionId);assert.equal(core.payload.sceneGraph.scenes.find(n=>n.sceneId===node.nodeId).animationCue,node.animationCue);}
  }
  assert.equal(selected.size,18);assert.equal(presentationCatalog.motions.filter(m=>!['NONE','ASK','NOD'].includes(m.id)).length,15);
});
test('face/emotion disagreement stops the current result once, without a repair/provider retry',async()=>{
  const model=port(true);await assert.rejects(generateStateful(input,defaultAuthoring,model),e=>e.code==='OUTPUT_INVALID');assert.equal(model.requests.length,2);
});
test('old Jev environment settings cannot trigger Jev while preparing new STORY audio',async()=>{
  const fake=mediaWorker(),config=(await fake({method:'GET',path:'/internal/v2/media-config'})).body;
  const wav=(await fake({method:'POST',path:'/internal/v2/sentence-media',body:{configurationHash:config.configurationHash,text:'모의 음원',actorId:'jun',emotion:'HAPPY',intensity:.4}})).body.audioBase64;
  let bridgeCalls=0;
  const worker=mediaWorker({live:true,env:{EF_JEV_ENABLED:'true',TYPECAST_JUN_VOICE_ID:'tc_test_jun',TYPECAST_NARA_VOICE_ID:'tc_test_nara'},bridgeImpl:async data=>{bridgeCalls++;return data.operation==='voices'?{voices:['jun','nara'].map(id=>({voiceId:`tc_test_${id}`,model:'ssfm-v30',emotionPresets:['normal','happy']}))}:{audioBase64:wav,acting:{requestedPreset:'happy',selectedPreset:'happy'}};}});
  const c=(await worker({method:'GET',path:'/internal/v2/media-config'})).body;
  const out=await worker({method:'POST',path:'/internal/v2/sentence-media',body:{configurationHash:c.configurationHash,requestPurpose:'STORY',text:'내가 그린 만화가 마음에 들어.',actorId:'jun',emotion:'HAPPY',intensity:.4}});
  assert.equal(bridgeCalls,2);assert.ok(!('npcStyle' in out.body.typecastActing));
});
