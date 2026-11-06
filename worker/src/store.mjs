import fs from 'node:fs/promises';
import path from 'node:path';
import { ROOT, readJson, writeJson, clone, fingerprint, need, AgentError, errorBody } from './base.mjs';
import { defaultAuthoring, CONFIG_REF, compile, generate } from './authoring.mjs';
import { check } from './schema.mjs';

export function validateSettings(value) {
  need(value && typeof value === 'object' && !Array.isArray(value), 'INPUT_INVALID', '설정 객체가 필요합니다.');
  need(Object.keys(value).sort().join(',') === 'authoring,request,revision', 'INPUT_INVALID', '정의된 설정 항목만 저장할 수 있습니다.');
  check('GenerateDayRequest', value.request);
  need(Number.isSafeInteger(value.revision) && value.revision >= 0, 'INPUT_INVALID', '설정 버전이 필요합니다.');
  const a = value.authoring;
  need(a && Object.keys(a).sort().join(',') === Object.keys(defaultAuthoring).sort().join(','), 'INPUT_INVALID', '제작 설정 항목이 맞지 않습니다.');
  need(typeof a.systemInstruction === 'string' && a.systemInstruction.trim() && [...a.systemInstruction].length <= 12000, 'INPUT_INVALID', '시스템 지침은 1~12000자로 입력하세요.');
  need(['gpt-5.4','gpt-5.4-mini'].includes(a.modelRef) && ['low','medium','high'].includes(a.reasoningEffort), 'INPUT_INVALID', '등록된 모델·추론 설정을 선택하세요.');
  need(Number.isSafeInteger(a.maxOutputTokens) && a.maxOutputTokens >= 2000 && a.maxOutputTokens <= 64000, 'INPUT_INVALID', '출력 토큰 설정은 2000~64000입니다.');
  need(typeof a.ragEnabled === 'boolean' && Array.isArray(a.permittedPlaces) && a.permittedPlaces.length && a.permittedPlaces.length <= 7 && new Set(a.permittedPlaces).size === a.permittedPlaces.length && a.permittedPlaces.every(p => ['Home','Class','Park','Traffic','School','Mall','Canteen'].includes(p)), 'INPUT_INVALID', '사용할 장소를 한 곳 이상 선택하세요.');
  compile(value.request, a); // 이 단계는 프롬프트/형식 확인만 하며 제공자를 호출하지 않는다.
  return value;
}

export class LabStore {
  constructor(dataDir = path.join(ROOT, 'data'), generator = generate) {
    this.dataDir = dataDir; this.generator = generator; this.queue = Promise.resolve();
  }
  locked(work) {
    const result = this.queue.then(work); this.queue = result.catch(() => {}); return result;
  }
  async settings() {
    try { return await readJson(path.join(this.dataDir, 'settings.json')); }
    catch (e) {
      if (e.code !== 'ENOENT') throw e;
      const request = await readJson(path.join(ROOT, 'examples/generate-request.json'));
      request.configurationRef = CONFIG_REF;
      return { revision: 0, request, authoring: clone(defaultAuthoring) };
    }
  }
  async saveSettings(value) {
    validateSettings(value);
    return this.locked(async () => {
      const old = await this.settings();
      need(old.revision === value.revision, 'VERSION_CONFLICT', '설정이 다른 요청에서 변경됐습니다. 다시 불러오세요.', 409);
      const next = clone(value); next.revision++;
      await writeJson(path.join(this.dataDir, 'settings.json'), next); return next;
    });
  }
  async job(id) {
    need(/^[0-9a-f-]{36}$/i.test(id), 'INPUT_INVALID', '작업 ID가 올바르지 않습니다.');
    try { return await readJson(path.join(this.dataDir, 'jobs', `${id}.json`)); }
    catch (e) { if (e.code === 'ENOENT') throw new AgentError('INPUT_INVALID', '해당 로컬 작업이 없습니다.', 404); throw e; }
  }
  async run(input, key) {
    check('GenerateDayRequest', input);
    need(typeof key === 'string' && key.length > 0 && key.length <= 128, 'INPUT_INVALID', 'Idempotency-Key가 필요합니다.');
    // 실험용 로컬 직렬 실행 원장. 운영에서는 foundation의 GenerationJobs port로 교체한다.
    return this.locked(async () => {
      const settings = await this.settings();
      compile(input, settings.authoring);
      const digest = fingerprint({ input, authoring: settings.authoring });
      const dir = path.join(this.dataDir, 'jobs'); await fs.mkdir(dir, { recursive: true, mode: 0o700 });
      const files = await fs.readdir(dir);
      for (const file of files.filter(n => /^[0-9a-f-]{36}\.json$/i.test(n))) {
        const prior = await readJson(path.join(dir, file));
        if (prior.jobId === input.jobId || prior.idempotencyKey === key) {
          need(prior.jobId === input.jobId && prior.idempotencyKey === key && prior.fingerprint === digest,
            'VERSION_CONFLICT', '같은 작업 ID/멱등키의 입력·설정이 다릅니다. 새 요청으로 구분하세요.', 409);
          if (prior.status === 'RUNNING') throw new AgentError('OUTCOME_UNKNOWN', '이전 실행이 완료됐는지 확인할 수 없습니다. 자동으로 다시 실행하지 않습니다.', 504, 'PROVIDER', false, 'UNKNOWN');
          return { ...prior, cached: true };
        }
      }
      const file = path.join(dir, `${input.jobId}.json`);
      const record = { jobId: input.jobId, idempotencyKey: key, fingerprint: digest, createdAt: new Date().toISOString(),
        status: 'RUNNING', request: clone(input), settingsRevision: settings.revision, mode: 'MOCK', result: null };
      await writeJson(file, record); // 전송 이전 예약. 프로세스가 중단되어도 자동 재실행하지 않는다.
      try {
        // 이 서비스 버전은 항상 모의 전송이다. 웹/환경 변수/설정으로 실수로 유료 호출을 켤 수 없다.
        const generated = await this.generator(input, settings.authoring, { paidEnabled: false });
        Object.assign(record, generated, { status: 'DONE' });
      } catch (e) {
        record.status = e.execution === 'UNKNOWN' ? 'OUTCOME_UNKNOWN' : 'ERROR';
        record.httpStatus = e.status ?? 500; record.error = errorBody(e, input.jobId);
      }
      await writeJson(file, record); return record;
    });
  }
}
