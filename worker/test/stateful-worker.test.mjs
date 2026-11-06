import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import { createWorker } from '../integration/foundation.mjs';
import { durableModel } from '../integration/stateful-worker.mjs';
const input=JSON.parse(await fs.readFile(new URL('../examples/generate-request.json',import.meta.url)));
const token='test-worker-only-secret-32-characters-long';
function memoryStorage() {
  const runs=new Map(),calls=new Map();
  const storage=async(job,path,body)=>{
    if(path==='run'&&body){if(!runs.has(job))runs.set(job,{state:'RUNNING'});return structuredClone(runs.get(job));}
    if(path==='run')return structuredClone(runs.get(job));
    if(path==='finish'){runs.set(job,structuredClone(body));return body;}
    if(path==='budget')return {callsUsed:0,inputTokensUsed:0,outputTokensUsed:0,estimatedCostMicrousd:0,inputTokensReserved:0,outputTokensReserved:0,estimatedCostReservedMicrousd:0,isFinal:true};
    if(path.endsWith('/reserve')) {
      const key=job+'/'+path.replace('/reserve','');
      if(calls.has(key))return {...structuredClone(calls.get(key)),created:false};
      calls.set(key,{state:'RESERVED'});return {state:'RESERVED',created:true};
    }
    if(path.endsWith('/settle')){calls.set(job+'/'+path.replace('/settle',''),structuredClone(body));return body;}
    throw Error('Unexpected storage operation');
  };
  return {storage,runs,calls};
}
test('async V2.1 worker persists 41 model-stage records, reuses finished output and creates five stable drafts; no provider I/O',async()=>{
  const store=memoryStorage();const worker=createWorker({token,v2:true,storage:store.storage});
  const submit=()=>worker({method:'POST',path:'/internal/v2/agent-jobs',authorization:`Bearer ${token}`,body:input});
  assert.equal((await submit()).status,202);
  let result;
  for(let i=0;i<30;i++) {
    await new Promise(resolve=>setImmediate(resolve));
    result=await worker({method:'GET',path:`/internal/v2/agent-jobs/${input.jobId}`,authorization:`Bearer ${token}`});
    if(result.status!==202)break;
  }
  assert.equal(result.status,200,JSON.stringify(result.body));assert.equal(store.calls.size,41);
  assert.ok([...store.calls.values()].every(c=>c.state==='COMPLETED'));
  assert.equal(result.body.contractVersion,'EF_FOUNDATION_AGENT_2');assert.equal(result.body.paidCalls,0);
  assert.equal(result.body.coreDrafts.length,5);assert.equal(result.body.result.scenarios[0].stateTrace.nodeStates.length,8);
  assert.deepEqual((await submit()).body,result.body);assert.equal(store.calls.size,41);
});
test('uncertain provider outcome keeps reservation and a restarted adapter never resends that call',async()=>{
  const store=memoryStorage();let sends=0;
  const model=durableModel({job:input.jobId,storage:store.storage,live:true,apiKey:'TEST_INJECTED_ONLY_NO_REAL_KEY',providerFetch:async()=>{sends++;throw Error('simulated disconnected transport');}});
  await assert.rejects(model.complete('turn',{messages:[],max_completion_tokens:10},{}),e=>e.code==='OUTCOME_UNKNOWN');
  const resumed=durableModel({job:input.jobId,storage:store.storage,live:true,apiKey:'TEST_INJECTED_ONLY_NO_REAL_KEY',providerFetch:async()=>{sends++;throw Error('must not run')}});
  await assert.rejects(resumed.complete('turn',{messages:[],max_completion_tokens:10},{}),e=>e.code==='OUTCOME_UNKNOWN');
  assert.equal(sends,1);assert.equal([...store.calls.values()][0].state,'OUTCOME_UNKNOWN');
});
