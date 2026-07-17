const CLAUDE_API_BASE = "https://claude.ai/api";
const CURSOR_SUMMARY_URL = "https://cursor.com/api/usage-summary";
const WIDGET_BASE = "http://127.0.0.1:32145";
const ALARM_NAME = "ai-usage-refresh";
const PING_ALARM_NAME = "widget-bridge-ping";
const REFRESH_MINUTES = 5;
const PING_MINUTES = 0.5;

chrome.runtime.onInstalled.addListener(() => {
  setupAlarm();
  refreshAll();
});

chrome.runtime.onStartup.addListener(() => {
  setupAlarm();
  refreshAll();
});

chrome.alarms.onAlarm.addListener(alarm => {
  if (alarm.name === ALARM_NAME) refreshAll();
  if (alarm.name === PING_ALARM_NAME) {
    reloadIfUpdatedOnDisk();
    ensureBridgeFresh();
  }
});

// 위젯 업데이트 등으로 디스크의 확장 파일이 교체되면 로드된 버전과 달라진다.
// 차이가 감지되면 스스로 다시 로드해 최신 코드를 반영한다. (확장이 켜져 있을 때만 동작)
async function reloadIfUpdatedOnDisk() {
  try {
    const response = await fetch(chrome.runtime.getURL("manifest.json"), {cache: "no-store"});
    const onDisk = await response.json();
    const loaded = chrome.runtime.getManifest().version;
    if (onDisk?.version && onDisk.version !== loaded) {
      chrome.runtime.reload();
    }
  } catch {
    // 읽기 실패 시 다음 확인 때 재시도한다.
  }
}

chrome.runtime.onMessage.addListener((message, _sender, sendResponse) => {
  if (message?.type !== "REFRESH") return false;
  refreshAll().then(sendResponse);
  return true;
});

async function setupAlarm() {
  const alarm = await chrome.alarms.get(ALARM_NAME);
  if (!alarm || alarm.periodInMinutes !== REFRESH_MINUTES) {
    await chrome.alarms.create(ALARM_NAME, {periodInMinutes: REFRESH_MINUTES});
  }
  const ping = await chrome.alarms.get(PING_ALARM_NAME);
  if (!ping || ping.periodInMinutes !== PING_MINUTES) {
    await chrome.alarms.create(PING_ALARM_NAME, {periodInMinutes: PING_MINUTES});
  }
}

// 위젯이 꺼졌다 켜지면 /health의 startedAt이 바뀐다. 1분 주기로 확인해서
// 재시작이 감지되면 캐시된 사용량을 즉시 재전송하고 최신값도 다시 조회한다.
async function ensureBridgeFresh() {
  try {
    const response = await fetch(`${WIDGET_BASE}/health`);
    if (!response.ok) return;
    const health = await response.json();
    const startedAt = health?.startedAt;
    if (!startedAt) return;
    const {bridgeServedStart} = await chrome.storage.local.get("bridgeServedStart");
    if (bridgeServedStart === startedAt) return;

    // 로그아웃 상태면 clear 신호를, 아니면 캐시된 값을 다시 보낸다.
    await pushCachedToWidget();
    await chrome.storage.local.set({bridgeServedStart: startedAt});
    refreshAll();
  } catch {
    // 위젯이 실행 중이 아니면 다음 핑에서 다시 확인한다.
  }
}

async function refreshAll() {
  const [claude, cursor] = await Promise.all([refreshClaude(), refreshCursor()]);
  return {claude, cursor};
}

async function refreshClaude() {
  try {
    const organizations = await fetchJson(`${CLAUDE_API_BASE}/organizations`,
      "Claude 로그인이 필요합니다");
    const org = selectOrganization(organizations);
    const orgId = org?.uuid || org?.organization_uuid || org?.id;
    if (!orgId) throw new Error("Claude 조직을 찾지 못했습니다");

    const usage = await fetchJson(`${CLAUDE_API_BASE}/organizations/${orgId}/usage`,
      "Claude 로그인이 필요합니다");
    const payload = {...parseClaudeLimits(usage), sourceUrl: "claude-internal-api",
      observedAt: new Date().toISOString()};
    if (payload.sessionPercent === null && payload.weeklyPercent === null) {
      throw new Error("사용 한도 데이터가 없습니다");
    }
    await chrome.storage.local.set({
      claudeStatus: "계정 한도 갱신 완료",
      claudeLastSeen: payload.observedAt,
      claudePayload: payload,
      claudeLoggedOut: false
    });
    updateBadge(payload.sessionPercent);
    await sendToWidget("/claude-usage", payload);
    return {ok: true, payload};
  } catch (error) {
    const message = String(error?.message || error);
    if (error?.authExpired) {
      // 로그아웃: 캐시된 값을 버리고 위젯 카드도 내리도록 clear 신호를 보낸다.
      await chrome.storage.local.remove("claudePayload");
      await chrome.storage.local.set({
        claudeStatus: "로그아웃됨 (세션 없음)",
        claudeLastSeen: new Date().toISOString(),
        claudeLoggedOut: true
      });
      chrome.action.setBadgeText({text: ""});
      await sendToWidget("/claude-usage", {authExpired: true});
      return {ok: false, error: message, loggedOut: true};
    }
    await chrome.storage.local.set({
      claudeStatus: `갱신 실패: ${message}`,
      claudeLastSeen: new Date().toISOString()
    });
    chrome.action.setBadgeText({text: "!"});
    chrome.action.setBadgeBackgroundColor({color: "#6b7280"});
    return {ok: false, error: message};
  }
}

