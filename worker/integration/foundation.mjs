// Foundation service-to-service boundary. No user JWT or provider key is accepted here.
import { timingSafeEqual, randomUUID, createHash } from 'node:crypto';
import { generate, defaultAuthoring } from '../src/authoring.mjs';
import { summarize } from '../src/learning.mjs';
import { need, sha256, stable, errorBody } from '../src/base.mjs';
import { backendStorage, statefulWorker } from './stateful-worker.mjs';
import { emotions } from '../src/stateful-authoring.mjs';
import { mediaWorker } from './media-worker.mjs';
import { runtimeExpressionWorker } from './runtime-expression-worker.mjs';
import { DIALOGUE_CONFIGURATION_REF } from '../src/dialogue-planning.mjs';

const actor = id => ({ JungHoon: 'jun', MainPlayer: 'nara' })[id];
export function toCoreDraft(scenario, jobId,includeUnsure=false) {
  const {draft,slot} = scenario, npc = actor(draft.npcId);
  need(npc, 'OUTPUT_INVALID', '알려진 캐릭터만 변환할 수 있습니다.');
  const voice = id => `unbound-${jobId}-${slot}-${id}`;
  // Unbound DRAFT references are deliberately not media/QA evidence. Publication validation rejects them.
  const body = { schemaVersion:'0.3.0', status:'DRAFT', scenarioVersionId:`st-${jobId}-${slot}`,
    arcVersionId:`st-day-${jobId}`,storyletVersionId:`st-slot-${jobId}-${slot}`, contentHash:'0'.repeat(64),
    teachingGoalIds:['EMOTION_CONTEXT','SELF_FEELING'], preconditions:[], terminalEffects:[],
    personaRefs:['unbound-persona-jun','unbound-persona-nara'], cardPolicyRef:{policyVersionId:'unbound-card-policy',policyHash:'0'.repeat(64)},
    assetRefs:[...draft.nodes.map(n=>voice(n.nodeId)),voice('original-reply')],
    sceneGraph:{startSceneId:draft.startNodeId,scenes:draft.nodes.map(n=>({sceneId:n.nodeId,actorId:npc,dialogue:n.text,
      expressionCue:n.faceCue,animationCue:n.animationCue,voiceRef:voice(n.nodeId),
      ...(n.isEnding ? {end:true} : n.nextNodeId ? {nextSceneId:n.nextNodeId} : {choices:n.choices.map(c=>({choiceId:c.choiceId,label:c.text,nextSceneId:c.targetNodeId,effects:[]}))})}))},
    emotionQuestionByTerminal:draft.emotionQuestions.map(q=>({terminalSceneId:q.terminalNodeId,targetActorId:npc,prompt:q.prompt,
      acceptedEmotionIds:[q.correctEmotionId],evidenceSceneIds:[q.targetNodeId],options:q.options.map(o=>({emotionId:o.emotionId,label:emotions[o.emotionId]??o.emotionId}))})),
    adaptiveSupport:{mode:'OFFER_NOT_FORCE',questionId:`support-${slot}`,prompt:'이야기를 다시 들어 볼까?',options:['다시 듣기','계속하기'],replaySceneIds:[draft.startNodeId]},
    expressionPrompt:{text:draft.expressionSupport.prompt,socialStoryHelp:{entryStates:['EMOTION_PICK','EMOTION_SUPPORT_OPTIONAL','ASK_RESPONSE'],
      lines:['내 마음은 내가 정해요.','다른 마음이어도 괜찮아요.','말하고 싶지 않으면 쉬어도 돼요.'],playbackSpeeds:[0.75,1,1.25],trigger:'USER_BUTTON'}},
    // Generic ORIGINAL fallback is a DRAFT, never asserted to have passed human or semantic review.
    characterReplyPolicy:{mode:'APPROVED_TEMPLATES',rules:[{ruleId:'original-fallback',whenFunction:'UNKNOWN',text:'네 이야기를 들려줘서 고마워.',voiceRef:voice('original-reply')}]}};
  if(includeUnsure)for(const question of body.emotionQuestionByTerminal)question.options.push({emotionId:'UNKNOWN',label:'지금은 모르겠어'});
  body.contentHash=sha256(JSON.stringify(stable({...body,contentHash:undefined,status:undefined})));
  return {slot, materialId:randomUUID(),scenarioVersionId:randomUUID(),subject:draft.topic.slice(0,40),summary:draft.summary.slice(0,300),
    payload:body, validationProfile:'GENERATED_1_TO_3', dependenciesStatus:'UNBOUND_NOT_PUBLISHABLE'};
}
export function createWorker({token, generateImpl=generate,v2=false,storage,backendOrigin,live=false,apiKey,providerFetch,settings}={}) {
  need(typeof token==='string' && token.length>=32,'CONFIG_UNAVAILABLE','32자 이상의 서버 전용 토큰이 필요합니다.');
  if(v2)need((settings?.configurationRef??DIALOGUE_CONFIGURATION_REF)===DIALOGUE_CONFIGURATION_REF,'CONFIG_UNAVAILABLE','Worker의 이야기 정책 버전이 배포 소스와 다릅니다. 생성 전에 설정을 확인해 주세요.');
  const expected=Buffer.from(`Bearer ${token}`);
  const media=mediaWorker({live});
  const expressions=runtimeExpressionWorker({live,apiKey,model:settings?.modelRef});
  const v2dispatch=v2?statefulWorker({storage:storage??backendStorage({origin:backendOrigin,token}),live,apiKey,providerFetch,settings,
    toEnvelope:(result,input,isLive)=>({contractVersion:'EF_FOUNDATION_AGENT_2',jobId:input.jobId,sourceSnapshot:input.sourceSnapshot,result,
      coreDrafts:result.scenarios.map(s=>{
        const d=toCoreDraft(s,input.jobId,true);const uuid=salt=>{const h=createHash('sha256').update(`${input.jobId}:${s.slot}:${salt}`).digest('hex');return `${h.slice(0,8)}-${h.slice(8,12)}-4${h.slice(13,16)}-a${h.slice(17,20)}-${h.slice(20,32)}`;};
        d.materialId=uuid('material');d.scenarioVersionId=uuid('scenario');return d;
      }),mode:isLive?'LIVE':'MOCK',paidCalls:result.provenance.modelCalls,publicationAllowed:false})}):null;
  return async function dispatch({method,path,authorization,body}) {
    const supplied=Buffer.from(authorization??'');
    if(supplied.length!==expected.length || !timingSafeEqual(supplied,expected))return {status:401,body:{code:'AUTH_REQUIRED'}};
    try {
      // Startup/readiness metadata only. This route never invokes GPT, voice discovery or synthesis.
      if(method==='GET'&&path==='/internal/v2/worker-status')return {status:200,body:{
        mode:live?'LIVE':'MOCK',engine:v2?'EF_ST_AGENT_DRAFT_V2_1':'EF_FOUNDATION_AGENT_1',
        ...(v2?{configurationRef:DIALOGUE_CONFIGURATION_REF}:{})}};
      if(v2dispatch){const response=await v2dispatch({method,path,body});if(response)return response;}
      if(v2){const response=await media({method,path,body});if(response)return response;}
      if(v2){const response=await expressions({method,path,body});if(response)return response;}
      if(method==='POST' && path==='/internal/v1/daily-scenarios') {
        const generated=await generateImpl(body,defaultAuthoring,{paidEnabled:false});
        need(generated.lab.externalProviderCalls===0 && generated.result.publicationAllowed===false,'OUTPUT_INVALID','실험용 결과 계약을 확인하지 못했습니다.');
        return {status:200,body:{contractVersion:'EF_FOUNDATION_AGENT_1',jobId:body.jobId,sourceSnapshot:body.sourceSnapshot,
          result:generated.result,coreDrafts:generated.result.scenarios.map(s=>toCoreDraft(s,body.jobId)),
          mode:'MOCK',paidCalls:0,publicationAllowed:false}};
      }
      if(method==='POST' && path==='/internal/v1/training-summary')return {status:200,body:summarize(body)};
      return {status:404,body:{code:'RESOURCE_NOT_FOUND'}};
    }catch(error){return {status:error.status??500,body:errorBody(error,body?.jobId)}};
  };
}
