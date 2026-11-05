import { test, expect } from '@playwright/test';

test('disabled security enters management without login or logout controls', async ({ page }) => {
  // 对接实际读取 security.enable=false 配置的控制台，不模拟会话接口。
  await page.goto('http://127.0.0.1:18766/');
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '连接到注册中心' })).toBeVisible();
  await expect(page.getByRole('heading', { name: '登录控制台' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '退出', exact: true })).toHaveCount(0);

  // 未生成登录 Cookie，刷新仍直接进入管理页。
  const cookies = await page.context().cookies();
  expect(cookies.some((cookie) => cookie.name === 'meteor_session')).toBe(false);
  await page.reload();
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible();
  await page.locator('.tabbar').getByRole('button', { name: '连接注册中心' }).click();
  await expect(page.getByLabel('连接名称')).toBeVisible();
});
