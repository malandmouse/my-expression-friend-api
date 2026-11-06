import fs from 'node:fs/promises';
import path from 'node:path';
import { randomBytes } from 'node:crypto';
import { ROOT, need, errorBody, AgentError, readJson } from './base.mjs';
import { openapi } from './schema.mjs';
import { cards, cardVersions, quizBank } from './content.mjs';
import { compile } from './authoring.mjs';
import { summarize } from './learning.mjs';
import { LabStore } from './store.mjs';

export const GENERATE_PATH = '/api/plugins/scenario-agent/v1/daily-scenarios/generate';
export const SUMMARY_PATH = '/api/plugins/scenario-agent/v1/training/summarize';
export function createApp({ store = new LabStore(), port = 5197 } = {}) {
  const token = randomBytes(32).toString('base64url');
  const allowedHosts = new Set([`127.0.0.1:${port}`, `localhost:${port}`]);
  async function dispatch({ method, pathname, headers = {}, body = null }) {
    let jobId = null;
    try {
      need(allowedHosts.has(headers.host), 'FORBIDDEN', '로컬 주소로 접속하세요.', 403);
      if (headers.origin) {
        let origin; try { origin = new URL(headers.origin); } catch { throw new AgentError('FORBIDDEN', '허용되지 않은 Origin입니다.', 403); }
        need(origin.protocol === 'http:' && allowedHosts.has(origin.host), 'FORBIDDEN', '다른 웹사이트에서 이 서비스를 호출할 수 없습니다.', 403);
      }
      need(headers['sec-fetch-site'] !== 'cross-site', 'FORBIDDEN', '외부 사이트 요청은 허용하지 않습니다.', 403);
      if (method === 'GET' && pathname === '/api/session') return { status: 200, body: { token, mode: 'MOCK', paidCallsEnabled: false,
        cardVersions: cardVersions(Object.keys(cards)), quizBankStatus: 'LOCAL_UNREVIEWED_FIXTURE', repository: 'expression-friend-st-agent-lab', sourceLicense: 'AGPL-3.0' } };
      if (pathname.startsWith('/api/')) need(headers.authorization === `Bearer ${token}`, 'AUTH_REQUIRED', '실험 화면을 새로 열어 로컬 세션을 받아 주세요.', 401);
      if (method === 'GET' && pathname === '/api/settings') return { status: 200, body: await store.settings() };
      if (method === 'POST' && pathname === '/api/settings') return { status: 200, body: await store.saveSettings(body) };
      if (method === 'POST' && pathname === '/api/preview') {
        const settings = await store.settings();
        const prepared = compile(body, settings.authoring);
        return { status: 200, body: { mode: 'PREVIEW_ONLY', externalProviderCalls: 0, slotPlan: prepared.plan,
          cards: cardVersions(prepared.plan.map(p => p.npcId)), messages: prepared.messages, requestBody: prepared.stRequest,
          rag: prepared.selected.map(r => ({ id: r.id, title: r.title, sources: r.sources, note: r.note })),
          quizBank: quizBank.map(q => ({ id: q.id, emotionId: q.emotionId, reviewStatus: q.reviewStatus })) } };
      }
      if (method === 'POST' && pathname === GENERATE_PATH) {
        if (typeof body?.jobId === 'string' && /^[0-9a-f-]{36}$/i.test(body.jobId)) jobId = body.jobId;
        const job = await store.run(body, headers['idempotency-key']);
        return job.error ? { status: job.httpStatus, body: job.error } : { status: 200, body: job.result, headers: { 'X-Lab-Mode': 'MOCK' } };
      }
      if (method === 'POST' && pathname === SUMMARY_PATH) return { status: 200, body: summarize(body) };
      const match = pathname.match(/^\/api\/jobs\/([0-9a-f-]{36})(\/download)?$/i);
      if (method === 'GET' && match) {
        const job = await store.job(match[1]);
        if (match[2]) {
          need(job.status === 'DONE', 'VERSION_CONFLICT', '완료된 결과만 다운로드할 수 있습니다.', 409);
          return { status: 200, body: job.result, headers: { 'Content-Disposition': `attachment; filename="day-${job.result.program.programDay}-scenarios.json"` } };
        }
        return { status: 200, body: job };
      }
      if (method === 'GET' && pathname === '/api/summary-example') return { status: 200, body: await readJson(path.join(ROOT, 'examples/summarize-request.json')) };
      if (method === 'GET' && pathname === '/api/openapi.json') return { status: 200, body: { ...openapi,
        info: { ...openapi.info, title: 'ST Agent Local Experiment', version: '0.1.0', description: 'Implemented standalone local worker. MOCK ONLY. Bearer is a local in-memory session token, not foundation JWT. No publication/DB integration.' },
        components: { ...openapi.components, securitySchemes: { serviceBearer: { type: 'http', scheme: 'bearer', bearerFormat: 'LAB_SESSION_TOKEN' } } } } };
      const staticFiles = { '/': ['index.html', 'text/html; charset=utf-8'], '/app.js': ['app.js','text/javascript; charset=utf-8'], '/style.css': ['style.css','text/css; charset=utf-8'] };
      if (method === 'GET' && staticFiles[pathname]) {
        const [file, contentType] = staticFiles[pathname];
        return { status: 200, raw: await fs.readFile(path.join(ROOT, 'public', file)), headers: { 'Content-Type': contentType } };
      }
      throw new AgentError('INPUT_INVALID', '해당 경로가 없습니다.', 404);
    } catch (e) { return { status: e.status ?? 500, body: errorBody(e, jobId) }; }
  }
  return { dispatch, token, store };
}
