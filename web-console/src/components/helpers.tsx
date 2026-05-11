import { Typography } from 'antd';
import { ApiError } from '../api';
import { Lang } from '../i18n';

export function EmptyText({ lang }: { lang: Lang }) {
  return <Typography.Text type="secondary">{lang === 'zh' ? '暂无数据' : 'No data'}</Typography.Text>;
}

export function formatApiError(error: unknown, lang: Lang): string {
  if (error instanceof ApiError) {
    const prefix = error.errorCode ? `[${error.errorCode}] ` : `[HTTP ${error.status}] `;
    return prefix + error.message;
  }
  return error instanceof Error ? error.message : (lang === 'zh' ? '未知错误' : 'Unknown error');
}

export function exceptionStatusText(status: number, lang: Lang) {
  const zh: Record<number, string> = { 10: '待处理', 20: '处理中', 30: '已解决', 40: '已关闭' };
  const en: Record<number, string> = { 10: 'Open', 20: 'In Progress', 30: 'Resolved', 40: 'Closed' };
  return (lang === 'zh' ? zh : en)[status] ?? String(status);
}

export function prettyJson(value: string) {
  if (!value) return '{}';
  try { return JSON.stringify(JSON.parse(value), null, 2); }
  catch { return value; }
}

export function healthText(health: string, lang: Lang) {
  if (health === 'ready') return lang === 'zh' ? '接口就绪' : 'API Ready';
  if (health === 'checking') return lang === 'zh' ? '检查中' : 'Checking';
  return lang === 'zh' ? '接口异常' : 'API Error';
}

export function suggestedAction(reasonCode: string, lang: Lang) {
  if (reasonCode.includes('AMOUNT')) return lang === 'zh' ? '核对支付流水' : 'Verify payment ledger';
  if (reasonCode.includes('ROUTE')) return lang === 'zh' ? '检查 order_route 并按 user_id 复查' : 'Check order_route and user_id';
  if (reasonCode.includes('OUTBOX')) return lang === 'zh' ? '检查重试次数和下游处理器' : 'Check retry and consumers';
  return lang === 'zh' ? '人工复核证据后处理' : 'Manual review';
}
