# 변경 이력

## 1.4.0 — 2026-07-17

**최초 릴리즈** (위젯 앱 1.4.0 · Chrome 확장 0.5.3)

- Claude·Codex·Antigravity·Cursor·GitHub Copilot의 계정 사용 한도를 하나의 위젯에서 표시
- Claude·Cursor는 Chrome 확장(로그인된 브라우저 세션), Codex·Antigravity·Copilot은 로컬 데이터로 조회
- 간략히 / 펼치기 표시 모드 (선택한 모드는 재실행 시에도 유지)
- 트레이(메뉴 막대) 아이콘: 에이전트별 요약, 모드 전환, 위젯 보이기/숨기기
- 조회되는 에이전트만 카드 표시, 조회가 시작되면 자동 추가
- 위젯 재시작 시 마지막 Claude·Cursor 값을 로컬 캐시에서 즉시 복원
- 단일 인스턴스 보장 (중복 실행 시 기존 창을 앞으로)
- Codex: 만료된 한도 구간 숨김, 10분 이상 지난 스냅샷에 관찰 시점 표시
- Windows 단일 포터블 EXE·macOS DMG 패키징 (Java 설치 불필요)
