import { generateStateful } from '../src/stateful-authoring.mjs';
import { defaultAuthoring } from '../src/authoring.mjs';
import { sendRequest } from '../vendor/sillytavern/chat-completion-request.mjs';
import { AgentError, need } from '../src/base.mjs';

/** Backend-only storage API; bearer and provider secrets never appear in results or error bodies. */
export function backendStorage({origin,token,fetchImpl=fetch}) {
  const url=new URL(origin);
  need((url.protocol==='https:'||url.protocol==='http:'&&['127.0.0.1','localhost'].includes(url.hostname))&&!url.username&&!url.password&&!url.search&&!url.hash&&url.pathname==='/','CONFIG_UNAVAILABLE','저장 포트 주소를 확인해 주세요.');
  return async (job,suffix,body) => {
    const response=await fetchImpl(new URL(`/internal/v2/agent-storage/jobs/${job}/${suffix}`,url),{method:body===undefined?'GET':'POST',redirect:'error',
      headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},body:body===undefined?undefined:JSON.stringify(body),signal:AbortSignal.timeout(15000)});
    if(!response.ok) {
      const data=await response.json().catch(()=>({}));
      throw new AgentError(['COST_BUDGET_EXCEEDED','STALE_DRAFT','CONSENT_REQUIRED','CHILD_ACCESS_DENIED'].includes(data.code)?data.code:'STORAGE_UNAVAILABLE','작업 저장 포트가 요청을 수락하지 않았습니다.',response.status,'STORAGE');
    }
    return await response.json();
  };
}

export function durableModel({job,storage,live=false,apiKey,providerFetch=fetch}) {
  const requestIds=[];
  return {live,requestIds,budget:()=>storage(job,'budget'),async complete(key,request,data) {
    const path=`calls/${encodeURIComponent(key)}`;
    const reserved=await storage(job,`${path}/reserve`,request);
    if(reserved.state==='COMPLETED') {
      if(reserved.providerRequestId)requestIds.push(reserved.providerRequestId);
      return decode(reserved.response);
    }
    // A prior reservation may already have reached the provider. Never repeat it after a timeout or restart.
    if(!reserved.created)throw new AgentError('OUTCOME_UNKNOWN','기존 요청의 실행 여부가 불명확합니다. 자동 재전송하지 않습니다.',409,'PROVIDER',live,'UNKNOWN');
    let attempted=false,settled=false;
    try {
      if(live)need(typeof apiKey==='string'&&apiKey.length>10,'CONFIG_UNAVAILABLE','서버 키와 명시적인 LIVE 설정이 필요합니다.');
      const response=await sendRequest(request,{apiKey:live?apiKey:'MOCK_NO_PROVIDER_KEY',signal:AbortSignal.timeout(120000),
        fetchImpl:live?async (url,options)=>{attempted=true;return providerFetch(url,options)}:async()=>new Response(JSON.stringify({id:'mock-v21',choices:[{finish_reason:'stop',message:{content:JSON.stringify(mockCandidate(key,data))}}],usage:{prompt_tokens:0,completion_tokens:0,total_tokens:0}}),{status:200})});
      if(!response.ok) {
        const known=[400,401,403,404,409,422,429].includes(response.status);
        await storage(job,`${path}/settle`,{state:known?'FAILED':'OUTCOME_UNKNOWN'});settled=true;
        throw new AgentError(known?'PROVIDER_REJECTED':'OUTCOME_UNKNOWN',`제공자 HTTP ${response.status}. 자동 재시도하지 않습니다.`,502,'PROVIDER',attempted,known?'KNOWN_FAILED':'UNKNOWN');
      }
      const raw=await response.json(),usage=raw.usage;
      if(!Number.isSafeInteger(usage?.prompt_tokens)||!Number.isSafeInteger(usage?.completion_tokens))throw Error('Missing usage');
      const requestId=response.headers.get('x-request-id');
      await storage(job,`${path}/settle`,{state:'COMPLETED',response:raw,inputTokens:usage.prompt_tokens,outputTokens:usage.completion_tokens,providerRequestId:requestId});settled=true;
      if(requestId)requestIds.push(requestId);
      return decode(raw);
    }catch(error) {
      if(!settled)await storage(job,`${path}/settle`,{state:attempted?'OUTCOME_UNKNOWN':'FAILED'}).catch(()=>undefined);
      if(error instanceof AgentError)throw error;
      throw new AgentError('OUTCOME_UNKNOWN','외부 응답/정산을 확인하지 못했습니다. 예약을 보존합니다.',504,'PROVIDER',attempted,'UNKNOWN');
    }
  }};
}
function decode(raw) {
  const choice=raw.choices?.[0];
  if(choice?.message?.refusal||choice?.finish_reason!=='stop')throw new AgentError('OUTPUT_INCOMPLETE','모델 응답이 완성되지 않았습니다. 자동 수정하지 않습니다.',422,'ASSEMBLY',true,'KNOWN_COMPLETED');
  try{return JSON.parse(choice.message.content)}catch{throw new AgentError('OUTPUT_INVALID','모델 응답이 JSON이 아닙니다.',422,'ASSEMBLY',true,'KNOWN_COMPLETED')}
}
export function mockCandidate(key,data) {
  if(key==='planner')return {scenarios:data.seeds.map(s=>({slot:s.slot,topic:`연결 확인용 대화 ${s.slot}`,summary:'도움이 필요한 캐릭터와 이야기를 나누는 모의 자료입니다. 실제 GPT 대본이 아닙니다.',meetingReason:'같은 교실 친구가 쉬는 시간에 만난다.',event:'모둠 준비물을 옮기려는데 도움이 필요한 허구 상황이다.',goal:'도움이 필요한 상황을 말하기',goalCategory:'NEEDS_HELP',hasUnresolvedIssue:true,emotionPattern:'OPEN_TO_CHANGE',initialEmotion:'FEAR',cause:'혼자 하기 어려운 일이 있어 걱정된다.'}))};
  const relieved=data.state.emotion.id==='RELIEF';
  const npcLine=key.endsWith('intro')?'모둠 준비물을 옮겨야 하는데 상자가 커서 걱정이야.':data.output.ending?(relieved?'도와준다고 말해 주니까 마음이 놓여. 내 이야기를 들어줘서 고마워.':'무엇이 어려운지 물어봐 줬구나. 상자를 혼자 옮길 수 있을지 아직 걱정이야.'):(relieved?'함께 도와줄 수 있다니 마음이 놓여.':'어떤 도움이 필요한지 궁금하구나. 상자가 커서 혼자 옮기기 걱정돼.');
  return {npcLine,emotionIntensity:data.state.emotion.intensity,emotionCause:data.state.emotion.cause,emotionEvidenceQuote:npcLine,goalOutcome:'UNCHANGED',
    emotionUsed:data.state.emotion.id,faceCue:data.state.emotion.id,animationCue:'NONE',choiceCandidates:data.output.choiceCount?[{choiceId:'offer',text:'같이 도와줄게.',observationFocus:'CONTEXTUAL_RESPONSE',eventCode:'HELP_OFFER',evidenceText:'도움을 제안한다.'},
      {choiceId:'ask',text:'어떤 도움이 필요해?',observationFocus:'CLARIFICATION',eventCode:'INFORMATION_QUESTION',evidenceText:'도움의 내용을 확인한다.'}]:[]};
}

