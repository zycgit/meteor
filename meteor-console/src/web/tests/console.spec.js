import { test, expect } from '@playwright/test';

const clusterProfile = {
  name: '生产 · 华东',
  type: 'jdbc',
  database: 'h2',
  address: 'jdbc:h2:mem:console-browser;DB_CLOSE_DELAY=-1',
  username: 'sa',
  password: ''
};
async function login(page) {
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill('admin');
  await page.getByLabel('密码', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible();
}
async function enter(page) {
  await login(page);
  let profiles = await (await page.request.get('/api/clusters')).json();
  let cluster = profiles.find((c) => c.name === clusterProfile.name);
  if (!cluster) cluster = await (await page.request.post('/api/clusters', { data: clusterProfile })).json();
  await page.goto(`/#view=applications&clusterId=${cluster.id}`);
  await page.reload();
  await expect(page.getByRole('navigation', { name: '服务治理' })).toBeVisible();
}
async function order(page) {
  const response = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/services');
  await page
    .getByRole('navigation', { name: '服务治理' })
    .getByRole('button', { name: '服务目录', exact: true })
    .click();
  const services = (await (await response).json()).items;
  await expect(page.locator('tbody tr td:first-child code')).toHaveText(
    services.map((service) => service.info.serviceId)
  );
  const serviceId = services.find((service) => service.info.name.includes('OrderService')).info.serviceId;
  await page.getByLabel('搜索服务 ID', { exact: true }).fill(serviceId);
  await expect(page.getByRole('columnheader', { name: '操作', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: serviceId, exact: true }).click();
  await expect(page.getByRole('navigation', { name: '服务详情' })).toBeVisible();
  await expect(page.locator('.breadcrumbs strong')).toHaveText(serviceId);
  await expect(page.getByRole('button', { name: '服务信息', exact: true })).toHaveClass('active');
  await expect(page.locator('.details-grid')).toContainText(serviceId);
}
async function tab(page, name) {
  await page.getByRole('navigation', { name: '服务详情' }).getByRole('button', { name, exact: true }).click();
}
async function addMeta(page, key, value) {
  await page.getByRole('button', { name: '新增', exact: true }).click();
  await page.getByLabel('元信息键', { exact: true }).fill(key);
  await page.getByLabel('元信息值（JSON）', { exact: true }).fill(value);
  await page.locator('.metadata-panel').getByRole('button', { name: '保存', exact: true }).click();
}

test('login stays visible when session lookup times out and recovers after proxy failure', async ({
  page
}) => {
  // 后端没有响应时先显示登录表单，不用整页加载状态遮住入口。
  const sessionRoute = '**/api/auth/session?*';
  await page.route(sessionRoute, () => {});
  await page.goto('/');
  await expect(page.getByRole('heading', { name: '登录控制台' })).toBeVisible();
  await expect(page.getByRole('button', { name: '检查登录…', exact: true })).toBeDisabled();
  await expect(page.getByRole('alert')).toContainText('连接控制台后端超时', { timeout: 8000 });
  await expect(page.getByRole('button', { name: '登录', exact: true })).toBeEnabled();

  // 开发代理返回非 JSON 错误时，显示可理解的连接错误。
  await page.unroute(sessionRoute);
  await page.route(sessionRoute, (route) => route.fulfill({ status: 502, body: 'Bad Gateway' }));
  await page.reload();
  await expect(page.getByRole('alert')).toContainText('无法连接控制台后端');

  // 后端恢复后可以直接登录，无需重启前端。
  await page.unroute(sessionRoute);
  await page.getByLabel('账号', { exact: true }).fill('admin');
  await page.getByLabel('密码', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('navigation', { name: '主要导航' })).toBeVisible();
});

test('login, first connection, persistence and compact cluster switching', async ({ page }) => {
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill('admin');
  await page.getByLabel('密码', { exact: true }).fill('wrong');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('账号或密码错误');
  await page.getByLabel('密码', { exact: true }).fill('test-password');
  await page.getByRole('button', { name: '登录', exact: true }).click();
  await expect(page.getByRole('heading', { name: '连接到注册中心' })).toBeVisible();

  // 连接配置是页面，修改测试过的参数后须重新测试。
  await page.locator('.tabbar').getByRole('button', { name: '连接注册中心' }).click();
  await page.getByRole('button', { name: 'JDBC', exact: true }).click();
  await page.getByLabel('连接名称').fill(clusterProfile.name);
  await page.getByLabel('数据库', { exact: true }).selectOption('h2');
  await page.getByLabel('JDBC URL').fill(clusterProfile.address);
  await page.getByLabel('用户名', { exact: true }).fill('sa');
  await expect(page.getByText('数据分组', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '测试连接', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('连接成功');
  await page.getByLabel('连接名称').fill('changed');
  await expect(page.getByRole('button', { name: '保存并进入' })).toBeDisabled();
  await page.getByLabel('连接名称').fill(clusterProfile.name);
  await page.getByRole('button', { name: '保存并进入' }).click();
  await expect(page.getByText('order-app', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.locator('.cluster-select')).toHaveText(/生产 · 华东/);
  await expect(page.locator('.topbar').getByText('jdbc:h2:', { exact: false })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '可观测性' })).toBeDisabled();
  await page.locator('.cluster-select').click();
  await page.locator('.cluster-menu').getByRole('button', { name: 'ALL', exact: true }).click();
  await expect(page.locator('.cluster-cards .card')).toHaveCount(1);
  await page.screenshot({ path: 'test-results/clusters.png', fullPage: true });
});

test('application cards, instance unit search and independent detail navigation', async ({ page }) => {
  await enter(page);
  await page.getByRole('button', { name: '切换为列表' }).click();
  await expect(page.locator('tbody tr')).toHaveCount(2);
  await page.getByRole('button', { name: '切换为卡片' }).click();
  await expect(page.locator('.card')).toHaveCount(2);
  const applicationCard = page.getByRole('button', { name: 'order-app', exact: true });
  await expect(applicationCard).toHaveText('order-app');
  await expect(applicationCard.locator('.protocols')).toHaveCount(0);
  const consumerCard = page.getByRole('button', { name: 'storefront', exact: true });
  await expect(consumerCard).toHaveText('storefront');
  await page.screenshot({ path: 'test-results/application-cards.png', fullPage: true });
  // 点击卡片边缘也可以进入，不依赖标题或单独的操作按钮。
  await applicationCard.click({ position: { x: 8, y: 8 } });
  const identity = page.locator('.instance-identity').first();
  await expect(page.getByRole('columnheader', { name: 'PID', exact: true })).toBeVisible();
  await expect(identity.locator('.instance-primary')).not.toContainText('PID');
  await expect(page.locator('.instance-pid').first()).toHaveText(/^\d+$/);
  await expect(identity.locator('.instance-id')).toHaveCount(0);
  const ip = (await identity.locator('.instance-primary').textContent()).trim();
  const processId = (await page.locator('.instance-pid').first().textContent()).trim();
  await page.getByLabel('搜索 IP、进程 ID 或实例 ID').fill(ip);
  await expect(page.locator('.instance-identity')).toHaveCount(2);
  await page.getByLabel('搜索 IP、进程 ID 或实例 ID').fill(processId);
  await expect(page.locator('.instance-identity')).toHaveCount(2);
  await page.getByLabel('搜索 IP、进程 ID 或实例 ID').fill('');
  await expect(page.getByLabel('筛选单元')).toBeVisible();
  await page.getByLabel('筛选单元').selectOption('east');
  await expect(page.getByText('本页 1 个实例', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '切换为卡片' }).click();
  await expect(page.locator('.instance-card')).toHaveCount(1);
  await expect(page.locator('.instance-card .instance-primary')).toHaveText(ip);
  await expect(page.locator('.instance-card .instance-process')).toHaveText(`PID ${processId}`);
  await expect(page.locator('.instance-card .instance-id')).toHaveCount(0);
  await expect(page.locator('.instance-card').getByRole('button', { name: '详情' })).toHaveCount(0);
  await page.screenshot({ path: 'test-results/instance-cards.png', fullPage: true });
  await page.locator('.instance-card').click({ position: { x: 8, y: 8 } });
  await expect(page.getByRole('navigation', { name: '实例详情' })).toBeVisible();
  await expect(page.locator('.breadcrumbs .instance-primary')).toHaveText(`${ip} (${processId})`);
  await expect(page.locator('.breadcrumbs .instance-id')).toHaveCount(0);
  await expect(
    page
      .locator('.details-grid div')
      .filter({ has: page.locator('dt', { hasText: '实例 IP' }) })
      .locator('dd')
  ).toHaveText(ip);
  await expect(
    page
      .locator('.details-grid div')
      .filter({ has: page.locator('dt', { hasText: '进程 ID' }) })
      .locator('dd')
  ).toHaveText(processId);
  const onlineStatus = page
    .locator('.details-grid div')
    .filter({ has: page.locator('dt', { hasText: '在线状态' }) });
  await expect(onlineStatus).toContainText('在线');
  await expect(page.getByRole('switch', { name: '阻断调用' })).toBeVisible();
  await page.getByRole('switch', { name: '阻断调用' }).click();
  await expect(page.getByRole('switch', { name: '阻断调用' })).toBeChecked();
  await expect(onlineStatus).toContainText('在线');
  await page.getByRole('switch', { name: '阻断调用' }).click();
  await expect(page.getByRole('switch', { name: '阻断调用' })).not.toBeChecked();
  await page.getByRole('button', { name: '服务清单', exact: true }).click();
  await expect(page.getByRole('switch', { name: '阻断调用' })).toHaveCount(0);
  const refreshed = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/services');
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  const snapshot = await (await refreshed).json();
  await expect(page.locator('tbody tr td:first-child code')).toHaveText(
    snapshot.items.map((service) => service.serviceId)
  );
  const inventoryId = snapshot.items.find((service) => service.name.includes('Inventory')).serviceId;
  await page.getByLabel('搜索服务', { exact: true }).fill(inventoryId);
  await expect(page.getByRole('columnheader', { name: '操作', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: inventoryId, exact: true })).toHaveCount(1);
  await page.getByRole('button', { name: inventoryId, exact: true }).click();
  await expect(page.locator('.breadcrumbs .instance-primary')).toHaveText(`${ip} (${processId})`);
  await expect(page.locator('.breadcrumbs strong')).toHaveText(inventoryId);
  await expect(
    page.locator('.breadcrumbs').getByRole('button', { name: 'order-app', exact: true })
  ).toBeVisible();
  await expect(page.getByRole('button', { name: '提供者', exact: true })).toHaveCount(0);
  await expect(page.getByLabel('目标范围', { exact: true })).toHaveCount(0);
  await page.goBack();
  await expect(page.getByRole('navigation', { name: '实例详情' })).toBeVisible();
});

for (const role of ['提供者', '消费者']) {
  test(`${role} entry governs only the selected instance and service`, async ({ page }) => {
    await enter(page);
    await order(page);
    const serviceRoute = new URLSearchParams(new URL(page.url()).hash.slice(1));
    const serviceId = serviceRoute.get('serviceId');
    await tab(page, role);
    const ip = (await page.locator('.member-table .instance-primary').first().textContent()).trim();
    const pid = (await page.locator('.member-table .instance-pid').first().textContent()).trim();
    await page.locator('.member-table .instance-link').first().click();

    // 两个成员入口均进入实例与服务的交集，刷新后仍保留该范围。
    await expect(page.locator('.breadcrumbs .instance-primary')).toHaveText(`${ip} (${pid})`);
    await expect(page.locator('.breadcrumbs strong')).toHaveText(serviceId);
    await expect(
      page
        .locator('.service-info-fields > div')
        .filter({ has: page.locator('dt', { hasText: /^身份$/ }) })
        .locator('dd')
    ).toHaveText(role);
    await expect(page.getByRole('button', { name: '提供者', exact: true })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '消费者', exact: true })).toHaveCount(0);
    await expect(page.getByLabel('目标范围', { exact: true })).toHaveCount(0);
    const scopedRoute = new URLSearchParams(new URL(page.url()).hash.slice(1));
    expect(scopedRoute.get('instanceId')).toBeTruthy();
    expect(scopedRoute.get('serviceId')).toBe(serviceId);
    await page.reload();
    await expect(page.locator('.breadcrumbs .instance-primary')).toHaveText(`${ip} (${pid})`);

    // 治理只写入该实例的服务覆盖，不修改全服务配置或另一个实例。
    await tab(page, '元信息');
    const key = role === '提供者' ? 'provider-entry' : 'consumer-entry';
    await addMeta(page, key, '"scoped"');
    const query = new URLSearchParams({
      mode: 'registry',
      clusterId: scopedRoute.get('clusterId'),
      scope: 'service',
      serviceId
    });
    const global = await (await page.request.get(`/api/metadata?${query}`)).json();
    expect(global[key]).toBeUndefined();
    query.set('instanceId', scopedRoute.get('instanceId'));
    const local = await (await page.request.get(`/api/metadata?${query}`)).json();
    expect(local[key]).toBe('scoped');
    const instanceQuery = new URLSearchParams({ mode: 'registry', clusterId: scopedRoute.get('clusterId') });
    const instances = await (await page.request.get(`/api/instances?${instanceQuery}`)).json();
    const other = instances.items.find((instance) => instance.instanceId !== scopedRoute.get('instanceId'));
    query.set('instanceId', other.instanceId);
    const unrelated = await (await page.request.get(`/api/metadata?${query}`)).json();
    expect(unrelated[key]).toBeUndefined();

    // 面包屑可返回该实例的服务清单。
    await page.locator('.breadcrumbs .instance-primary').click();
    await expect(page.getByRole('button', { name: '服务清单', exact: true })).toHaveClass('active');
  });
}

test('instance cards distinguish provider, consumer and combined roles', async ({ page }) => {
  await enter(page);
  await page.getByRole('button', { name: 'order-app', exact: true }).click();
  await page.getByRole('button', { name: '切换为卡片' }).click();
  const roles = page.locator('.instance-card .instance-role');
  await expect(roles).toHaveCount(2);
  await expect(roles.filter({ hasText: /^提供者$/ })).toHaveCount(1);
  await expect(roles.filter({ hasText: /^提供\/消费$/ })).toHaveCount(1);
  await expect(roles.first()).toHaveCSS('color', 'rgb(49, 95, 145)');

  const provider = page.locator('.instance-card').filter({ hasText: '提供者' });
  const protocols = await provider.locator('.protocols').boundingBox();
  const role = await provider.locator('.instance-role').boundingBox();
  expect(protocols.x + protocols.width).toBeLessThanOrEqual(role.x);
  await page.screenshot({ path: 'test-results/instance-roles.png', fullPage: true });

  await page.getByRole('button', { name: '应用列表', exact: true }).click();
  await page.getByRole('button', { name: 'storefront', exact: true }).click();
  await expect(page.locator('.instance-card .instance-role')).toHaveText('消费者');
  await expect(page.locator('.instance-card .protocols')).toHaveCount(0);
});

test('cluster service information does not choose one instance interface', async ({ page }) => {
  // 服务目录聚合同一登记身份，不把某个实例的 Java 声明当成统一接口。
  await enter(page);
  const response = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/service');
  await order(page);
  const service = await (await response).json();
  expect(service.info).not.toHaveProperty('type');
  expect(service.description).toBeUndefined();
  await expect(page.locator('.service-info-fields dt').filter({ hasText: /^接口$/ })).toHaveCount(0);
});

test('instance service information displays the selected instance Java description', async ({ page }) => {
  await enter(page);
  await order(page);
  const membersResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/service/members'
  );
  await tab(page, '提供者');
  const members = (await (await membersResponse).json()).items;
  expect(members).toHaveLength(2);
  const firstJavaType = 'example.orders.PrimaryOrderApi';
  const secondJavaType = 'example.orders.ReplicaOrderApi';
  const descriptions = new Map([
    [members[0].instance.instanceId, firstJavaType],
    [members[1].instance.instanceId, secondJavaType]
  ]);

  // 两个真实成员共享 serviceId，但各自的响应声明不同的 Java 接口。
  await page.route('**/api/service?*', async (route) => {
    const instanceId = new URL(route.request().url()).searchParams.get('instanceId');
    const response = await route.fetch();
    const service = await response.json();
    if (descriptions.has(instanceId)) {
      service.description = { ...service.description, javaType: descriptions.get(instanceId) };
    }
    await route.fulfill({ response, json: service });
  });
  await page.locator('.member-table .instance-link').first().click();
  await expect(page.locator('.service-info-fields code')).toHaveText(firstJavaType);
  expect(new URLSearchParams(new URL(page.url()).hash.slice(1)).get('instanceId')).toBe(
    members[0].instance.instanceId
  );

  // 返回成员列表后打开另一个实例，接口随目标切换。
  await page.goBack();
  await expect(page.locator('.member-table .instance-link')).toHaveCount(2);
  await page.locator('.member-table .instance-link').nth(1).click();
  await expect(page.locator('.service-info-fields code')).toHaveText(secondJavaType);
  expect(new URLSearchParams(new URL(page.url()).hash.slice(1)).get('instanceId')).toBe(
    members[1].instance.instanceId
  );
});

