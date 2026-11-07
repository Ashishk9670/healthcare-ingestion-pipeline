// Vanilla OIDC Authorization Code + PKCE against Keycloak, then GraphQL calls with the bearer token.
(() => {
  const cfg = window.HDIP_CONFIG;
  const base = `${cfg.keycloakUrl}/realms/${cfg.realm}/protocol/openid-connect`;
  const redirectUri = window.location.origin + "/";
  const $ = (id) => document.getElementById(id);
  const store = window.sessionStorage;

  const b64url = (buf) => btoa(String.fromCharCode(...new Uint8Array(buf))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  const rand = () => b64url(crypto.getRandomValues(new Uint8Array(32)));

  async function login() {
    const verifier = rand(), state = rand();
    store.setItem("pkce_verifier", verifier);
    store.setItem("oidc_state", state);
    const challenge = b64url(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier)));
    const p = new URLSearchParams({
      client_id: cfg.clientId, response_type: "code", scope: "openid", redirect_uri: redirectUri,
      code_challenge: challenge, code_challenge_method: "S256", state,
    });
    window.location.assign(`${base}/auth?${p}`);
  }

  async function handleCallback() {
    const q = new URLSearchParams(window.location.search);
    if (!q.has("code")) return;
    if (q.get("state") !== store.getItem("oidc_state")) { showError("Invalid OIDC state"); return; }
    const body = new URLSearchParams({
      grant_type: "authorization_code", client_id: cfg.clientId, code: q.get("code"),
      redirect_uri: redirectUri, code_verifier: store.getItem("pkce_verifier"),
    });
    const res = await fetch(`${base}/token`, { method: "POST", body });
    if (!res.ok) { showError("Token exchange failed"); return; }
    const tok = await res.json();
    store.setItem("access_token", tok.access_token);
    if (tok.id_token) store.setItem("id_token", tok.id_token);
    history.replaceState({}, "", "/");
  }

  function logout() {
    const idToken = store.getItem("id_token");
    store.clear();
    // id_token_hint lets Keycloak skip its "Do you want to log out?" confirmation page
    const hint = idToken ? `&id_token_hint=${encodeURIComponent(idToken)}` : "";
    window.location.assign(`${base}/logout?client_id=${cfg.clientId}&post_logout_redirect_uri=${encodeURIComponent(redirectUri)}${hint}`);
  }

  const token = () => store.getItem("access_token");
  function claims() {
    try { return JSON.parse(atob(token().split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))); } catch { return null; }
  }
  const roles = () => ((claims() || {}).realm_access || {}).roles || [];

  function showError(msg) { $("error").textContent = msg || ""; }

  async function gql(query, variables) {
    const res = await fetch("/graphql", {
      method: "POST",
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token()}` },
      body: JSON.stringify({ query, variables }),
    });
    if (res.status === 401 || res.status === 403) throw new Error(`HTTP ${res.status}`);
    const json = await res.json();
    if (json.errors && json.errors.length) throw new Error(json.errors[0].message);
    return json.data;
  }

  // Everything rendered with innerHTML goes through esc(): audit details, actors and keys come from the server / token.
  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  const fill = (el, rows, head) => {
    el.innerHTML = `<tr>${head.map((h) => `<th>${esc(h)}</th>`).join("")}</tr>` +
      rows.map((r) => `<tr>${r.map((c) => `<td>${esc(c)}</td>`).join("")}</tr>`).join("");
  };

  // Two-column breakdown with an inline bar scaled to the largest value.
  const fillBars = (el, items, head) => {
    if (!items.length) { el.innerHTML = `<tr><th>${esc(head)}</th><th class="num">Patients</th></tr><tr><td colspan="2" class="empty">No matching patients</td></tr>`; return; }
    const max = Math.max(...items.map((i) => i.count), 1);
    el.innerHTML = `<tr><th>${esc(head)}</th><th class="num">Patients</th><th></th></tr>` +
      items.map((i) => `<tr><td>${esc(i.key)}</td><td class="num">${esc(i.count)}</td>` +
        `<td class="barcell"><div class="bar"><i style="width:${Math.round((i.count / max) * 100)}%"></i></div></td></tr>`).join("");
  };

  const busy = async (btn, work) => {
    if (!btn) return work();
    const label = btn.innerHTML;
    btn.disabled = true;
    btn.innerHTML = `<span class="spinner"></span>${label}`;
    try { return await work(); } finally { btn.disabled = false; btn.innerHTML = label; }
  };

  async function runCohort(e) {
    e && e.preventDefault();
    showError("");
    const filter = {};
    if ($("f-dx").value) filter.diagnosisCode = $("f-dx").value;
    if ($("f-age").value) filter.ageBand = $("f-age").value;
    if ($("f-gender").value) filter.gender = $("f-gender").value;
    try {
      const d = await gql(`query($f: CohortFilter){ cohort(filter:$f, limit:0){ totalPatients
        byAgeBand{key count} byGender{key count} byDiagnosis{key count} } }`, { f: filter });
      $("total").textContent = d.cohort.totalPatients;
      fillBars($("t-age"), d.cohort.byAgeBand, "Age band");
      fillBars($("t-gender"), d.cohort.byGender, "Gender");
      fillBars($("t-dx"), d.cohort.byDiagnosis, "Diagnosis");
      const top = (items) => (items.length ? [...items].sort((a, b) => b.count - a.count)[0] : null);
      const a = top(d.cohort.byAgeBand), x = top(d.cohort.byDiagnosis);
      $("kpi-age").textContent = a ? `${a.key} (${a.count})` : "-";
      $("kpi-dx").textContent = x ? `${x.key} (${x.count})` : "-";
    } catch (err) { showError(err.message); }
  }

  async function findPatient(e) {
    e.preventDefault();
    showError("");
    try {
      const d = await gql(`query($m:String!){ patient(mrn:$m){ mrn familyName givenName birthDate gender ageBand
        diagnoses{code display} observations{code display valueNumeric unit} } }`, { m: $("p-mrn").value });
      $("patient-out").textContent = d.patient ? JSON.stringify(d.patient, null, 2) : "Not found";
      renderPatient(d.patient);
    } catch (err) { showError(err.message); }
  }

  function renderPatient(p) {
    const el = $("patient-card");
    if (!p) { el.innerHTML = `<div class="notfound">No patient found for that MRN.</div>`; return; }
    const initials = ((p.givenName || "?")[0] + (p.familyName || "?")[0]);
    const dx = (p.diagnoses || []).map((x) => `<span class="chip">${esc(x.code)} ${esc(x.display)}</span>`).join("") || `<span class="chip neutral">none recorded</span>`;
    const obs = (p.observations || []).map((o) => `<span class="chip neutral">${esc(o.display || o.code)}: ${esc(o.valueNumeric)} ${esc(o.unit)}</span>`).join("") || `<span class="chip neutral">none recorded</span>`;
    el.innerHTML = `<div class="patient">
      <div class="head"><div class="avatar">${esc(initials)}</div>
        <div><div class="who">${esc(p.givenName)} ${esc(p.familyName)}</div>
        <div class="sub">${esc(p.mrn)} &middot; ${esc(p.gender)} &middot; born ${esc(p.birthDate)} &middot; age band ${esc(p.ageBand)}</div></div></div>
      <div><div class="sub">Diagnoses</div><div class="chips">${dx}</div></div>
      <div><div class="sub">Observations</div><div class="chips">${obs}</div></div></div>`;
  }

  async function loadAudit() {
    showError("");
    try {
      const d = await gql(`{ auditLog(limit:25){ timestamp actor action resourceType detail } }`);
      const head = ["Time", "Actor", "Action", "Resource", "Detail"];
      $("t-audit").innerHTML = `<tr>${head.map((h) => `<th>${h}</th>`).join("")}</tr>` + d.auditLog.map((a) =>
        `<tr><td class="mono">${esc(String(a.timestamp).replace("T", " ").replace(/\.\d+Z?$/, ""))}</td><td>${esc(a.actor)}</td>` +
        `<td><span class="pill ${esc(a.action)}">${esc(a.action)}</span></td><td>${esc(a.resourceType)}</td><td class="mono">${esc(a.detail)}</td></tr>`).join("");
    } catch (err) { showError(err.message); }
  }

  async function synthetic() {
    showError("");
    const res = await fetch(`/api/v1/synthetic?count=${encodeURIComponent($("syn-count").value)}`, {
      method: "POST", headers: { Authorization: `Bearer ${token()}` },
    });
    $("syn-status").textContent = res.ok ? `Submitted ${(await res.json()).submitted} synthetic patients` : `Failed: HTTP ${res.status}`;
  }

  function render() {
    const authed = !!token() && !!claims();
    $("login").hidden = authed;
    $("logout").hidden = !authed;
    $("app").hidden = !authed;
    $("hero").hidden = authed;
    if (!authed) { $("user").textContent = ""; return; }
    const c = claims();
    const r = roles();
    const mine = r.filter((x) => ["INGESTOR", "CLINICIAN", "ANALYST", "AUDITOR"].includes(x));
    $("user").innerHTML = `<span class="userchip"><span class="avatar">${esc((c.preferred_username || "?")[0])}</span>` +
      `<span class="name">${esc(c.preferred_username)}</span>${mine.map((x) => `<span class="rolepill">${esc(x)}</span>`).join("")}</span>`;
    document.querySelectorAll("[data-role]").forEach((el) => {
      el.hidden = !el.dataset.role.split(",").some((x) => r.includes(x));
    });
  }

  document.addEventListener("DOMContentLoaded", async () => {
    $("login").addEventListener("click", login);
    $("hero-login").addEventListener("click", login);
    $("logout").addEventListener("click", logout);
    $("cohort-form").addEventListener("submit", (e) => busy(e.submitter, () => runCohort(e)));
    $("patient-form").addEventListener("submit", findPatient);
    $("audit-go").addEventListener("click", (e) => busy(e.currentTarget, loadAudit));
    $("syn-go").addEventListener("click", (e) => busy(e.currentTarget, synthetic));
    await handleCallback();
    render();
    if (token() && roles().some((x) => x === "CLINICIAN" || x === "ANALYST")) runCohort();
  });
})();
