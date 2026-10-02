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

  const fill = (el, rows, head) => {
    el.innerHTML = `<tr>${head.map((h) => `<th>${h}</th>`).join("")}</tr>` +
      rows.map((r) => `<tr>${r.map((c) => `<td>${c ?? ""}</td>`).join("")}</tr>`).join("");
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
      fill($("t-age"), d.cohort.byAgeBand.map((b) => [b.key, b.count]), ["Age band", "Patients"]);
      fill($("t-gender"), d.cohort.byGender.map((b) => [b.key, b.count]), ["Gender", "Patients"]);
      fill($("t-dx"), d.cohort.byDiagnosis.map((b) => [b.key, b.count]), ["Diagnosis", "Patients"]);
    } catch (err) { showError(err.message); }
  }

  async function findPatient(e) {
    e.preventDefault();
    showError("");
    try {
      const d = await gql(`query($m:String!){ patient(mrn:$m){ mrn familyName givenName birthDate gender ageBand
        diagnoses{code display} observations{code display valueNumeric unit} } }`, { m: $("p-mrn").value });
      $("patient-out").textContent = d.patient ? JSON.stringify(d.patient, null, 2) : "Not found";
    } catch (err) { showError(err.message); }
  }

  async function loadAudit() {
    showError("");
    try {
      const d = await gql(`{ auditLog(limit:25){ timestamp actor action resourceType detail } }`);
      fill($("t-audit"), d.auditLog.map((a) => [a.timestamp, a.actor, a.action, a.resourceType, a.detail]),
        ["Time", "Actor", "Action", "Resource", "Detail"]);
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
    if (!authed) { $("user").textContent = ""; return; }
    const c = claims();
    const r = roles();
    $("user").textContent = `${c.preferred_username} [${r.filter((x) => ["INGESTOR", "CLINICIAN", "ANALYST", "AUDITOR"].includes(x)).join(", ")}]`;
    document.querySelectorAll("[data-role]").forEach((el) => {
      el.hidden = !el.dataset.role.split(",").some((x) => r.includes(x));
    });
  }

  document.addEventListener("DOMContentLoaded", async () => {
    $("login").addEventListener("click", login);
    $("logout").addEventListener("click", logout);
    $("cohort-form").addEventListener("submit", runCohort);
    $("patient-form").addEventListener("submit", findPatient);
    $("audit-go").addEventListener("click", loadAudit);
    $("syn-go").addEventListener("click", synthetic);
    await handleCallback();
    render();
    if (token() && roles().some((x) => x === "CLINICIAN" || x === "ANALYST")) runCohort();
  });
})();
