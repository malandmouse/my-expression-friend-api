"""Own only this container's API/worker children. Never log keys or start paid generation."""
from pathlib import Path
import json
import os
import shlex
import signal
import subprocess
import sys
import time
import urllib.request

CONFIGURATION_REF = 'prototype-st-v2.1-ko4'

def configuration(env):
    result = dict(env)
    port = int(result.get('PORT', '8080'))
    worker_port = int(result.get('EF_AGENT_PORT', '5198'))
    if not all(1024 <= p <= 65535 for p in (port, worker_port)) or port == worker_port:
        raise ValueError('PORT_CONFIGURATION_INVALID')
    if result.get('EF_PROTOTYPE_TEST_CLOCK_ENABLED', 'false') != 'false':
        raise ValueError('DEPLOYMENT_TEST_CLOCK_MUST_BE_OFF')
    bundle = result.get('EF_AGENT_ENABLED', 'false') == 'true' and result.get('EF_AGENT_BUNDLE_WORKER', 'true') == 'true'
    if bundle:
        if result.get('EF_AGENT_MODE', 'MOCK') not in ('MOCK', 'LIVE') or result.get('EF_AGENT_V2') != 'true':
            raise ValueError('V2_WORKER_MODE_REQUIRED')
        if len(result.get('EF_AGENT_SERVICE_TOKEN', '')) < 32:
            raise ValueError('SERVICE_TOKEN_REQUIRED')
        if result.get('EF_AGENT_CONFIGURATION_REF', CONFIGURATION_REF) != CONFIGURATION_REF:
            raise ValueError('BUNDLED_WORKER_CONFIGURATION_REF_MISMATCH')
        result['EF_AGENT_CONFIGURATION_REF'] = CONFIGURATION_REF
        live = result.get('EF_AGENT_MODE', 'MOCK') == 'LIVE'
        if live != (result.get('EF_PROTOTYPE_LIVE_ENABLED', 'false') == 'true'):
            raise ValueError('API_WORKER_MODE_MISMATCH')
        if live and (result.get('EF_AGENT_V2') != 'true' or not all(result.get(x) for x in
                ('OPENAI_API_KEY', 'TYPECAST_API_KEY', 'TYPECAST_JUN_VOICE_ID', 'TYPECAST_NARA_VOICE_ID'))):
            raise ValueError('LIVE_PROVIDER_CONFIGURATION_REQUIRED')
        origin = f'http://127.0.0.1:{worker_port}'
        if result.get('EF_AGENT_URL', origin).rstrip('/') != origin:
            raise ValueError('BUNDLED_WORKER_ORIGIN_MISMATCH')
        storage = f'http://127.0.0.1:{port}'
        if result.get('EF_AGENT_STORAGE_ORIGIN', storage).rstrip('/') != storage:
            raise ValueError('BUNDLED_STORAGE_ORIGIN_MISMATCH')
        result.update(EF_AGENT_URL=origin, EF_AGENT_STORAGE_ORIGIN=storage, EF_AGENT_PORT=str(worker_port))
    result.update(PORT=str(port), SERVER_ADDRESS=result.get('SERVER_ADDRESS', '0.0.0.0'))
    return result, bundle

def worker_ready(env, status):
    return isinstance(status, dict) and status.get('mode') == env.get('EF_AGENT_MODE', 'MOCK') and \
        status.get('engine') == 'EF_ST_AGENT_DRAFT_V2_1' and status.get('configurationRef') == env['EF_AGENT_CONFIGURATION_REF']


def read_status(url, token=None):
    headers = {'Authorization': 'Bearer ' + token} if token else {}
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=3) as response:
        return json.loads(response.read(8192))


def stop_owned(processes):
    for _, process in reversed(processes):
        if process.poll() is None:
            process.terminate()
    deadline = time.monotonic() + 20
    for _, process in reversed(processes):
        try:
            process.wait(timeout=max(.1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def main():
    env, bundle = configuration(os.environ)
    if '--healthcheck' in sys.argv:
        return 0 if read_status(f'http://127.0.0.1:{env["PORT"]}/actuator/health').get('status') == 'UP' else 1
    root = Path(__file__).resolve().parents[1]
    processes = []
    def stop(_number, _frame):
        raise KeyboardInterrupt()
    for number in (signal.SIGTERM, signal.SIGINT):
        signal.signal(number, stop)
    try:
        if bundle:
            worker = subprocess.Popen(['node', str(root / 'worker/integration/server.mjs')], env=env)
            processes.append(('worker', worker))
            deadline = time.monotonic() + 25
            ready = False
            while worker.poll() is None and time.monotonic() < deadline:
                try:
                    status = read_status(f'http://127.0.0.1:{env["EF_AGENT_PORT"]}/internal/v2/worker-status', env['EF_AGENT_SERVICE_TOKEN'])
                    ready = worker_ready(env, status)
                    if ready:
                        break
                except (OSError, ValueError):
                    pass
                time.sleep(.2)
            if not ready:
                raise ValueError('WORKER_STARTUP_FAILED')
        api = subprocess.Popen(['java', *shlex.split(env.get('JAVA_OPTS', '')), '-jar', str(root / 'app.jar')], env=env)
        processes.append(('api', api))
        print('API runtime started; worker private, test clock off. No fixture account or generation request is created.', flush=True)
        while True:
            for name, process in processes:
                if process.poll() is not None:
                    print(name + ' exited; stopping owned companion process.', flush=True)
                    return 1
            time.sleep(.5)
    except KeyboardInterrupt:
        return 0
    finally:
        stop_owned(processes)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception:
        print('RUNTIME_CONFIGURATION_OR_STARTUP_FAILED. Check server environment; secret values are not printed.', flush=True)
        sys.exit(1)
