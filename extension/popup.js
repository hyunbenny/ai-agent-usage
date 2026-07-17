function claudeUsageText(payload) {
  if (!payload) return "";
  return [
    payload.sessionPercent == null ? null : `5시간 ${payload.sessionPercent}%`,
    payload.weeklyPercent == null ? null : `주간 ${payload.weeklyPercent}%`
  ].filter(Boolean).join(" · ");
}

function cursorUsageText(payload) {
  if (!payload || payload.monthlyPercent == null) return "";
  const plan = payload.plan ? `${payload.plan} · ` : "";
  return `${plan}월간 ${payload.monthlyPercent}%`;
}

async function loadStatus() {
  const data = await chrome.storage.local.get([
    "claudeStatus", "claudePayload", "cursorStatus", "cursorPayload"
  ]);
  document.querySelector("#claude-status").textContent = data.claudeStatus || "아직 수집된 정보가 없습니다.";
  document.querySelector("#claude-usage").textContent = claudeUsageText(data.claudePayload);
  document.querySelector("#cursor-status").textContent = data.cursorStatus || "아직 수집된 정보가 없습니다.";
  document.querySelector("#cursor-usage").textContent = cursorUsageText(data.cursorPayload);
}

document.querySelector("#refresh").addEventListener("click", async () => {
  document.querySelector("#claude-status").textContent = "계정 한도 조회 중…";
  document.querySelector("#cursor-status").textContent = "계정 한도 조회 중…";
  await chrome.runtime.sendMessage({type: "REFRESH"});
  await loadStatus();
});

loadStatus();
