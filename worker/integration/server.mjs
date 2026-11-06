import http from 'node:http';
import { createWorker } from './foundation.mjs';
import { defaultAuthoring } from '../src/authoring.mjs';
import { DIALOGUE_CONFIGURATION_REF } from '../src/dialogue-planning.mjs';
const token=process.env.EF_AGENT_SERVICE_TOKEN;
const v2=process.env.EF_AGENT_V2==='true',live=process.env.EF_AGENT_MODE==='LIVE';
if(live&&!v2)throw Error('LIVE requires explicit V2.1 engine');
if(live&&(!process.env.OPENAI_API_KEY||!process.env.TYPECAST_API_KEY||
  !/^tc_[A-Za-z0-9_-]{1,100}$/.test(process.env.TYPECAST_JUN_VOICE_ID??'')||
  !/^tc_[A-Za-z0-9_-]{1,100}$/.test(process.env.TYPECAST_NARA_VOICE_ID??'')))
  throw Error('LIVE provider keys and both Typecast voice IDs must be configured on the server');
const dispatch=createWorker({token,v2,live,apiKey:live?process.env.OPENAI_API_KEY:undefined,
  backendOrigin:process.env.EF_AGENT_STORAGE_ORIGIN??'http://127.0.0.1:8080',
  settings:{...defaultAuthoring,modelRef:process.env.EF_AGENT_MODEL??defaultAuthoring.modelRef,
    configurationRef:process.env.EF_AGENT_CONFIGURATION_REF??DIALOGUE_CONFIGURATION_REF}});
const port=Number(process.env.EF_AGENT_PORT??5198);
if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Invalid worker port');
// Never expose the worker/UI bootstrap token on a public interface.
const server=http.createServer(async(req,res)=>{
  res.setHeader('Cache-Control','no-store');res.setHeader('Content-Type','application/json');
  let size=0;const chunks=[];
  try {
    for await(const chunk of req){size+=chunk.length;if(size>262144){res.writeHead(413);res.end('{"code":"PAYLOAD_TOO_LARGE"}');return;}chunks.push(chunk)}
    const response=await dispatch({method:req.method,path:new URL(req.url,'http://127.0.0.1').pathname,
      authorization:req.headers.authorization,body:JSON.parse(Buffer.concat(chunks).toString('utf8')||'null')});
    res.writeHead(response.status);res.end(JSON.stringify(response.body));
  }catch{res.writeHead(400);res.end('{"code":"INVALID_SCHEMA"}')}
});
server.requestTimeout=250000;server.headersTimeout=10000;
server.listen(port,'127.0.0.1',()=>console.log(`Foundation ${v2?'V2.1':'V1'} ${live?'LIVE':'MOCK'} worker ready on 127.0.0.1:${port}.`));
