#!/usr/bin/env bash
# macOS/리눅스용 실행 스크립트 (run.ps1과 동일한 역할: 빌드가 없으면 빌드 후 실행)
set -euo pipefail
cd "$(dirname "$0")"

if [ ! -f build/classes/dev/tokenwidget/App.class ]; then
  bash build.sh
fi

exec java -cp build/classes dev.tokenwidget.App