test('instance service information remains usable when its description is missing', async ({ page }) => {
  await enter(page);
  await order(page);
  await tab(page, '提供者');

  // 描述缺失不影响已有服务摘要、身份和治理入口。
  await page.route('**/api/service?*', async (route) => {
    const response = await route.fetch();
    const service = await response.json();
    delete service.description;
    await route.fulfill({ response, json: service });
  });
  const pageErrors = [];
  page.on('pageerror', (error) => pageErrors.push(error.message));
  await page.locator('.member-table .instance-link').first().click();
  await expect(page.locator('.service-info-fields')).toBeVisible();
  await expect(page.locator('.service-info-fields')).toContainText('提供者');
  await expect(page.locator('.service-info-fields dt').filter({ hasText: /^接口$/ })).toHaveCount(0);
  await expect(page.getByRole('switch', { name: '启用服务' })).toBeEnabled();
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(pageErrors).toEqual([]);
});

test('provider protocol labels and filters use endpoint protocolName', async ({ page }) => {
  await enter(page);
  await order(page);
  const membersResponse = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/service/members'
  );
  await tab(page, '提供者');
  const members = (await (await membersResponse).json()).items;
  await expect(page.locator('.member-table tbody tr')).toHaveCount(2);
  for (const member of members) {
    for (const endpoint of member.endpoints) {
      expect(endpoint.protocolName).toBeTruthy();
      expect(endpoint).not.toHaveProperty('protocol');
    }
  }
  await expect(page.locator('.member-table .protocols .badge').filter({ hasText: /^hprose$/ })).toHaveCount(
    1
  );
  await expect(page.locator('.member-table .protocols .badge').filter({ hasText: /^rsf$/ })).toHaveCount(2);

  // 端点字段更名后，筛选查询仍用 protocol 参数并返回对应成员。
  const filteredResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === '/api/service/members' && url.searchParams.get('protocol') === 'hprose';
  });
  await page.getByLabel('筛选协议').selectOption('hprose');
  const filtered = (await (await filteredResponse).json()).items;
  expect(filtered).toHaveLength(1);
  expect(filtered[0].endpoints.some((endpoint) => endpoint.protocolName === 'hprose')).toBe(true);
  await expect(page.locator('.member-table tbody tr')).toHaveCount(1);
  await expect(page.locator('.member-table .protocols')).toContainText('hprose');
  await page.getByLabel('筛选协议').selectOption('rsf');
  await expect(page.locator('.member-table tbody tr')).toHaveCount(2);
});

