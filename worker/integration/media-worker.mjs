import { spawn } from 'node:child_process';
import path from 'node:path';
import { ROOT, need, fingerprint, sha256, AgentError } from '../src/base.mjs';

const presets={NEUTRAL:'normal',HAPPY:'happy',GRATITUDE:'happy',LOVE:'happy',SATISFACTION:'happy',RELIEF:'normal',SAD:'sad',DISAPPOINTMENT:'sad',DISTRESS:'sad',SYMPATHY:'tonedown',ANGRY:'angry',UNFAIRNESS:'angry',SURPRISE:'toneup',SHOCK:'toneup',FEAR:'tonedown',DISGUST:'tonedown'};
function bridge(body,env) {
  return new Promise((resolve,reject)=>{
    const child=spawn(env.EF_AGENT_PYTHON??'python3',[path.join(ROOT,'integration/typecast_bridge.py')],{
      env:{...env,PYTHONPATH:path.join(ROOT,'vendor/emotion-friend-typecast'),PYTHONDONTWRITEBYTECODE:'1'},stdio:['pipe','pipe','pipe']});
    const chunks=[];let size=0;const timer=setTimeout(()=>child.kill('SIGKILL'),45000);
    child.stdout.on('data',chunk=>{size+=chunk.length;if(size>5*1024*1024)child.kill('SIGKILL');else chunks.push(chunk)});
    // Never forward child stderr: Python imports/provider errors must not disclose scripts or environment.
    child.stderr.resume();child.on('error',()=>{clearTimeout(timer);reject(new AgentError('TYPECAST_BRIDGE_UNAVAILABLE','음성 실행 환경을 확인해 주세요.',503));});
    child.on('close',code=>{clearTimeout(timer);let result;try{result=JSON.parse(Buffer.concat(chunks).toString('utf8'))}catch{reject(new AgentError('TYPECAST_OUTCOME_UNKNOWN','음성 응답을 확인하지 못했습니다.',504));return;}
      if(code!==0||result.errorCode)reject(new AgentError(/^[A-Z_]{1,80}$/.test(result.errorCode??'')?result.errorCode:'TYPECAST_OUTCOME_UNKNOWN','음성 제작이 완료되지 않았습니다. 자동 재시도하지 않습니다.',502));else resolve(result);});
    child.stdin.on('error',()=>{});child.stdin.end(JSON.stringify(body));
  });
}
export function mediaWorker({live=false,env=process.env,bridgeImpl=body=>bridge(body,env)}={}) {
  let frozen;
  async function config() {
    if(!frozen)frozen=(async()=>{
      const model=env.TYPECAST_MODEL??'ssfm-v30';
      const voices=live?(await bridgeImpl({operation:'voices'})).voices:[];
      const actors={};
      for(const [actor,key] of [['jun','TYPECAST_JUN_VOICE_ID'],['nara','TYPECAST_NARA_VOICE_ID']]) {
        const voiceId=live?env[key]:`mock-${actor}-not-typecast`;
        const capability=live?voices.find(v=>v.voiceId===voiceId&&v.model===model):{voiceId,model,emotionPresets:['normal']};
        need(capability,'TYPECAST_VOICE_CAPABILITY_REQUIRED','설정한 목소리의 지원 모델·연기 목록을 확인할 수 없습니다.');
        actors[actor]={voiceId,capability};
      }
      const value={mode:live?'LIVE':'MOCK',model,actors,actingMappingVersion:'EF_NPC_TYPECAST_ACTING_V2'};return {...value,configurationHash:fingerprint(value)};
    })();
    try{return await frozen}catch(error){frozen=undefined;throw error}
  }
  return async({method,path,body})=>{
    if(method==='GET'&&path==='/internal/v2/media-config')return {status:200,body:await config()};
    if(method!=='POST'||path!=='/internal/v2/sentence-media')return null;
    const c=await config();
    need(body.configurationHash===c.configurationHash&&typeof body.text==='string'&&body.text.trim()&&body.text.length<=1000
      &&Object.hasOwn(c.actors,body.actorId)&&Object.hasOwn(presets,body.emotion)&&Number.isFinite(body.intensity)&&body.intensity>=0&&body.intensity<=1,
      'INPUT_INVALID','확정한 문장·화자·연기 설정을 확인해 주세요.');
    const selected=c.actors[body.actorId];let audio,performance;
    // 학습 시나리오의 얼굴/동작은 GPT 원고에 이미 결속되어 있습니다.
    // EF_JEV_ENABLED가 과거 설정에 남아 있어도 여기서 Jev를 호출하지 않습니다.
    if(live) {
      const response=await bridgeImpl({...body,capability:selected.capability});audio=Buffer.from(response.audioBase64,'base64');performance=response.acting;
    }else {audio=mockWave();performance={requestedPreset:presets[body.emotion],selectedPreset:'normal',fallbackReason:'MOCK_TONE_NOT_SPEECH'};}
    need(audio.length>=44&&audio.length<=3*1024*1024&&audio.toString('ascii',0,4)==='RIFF'&&audio.readUInt32LE(24)===24000,'TYPECAST_INVALID_AUDIO','음원 형식이 다릅니다.');
    return {status:200,body:{mode:c.mode,configurationHash:c.configurationHash,voiceId:selected.voiceId,model:c.model,
      audioBase64:audio.toString('base64'),audioHash:sha256(audio),byteLength:audio.length,durationMs:Math.round((audio.length-44)/48),
      typecastActing:{requestedPreset:performance.requestedPreset,selectedPreset:performance.selectedPreset,fallbackReason:performance.fallbackReason??null,
        ...(performance.prompt?{emotionIntensity:performance.prompt.emotion_intensity,emotionType:performance.prompt.emotion_type??'preset',mappingVersion:performance.mappingVersion}:{})}}};
  };
}
function mockWave() {
  // Explicit non-speech tone for transport/codec tests only. It is never labeled real TTS.
  const frames=4800,b=Buffer.alloc(44+frames*2);b.write('RIFF');b.writeUInt32LE(b.length-8,4);b.write('WAVEfmt ',8);b.writeUInt32LE(16,16);b.writeUInt16LE(1,20);b.writeUInt16LE(1,22);b.writeUInt32LE(24000,24);b.writeUInt32LE(48000,28);b.writeUInt16LE(2,32);b.writeUInt16LE(16,34);b.write('data',36);b.writeUInt32LE(frames*2,40);
  for(let i=0;i<frames;i++)b.writeInt16LE(Math.round(400*Math.sin(i*2*Math.PI*220/24000)),44+i*2);return b;
}