async function refreshCursor() {
  try {
    const summary = await fetchJson(CURSOR_SUMMARY_URL, "Cursor 로그인이 필요합니다");
    const plan = summary?.individualUsage?.plan;
    const percent = normalizePercent(
      plan?.totalPercentUsed
        ?? (plan?.limit ? (plan.used / plan.limit) * 100 : null));
    if (percent === null) throw new Error("사용 한도 데이터가 없습니다");
    const payload = {
      monthlyPercent: percent,
      monthlyResetAt: normalizeReset(summary?.billingCycleEnd),
      plan: summary?.membershipType || "cursor",
      observedAt: new Date().toISOString()
    };
    await chrome.storage.local.set({
      cursorStatus: "사용량 갱신 완료",
      cursorLastSeen: payload.observedAt,
      cursorPayload: payload,
      cursorLoggedOut: false
    });
    await sendToWidget("/cursor-usage", payload);
    return {ok: true, payload};
  } catch (error) {
    const message = String(error?.message || error);
    if (error?.authExpired) {
      // 로그아웃: 캐시된 값을 버리고 위젯 카드도 내리도록 clear 신호를 보낸다.
      await chrome.storage.local.remove("cursorPayload");
      await chrome.storage.local.set({
        cursorStatus: "로그아웃됨 (세션 없음)",
        cursorLastSeen: new Date().toISOString(),
        cursorLoggedOut: true
      });
      await sendToWidget("/cursor-usage", {authExpired: true});
      return {ok: false, error: message, loggedOut: true};
    }
    await chrome.storage.local.set({
      cursorStatus: `갱신 실패: ${message}`,
      cursorLastSeen: new Date().toISOString()
    });
    return {ok: false, error: message};
  }
}

async function fetchJson(url, loginMessage) {
  const response = await fetch(url, {method: "GET", credentials: "include",
    headers: {Accept: "application/json, text/plain, */*"}});
  if (response.status === 401 || response.status === 403) {
    // 세션이 없거나 만료됨(로그아웃). 다른 오류와 구분하려고 플래그를 붙인다.
    const authError = new Error(loginMessage || "로그인이 필요합니다");
    authError.authExpired = true;
    throw authError;
  }
  if (!response.ok) throw new Error(`API HTTP ${response.status}`);
  return response.json();
}

function selectOrganization(payload) {
  const organizations = Array.isArray(payload) ? payload
    : Array.isArray(payload?.organizations) ? payload.organizations : [];
  return organizations.find(org => Array.isArray(org?.capabilities) && org.capabilities.includes("chat"))
    || organizations[0] || null;
}

function parseClaudeLimits(usage) {
  const scoped = {};
  if (Array.isArray(usage?.limits)) {
    for (const limit of usage.limits) {
      if (limit?.kind === "session") scoped.session = limit;
      if (limit?.kind === "weekly_all") scoped.weekly = limit;
    }
  }
  const session = scoped.session || usage?.five_hour || null;
  const weekly = scoped.weekly || usage?.seven_day || null;
  return {
    sessionPercent: normalizePercent(session?.percent ?? session?.utilization),
    sessionResetAt: normalizeReset(session?.resets_at),
    weeklyPercent: normalizePercent(weekly?.percent ?? weekly?.utilization),
    weeklyResetAt: normalizeReset(weekly?.resets_at)
  };
}

function normalizePercent(value) {
  if (value === null || value === undefined || value === "") return null;
  const number = Number(value);
  return Number.isFinite(number) && number >= 0 ? Math.min(100, number) : null;
}

function normalizeReset(value) {
  if (!value) return null;
  const timestamp = Date.parse(value);
  return Number.isFinite(timestamp) ? new Date(timestamp).toISOString() : null;
}

async function sendToWidget(path, payload) {
  try {
    await fetch(`${WIDGET_BASE}${path}`, {method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify(payload)});
  } catch {
    // The widget may be closed. Cached values remain available in the popup.
  }
}

function updateBadge(percent) {
  if (percent === null) {
    chrome.action.setBadgeText({text: "?"});
    chrome.action.setBadgeBackgroundColor({color: "#6b7280"});
    return;
  }
  chrome.action.setBadgeText({text: `${Math.round(percent)}%`});
  chrome.action.setBadgeBackgroundColor({color: percent < 50 ? "#22c55e" : percent < 80 ? "#f59e0b" : "#ef4444"});
}

// 저장된 상태를 위젯에 반영한다. 로그아웃(logged out)인 provider는 clear 신호로
// 카드를 내리고, 정상 provider는 마지막 값을 다시 전송한다.
async function pushCachedToWidget() {
  const state = await chrome.storage.local.get([
    "claudePayload", "claudeLoggedOut", "cursorPayload", "cursorLoggedOut"
  ]);
  if (state.claudeLoggedOut) {
    await sendToWidget("/claude-usage", {authExpired: true});
  } else if (state.claudePayload) {
    updateBadge(state.claudePayload.sessionPercent);
    await sendToWidget("/claude-usage", state.claudePayload);
  }
  if (state.cursorLoggedOut) {
    await sendToWidget("/cursor-usage", {authExpired: true});
  } else if (state.cursorPayload) {
    await sendToWidget("/cursor-usage", state.cursorPayload);
  }
}

pushCachedToWidget();

// 서비스 워커가 깨어날 때마다 파일 갱신·위젯 재시작 여부를 바로 확인한다.
reloadIfUpdatedOnDisk();
ensureBridgeFresh();
