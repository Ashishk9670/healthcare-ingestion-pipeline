import { test, expect } from '@playwright/test';
import { apiToken, gql, loginAs } from './helpers';

test.describe('cohort dashboard (synthetic data)', () => {
  test('anonymous visitor sees only the sign-in button', async ({ page }) => {
    await page.goto('/');
    await expect(page.getByTestId('login')).toBeVisible();
    await expect(page.locator('#app')).toBeHidden();
  });

  test('anonymous GraphQL call is rejected', async ({ request }) => {
    const res = await request.post('/graphql', { data: { query: '{ cohort{ totalPatients } }' } });
    expect(res.status()).toBe(401);
  });

  test('clinician logs in, sees cohort + patient lookup, and filters the cohort', async ({ page }) => {
    await loginAs(page, 'clinician1');
    await expect(page.locator('#cohort-panel')).toBeVisible();
    await expect(page.locator('#patient-panel')).toBeVisible();
    await expect(page.locator('#audit-panel')).toBeHidden();
    await expect(page.locator('#ingest-panel')).toBeHidden();

    await expect(page.getByTestId('total')).not.toHaveText('-');
    const all = Number(await page.getByTestId('total').innerText());
    expect(all).toBeGreaterThan(0);

    await page.locator('#f-gender').selectOption('female');
    await page.getByTestId('cohort-run').click();
    await expect.poll(async () => Number(await page.getByTestId('total').innerText())).toBeLessThanOrEqual(all);
    await expect(page.getByTestId('t-age')).toContainText('Age band');
  });

  test('clinician can look up an unknown MRN without error', async ({ page }) => {
    await loginAs(page, 'clinician1');
    await page.locator('#p-mrn').fill('SYN-000000');
    await page.getByTestId('patient-run').click();
    await expect(page.getByTestId('patient-out')).toHaveText('Not found');
  });

  test('analyst sees cohort aggregates but no patient lookup', async ({ page }) => {
    await loginAs(page, 'analyst1');
    await expect(page.locator('#cohort-panel')).toBeVisible();
    await expect(page.locator('#patient-panel')).toBeHidden();
    await expect(page.locator('#audit-panel')).toBeHidden();
    await expect(page.getByTestId('total')).not.toHaveText('-');
  });

  test('auditor sees the audit log including earlier cohort/patient access', async ({ page, request }) => {
    const clinician = await apiToken(request, 'clinician1');
    await gql(request, clinician, '{ cohort(filter:{diagnosisCode:"E11"}){ totalPatients } }');

    await loginAs(page, 'auditor1');
    await expect(page.locator('#audit-panel')).toBeVisible();
    await expect(page.locator('#cohort-panel')).toBeHidden();
    await page.getByTestId('audit-go').click();
    await expect(page.getByTestId('t-audit')).toContainText('COHORT_QUERY');
    await expect(page.getByTestId('t-audit')).toContainText('clinician1');
  });

  test('ingestor can trigger synthetic ingestion but sees no data panels', async ({ page }) => {
    await loginAs(page, 'ingestor1');
    await expect(page.locator('#ingest-panel')).toBeVisible();
    await expect(page.locator('#cohort-panel')).toBeHidden();
    await page.locator('#syn-count').fill('3');
    await page.getByTestId('syn-go').click();
    await expect(page.getByTestId('syn-status')).toContainText('Submitted 3 synthetic patients');
  });

  test('sign out returns to the anonymous view', async ({ page }) => {
    await loginAs(page, 'analyst1');
    await page.getByTestId('logout').click();
    await expect(page.getByTestId('login')).toBeVisible();
  });
});
