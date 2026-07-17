#!/usr/bin/env bash
# macOS 배포용 DMG 생성 스크립트 (package-exe.ps1의 맥 버전)
# 요구 사항: macOS + jar·jpackage가 포함된 전체 JDK 17 이상
# 산출물: dist/AIUsageWidget-<버전>.dmg
set -euo pipefail
cd "$(dirname "$0")"

if [ "$(uname)" != "Darwin" ]; then
  echo "이 스크립트는 macOS에서만 실행할 수 있습니다. (jpackage는 크로스 패키징을 지원하지 않음)" >&2
  exit 1
fi

APP_VERSION="1.4.0"

# 1. 컴파일 + 테스트
bash build.sh

# 2. JAR 패키징 (아이콘 리소스 포함)
PACKAGE_INPUT="build/package-input"
rm -rf "$PACKAGE_INPUT" build/package-output
mkdir -p "$PACKAGE_INPUT"
jar --create --file "$PACKAGE_INPUT/token-usage-widget.jar" -C build/classes .

# 3. Chrome 확장 프로그램을 앱 번들에 동봉한다.
#    설치 후 위치: /Applications/AIUsageWidget.app/Contents/app/extension
cp -R extension "$PACKAGE_INPUT/extension"

# 4. DMG 생성
JPACKAGE_ARGS=(
  --type dmg
  --name AIUsageWidget
  --app-version "$APP_VERSION"
  --input "$PACKAGE_INPUT"
  --main-jar token-usage-widget.jar
  --main-class dev.tokenwidget.App
  --add-modules "java.desktop,java.prefs,java.net.http,jdk.httpserver"
  --dest dist
)
if [ -f assets/app-icon.icns ]; then
  JPACKAGE_ARGS+=(--icon assets/app-icon.icns)
fi

mkdir -p dist
jpackage "${JPACKAGE_ARGS[@]}"

echo "macOS DMG: $(ls dist/AIUsageWidget-*.dmg | tail -1)"
