// Port of this project's preserved tools/npc_animation/decision_service.py request contract.
// Optional NPC-only preparation: one durable attempt per text/actor/model; never child input or a playback-time network wait.
import fs from 'node:fs/promises';
import path from 'node:path';
import { createHash } from 'node:crypto';
const motions=['NONE','ACKNOWLEDGE','NOD_YES','HAPPY_HAND','RELIEVED_SIGH'];
const hash=v=>createHash('sha256').update(v).digest('hex');
export function jevPerformance({env=process.env,fetcher=globalThis.fetch}={}) {
  const enabled=env.EF_JEV_ENABLED==='true',directory=env.EF_JEV_LEDGER_DIRECTORY;
  const maximum=Number(env.EF_JEV_MAX_REQUESTS??0),model=env.EF_JEV_MODEL??'jev-latest';
  if(enabled&&(!env.TYPESAFE_API_KEY||!directory||!path.isAbsolute(directory)||!Number.isInteger(maximum)||maximum<1||maximum>100))throw Error('JEV_EXPLICIT_KEY_LEDGER_LIMIT_REQUIRED');
  const running=new Map();
  async function decide(body) {
    if(!enabled)return null;
    if(!['jun','nara'].includes(body.actorId)||typeof body.text!=='string'||!body.text.trim()||body.text.length>1000)return null;
    const textHash=hash(body.text),identity=hash(JSON.stringify([model,body.actorId,textHash,body.emotion]));
    if(running.has(identity))return running.get(identity);
    const work=(async()=>{
      await fs.mkdir(directory,{recursive:true,mode:0o700});const file=path.join(directory,identity+'.json');
      try{const old=JSON.parse(await fs.readFile(file,'utf8'));return old.state==='COMPLETED'?old.decision:null}catch(e){if(e.code!=='ENOENT')return null;}
      // A cross-process reservation lock keeps concurrent requests inside the explicit attempt limit.
      const lock=path.join(directory,'quota.lock');let quota;
      try{quota=await fs.open(lock,'wx',0o600)}catch{return null;}
      try{
        if((await fs.readdir(directory)).filter(n=>n.endsWith('.json')).length>=maximum)return null;
        let handle;try{handle=await fs.open(file,'wx',0o600)}catch{return null;}
        await handle.writeFile(JSON.stringify({state:'RESERVED',model,actorId:body.actorId,textHash}));await handle.close();
      }finally{await quota.close();await fs.unlink(lock);}

      const request={model,state:{speaker:'npc',text:body.text,presentationState:'Speaking',authoredEmotion:body.emotion},questions:{
        gesture:{type:'choice',instructions:'Choose one restrained co-speech gesture fitting this fictional NPC line. Prefer NONE. Do not infer the child emotion.',criteria:Object.fromEntries(motions.map(id=>[id,id.replaceAll('_',' ')]))},
        intensity:{type:'score',instructions:'Gentle expressiveness for an NPC speaking to a child.',criteria:['neutral','subtle','gentle','clear','strong']}}};
      try {
        const response=await fetcher('https://api.typesafe.ai/v1/systemone',{method:'POST',headers:{Authorization:'Bearer '+env.TYPESAFE_API_KEY,'Content-Type':'application/json'},body:JSON.stringify(request),redirect:'error',signal:AbortSignal.timeout(2500)});
        if(!response.ok){await fs.writeFile(file,JSON.stringify({state:'FAILED',status:response.status,model,textHash}));return null;}
        const text=await response.text();if(text.length>32768)throw Error('BOUNDED_RESPONSE');const value=JSON.parse(text),g=value.answers?.gesture,i=value.answers?.intensity;
        if(!motions.includes(g?.choice)||!Number.isFinite(g.confidence)||g.confidence<0||g.confidence>1||!Number.isFinite(i?.score)||i.score<0||i.score>4)throw Error('INVALID_DECISION');
        const decision={format:'EF_FOUNDATION_NPC_STYLE_V1',source:'JEV',actorId:body.actorId,textHash,gesture:g.choice,confidence:Math.round(g.confidence*10000)/10000,intensity:Math.round(Math.min(.65,i.score/4)*10000)/10000,model:String(value.model??model).slice(0,80)};
        await fs.writeFile(file,JSON.stringify({state:'COMPLETED',model,textHash,decision,usage:value.usage??null}));return decision;
      }catch{await fs.writeFile(file,JSON.stringify({state:'OUTCOME_UNKNOWN',model,textHash}));return null;}
    })();running.set(identity,work);try{return await work}catch{return null}finally{running.delete(identity)}
  }
  return {enabled,decide};
}