test('service members expose instance identity, units and protocol tags', async ({ page }) => {
  await enter(page);
  await order(page);
  await tab(page, '提供者');
  await expect(page.getByRole('columnheader', { name: '单元', exact: true })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '进程', exact: true })).toBeVisible();
  const identity = page.locator('.member-table .instance-primary').first();
  const pid = page.locator('.member-table .instance-pid').first();
  await expect(identity).toHaveText('127.0.0.1');
  await expect(pid).toHaveText(/^\d+$/);
  await expect(page.locator('.member-table .instance-id')).toHaveCount(0);
  const primary = (await identity.textContent()).trim();
  const processId = (await pid.textContent()).trim();
  await page.getByLabel('搜索成员').fill(processId);
  await expect(page.getByText('本页 2 个提供者', { exact: true })).toBeVisible();
  await page.getByLabel('搜索条件').selectOption('unit');
  await page.getByLabel('搜索成员').fill('east');
  await expect(page.getByText('本页 1 个提供者', { exact: true })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '发布入口', exact: true })).toHaveCount(0);
  await expect(page.getByRole('columnheader', { name: '操作', exact: true })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '治理服务', exact: true })).toHaveCount(0);
  await expect(page.locator('.endpoint-table')).toHaveCount(0);
  await expect(page.locator('.member-table .protocols')).toContainText('rsf');
  await expect(page.locator('.member-table .protocols')).toContainText('hprose');
  await page.screenshot({ path: 'test-results/service-endpoints.png', fullPage: true });
  await tab(page, '消费者');
  await expect(page.getByRole('columnheader', { name: '使用协议', exact: true })).toBeVisible();
  await expect(page.getByRole('columnheader', { name: '支持协议', exact: true })).toHaveCount(0);
  await expect(page.locator('.member-table .instance-primary')).toHaveText(primary);
  await expect(page.locator('.member-table .instance-pid')).toHaveText(processId);
  await expect(page.locator('.member-table .instance-id')).toHaveCount(0);
  await expect(page.getByRole('columnheader', { name: '订阅端点', exact: true })).toHaveCount(0);
  await expect(page.getByRole('columnheader', { name: '操作', exact: true })).toHaveCount(0);
  await expect(page.locator('.endpoint-table')).toHaveCount(0);
  await expect(page.locator('.member-table .protocols')).toContainText('rsf');
  await page.getByLabel('筛选协议').selectOption('rsf');
  await expect(page.getByText('本页 1 个消费者', { exact: true })).toBeVisible();
});

