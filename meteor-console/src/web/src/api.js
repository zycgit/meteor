export function createApi(onUnauthorized, directCredential = () => undefined) {
  return async function api(path, params = {}, method = 'GET', body, options = {}) {
    const query = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
      if (value !== null && value !== undefined && value !== '') query.set(key, value);
    }
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), options.timeout ?? 30000);
    try {
      const headers = { 'Content-Type': 'application/json' };
      if (params.mode === 'direct') {
        const token = options.managementToken ?? directCredential(params.target);
        if (!token) throw new Error('请返回直连页面填写目标实例的管理凭据。');
        const bytes = new TextEncoder().encode(token);
        headers['X-Meteor-Management-Token'] = btoa(String.fromCharCode(...bytes));
      }
      const response = await fetch(`/api/${path}?${query}`, {
        method,
        credentials: 'same-origin',
        signal: controller.signal,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body)
      });
      let result;
      try {
        result = await response.json();
      } catch (error) {
        if (error.name === 'AbortError') throw error;
        // 开发代理在后端未启动时可能返回空白或 HTML 错误页。
        throw new Error('无法连接控制台后端，请确认后端服务已启动且 API 地址正确。');
      }
      if (!response.ok) {
        if (response.status === 401 && path !== 'auth/login') onUnauthorized?.();
        const error = new Error(result.error || `操作失败（${response.status}）`);
        error.status = response.status;
        throw error;
      }
      return result;
    } catch (error) {
      if (error.name === 'AbortError') {
        if (method === 'GET') {
          throw new Error('连接控制台后端超时，请确认后端服务已启动且 API 地址正确。');
        }
        throw new Error('请求超时。写入可能已完成，请刷新后确认。');
      }
      if (error instanceof TypeError) {
        throw new Error('无法连接控制台后端，请检查服务和网络连接。');
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  };
}
