import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const VERSION = 'EF_ST_AGENT_DRAFT_V1';
export const NPCS = { JungHoon: '준', MainPlayer: '나래' };
export const ST_COMMIT = '06bde939fb1e9c4c8d8641d810f0a916b5bce127';
export const readJson = async p => JSON.parse(await fs.readFile(p, 'utf8'));
export const clone = value => structuredClone(value);
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
// 이 해시는 로컬 멱등 비교 전용이다. 기존 제품의 packageHash/0.6 digest를 대신하지 않는다.
export function stable(value) {
  if (Array.isArray(value)) return value.map(stable);
  if (value && typeof value === 'object') return Object.fromEntries(Object.keys(value).sort().map(k => [k, stable(value[k])]));
  return value;
}
export const fingerprint = value => sha256(JSON.stringify(stable(value)));
export class AgentError extends Error {
  constructor(code, message, status = 422, stage = 'INPUT', attempted = false, execution = 'NOT_STARTED', issues = []) {
    super(message); Object.assign(this, { code, status, stage, attempted, execution, issues });
  }
}
export function need(ok, code, message, status = 422) { if (!ok) throw new AgentError(code, message, status); }
export async function writeJson(file, value) {
  await fs.mkdir(path.dirname(file), { recursive: true, mode: 0o700 });
  const tmp = `${file}.${process.pid}.tmp`;
  await fs.writeFile(tmp, JSON.stringify(value, null, 2) + '\n', { mode: 0o600 });
  await fs.rename(tmp, file);
}
export function errorBody(error, jobId = null) {
  const e = error instanceof AgentError ? error : new AgentError('OUTPUT_INVALID', '처리하지 못했습니다. 서버의 원문·키는 오류에 포함하지 않습니다.', 500, 'ASSEMBLY');
  return { contractVersion: VERSION, jobId, error: { code: e.code, stage: e.stage, providerAttempted: e.attempted,
    executionState: e.execution, automaticRetryAllowed: false,
    issues: e.issues.length ? e.issues : [{ path: e.stage.toLowerCase(), reason: e.message }] } };
}
