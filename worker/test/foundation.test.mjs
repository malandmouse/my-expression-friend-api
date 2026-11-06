import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import {createWorker} from '../integration/foundation.mjs';
const token='test-only-service-token-never-a-provider-key';
const input=JSON.parse(await fs.readFile(new URL('../examples/generate-request.json',import.meta.url)));
test('readiness requires service identity and never calls providers or job storage, even in LIVE mode',async()=>{
 let external=0,storage=0;
 const worker=createWorker({token,v2:true,live:true,apiKey:'synthetic-not-a-real-key',
  providerFetch:async()=>{external++;throw Error('No provider request allowed')},
  storage:async()=>{storage++;throw Error('No job request allowed')}});
 assert.equal((await worker({method:'GET',path:'/internal/v2/worker-status'})).status,401);
 const status=await worker({method:'GET',path:'/internal/v2/worker-status',authorization:`Bearer ${token}`});
 assert.deepEqual(status,{status:200,body:{mode:'LIVE',engine:'EF_ST_AGENT_DRAFT_V2_1',configurationRef:'prototype-st-v2.1-ko4'}});
 assert.throws(()=>createWorker({token,v2:true,live:true,settings:{configurationRef:'prototype-st-v2.1-ko2'}}),e=>e.code==='CONFIG_UNAVAILABLE');
 assert.equal(external,0);assert.equal(storage,0);
});
test('worker rejects missing/wrong service identity; no local UI token bootstrap',async()=>{
 const worker=createWorker({token});
 assert.equal((await worker({method:'POST',path:'/internal/v1/daily-scenarios',body:input})).status,401);
 assert.equal((await worker({method:'GET',path:'/api/session',authorization:`Bearer ${token}`})).status,404);
 assert.throws(()=>createWorker({token:'short'}));
});
test('foundation gets five bound DRAFT manuscripts without provider I/O',async()=>{
 const worker=createWorker({token});
 const response=await worker({method:'POST',path:'/internal/v1/daily-scenarios',authorization:`Bearer ${token}`,body:input});
 assert.equal(response.status,200);assert.equal(response.body.mode,'MOCK');assert.equal(response.body.paidCalls,0);
 assert.deepEqual(response.body.sourceSnapshot,input.sourceSnapshot);assert.equal(response.body.jobId,input.jobId);
 assert.equal(response.body.coreDrafts.length,5);assert.equal(response.body.result.scenarios.length,5);
 for(const [i,d] of response.body.coreDrafts.entries()){
  assert.equal(d.slot,i+1);assert.equal(d.payload.status,'DRAFT');assert.equal(d.dependenciesStatus,'UNBOUND_NOT_PUBLISHABLE');
  assert.equal(d.payload.sceneGraph.scenes.length,response.body.result.scenarios[i].draft.nodes.length);
  assert.ok(d.payload.characterReplyPolicy.rules.every(r=>d.payload.assetRefs.includes(r.voiceRef)));
 }
});
test('summary has no writes/model calls; malformed contracts do not echo raw content',async()=>{
 const worker=createWorker({token});const body=JSON.parse(await fs.readFile(new URL('../examples/summarize-request.json',import.meta.url)));
 const response=await worker({method:'POST',path:'/internal/v1/training-summary',authorization:`Bearer ${token}`,body});
 assert.equal(response.status,200);assert.equal(response.body.modelCalls,0);assert.equal(response.body.starWrites,0);
 const bad=await worker({method:'POST',path:'/internal/v1/daily-scenarios',authorization:`Bearer ${token}`,body:{secret:'do not echo'}});
 assert.equal(bad.status,422);assert.ok(!JSON.stringify(bad.body).includes('do not echo'));
});
