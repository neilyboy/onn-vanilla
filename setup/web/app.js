const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
let S = {};
let saveTimer;

const THEMES = {
  midnight: { bg: "linear-gradient(135deg,#0B0D12,#141826)", text: "#F2F2F2", tile: "rgba(255,255,255,.2)", accent: "#8AB4F8" },
  graphite: { bg: "linear-gradient(135deg,#111,#1C1C1C)", text: "#EDEDED", tile: "rgba(255,255,255,.15)", accent: "#FFFFFF" },
  nord: { bg: "linear-gradient(135deg,#2E3440,#3B4252)", text: "#ECEFF4", tile: "rgba(76,86,106,.6)", accent: "#88C0D0" },
  forest: { bg: "linear-gradient(135deg,#0E1A14,#16261D)", text: "#EFF5EF", tile: "rgba(255,255,255,.18)", accent: "#91C49B" },
  sand: { bg: "linear-gradient(135deg,#EDE6DA,#F6F1E8)", text: "#26231F", tile: "rgba(0,0,0,.13)", accent: "#8C6A43" },
  oled: { bg: "#000", text: "#E0E0E0", tile: "rgba(255,255,255,.1)", accent: "#E0E0E0" },
};
const LEAGUES = {
  nfl: "NFL", "college-football": "College Football", nba: "NBA", "mens-college-basketball": "College Basketball",
  wnba: "WNBA", nhl: "NHL", mlb: "MLB", mls: "MLS", epl: "Premier League",
};

