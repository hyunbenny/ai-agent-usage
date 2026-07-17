#!/usr/bin/env bash
# macOS/리눅스용 빌드 스크립트 (build.ps1과 동일한 역할: 컴파일 + 리소스 복사 + 테스트)
set -euo pipefail
cd "$(dirname "$0")"

rm -rf build/classes build/test-classes
mkdir -p build/classes build/test-classes

javac --release 17 -encoding UTF-8 -d build/classes $(find src/main/java -name '*.java')
javac --release 17 -encoding UTF-8 -d build/test-classes $(find src -name '*.java')

# 애플리케이션·트레이 아이콘 리소스를 클래스패스에 포함시킨다.
if [ -d assets ]; then
  mkdir -p build/classes/assets build/test-classes/assets
  cp assets/*.png build/classes/assets/
  cp assets/*.png build/test-classes/assets/
fi

java -ea -cp build/test-classes dev.tokenwidget.UsageParserTest

echo "Built classes: $(pwd)/build/classes"
