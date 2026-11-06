"""Read-only container/configuration checks. No sockets, providers, DB migrations or secret output."""
from pathlib import Path
import argparse
import base64
import importlib.util
import json
import os
import re
import ssl
import subprocess
import sys
from decimal import Decimal, InvalidOperation

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('prototype_container_runtime', HERE / 'serve.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


def configuration_issues(env):
    issues = []
    if any(env.get(name) != 'true' for name in ('EF_AGENT_ENABLED', 'EF_AGENT_V2', 'EF_PROTOTYPE_PUBLICATION_ENABLED')):
        issues.append('PROTOTYPE_AGENT_V2_AND_PUBLICATION_REQUIRED')
    try:
        runtime.configuration(env)
    except (ValueError, TypeError):
        issues.append('API_WORKER_PORT_MODE_TOKEN_OR_TEST_CLOCK_INVALID')
    if not env.get('EF_DB_URL', '').startswith('jdbc:postgresql://'):
        issues.append('POSTGRES_JDBC_URL_REQUIRED')
    if not env.get('EF_DB_USERNAME') or not env.get('EF_DB_PASSWORD'):
        issues.append('PRODUCTION_DATABASE_CREDENTIALS_REQUIRED')
    keys = []
    for name in ('EF_IDEMPOTENCY_FINGERPRINT_KEY', 'EF_IDEMPOTENCY_ENCRYPTION_KEY', 'EF_JWT_SIGNING_KEY'):
        try:
            value = base64.b64decode(env.get(name, ''), validate=True)
            if len(value) != 32:
                raise ValueError()
            keys.append(value)
        except (ValueError, TypeError):
            issues.append(name + '_BASE64_32_BYTES_REQUIRED')
    if len(keys) == 3 and len(set(keys)) != 3:
        issues.append('THREE_DISTINCT_PERSISTENT_SERVER_KEYS_REQUIRED')
    try:
        if not 1 <= int(env.get('EF_CORE_LEASE_SECONDS', '0')) <= 30:
            raise ValueError()
    except ValueError:
        issues.append('MEDIA_LEASE_1_TO_30_SECONDS_REQUIRED')
    if env.get('EF_AGENT_MODE') == 'LIVE':
        for name in ('EF_AGENT_INPUT_USD_PER_MILLION', 'EF_AGENT_OUTPUT_USD_PER_MILLION',
                     'EF_AGENT_MAX_CALLS', 'EF_AGENT_MAX_INPUT_TOKENS', 'EF_AGENT_MAX_OUTPUT_TOKENS', 'EF_AGENT_MAX_COST_MICROUSD'):
            try:
                value = Decimal(env.get(name, '0'))
                if not value.is_finite() or value <= 0 or ('MAX_' in name and value != value.to_integral_value()):
                    raise ValueError()
            except (ValueError, InvalidOperation):
                issues.append(name + '_POSITIVE_REQUIRED')
    # 학습 경로는 Jev를 호출하지 않습니다. 과거 Jev 환경값/볼륨을 시작 요건으로 삼지 않습니다.
    return issues


def image_checks(root):
    checks = {'python3': sys.version_info >= (3, 10)}
    try:
        import certifi
        checks['typecastTrustStore'] = bool(ssl.create_default_context(cafile=certifi.where()).get_ca_certs())
    except (ImportError, OSError):
        checks['typecastTrustStore'] = False
    for command, minimum, label in ((['java', '-version'], 21, 'java21'), (['node', '--version'], 24, 'node24')):
        try:
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            match = re.search(r'(?:version\s+\"|^v)(\d+)', result.stdout + result.stderr)
            checks[label] = result.returncode == 0 and match is not None and int(match[1]) >= minimum
        except (OSError, subprocess.TimeoutExpired):
            checks[label] = False
    checks['applicationJar'] = (root / 'app.jar').is_file()
    checks['worker'] = (root / 'worker/integration/server.mjs').is_file()
    checks['koreanPrompt'] = (root / 'worker/prompts/korean-peer-style.json').is_file()
    checks['dailyDialoguePlanner'] = (root / 'worker/src/dialogue-planning.mjs').is_file()
    checks['gptPresentationSelector'] = (root / 'worker/src/presentation.mjs').is_file()
    checks['gptPresentationCatalog'] = (root / 'worker/contracts/gpt-presentation-v2.json').is_file()
    checks['installedCueCatalogV2'] = (root / 'worker/contracts/installed-cues-p2.json').is_file()
    checks['p1Contract'] = (root / 'worker/contracts/prototype-p1.schema.json').is_file()
    checks['typecastBridge'] = (root / 'worker/integration/typecast_bridge.py').is_file()
    license_file = root / 'worker/LICENSE'
    checks['sillyTavernLicense'] = license_file.is_file() and 'AFFERO GENERAL PUBLIC LICENSE' in license_file.read_text()
    checks['sillyTavernProvenance'] = (root / 'worker/vendor/sillytavern/UPSTREAM.json').is_file()
    return checks


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--image', action='store_true', help='Inspect the built image only; no environment/DB/provider access.')
    parser.add_argument('--environment', action='store_true', help='Inspect current environment values without printing them.')
    args = parser.parse_args()
    if args.image:
        checks = image_checks(HERE.parent)
        passed = all(checks.values())
        report = {'scope': 'BUILT_IMAGE_PREREQUISITES_ONLY', 'checks': checks}
    else:
        issues = configuration_issues(os.environ)
        passed = not issues
        report = {'scope': 'ENVIRONMENT_CONFIGURATION_ONLY', 'issues': issues}
    report.update(status='PASS' if passed else 'REQUIRES_CONFIGURATION', providerCalls=0, databaseConnections=0,
                  secretValuesPrinted=False, liveE2E='NOT_ASSESSED')
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