test('rule editing, unit submission, dirty navigation and global configuration tabs', async ({ page }) => {
  await enter(page);
  await order(page);
  await tab(page, '路由策略');
  const editor = page.getByLabel('规则正文');
  await editor.fill('<route><return value="addresses"/></route>');
  await page.getByLabel('目标单元').selectOption('east');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('已保存到 2 个实例');
  await editor.fill('<route><!-- unsaved --></route>');
  page.once('dialog', (dialog) => dialog.dismiss());
  await page.getByRole('button', { name: '方法级', exact: true }).click();
  await expect(editor).toHaveValue('<route><!-- unsaved --></route>');
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '方法级', exact: true }).click();
  await expect(editor).toHaveValue('');
  await expect(page.getByText('方法级路由', { exact: true })).toHaveCount(0);
  await editor.fill('<route/>');
  await page.getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: '服务目录', exact: true }).click();
  await page.getByRole('navigation', { name: '服务治理' }).getByRole('button', { name: '全局配置' }).click();
  await expect(page.getByLabel('规则正文')).toBeVisible();
  await page.getByRole('navigation', { name: '全局配置' }).getByRole('button', { name: '路由策略' }).click();
  for (const level of ['服务级', '方法级', '参数级']) {
    await page.getByRole('button', { name: level, exact: true }).click();
    await expect(page.getByLabel('规则正文')).toBeEditable();
  }
});

