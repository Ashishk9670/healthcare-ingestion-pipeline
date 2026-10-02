import { request, expect } from '@playwright/test';
import { APP, apiToken, gql } from './helpers';

/** Seeds SYNTHETIC patients through the real pipeline and waits until they are queryable. */
export default async function globalSetup() {
  const ctx = await request.newContext();
  const ingestor = await apiToken(ctx, 'ingestor1');
  const res = await ctx.post(`${APP}/api/v1/synthetic?count=30`, { headers: { Authorization: `Bearer ${ingestor}` } });
  expect(res.status()).toBe(202);

  const clinician = await apiToken(ctx, 'clinician1');
  await expect
    .poll(async () => {
      const r = await gql(ctx, clinician, '{ cohort(limit:0){ totalPatients } }');
      return r.body?.data?.cohort?.totalPatients ?? 0;
    }, { timeout: 90_000, intervals: [1000] })
    .toBeGreaterThan(0);
  await ctx.dispose();
}
