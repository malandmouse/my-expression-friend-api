const $ = id => document.getElementById(id);
const choices = {
  ageBands: [['ELEMENTARY_MIDDLE','초등 중학년'],['TARGET_8_TO_13','8~13세']],
  experienceNarrative: [['KEY_POINT','있었던 일을 핵심 위주로 말해요'],['SEQUENCE','있었던 일을 순서대로 연결해 말해요'],['CAUSE_RESULT','사건의 이유나 결과까지 연결해 말해요'],['BACKGROUND_THOUGHT','배경과 자신의 생각까지 자세히 이야기해요'],['VARIABLE','소재나 상황에 따라 많이 달라요']],
  emotionExpression: [['BROAD_FEELING','넓은 기분 표현'],['EMOTION_WORDS','감정 이름'],['SIMILAR_FEELING_WORDS','비슷한 감정 구별'],['FEELING_CAUSE','감정과 이유'],['INTENSITY_OR_MIXED','세기·여러 감정'],['SELF_FEELING_DIFFICULT','자기 감정 말하기 어려움']],
  informationAmount: [['ONE_EVENT','한 가지 사건씩 나누면 편해요'],['SEQUENTIAL_EVENTS','관련 사건을 순서대로 이어 설명해도 괜찮아요'],['BACKGROUND_AND_REASONS','배경과 이유가 함께 있어도 괜찮아요'],['VARIABLE','상황에 따라 달라요']],
  causeExplanation: [['EXPLICIT_LINK','감정과 이유를 직접 연결해 주면 편해요'],['EVENT_REMINDER','앞에서 나온 사건을 짚어 주면 연결해요'],['CONTEXT_INFERENCE','이유가 일부 생략돼도 문맥으로 연결해요'],['VARIABLE','상황에 따라 달라요']],
  personReference: [['NAMES_EXPLICIT','인물 이름을 직접 말해 주면 편해요'],['PRONOUN_AFTER_NAME','처음 이름을 알려 주면 대명사도 괜찮아요'],['MULTIPLE_REFERENTS','여러 인물도 따라가는 편이에요'],['VARIABLE','상황에 따라 달라요']],
  priorityGoals: [['EMOTION_CONTEXT','상황·대사에서 감정 알아보기'],['EMOTION_CAUSE','감정과 이유 연결'],['DIFFERENT_FEELINGS','서로 다른 마음 이해'],['SIMILAR_EMOTIONS','비슷한 감정 구별'],['SELF_FEELING','내 감정 표현'],['SELF_CAUSE','내 감정과 이유 표현'],['INTENSITY_MIXED','감정 세기·여러 마음'],['THOUGHT_WISH','생각·바람 표현']],
  permittedPlaces: [['Home','집'],['Class','교실'],['Park','공원'],['Traffic','교통·도로'],['School','학교'],['Mall','쇼핑몰'],['Canteen','식당·급식']]
};
const names = {JungHoon:'준',MainPlayer:'나래'};
const emotions = {HAPPY:'기쁨',GRATITUDE:'고마움',SATISFACTION:'만족',LOVE:'사랑',RELIEF:'안도',SURPRISE:'놀람',DISAPPOINTMENT:'아쉬움',DISTRESS:'괴로움',SHOCK:'충격',SYMPATHY:'공감',FEAR:'두려움',UNFAIRNESS:'억울함',ANGRY:'화남',DISGUST:'싫음',SAD:'슬픔',NEUTRAL:'중립'};
let settings, session, currentResult, currentSummary, busy=false;
function element(tag, text, cls) { const e=document.createElement(tag); if(text!==undefined)e.textContent=text; if(cls)e.className=cls; return e; }
function notify(text,error=false){$('status').textContent=text;$('status').classList.toggle('error',error);}
async function api(url,{method='GET',body,key}={}) {
  const response=await fetch(url,{method,headers:{...(session?{Authorization:`Bearer ${session.token}`}:{ }),...(body?{'Content-Type':'application/json'}:{}),...(key?{'Idempotency-Key':key}:{})},body:body?JSON.stringify(body):undefined});
  const data=await response.json();
  if(!response.ok)throw new Error(data.error?.issues?.map(i=>`${i.path}: ${i.reason}`).join('\n')||'요청하지 못했습니다.');
  return data;
}
function selected(id){return [...$(id).querySelectorAll('input:checked')].map(e=>e.value);}
function setSelected(id,values){for(const e of $(id).querySelectorAll('input'))e.checked=values.includes(e.value);}
function lines(id){return $(id).value.split('\n').map(v=>v.trim()).filter(Boolean);}
function tags(id){return $(id).value.split(',').map(v=>v.trim()).filter(Boolean);}
function populate(){
  const r=settings.request,p=r.childProfile,a=settings.authoring;
  for(const id of ['nickname','characterPreference'])$(id).value=p[id];
  $('programDay').value=r.program.programDay;$('localDate').value=r.program.localDate;
  for(const id of ['ageBands','emotionExpression','priorityGoals'])setSelected(id,p[id]??[]);
  $('experienceNarrative').value=p.experienceNarrative??'';
  for(const id of ['informationAmount','causeExplanation','personReference'])$(id).value=p.understanding[id]??'';
  for(const id of ['interests','preferredActivities','avoidTopics'])$(id).value=p[id].join(', ');
  $('sensoryPreferences').value=p.sensoryPreferences??'';$('expressionExamples').value=p.expressionExamples.map(e=>e.text).join('\n');
  $('dailyEvents').value=r.dailyEvents.map(e=>e.text).join('\n');
  $('meetingState').value=r.continuity.relationships[0]?.meetingState??'UNKNOWN';
  for(const id of ['systemInstruction','modelRef','maxOutputTokens','reasoningEffort'])$(id).value=a[id];
  $('ragEnabled').checked=a.ragEnabled;setSelected('permittedPlaces',a.permittedPlaces);
  $('advancedRequest').value=JSON.stringify(r,null,2);$('revision').textContent=`로컬 설정 v${settings.revision}`;
}
function basicRequest(){
  const r=structuredClone(settings.request),p=r.childProfile;
  if(selected('priorityGoals').length>2)throw new Error('우선 감정 목표는 최대 2개까지 선택해 주세요.');
  p.nickname=$('nickname').value;p.characterPreference=$('characterPreference').value;
  for(const id of ['ageBands','priorityGoals'])p[id]=selected(id);
  p.emotionExpression=selected('emotionExpression');if(!p.emotionExpression.length)p.emotionExpression=null;
  p.experienceNarrative=$('experienceNarrative').value||null;
  for(const id of ['informationAmount','causeExplanation','personReference'])p.understanding[id]=$(id).value||null;
  for(const id of ['interests','preferredActivities','avoidTopics'])p[id]=tags(id);
  p.sensoryPreferences=$('sensoryPreferences').value.trim()||null;
  p.expressionExamples=lines('expressionExamples').map(text=>p.expressionExamples.find(e=>e.text===text)??{text,context:null,source:'CAREGIVER_REPORTED_CHILD_UTTERANCE',recordedAt:null});
  r.program.programDay=Number($('programDay').value);r.program.localDate=$('localDate').value;
  r.dailyEvents=lines('dailyEvents').map((text,i)=>r.dailyEvents.find(e=>e.text===text)??{eventId:`local-report-${i+1}`,occurredOn:r.program.localDate,source:'CAREGIVER_REPORT',text,reportedFeeling:null,allowedUse:'CHILD_REPLY_CANDIDATE_ONLY'});
  const state=$('meetingState').value;
  if(!r.continuity.relationships.length||r.continuity.relationships.some(e=>e.meetingState!==state))r.continuity.relationships=['JungHoon','MainPlayer'].map(npcId=>({npcId,meetingState:state,evidenceRefs:state==='UNKNOWN'?[]:['LOCAL_AUTHOR_SETTING_NOT_CORE_EVIDENCE']}));
  if(JSON.stringify(p)!==JSON.stringify(settings.request.childProfile))r.sourceSnapshot.profileVersion++;
  r.sourceSnapshot.inputRevision=settings.revision+1;return r;
}
function collect(){
  const request=$('useAdvanced').checked?JSON.parse($('advancedRequest').value):basicRequest();
  const authoring={systemInstruction:$('systemInstruction').value,modelRef:$('modelRef').value,maxOutputTokens:Number($('maxOutputTokens').value),reasoningEffort:$('reasoningEffort').value,ragEnabled:$('ragEnabled').checked,permittedPlaces:selected('permittedPlaces')};
  return {revision:settings.revision,request,authoring};
}
async function save(){settings=await api('/api/settings',{method:'POST',body:collect()});$('revision').textContent=`로컬 설정 v${settings.revision}`;return settings;}
async function work(fn){if(busy)return;busy=true;for(const id of ['generate','preview-request','save-settings','summarize','apply-next'])$(id).disabled=true;
  try{await fn();}catch(e){notify(e.message,true);}finally{busy=false;for(const id of ['generate','preview-request','save-settings','summarize'])$(id).disabled=false;$('apply-next').disabled=!currentSummary;}}
