export function instanceSearchText(instance) {
  return [instance?.ipAddress, instance?.processId, instance?.instanceId].join(' ').toLowerCase();
}

export function instanceRole(instance) {
  const roles = instance.roles || [];
  const provider = roles.includes('Provider');
  const consumer = roles.includes('Consumer');
  if (provider && consumer) return '提供/消费';
  if (provider) return '提供者';
  if (consumer) return '消费者';
  return '';
}
