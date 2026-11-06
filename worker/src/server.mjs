import http from 'node:http';
import { createApp } from './app.mjs';
import { errorBody, AgentError } from './base.mjs';

const port = Number(process.env.EF_LAB_PORT ?? 5197);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid local port');
const app = createApp({ port });
const server = http.createServer(async (req, res) => {
  const headers = { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff', 'X-Frame-Options': 'DENY',
    'Content-Security-Policy': "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'none'" };
  try {
    let body = null;
    if (req.method === 'POST') {
      if (!String(req.headers['content-type']).startsWith('application/json')) throw new AgentError('INPUT_INVALID', 'application/json 요청이 필요합니다.', 415);
      const chunks = []; let size = 0;
      for await (const b of req) { size += b.length; if (size > 1024 * 1024) throw new AgentError('INPUT_INVALID', '요청은 1 MiB 이하여야 합니다.', 413); chunks.push(b); }
      try { body = JSON.parse(Buffer.concat(chunks).toString('utf8')); } catch { throw new AgentError('INPUT_INVALID', 'JSON을 읽을 수 없습니다.'); }
    }
    const result = await app.dispatch({ method: req.method, pathname: new URL(req.url, `http://127.0.0.1:${port}`).pathname, headers: req.headers, body });
    res.writeHead(result.status, { ...headers, ...result.headers });
    res.end(result.raw ?? JSON.stringify(result.body, null, 2));
  } catch (e) { res.writeHead(e.status ?? 500, headers); res.end(JSON.stringify(errorBody(e))); }
});
server.on('error', e => {
  console.error(e.code === 'EADDRINUSE' ? `포트 ${port}가 사용 중입니다. 기존 서버를 종료하지 말고 EF_LAB_PORT로 다른 포트를 지정하세요.` : `서버를 시작하지 못했습니다 (${e.code ?? 'ERROR'}).`);
  process.exitCode = 1;
});
server.listen(port, '127.0.0.1', () => console.log(`AI Agent 실험실: http://127.0.0.1:${port}\n모의 전송 전용 · GPT/Typecast 유료 호출 0 · 종료하려면 Control+C`));
process.on('SIGTERM', () => server.close());