function freshRequest(){const r=structuredClone(settings.request);r.jobId=crypto.randomUUID();r.sourceSnapshot.snapshotId=crypto.randomUUID();return r;}
function downloadJson(data,name){const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)+'\n'],{type:'application/json;charset=utf-8'}));const a=element('a');a.href=url;a.download=name;document.body.append(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),5000);}
function addQuestion(parent,draft,end){
  const q=draft.emotionQuestions.find(q=>q.terminalNodeId===end);if(!q)return;
  const box=element('div',undefined,'question');box.append(element('strong','캐릭터 감정 확인 · 검토용'));
  box.append(element('p',q.prompt));const ul=element('ul');for(const o of q.options)ul.append(element('li',`${emotions[o.emotionId]??o.emotionId}${o.relation==='CORRECT'?' — 작성자용 참고답':''}`));box.append(ul);
  box.append(element('small',`대상 순간: ${q.targetNodeId}. 고정 문제은행 예시이며 교육 적합성 검토 전입니다.`));parent.append(box);
  parent.append(element('p',draft.expressionSupport.prompt,'hint'));parent.append(element('small','[아동 원문 입력 대기] 이 화면은 카드 생성·Unity 실행·학습 기록을 만들지 않습니다.'));
}
function storyCard(s,preview){
  const card=element('article',undefined,'panel story'),head=element('div',undefined,'story-head');head.append(element('div',String(s.slot).padStart(2,'0'),'slot'));
  const text=element('div');text.append(element('div',`${names[s.draft.npcId]} · ${s.place} · ${emotions[preview.targetEmotionId]}`,'meta'));text.append(element('h3',s.draft.topic));text.append(element('p',s.draft.summary,'summary'));head.append(text);card.append(head);
  card.append(element('p',s.meetingReason,'hint'));
  const scene=element('div',undefined,'scene'),log=element('div'),controls=element('div',undefined,'choice-row');scene.append(log,controls);card.append(scene);
  const map=new Map(s.draft.nodes.map(n=>[n.nodeId,n]));
  function enter(id,reset=false){
    if(reset)log.replaceChildren();controls.replaceChildren();const n=map.get(id);
    log.append(element('div',`${names[s.draft.npcId]} · ${id}`,'speaker'),element('p',n.text,'dialogue'));
    if(n.isEnding){addQuestion(log,s.draft,id);const b=element('button','처음부터 다시 보기','secondary');b.onclick=()=>enter(s.draft.startNodeId,true);controls.append(b);return;}
    if(n.choices.length){for(const c of n.choices){const b=element('button',c.text,'choice');b.onclick=()=>{log.append(element('div',`아동 답변 후보: ${c.text}`,'reply'));enter(c.targetNodeId);};controls.append(b);}}
    else{const b=element('button','다음 대사 →','secondary');b.onclick=()=>enter(n.nextNodeId);controls.append(b);}
  }
  enter(s.draft.startNodeId);
  const all=element('details'),sum=element('summary','전체 분기와 선택별 반응 보기');all.append(sum);const content=element('div',undefined,'all-nodes');
  for(const n of s.draft.nodes){const box=element('div',undefined,'node');box.append(element('span',`${n.nodeId} · ${emotions[n.emotionId]}`,'node-id'),element('p',n.text));const ul=element('ul');for(const c of n.choices)ul.append(element('li',`${c.text} → ${c.targetNodeId}`));box.append(ul);if(n.nextNodeId)box.append(element('small',`다음: ${n.nextNodeId}`));if(n.isEnding)addQuestion(box,s.draft,n.nodeId);content.append(box);}all.append(content);card.append(all);return card;
}
function renderResult(result){currentResult=result;$('empty').hidden=true;$('download').disabled=false;$('stories').replaceChildren();
  $('result-caption').textContent=`Day ${result.program.programDay} · ${result.scenarios.length}편 · 준·나래 카드 v0.3.0 · 실제 GPT 호출 ${result.provenance.modelCalls}회 · 미발행 모의 자료`;
  for(const s of result.scenarios)$('stories').append(storyCard(s,result.webPreview.find(p=>p.slot===s.slot)));
  $('result-title').scrollIntoView({block:'start'});
}
for(const id of ['ageBands','emotionExpression','priorityGoals','permittedPlaces'])for(const [value,text] of choices[id]){const label=element('label'),input=element('input');input.type='checkbox';input.value=value;label.append(input,document.createTextNode(text));$(id).append(label);}
for(const id of ['experienceNarrative','informationAmount','causeExplanation','personReference']){const options=[['','잘 모르겠어요'],...choices[id]];for(const [value,text] of options){const o=element('option',text);o.value=value;$(id).append(o);}}
for(const tab of document.querySelectorAll('[role=tab]'))tab.onclick=()=>{for(const t of document.querySelectorAll('[role=tab]')){const active=t===tab;t.setAttribute('aria-selected',String(active));$(t.getAttribute('aria-controls')).hidden=!active;}};
$('save-settings').onclick=()=>work(async()=>{await save();notify('설정을 이 실험 레포에 저장했습니다. 외부 전송은 없습니다.');});
$('refresh-json').onclick=()=>{try{$('advancedRequest').value=JSON.stringify(basicRequest(),null,2);notify('기본 설정으로 고급 JSON을 채웠습니다.');}catch(e){notify(e.message,true);}};
$('preview-request').onclick=()=>work(async()=>{await save();const preview=await api('/api/preview',{method:'POST',body:freshRequest()});$('preview-content').textContent=JSON.stringify(preview,null,2);$('preview-panel').hidden=false;notify('실제 ST 요청 조립 코드를 거친 미리보기입니다. 모델 전송은 0회입니다.');});
$('close-preview').onclick=()=>{$('preview-panel').hidden=true;};
$('generate').onclick=()=>work(async()=>{notify('설정을 저장하고 모의 응답으로 하루 묶음을 준비하고 있습니다…');await save();const r=freshRequest();const result=await api('/api/plugins/scenario-agent/v1/daily-scenarios/generate',{method:'POST',body:r,key:r.jobId});renderResult(result);notify('모의 하루 5편을 준비했습니다. 선택 버튼으로 반응을 따라가거나 JSON을 다운로드하세요.');});
$('download').onclick=()=>work(async()=>{const data=await api(`/api/jobs/${currentResult.jobId}/download`);downloadJson(data,`day-${String(data.program.programDay).padStart(2,'0')}-scenarios.json`);notify('하루 5편의 기존 명세 JSON을 다운로드했습니다.');});
$('load-observations').onclick=()=>work(async()=>{$('observations').value=JSON.stringify(await api('/api/summary-example'),null,2);$('summary-status').textContent='실제 아동 기록이 아닌 허구 예시를 불러왔습니다.';});
$('summarize').onclick=()=>work(async()=>{const data=await api('/api/plugins/scenario-agent/v1/training/summarize',{method:'POST',body:JSON.parse($('observations').value)});currentSummary=data;$('summary-result').replaceChildren();const stats=element('div',undefined,'stat-grid');for(const [value,label] of [[data.counts.completedSlots,'입력된 완료 영수증'],[data.counts.recognition.matched,'허용 답 일치'],[data.counts.recognition.validFirstResponses,'유효 첫 응답']]){const e=element('div',label);e.prepend(element('b',String(value)));stats.append(e);}$('summary-result').append(stats,element('p',data.caregiverText),element('pre',JSON.stringify(data,null,2)));$('summary-status').textContent='규칙 기반 집계 완료 · LLM 호출 0회 · 완료/별 쓰기 0회';notify('시험 기록을 정리했습니다. 다음 생성 참고를 적용할 수 있습니다.');});
$('apply-next').onclick=()=>work(async()=>{const value=collect();value.request.nextTraining=currentSummary.nextTraining;settings=await api('/api/settings',{method:'POST',body:value});populate();notify('다음 생성의 참고 정보로 저장했습니다. 아동 프로필·기존 결과는 자동 변경하지 않습니다.');});
(async()=>{try{session=await api('/api/session');settings=await api('/api/settings');populate();$('card-stamp').textContent=session.cardVersions.map(c=>`${names[c.npcId]} v${c.version}`).join(' · ')+' / 제공 파일 그대로';$('observations').value=JSON.stringify(await api('/api/summary-example'),null,2);notify('설정 준비 완료. 모의 요청을 실행할 수 있습니다. 유료 호출은 켜져 있지 않습니다.');}catch(e){notify(e.message,true);$('generate').disabled=true;}})();