test('metadata preserves JSON types, rejects invalid drafts and surfaces network failures', async ({
  page
}) => {
  await enter(page);
  await order(page);
  await tab(page, '元信息');
  await addMeta(page, 'policy', '{"enabled":true,"zones":["east","west"],"weight":10}');
  await expect(page.locator('.metadata-table')).toContainText('"weight":10');
  await addMeta(page, 'team', '"orders"');
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.locator('.metadata-table')).toContainText('"orders"');
  await page.screenshot({ path: 'test-results/service-metadata.png', fullPage: true });
  await page
    .locator('.metadata-table tr')
    .filter({ hasText: 'team' })
    .getByRole('button', { name: '编辑' })
    .click();
  await page.getByLabel('元信息值（JSON）').fill('{invalid');
  await page.locator('.metadata-panel').getByRole('button', { name: '保存', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('有效 JSON');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  page.once('dialog', (dialog) => dialog.accept());
  await page
    .locator('.metadata-table tr')
    .filter({ hasText: 'team' })
    .getByRole('button', { name: '删除' })
    .click();
  await expect(page.locator('.metadata-table')).not.toContainText('"orders"');
  await page.route('**/api/metadata?*', (route) =>
    route.fulfill({
      status: 502,
      contentType: 'application/json',
      body: JSON.stringify({ error: '存储连接中断' })
    })
  );
  await page.getByRole('button', { name: '刷新', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('存储连接中断');
  await page.unroute('**/api/metadata?*');
});

test('direct management uses real RPC without a selected registry', async ({ page }) => {
  await login(page);
  await page.locator('.cluster-select').click();
  await page.locator('.cluster-menu').getByRole('button', { name: '直连实例', exact: true }).click();
  await page.getByLabel('管理凭据', { exact: true }).fill('wrong-direct-secret');
  await page.getByLabel('管理 RPC 地址').fill('bad-address');
  await page.getByRole('button', { name: '连接', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('管理地址格式');
  await page.getByLabel('管理 RPC 地址').fill('rsf://127.0.0.1:18767/default');
  await page.getByRole('button', { name: '连接', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('authentication failed');
  await page.getByLabel('管理凭据', { exact: true }).fill('browser-direct-令牌+secret');
  const request = page.waitForRequest(
    (request) => request.url().includes('/api/instance?') && request.url().includes('mode=direct')
  );
  await page.getByRole('button', { name: '连接', exact: true }).click();
  expect((await request).headers()['x-meteor-management-token']).toBe(
    Buffer.from('browser-direct-令牌+secret').toString('base64')
  );
  await expect(page.getByText('direct-order-app', { exact: true })).toBeVisible();
  expect(decodeURIComponent(page.url())).not.toContain('browser-direct-令牌+secret');
  const storage = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }));
  expect(storage).not.toContain('browser-direct-令牌+secret');
  await page.getByRole('button', { name: '服务清单', exact: true }).click();
  await page.getByRole('button', { name: /InventoryService/ }).click();
  await expect(page.locator('.service-info-fields code')).toHaveText(
    'net.hasor.meteor.test.scenario.ConsoleBrowserFixture$InventoryService'
  );
  await tab(page, '流量控制');
  await expect(page.getByLabel('规则正文')).toBeEditable();
  await expect(page.getByRole('switch', { name: '启用规则' })).toHaveCount(0);
  await tab(page, '元信息');
  await addMeta(page, 'owner', '"direct"');
  await expect(page.locator('.metadata-table')).toContainText('"direct"');
});

test('dense layouts fit 1080p and 1360-wide screens, narrow screens scroll only inside tables', async ({
  page
}) => {
  await page.setViewportSize({ width: 1920, height: 960 });
  await enter(page);
  await expect(page.getByText('order-app', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/applications-1080p.png', fullPage: true });
  await order(page);
  await tab(page, '路由策略');
  for (const width of [1920, 1360]) {
    await page.setViewportSize({ width, height: width === 1920 ? 960 : 768 });
    await expect(page.getByRole('button', { name: '保存', exact: true })).toBeInViewport();
    const dimensions = await page.evaluate(() => ({
      width: innerWidth,
      actual: document.documentElement.scrollWidth
    }));
    expect(dimensions.actual).toBeLessThanOrEqual(dimensions.width);
  }
  await page.screenshot({ path: 'test-results/route-1360.png', fullPage: true });
  await tab(page, '提供者');
  await page.setViewportSize({ width: 390, height: 844 });
  const dimensions = await page.evaluate(() => ({
    width: innerWidth,
    actual: document.documentElement.scrollWidth
  }));
  expect(dimensions.actual).toBeLessThanOrEqual(dimensions.width);
});

test('connection pages cover provider fields, failed connection, second cluster and removal', async ({
  page
}) => {
  await login(page);
  await page.locator('.tabbar').getByRole('button', { name: '连接注册中心' }).click();
  await expect(page.getByText('命名空间', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'ZooKeeper', exact: true }).click();
  await expect(page.getByLabel('根路径')).toHaveValue('/meteor');
  await expect(page.getByText('命名空间', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'JDBC', exact: true }).click();
  await page.getByLabel('连接名称').fill('独立集群');
  await page.getByLabel('数据库', { exact: true }).selectOption('h2');
  await page.getByLabel('JDBC URL').fill('jdbc:h2:mem:no-schema;DB_CLOSE_DELAY=-1');
  await page.getByLabel('用户名', { exact: true }).fill('sa');
  await page.getByRole('button', { name: '测试连接', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('Table');
  await expect(page.getByRole('button', { name: '保存并进入' })).toBeDisabled();
  await page.getByLabel('JDBC URL').fill('jdbc:h2:mem:console-other;DB_CLOSE_DELAY=-1');
  await page.getByRole('button', { name: '测试连接', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('连接成功');
  await page.getByRole('button', { name: '保存并进入' }).click();
  await expect(page.getByText('没有匹配的应用', { exact: true })).toBeVisible();
  await page.locator('.cluster-select').click();
  await page.locator('.cluster-menu').getByRole('button', { name: 'ALL', exact: true }).click();
  const card = page.locator('.cluster-cards .card').filter({ hasText: '独立集群' });
  await card.getByRole('button', { name: '编辑', exact: true }).click();
  await expect(page.getByLabel('连接名称')).toHaveValue('独立集群');
  await page.getByRole('button', { name: '取消', exact: true }).click();
  await card.getByRole('button', { name: '移除', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '移除', exact: true }).click();
  await expect(card).toHaveCount(0);
});

test('Remote connects through authenticated RPC and hides storage credentials', async ({ page }) => {
  await login(page);
  await page.locator('.tabbar').getByRole('button', { name: '连接注册中心' }).click();
  await page.getByRole('button', { name: 'Remote', exact: true }).click();
  await expect(page.getByLabel('用户名', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('密码', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('管理凭据', { exact: true })).toBeVisible();
  await page.getByLabel('连接名称').fill('Remote 管理');
  await page.getByLabel('RPC 地址').fill('rsf://127.0.0.1:18768/default');
  await page.getByLabel('管理凭据', { exact: true }).fill('wrong-secret');
  await page.getByRole('button', { name: '测试连接', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('authentication failed');
  await expect(page.getByRole('button', { name: '保存并进入' })).toBeDisabled();

  await page.getByLabel('管理凭据', { exact: true }).fill('browser-remote-secret');
  await page.getByRole('button', { name: '测试连接', exact: true }).click();
  await expect(page.getByRole('status')).toContainText('连接成功');
  await page.getByRole('button', { name: '保存并进入' }).click();
  await expect(page.getByText('order-app', { exact: true })).toBeVisible();
  const profiles = await (await page.request.get('/api/clusters')).json();
  const remote = profiles.find((profile) => profile.name === 'Remote 管理');
  expect(remote.hasManagementToken).toBe(true);
  expect(remote.managementToken).toBeUndefined();
  expect(remote.hasPassword).toBe(false);
  await page.reload();
  await expect(page.locator('.cluster-select')).toContainText('Remote 管理');
  await page.request.delete(`/api/clusters?id=${remote.id}`);
});
