import { test, expect } from '@playwright/test';
import * as path from 'path';
import { loginAs } from './helpers';

// Captures README screenshots of the dashboard. SYNTHETIC DATA ONLY (seeded by global-setup). Opt-in:
//   CAPTURE_SCREENSHOTS=1 npx playwright test screenshots.spec.ts
// Only aggregate cohort views and the audit log are captured; no patient detail view and no credentials.
const OUT = path.resolve(__dirname, '..', '..', 'docs', 'img');

test.describe('README screenshots', () => {
  test.skip(!process.env.CAPTURE_SCREENSHOTS, 'opt-in via CAPTURE_SCREENSHOTS=1');
  test.use({ viewport: { width: 1280, height: 860 } });

  test('clinician cohort dashboard', async ({ page }) => {
    await loginAs(page, 'clinician1');
    await expect(page.getByTestId('total')).not.toHaveText('-');
    await page.screenshot({ path: path.join(OUT, 'dashboard-clinician.png'), fullPage: true });
  });

  test('analyst cohort aggregates (no patient lookup)', async ({ page }) => {
    await loginAs(page, 'analyst1');
    await expect(page.getByTestId('total')).not.toHaveText('-');
    await page.screenshot({ path: path.join(OUT, 'dashboard-analyst.png'), fullPage: true });
  });

  test('auditor audit log', async ({ page }) => {
    await loginAs(page, 'auditor1');
    await page.getByTestId('audit-go').click();
    await expect(page.getByTestId('t-audit')).toContainText('COHORT_QUERY');
    await page.screenshot({ path: path.join(OUT, 'dashboard-auditor.png'), fullPage: true });
  });
});
