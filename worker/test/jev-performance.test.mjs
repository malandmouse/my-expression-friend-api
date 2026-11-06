import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { jevPerformance } from '../integration/jev-performance.mjs';
const body={actorId:'jun',text:'도와준다고 말해 줘서 고마워.',emotion:'GRATITUDE'};
test('Jev is off by default; a completed same-text decision is reused across worker restarts',async()=>{
  let calls=0;assert.equal(await jevPerformance({env:{},fetcher:()=>{throw Error('network')}}).decide(body),null);
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'ef-jev-contract-'));
  const env={EF_JEV_ENABLED:'true',TYPESAFE_API_KEY:'synthetic-never-sent',EF_JEV_LEDGER_DIRECTORY:dir,EF_JEV_MAX_REQUESTS:'1'};
  const fetcher=async(url,options)=>{calls++;assert.equal(url,'https://api.typesafe.ai/v1/systemone');assert.equal(JSON.parse(options.body).state.speaker,'npc');return {ok:true,text:async()=>JSON.stringify({model:'jev-test',answers:{gesture:{choice:'ACKNOWLEDGE',confidence:.9},intensity:{score:1}},usage:{input_tokens:400}})}};
  const first=await jevPerformance({env,fetcher}).decide(body);assert.equal(first.gesture,'ACKNOWLEDGE');assert.equal(first.intensity,.25);
  assert.deepEqual(await jevPerformance({env,fetcher}).decide(body),first);assert.equal(await jevPerformance({env,fetcher}).decide({...body,text:'다른 대사'}),null);assert.equal(calls,1);
  const ledger=(await fs.readdir(dir)).find(x=>x.endsWith('.json'));const saved=await fs.readFile(path.join(dir,ledger),'utf8');assert.ok(!saved.includes('synthetic-never-sent'));assert.ok(!saved.includes(body.text));
});
test('unknown Jev result is preserved; malformed choice never becomes an arbitrary animation',async()=>{
  const dir=await fs.mkdtemp(path.join(os.tmpdir(),'ef-jev-unknown-'));let calls=0;
  const env={EF_JEV_ENABLED:'true',TYPESAFE_API_KEY:'synthetic',EF_JEV_LEDGER_DIRECTORY:dir,EF_JEV_MAX_REQUESTS:'2'};
  const fetcher=async()=>{calls++;throw Error('simulated lost reply')};
  assert.equal(await jevPerformance({env,fetcher}).decide(body),null);assert.equal(await jevPerformance({env,fetcher}).decide(body),null);assert.equal(calls,1);
});
