

/**
 * 获取环境变量（Vite 标准用法）
 * 使用 import.meta.env 静态访问，避免 process.define 注入系统变量导致构建环境差异
 */
export const env = (param: string) => {
  const value = import.meta.env[param];
  // 仅对业务关键键做兜底，保证 k8s/ci 不同环境下一致性
  if (value === undefined || value === null || value === '') {
    switch (param) {
      case 'api_prefix': return '/api/apptech-srm-server';
      case 'VITE_NODE_ENV': return 'production';
      case 'project_code': return 'apptech-srm';
      case 'project_name': return '供应商风险管理';
      case 'company_name': return '药明康德';
      case 'amis_editor_url': return 'http://develop.deepcomplus.cn:52880/';
      case 'WEB_PORT': return '55101';
      case 'server_api_url': return `/api/apptech-srm-server`;
      default: return undefined;
    }
  }
  return value;
}