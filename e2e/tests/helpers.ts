import { APIRequestContext, Page, expect } from '@playwright/test';

export const KEYCLOAK = process.env.KEYCLOAK_URL ?? 'http://localhost:8180';
export const APP = process.env.APP_URL ?? 'http://localhost:8080';

/** Dev users from docker/keycloak/healthcare-realm.json (synthetic, local only); password = "<user>-dev". */
export async function apiToken(request: APIRequestContext, user: string): Promise<string> {
  const res = await request.post(`${KEYCLOAK}/realms/healthcare/protocol/openid-connect/token`, {
    form: { grant_type: 'password', client_id: 'pipeline-client', username: user, password: `${user}-dev` },
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
  await page.locator('#password').fill(`${user}-dev`);
  await page.locator('#kc-login').click();
  await expect(page.getByTestId('user')).toContainText(user);
}
