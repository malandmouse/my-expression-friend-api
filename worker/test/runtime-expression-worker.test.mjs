import test from 'node:test';
import assert from 'node:assert/strict';
import {runtimeExpressionWorker} from '../integration/runtime-expression-worker.mjs';
test('ST mock offers exactly missing cards without replacing retained words',async()=>{
 const run=runtimeExpressionWorker();const result=await run({method:'POST',path:'/internal/v2/runtime-expression',body:{operation:'OPTIONS',payload:{cardsNeeded:4,retainedCards:[{text:'그때'},{text:'조금'}]}}});
 assert.equal(result.body.mode,'MOCK');assert.equal(result.body.candidate.cards.length,4);assert.ok(result.body.candidate.cards.every(c=>!['그때','조금'].includes(c.text)));assert.equal(result.body.inputTokens,0);
});
test('known-completed malformed provider output preserves usage; no repair call',async()=>{
 let calls=0;const run=runtimeExpressionWorker({live:true,apiKey:'INJECTED_TEST_KEY_NOT_REAL',fetchImpl:async()=>{calls++;return new Response(JSON.stringify({choices:[{finish_reason:'stop',message:{content:'not JSON'}}],usage:{prompt_tokens:120,completion_tokens:9}}),{headers:{'x-request-id':'test-response'}});}});
 const result=await run({method:'POST',path:'/internal/v2/runtime-expression',body:{operation:'EXPANSION',payload:{originalText:'모르겠어',selectedCards:[]}}});assert.equal(calls,1);assert.equal(result.body.candidate,null);assert.equal(result.body.inputTokens,120);assert.equal(result.body.outputTokens,9);assert.equal(result.body.errorCode,'OUTPUT_INVALID');
});