/** Worker owns no app DB. Only short-lived execution promises are local; inputs/calls/results live in Core. */
export function statefulWorker({storage,live=false,apiKey,providerFetch,settings=defaultAuthoring,toEnvelope}) {
  const active=new Map();
  return async ({method,path,body})=>{
    if(method==='POST'&&path==='/internal/v2/agent-jobs') {
      need(/^[0-9a-f-]{36}$/i.test(body?.jobId??''),'INPUT_INVALID','작업 ID가 필요합니다.');
      const job=body.jobId,prior=await storage(job,'run',{input:body,mode:live?'LIVE':'MOCK'});
      if(prior.state==='RESULT_READY')return {status:200,body:prior.result};
      if(!['RUNNING'].includes(prior.state))return {status:409,body:{code:prior.errorCode??prior.state}};
      if(!active.has(job)) {
        const work=(async()=>{
          try {
            const result=await generateStateful(body,settings,durableModel({job,storage,live,apiKey,providerFetch}));
            const envelope=toEnvelope(result,body,live);
            await storage(job,'finish',{state:'RESULT_READY',result:envelope});
          }catch(error) {
            await storage(job,'finish',{state:error.code==='OUTCOME_UNKNOWN'?'OUTCOME_UNKNOWN':'FAILED',errorCode:/^[A-Z_]{1,80}$/.test(error.code??'')?error.code:'WORKER_FAILED'}).catch(()=>undefined);
          }finally{active.delete(job);}
        })();active.set(job,work);
      }
      return {status:202,body:{jobId:job,state:'RUNNING'}};
    }
    const match=path.match(/^\/internal\/v2\/agent-jobs\/([0-9a-f-]{36})$/i);
    if(method==='GET'&&match) {
      const state=await storage(match[1],'run');
      if(state.state==='RESULT_READY')return {status:200,body:state.result};
      if(state.state==='FAILED')return {status:422,body:{code:state.errorCode??'WORKER_FAILED'}};
      if(state.state==='OUTCOME_UNKNOWN')return {status:409,body:{code:'OUTCOME_UNKNOWN'}};
      return {status:202,body:{jobId:match[1],state:state.state}};
    }
    return null;
  };
}
