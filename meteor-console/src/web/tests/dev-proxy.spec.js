import { test, expect } from '@playwright/test';
import { createServer } from 'vite';

test('Vite proxy preserves the browser origin for login and logout', async ({ page, baseURL }) => {
  // 使用正式 Vite 配置，目标指向当前测试启动的真实 MVC 后端。
  const previous = process.env.METEOR_CONSOLE_API;
  process.env.METEOR_CONSOLE_API = baseURL;
  const vite = await createServer({ server: { host: '127.0.0.1', port: 0, strictPort: true } });
  try {
    await vite.listen();
    await page.goto(vite.resolvedUrls.local[0]);
    await page.getByLabel('账号', { exact: true }).fill('admin');
    await page.getByLabel('密码', { exact: true }).fill('test-password');
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible();

    // Cookie 随同源代理请求发送；重新加载和退出也走相同的代理链路。
    await page.reload();
    await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible();
    await page.getByRole('button', { name: '退出', exact: true }).click();
    await expect(page.getByRole('heading', { name: '登录控制台' })).toBeVisible();
  } finally {
    await vite.close();
    if (previous === undefined) delete process.env.METEOR_CONSOLE_API;
    else process.env.METEOR_CONSOLE_API = previous;
  }
});
