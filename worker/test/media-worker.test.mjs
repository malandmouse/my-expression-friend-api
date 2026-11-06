import test from 'node:test';
import assert from 'node:assert/strict';
import { mediaWorker } from '../integration/media-worker.mjs';
import { sha256 } from '../src/base.mjs';
test('MOCK media is real PCM bytes explicitly labeled non-speech; configuration/text validation precedes provider I/O',async()=>{
  let bridges=0;const worker=mediaWorker({bridgeImpl:async()=>{bridges++;throw Error('must not run')}});
  const config=(await worker({method:'GET',path:'/internal/v2/media-config'})).body;
  const result=await worker({method:'POST',path:'/internal/v2/sentence-media',body:{configurationHash:config.configurationHash,text:'함께해 준다니 마음이 놓여.',actorId:'jun',emotion:'RELIEF',intensity:0.35}});
  assert.equal(result.body.mode,'MOCK');assert.equal(result.body.typecastActing.fallbackReason,'MOCK_TONE_NOT_SPEECH');
  const wave=Buffer.from(result.body.audioBase64,'base64');assert.equal(wave.readUInt32LE(24),24000);assert.equal(sha256(wave),result.body.audioHash);assert.equal(result.body.durationMs,200);
  await assert.rejects(worker({method:'POST',path:'/internal/v2/sentence-media',body:{configurationHash:'wrong',text:'test',actorId:'jun',emotion:'RELIEF',intensity:0.35}}));assert.equal(bridges,0);
});
test('LIVE adapter reuses existing Python emotion bridge with discovered voice capabilities; injected bridge only',async()=>{
  const fixture=mediaWorker();const c=(await fixture({method:'GET',path:'/internal/v2/media-config'})).body;
  const wave=(await fixture({method:'POST',path:'/internal/v2/sentence-media',body:{configurationHash:c.configurationHash,text:'검사',actorId:'jun',emotion:'HAPPY',intensity:0.5}})).body.audioBase64;
  const requests=[];const worker=mediaWorker({live:true,env:{TYPECAST_JUN_VOICE_ID:'tc_test_jun',TYPECAST_NARA_VOICE_ID:'tc_test_nara'},bridgeImpl:async body=>{
    requests.push(body);if(body.operation==='voices')return {voices:['jun','nara'].map(actor=>({voiceId:`tc_test_${actor}`,model:'ssfm-v30',emotionPresets:['normal','happy']}))};
    return {audioBase64:wave,acting:{requestedPreset:'happy',selectedPreset:'happy',fallbackReason:null}};
  }});
  const config=(await worker({method:'GET',path:'/internal/v2/media-config'})).body;
  const response=await worker({method:'POST',path:'/internal/v2/sentence-media',body:{configurationHash:config.configurationHash,text:'오늘 기쁜 일이 있었어.',actorId:'jun',emotion:'HAPPY',intensity:0.5}});
  assert.equal(response.body.typecastActing.selectedPreset,'happy');assert.equal(requests.length,2);assert.equal(requests[1].capability.voiceId,'tc_test_jun');
});
