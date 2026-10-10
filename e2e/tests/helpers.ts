import { APIRequestContext, Page, expect } from '@playwright/test';

export const KEYCLOAK = process.env.KEYCLOAK_URL ?? 'http://localhost:8180';
export const APP = process.env.APP_URL ?? 'http://localhost:8080';

function pw(user: string): string {
  const v = process.env[`HDIP_PW_${user.toUpperCase()}`];
  if (!v) throw new Error(`HDIP_PW_${user.toUpperCase()} is not set; run scripts/gen-dev-env.sh and source .env`);
  return v;
}

/** Dev users from docker/keycloak/healthcare-realm.json (synthetic, local only); password from env HDIP_PW_<USER> (see scripts/gen-dev-env.sh). */
export async function apiToken(request: APIRequestContext, user: string): Promise<string> {
  const res = await request.post(`${KEYCLOAK}/realms/healthcare/protocol/openid-connect/token`, {
    form: { grant_type: 'password', client_id: 'pipeline-client', username: user, password: pw(user) },
  });
  expect(res.ok(), `token for ${user}`).toBeTruthy();
  return (await res.json()).access_token;
}

export async function gql(request: APIRequestContext, token: string, query: string) {
  const res = await request.post(`${APP}/graphql`, {
    headers: { Authorization: `Bearer ${token}` },
    data: { query },
  });
  return { status: res.status(), body: await res.json().catch(() => ({})) };
}

/** Full browser login through the Keycloak login page (authorization code + PKCE). */
export async function loginAs(page: Page, user: string) {
  await page.goto('/');
  await page.getByTestId('login').click();
  await page.locator('#username').fill(user);
  await page.locator('#password').fill(pw(user));
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('user')).toContainText(user);
}
