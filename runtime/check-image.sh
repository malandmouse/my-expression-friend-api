#!/bin/sh
# Build and inspect only. Never start the API, mount credentials, connect to a DB,
# or request GPT/Typecast/Jev. The first build can download base images/packages.
set -eu
if ! command -v docker >/dev/null 2>&1; then
    echo 'Docker가 필요합니다. 이미지 실행 확인은 아직 완료되지 않았습니다.' >&2
    exit 2
fi
core_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
image_tag=expression-friend-prototype:review
docker build --tag "$image_tag" "$core_root"
docker run --rm --network none --entrypoint python3 "$image_tag" /app/runtime/preflight.py --image