async function api(path, body) {
  const r = await fetch(path, body === undefined ? {} : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
  const ct = r.headers.get("content-type") || "";
  const data = ct.includes("json") ? await r.json() : await r.text();
  if (!r.ok) throw new Error(data.error || data);
  return data;
}

function save() {
  clearTimeout(saveTimer);
  $("#saved").textContent = "Saving…";
  saveTimer = setTimeout(async () => {
    try { await api("/api/setup", S); $("#saved").textContent = "Saved on this computer only"; }
    catch (e) { $("#saved").textContent = "Save failed: " + e.message; }
  }, 300);
}

function go(step) {
  $$(".step").forEach(s => s.classList.toggle("active", s.id === step));
  $$("#nav a").forEach(a => a.classList.toggle("active", a.dataset.step === step));
  window.scrollTo(0, 0);
  if (step === "box") checkAdb();
}

// Starting points for the layout picker; each widget can still be changed afterwards.
const WIDGET_PRESETS = {
  minimal: {},
  glance: { wScores: "center", wOnnow: "center", wResume: "center", wNewvod: "center" },
  mirror: { wCalendar: "left", wNews: "left", wWeather: "right", wHa: "right", wScores: "center", wOnnow: "center", wCameras: "center", wResume: "center", wNewvod: "center" },
};

function bindFields() {
  $$("[data-key]").forEach(el => {
    const k = el.dataset.key;
    if (el.type === "checkbox") el.checked = !!S[k]; else el.value = S[k] ?? "";
    el.addEventListener(el.type === "checkbox" ? "change" : "input", () => {
      S[k] = el.type === "checkbox" ? el.checked : (el.type === "number" || "num" in el.dataset) ? +el.value : el.value;
      if (k === "accent" && /^#[0-9a-f]{6}$/i.test(el.value)) $("#accent-pick").value = el.value;
      save();
    });
  });
  if (/^#[0-9a-f]{6}$/i.test(S.accent || "")) $("#accent-pick").value = S.accent;
  $("#accent-pick").addEventListener("input", e => { S.accent = e.target.value; $("#accent").value = S.accent; save(); });
  $("#layout").addEventListener("change", e => {
    const preset = WIDGET_PRESETS[e.target.value] || {};
    for (const k of Object.keys(WIDGET_PRESETS.mirror)) {
      S[k] = preset[k] || "";
      const el = $(`[data-key="${k}"]`);
      if (el) el.value = S[k];
    }
    save();
  });
}

// ---------- Step 1: playlist ----------
async function testPlaylist() {
  const st = $("#test-status"), box = $("#test-result");
  st.textContent = "Testing…"; box.classList.add("hidden");
  try {
    const r = await api("/api/test-playlist", S);
    st.textContent = "";
    box.className = "card " + (r.channels ? "ok" : "err");
    box.innerHTML = r.channels
      ? `<b>${r.channels.toLocaleString()} channels</b> in ${r.groups} groups` +
        (r.epgChannels !== undefined ? ` · guide has ${r.epgChannels.toLocaleString()} channels, ${r.epgProgrammes.toLocaleString()} programmes` : "") +
        (r.epgError ? `<br><span class="muted">Guide error: ${esc(r.epgError)}</span>` : "") +
        `<div class="muted" style="margin-top:8px">${r.sample.map(esc).join(" · ")}</div>`
      : `The playlist works but has <b>0 channels</b>. In Dispatcharr, create channels from your streams (Channels page → select streams → Create Channels).`;
  } catch (e) { st.textContent = ""; box.className = "card err"; box.textContent = e.message; }
}

// ---------- Step 2: home ----------
// ---------- Profiles ----------
const DEFAULT_PROFILES = () => [
  { name: "Everyone", sports: true, groups: [], keywords: [] },
  { name: "Sports", sports: true, groups: ["Sports", "NFL", "College Football", "NBA", "NHL", "MLB", "Soccer", "Fights & PPV"], keywords: [] },
  { name: "Movies", sports: false, groups: ["Movies", "24/7 Channels"], keywords: ["Western", "Comedy", "Action", "Thriller"] },
  { name: "TV & Game Shows", sports: false, groups: ["Entertainment", "Free TV"], keywords: ["Game Show", "Jeopardy", "Wheel of Fortune", "Family Feud", "Price Is Right"] },
  { name: "News", sports: false, groups: ["News", "Locals"], keywords: [] },
  { name: "Kids", sports: false, groups: ["Kids"], keywords: [], kids: true },
];
const PROFILE_COLORS = ["#E5484D", "#3E9BFF", "#F5A524", "#8E6CEF", "#30A46C", "#E86FB4", "#12A5A5"];
const splitList = v => v.split(",").map(x => x.trim()).filter(Boolean);

function renderProfiles() {
  if (!Array.isArray(S.profiles) || !S.profiles.length) S.profiles = DEFAULT_PROFILES();
  $("#profiles").innerHTML = S.profiles.map((p, i) => `
    <div class="profile" data-i="${i}">
      <input type="text" data-f="name" value="${esc(p.name)}" placeholder="Name">
      <input type="color" data-f="color" value="${esc(p.color || PROFILE_COLORS[i % PROFILE_COLORS.length])}" title="Avatar color">
      <label class="toggle"><input type="checkbox" data-f="sports" ${p.sports ? "checked" : ""}> Sports</label>
      <label class="toggle" title="Hides adult channels everywhere"><input type="checkbox" data-f="kids" ${p.kids ? "checked" : ""}> Kids</label>
      <input type="text" data-f="pin" value="${esc(p.pin || "")}" placeholder="PIN" inputmode="numeric" maxlength="8" title="Optional: digits needed to switch to this profile">
      <input type="text" data-f="groups" value="${esc((p.groups || []).join(", "))}" placeholder="Groups, e.g. Movies, 24/7 Channels">
      <input type="text" data-f="keywords" value="${esc((p.keywords || []).join(", "))}" placeholder="Keywords, e.g. Western, Gunsmoke">
      <button class="ghost" data-del title="Remove">✕</button>
    </div>`).join("");
  $$("#profiles .profile").forEach(row => {
    const p = S.profiles[+row.dataset.i];
    row.querySelectorAll("[data-f]").forEach(el => el.addEventListener(el.type === "checkbox" ? "change" : "input", () => {
      const f = el.dataset.f;
      if (f === "pin") el.value = el.value.replace(/\D/g, "");
      p[f] = el.type === "checkbox" ? el.checked : (f === "name" || f === "color" || f === "pin") ? el.value : splitList(el.value);
      save();
    }));
    row.querySelector("[data-del]").onclick = () => { S.profiles.splice(+row.dataset.i, 1); renderProfiles(); save(); };
  });
}

function renderSources() {
  if (!Array.isArray(S.sources)) S.sources = [];
  if (S.sources.length) $("#sources-box").open = true;
  $("#sources").innerHTML = S.sources.map((x, i) => `
    <div class="profile" data-i="${i}">
      <input type="text" data-f="name" value="${esc(x.name)}" placeholder="Name">
      <select data-f="type"><option value="xtream" ${x.type !== "m3u" ? "selected" : ""}>Xtream Codes</option><option value="m3u" ${x.type === "m3u" ? "selected" : ""}>M3U link</option></select>
      <input type="text" data-f="url" value="${esc(x.url)}" placeholder="${x.type === "m3u" ? "M3U link" : "Server, e.g. http://line.example.com:8080"}">
      ${x.type === "m3u" ? "" : `<input type="text" data-f="username" value="${esc(x.username)}" placeholder="Username" autocomplete="off">
      <input type="password" data-f="password" value="${esc(x.password)}" placeholder="Password" autocomplete="new-password">`}
      <input type="text" data-f="epg" value="${esc(x.epg)}" placeholder="Guide (XMLTV) link, optional">
      <button class="ghost" data-del title="Remove">✕</button>
    </div>`).join("");
  $$("#sources .profile").forEach(row => {
    const x = S.sources[+row.dataset.i];
    row.querySelectorAll("[data-f]").forEach(el => el.addEventListener(el.tagName === "SELECT" ? "change" : "input", () => {
      x[el.dataset.f] = el.value.trim(); save();
      if (el.tagName === "SELECT") renderSources();
    }));
    row.querySelector("[data-del]").onclick = () => { S.sources.splice(+row.dataset.i, 1); renderSources(); save(); };
  });
}

function renderThemes() {
  $("#themes").innerHTML = Object.entries(THEMES).map(([name, t]) => `
    <div class="theme ${S.theme === name ? "on" : ""}" data-theme="${name}" style="background:${t.bg};color:${t.text}">
      <div><div class="clock">7:30</div><div class="name">${name}</div></div>
      <div class="tiles"><i style="background:${S.accent || t.accent}"></i><i style="background:${t.tile}"></i><i style="background:${t.tile}"></i></div>
    </div>`).join("");
  $$(".theme").forEach(el => el.onclick = () => { S.theme = el.dataset.theme; renderThemes(); save(); });
}
function renderLocation() {
  $("#loc-current").textContent = S.latitude != null
    ? `Weather for ${S.location || "selected location"}${S.timezone ? " · time zone " + S.timezone : ""}`
    : "No weather location set (the weather widget is hidden).";
}
async function searchLocation() {
  const q = $("#loc-q").value.trim(); if (!q) return;
  const out = $("#loc-results"); out.innerHTML = `<span class="muted">Searching…</span>`;
  try {
    const list = await api("/api/geocode?q=" + encodeURIComponent(q));
    out.innerHTML = list.map((p, i) => `<span class="chip" data-i="${i}">${esc(p.name)}</span>`).join("");
    $$(".chip", out).forEach(c => c.onclick = () => {
      const p = list[c.dataset.i];
      Object.assign(S, { location: p.name.replace(/ \(\w+\)$/, ""), latitude: p.latitude, longitude: p.longitude, timezone: p.timezone || S.timezone });
      out.innerHTML = ""; renderLocation(); save();
    });
  } catch (e) { out.innerHTML = `<span class="muted">${esc(e.message)}</span>`; }
}

// ---------- Step 3: sports ----------
const teamCache = {};
function renderLeagues() {
  $("#leagues").innerHTML = Object.entries(LEAGUES).map(([id, n]) =>
    `<span class="chip ${S.leagues?.includes(id) ? "on" : ""}" data-l="${id}">${n}</span>`).join("");
  $$("#leagues .chip").forEach(c => c.onclick = () => {
    const l = c.dataset.l; S.leagues = S.leagues || [];
    S.leagues = S.leagues.includes(l) ? S.leagues.filter(x => x !== l) : [...S.leagues, l];
    renderLeagues(); save();
  });
  const sel = $("#team-league"), cur = sel.value;
  sel.innerHTML = Object.entries(LEAGUES).map(([id, n]) => `<option value="${id}">${n}</option>`).join("");
  sel.value = cur || (S.leagues && S.leagues[0]) || "nfl";
}
async function renderTeams() {
  const l = $("#team-league").value, f = $("#team-filter").value.toLowerCase();
  const grid = $("#teams");
  if (!teamCache[l]) {
    grid.innerHTML = `<span class="muted">Loading teams…</span>`;
    try { teamCache[l] = await api("/api/teams?league=" + l); } catch (e) { grid.innerHTML = `<span class="muted">${esc(e.message)}</span>`; return; }
  }
  const picked = new Set(S.teams || []);
  grid.innerHTML = teamCache[l].filter(t => !f || t.name.toLowerCase().includes(f) || t.abbr.toLowerCase() === f)
    .map(t => `<div class="team ${picked.has(t.abbr) ? "on" : ""}" data-a="${esc(t.abbr)}" data-logo="${esc(t.logo)}" data-name="${esc(t.name)}">
      <img src="${esc(t.logo)}" loading="lazy" alt="">${esc(t.name)}</div>`).join("");
  $$(".team", grid).forEach(el => el.onclick = () => {
    const a = el.dataset.a; S.teams = S.teams || []; S.teamInfo = S.teamInfo || {};
    if (S.teams.includes(a)) S.teams = S.teams.filter(x => x !== a);
    else { S.teams.push(a); S.teamInfo[a] = { name: el.dataset.name, logo: el.dataset.logo }; }
    el.classList.toggle("on"); renderPicked(); save();
  });
}
function renderPicked() {
  const info = S.teamInfo || {};
  $("#team-picked").innerHTML = (S.teams || []).length
    ? S.teams.map(a => `<span class="chip on" data-a="${esc(a)}">${info[a]?.logo ? `<img src="${esc(info[a].logo)}">` : ""}${esc(info[a]?.name || a)} ✕</span>`).join("")
    : `<span class="muted">None yet. Pick from the list below.</span>`;
  $$("#team-picked .chip").forEach(c => c.onclick = () => { S.teams = S.teams.filter(x => x !== c.dataset.a); renderPicked(); renderTeams(); renderProfiles();
  renderSources();
  $("#source-add").onclick = () => { S.sources = [...(S.sources || []), { name: "", type: "xtream", url: "" }]; renderSources(); save(); };
  $("#profile-add").onclick = () => { S.profiles.push({ name: "", sports: false, groups: [], keywords: [] }); renderProfiles(); save(); $("#profiles .profile:last-child input").focus(); };
  $("#profile-reset").onclick = () => { S.profiles = DEFAULT_PROFILES(); renderProfiles(); save(); }; save(); });
}

// ---------- Step 4: box ----------
async function checkAdb() {
  try {
    const r = await api("/api/adb");
    $("#adb-status").textContent = (r.path ? "adb ready" : "adb will be downloaded on first connect") + (r.apks ? "" : " · ⚠ no APKs bundled in this build");
  } catch {}
  renderRecent();
}
function renderRecent() {
  $("#recent").innerHTML = (S.boxes || []).map(ip => `<span class="chip" data-ip="${esc(ip)}">${esc(ip)}</span>`).join("");
  $$("#recent .chip").forEach(c => c.onclick = () => { $("#box-ip").value = c.dataset.ip; inspect(); });
}
let busy = false;
function setBusy(b) { busy = b; $$("#box button").forEach(x => x.disabled = b); }
async function inspect() {
  const ip = $("#box-ip").value.trim(); if (!ip || busy) return;
  setBusy(true);
  $("#box-info").classList.add("hidden");
  const log = $("#log"); log.classList.remove("hidden");
  log.textContent = `Connecting to ${ip}… if the TV asks "Allow USB debugging?", tick Always allow and press OK.\n`;
  try {
    const b = await api("/api/box/inspect", { ip });
    S.boxes = [ip, ...(S.boxes || []).filter(x => x !== ip)].slice(0, 8); save(); renderRecent();
    log.classList.add("hidden");
    $("#box-card").innerHTML = `<div class="kv">
      <span>Box</span><span><b>${esc(b.maker)} ${esc(b.model)}</b></span>
      <span>Android</span><span>${esc(b.family)} · Android ${esc(b.android)} (SDK ${esc(b.sdk)})</span>
      <span>Serial</span><span>${esc(b.serial)}</span>
      <span>Status</span><span>${b.setupDone ? "Setup complete" : "⚠ Finish the on-screen setup first"}${b.launcher ? " · Vanilla Home installed" : ""}${b.livetvInstalled ? " · Live TV installed" : ""}</span>
      <span>Your apps</span><span class="muted">${b.userApps.map(esc).join(", ") || "none"}</span></div>`;
    $("#removable").innerHTML = b.removable.length
      ? b.removable.map(p => `<label><input type="checkbox" value="${esc(p.package)}" ${b.known ? "checked" : ""}> ${esc(p.label)} <small>${esc(p.package)}</small></label>`).join("")
      : `<span class="muted">Nothing from the removal list is installed.</span>`;
    const mine = b.userApps.filter(p => !p.startsWith("tv.vanilla.") && !b.removable.some(r => r.package === p));
    if (mine.length) $("#removable").innerHTML += `<div class="muted" style="margin-top:10px">Apps you installed (tick to remove):</div>` +
      mine.map(p => `<label><input type="checkbox" value="${esc(p)}"> ${esc(p)}</label>`).join("");
    if (!b.known) $("#removable").insertAdjacentHTML("afterbegin", `<p class="muted">This device hasn't been tested yet, so nothing is selected. Vanilla Home and Live TV still install, and its own home screen is never removed. Tick only apps you know you don't need.</p>`);
    $("#restore").classList.toggle("hidden", !b.hasBackup);
    $("#box-info").classList.remove("hidden");
  } catch (e) { log.textContent += "\nERROR: " + e.message; }
  setBusy(false);
}
async function run(path, body) {
  if (busy) return;
  setBusy(true);
  const log = $("#log"); log.classList.remove("hidden"); log.textContent = "";
  try {
    await api("/api/setup", S);
    const r = await fetch(path, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const rd = r.body.getReader(), dec = new TextDecoder();
    for (;;) {
      const { value, done } = await rd.read(); if (done) break;
      log.textContent += dec.decode(value, { stream: true }); log.scrollTop = log.scrollHeight;
    }
  } catch (e) { log.textContent += "\nERROR: " + e.message; }
  setBusy(false);
}
function provisionBody(extra) {
  return {
    ip: $("#box-ip").value.trim(),
    remove: $$("#removable input:checked").map(i => i.value),
    removePlayStore: $("#rm-store").checked,
    reboot: $("#reboot").checked, ...extra,
  };
}

function esc(s) { return String(s ?? "").replace(/[&<>"']/g, c => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]); }

async function init() {
  S = await api("/api/setup");
  history.replaceState(null, "", "/");
  bindFields(); renderThemes(); renderLocation(); renderLeagues(); renderPicked(); renderTeams();
  $$("#nav a").forEach(a => a.onclick = () => go(a.dataset.step));
  $$("[data-go]").forEach(b => b.onclick = () => go(b.dataset.go));
  $("#test").onclick = testPlaylist;
  $("#loc-search").onclick = searchLocation;
  $("#loc-q").onkeydown = e => { if (e.key === "Enter") searchLocation(); };
  $("#loc-clear").onclick = () => { S.latitude = S.longitude = null; S.location = ""; renderLocation(); save(); };
  $("#team-league").onchange = renderTeams;
  $("#team-filter").oninput = renderTeams;
  $("#inspect").onclick = inspect;
  fetch("/api/lineup/groups").then(r => r.json()).then(names => {
    $("#dc-cats").innerHTML = names.map(n => `<label><input type="checkbox" value="${esc(n)}" ${!S.lineupCats || !S.lineupCats.length || S.lineupCats.includes(n) ? "checked" : ""}> ${esc(n)}</label>`).join("");
  });
  $$("#dc-extra input").forEach(i => i.checked = (S.lineupExtra || []).includes(i.value));
  $("#dc-hide-empty").checked = !!S.lineupHideEmpty;
  const lineupBody = () => {
    S.lineupCats = $$("#dc-cats input:checked").map(i => i.value);
    S.lineupExtra = $$("#dc-extra input:checked").map(i => i.value);
    S.lineupHideEmpty = $("#dc-hide-empty").checked;
    S.lineupAdd = S.lineupAdd || [];
    save();
    return { user: $("#dc-user").value, pass: $("#dc-pass").value, prefix: $("#dc-prefix").value, locals: $("#dc-locals").value.trim(),
      cats: S.lineupCats, extra: S.lineupExtra, add: S.lineupAdd, hideEmpty: S.lineupHideEmpty };
  };
  const filterHidden = () => {
    const q = $("#dc-hidden-filter").value.trim().toLowerCase();
    $$("#dc-hidden-list label").forEach(l => l.classList.toggle("hidden", !!q && !l.textContent.toLowerCase().includes(q)));
  };
  $("#dc-hidden-filter").oninput = filterHidden;
  $("#dc-hidden").onclick = async () => {
    const st = $("#dc-hidden-status"); st.textContent = "Reading your providers…";
    try {
      const rows = await api("/api/lineup/hidden", lineupBody());
      const add = new Set(S.lineupAdd);
      rows.sort((a, b) => add.has(b.name) - add.has(a.name));
      $("#dc-hidden-list").innerHTML = rows.map(h => `<label><input type="checkbox" value="${esc(h.name)}" ${add.has(h.name) ? "checked" : ""}> ${esc(h.name)} <small>${h.streams} · ${esc(h.reason)}</small></label>`).join("");
      $$("#dc-hidden-list input").forEach(i => i.onchange = () => {
        S.lineupAdd = (S.lineupAdd || []).filter(n => n !== i.value).concat(i.checked ? [i.value] : []); save();
        st.textContent = `${S.lineupAdd.length} ticked`;
      });
      st.textContent = `${rows.length} groups, ${rows.reduce((n, h) => n + h.streams, 0)} streams left out` + (S.lineupAdd.length ? `, ${S.lineupAdd.length} ticked` : "");
      filterHidden();
    } catch (e) { st.textContent = "✗ " + e.message; }
  };
  $("#vod-status").textContent = S.xcUser ? "✓ On (sent to the box with your settings)" : "";
  $("#vod-enable").onclick = async () => {
    const st = $("#vod-status"); st.textContent = "Setting up…";
    try {
      await api("/api/vod/enable", { user: $("#dc-user").value, pass: $("#dc-pass").value });
      S = await api("/api/setup");
      st.textContent = "✓ On. Click \"Only send settings\" on the box step to update your TVs.";
    } catch (e) { st.textContent = "✗ " + e.message; }
  };
  $("#dc-preview").onclick = async () => {
    const st = $("#dc-status"), out = $("#dc-result"); st.textContent = "Reading your providers… (can take a minute)"; out.classList.add("hidden");
    try {
      await api("/api/setup", S);
      const r = await api("/api/lineup/preview", lineupBody());
      st.textContent = `${r.total} channels`;
      const lim = (r.limits || []).map(l => `<li>${esc(l.name)}: ${l.provider ? l.provider + " streams" : "limit unknown"}${l.provider && l.provider !== l.current ? ` <span class="muted">(Dispatcharr has ${l.current}; Build fixes it)</span>` : ""}</li>`).join("");
      out.innerHTML = (lim ? `<h4>Stream limits</h4><ul>${lim}</ul>` : "") + r.groups.map(g => `<h4>${esc(g.name)} — ${r.counts[g.name]} channels</h4>
        <div class="muted small">From: ${g.sources.map(esc).join(", ") || "no matching groups"}</div>
        <div class="chips">${g.channels.map(c => `<span class="chip">${esc(c.name)}${c.streams > 1 ? ` <small>×${c.streams}</small>` : ""}</span>`).join("")}${r.counts[g.name] > g.channels.length ? `<span class="muted">…and ${r.counts[g.name] - g.channels.length} more</span>` : ""}</div>`).join("");
      out.classList.remove("hidden");
    } catch (e) { st.textContent = "✗ " + e.message; }
  };
  $("#dc-apply").onclick = async () => {
    if (!confirm("Build the channel list in Dispatcharr? Channels in these groups are replaced; nothing else is touched.")) return;
    const log = $("#dc-log"); log.classList.remove("hidden"); log.textContent = "";
    await api("/api/setup", S);
    const r = await fetch("/api/lineup/apply", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(lineupBody()) });
    const rd = r.body.getReader(), dec = new TextDecoder();
    for (;;) { const { value, done } = await rd.read(); if (done) break; log.textContent += dec.decode(value, { stream: true }); log.scrollTop = log.scrollHeight; }
  };
  $$("#adb-mode .tab").forEach(t => t.onclick = () => {
    $$("#adb-mode .tab").forEach(x => x.classList.toggle("active", x === t));
    $$("[data-pane]").forEach(p => p.classList.toggle("hidden", p.dataset.pane !== t.dataset.mode));
  });
  $("#pair").onclick = async () => {
    const st = $("#pair-status"); st.textContent = "Pairing…";
    try {
      await api("/api/box/pair", { pair: $("#pair-addr").value, code: $("#pair-code").value });
      st.textContent = "✓ Paired. Now enter the IP:port from the Wireless debugging screen and click Connect.";
      const host = $("#pair-addr").value.split(":")[0];
      if (!$("#box-ip").value) $("#box-ip").value = host + ":";
      $("#box-ip").focus();
    } catch (e) { st.textContent = "✗ " + e.message; }
  };
  $("#box-ip").onkeydown = e => { if (e.key === "Enter") inspect(); };
  $("#sel-all").onclick = e => { e.preventDefault(); $$("#removable input").forEach(i => i.checked = true); };
  $("#sel-none").onclick = e => { e.preventDefault(); $$("#removable input").forEach(i => i.checked = false); };
  $("#dry").onclick = () => run("/api/box/provision", provisionBody({ dryRun: true }));
  $("#go").onclick = () => confirm("Install the apps, remove the selected apps and set Vanilla Home as the home screen?") && run("/api/box/provision", provisionBody({}));
  $("#push").onclick = () => run("/api/box/provision", provisionBody({ configOnly: true }));
  $("#export").onclick = () => {
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([JSON.stringify({ onnVanilla: 1, setup: { ...S, xcUser: undefined, xcPass: undefined, haToken: undefined, sources: (S.sources || []).map(s => ({ ...s, password: undefined })) } }, null, 2)], { type: "application/json" }));
    a.download = "onn-vanilla-settings.json";
    a.click();
    URL.revokeObjectURL(a.href);
  };
  $("#import").onclick = () => $("#import-file").click();
  $("#import-file").onchange = async e => {
    const f = e.target.files[0];
    if (!f) return;
    try {
      const j = JSON.parse(await f.text());
      const s = j.setup || j;
      if (typeof s !== "object" || Array.isArray(s)) throw new Error("not a settings file");
      if (!confirm("Replace your current settings with the ones in " + f.name + "?")) return;
      await api("/api/setup", { ...S, ...s });
      location.reload();
    } catch (err) { alert("Couldn't import: " + err.message); }
  };
  $("#restore").onclick = () => confirm("Put back every app that was on this box before it was first set up?") && run("/api/box/restore", { ip: $("#box-ip").value.trim() });
}
init();
