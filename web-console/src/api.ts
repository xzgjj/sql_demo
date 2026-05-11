export type ApiResponse<T> = { success: boolean; data: T; errorCode?: string; errorMessage?: string };

export class ApiError extends Error {
  errorCode?: string;
  status: number;

  constructor(message: string, status: number, errorCode?: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.errorCode = errorCode;
  }
}

export type DashboardSummary = {
  ordersToday: number;
  paidSuccess: number;
  fulfillmentTotal: number;
  openExceptions: number;
  outboxBacklog: number;
  routeMiss: number;
  statusDistribution: Array<{ status: number; count: number }>;
  recentExceptions: ExceptionItem[];
  workQueue: Array<{ taskId: number; taskNo: string; orderNo: string; status: number; version: number; createdAt: string }>;
};

export type OrderSummary = {
  orderId: number;
  orderNo: string;
  status: number;
  totalAmount: number;
  itemCount: number;
  createdAt: string;
};

export type OrderPage = { items: OrderSummary[]; page: number; pageSize: number; total: number };

export type OrderDetail = OrderSummary & {
  userId: number;
  paidAmount: number;
  remark?: string;
  expiresAt?: string;
  paidAt?: string;
  cancelledAt?: string;
  completedAt?: string;
  items: Array<{ productId: number; productSku: string; productName: string; unitPrice: number; quantity: number; lineAmount: number }>;
  payment?: { paymentNo: string; channel: string; amount: number; status: number; paidAt?: string };
  fulfillment?: { taskNo: string; status: number; assigneeId?: number; claimedAt?: string; shippedAt?: string; carrier?: string; trackingNo?: string };
  statusTimeline: Array<{ fromStatus?: number; toStatus: number; operator: string; reason: string; createdAt: string }>;
};

export type TaskItem = {
  taskId: number;
  taskNo: string;
  userId: number;
  orderId: number;
  orderNo: string;
  status: number;
  version: number;
  assigneeId?: number;
  claimedAt?: string;
  createdAt: string;
};

export type TaskPage = { items: TaskItem[]; page: number; pageSize: number; total: number };
export type ExceptionItem = { id: number; bizType: string; bizNo: string; reasonCode: string; detail: string; status: number; createdAt: string };
export type ExceptionPage = { items: ExceptionItem[]; page: number; pageSize: number; total: number };

export type OrderTrace = {
  order: { orderId: number; orderNo: string; userId: number; status: number; totalAmount: number; paidAmount: number };
  route: string;
  transactionContext: string;
  routeTable: Array<Record<string, unknown>>;
  idempotency: Array<Record<string, unknown>>;
  outbox: Array<Record<string, unknown>>;
  timeline: Array<Record<string, unknown>>;
  sqlHistory: string[];
  sqlAuditLogs: Array<SqlAuditEntry>;
};

export type SqlAuditEntry = {
  traceId: string;
  sqlDigest: string;
  sqlSummary: string;
  targetDs: string;
  targetShard: number | null;
  status: string;
  errorCode: string | null;
  elapsedMs: number;
  createdAt: string;
};

export type LabRunResult = {
  scenario: string;
  steps: string[];
  routeTrace: Array<Record<string, unknown>>;
  transactionContext: string;
  idempotency: string[];
  outbox: string[];
  mvccChains: Record<string, string>;
  mvccSteps: Array<MvccStepDetail>;
  readViews: string[];
  assertions: string[];
  errors: string[];
};

export type MvccStepDetail = {
  sequence: number;
  txnId: number;
  operation: string;
  key?: string;
  value?: string;
  detail?: string;
  explanation: string;
  readView?: ReadViewInfo | null;
  versionChain?: VersionNode[] | null;
};

export type ReadViewInfo = {
  creatorTxnId: number;
  lowWatermark: number;
  highWatermark: number;
  activeTxnIds: number[];
  isolationLevel: string;
};

export type VersionNode = {
  value: string;
  createdByTxnId: number;
  deletedByTxnId: number;
  isLatest: boolean;
  txnStatus: string;
};

export type RuntimeMode = {
  mode: string;
  proxyMode: boolean;
  demoEnabled: boolean;
  testProfile: boolean;
  shardCount: number;
  activeProfiles: string;
  warnings: string[];
};

export function getApiKey(): string | null {
  try {
    return sessionStorage.getItem('minidb-api-key');
  } catch {
    return null;
  }
}

export function setApiKey(key: string): void {
  try {
    sessionStorage.setItem('minidb-api-key', key);
  } catch {
    // sessionStorage not available
  }
}

export function clearApiKey(): void {
  try {
    sessionStorage.removeItem('minidb-api-key');
  } catch {
    // sessionStorage not available
  }
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(init?.headers as Record<string, string> ?? {}),
  };

  const apiKey = getApiKey();
  if (apiKey) {
    headers['X-Api-Key'] = apiKey;
  }

  const res = await fetch(url, { ...init, headers });
  const body = (await res.json()) as ApiResponse<T>;
  if (!res.ok || body.success === false) {
    throw new ApiError(body.errorMessage || body.errorCode || `HTTP ${res.status}`, res.status, body.errorCode);
  }
  return body.data;
}

export const api = {
  runtimeMode: () => request<RuntimeMode>('/api/runtime/mode'),
  dashboard: () => request<DashboardSummary>('/api/dashboard/summary'),
  loadDemo: () => request<{ orderNos: string[]; fulfillmentTasks: number; exceptions: number }>('/api/console/demo/load', { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } }),
  orders: (userId: number, status?: number) => request<OrderPage>(`/api/orders?${pageParams(status)}`, { headers: { 'X-User-Id': String(userId) } }),
  order: (id: number, userId?: number) => request<OrderDetail>(`/api/orders/${id}`, userId ? { headers: { 'X-User-Id': String(userId) } } : undefined),
  cancelOrder: (id: number, userId: number, reason: string) => request<void>(`/api/orders/${id}/cancel`, {
    method: 'POST',
    headers: { 'X-User-Id': String(userId), 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ reason }),
  }),
  tasks: (status?: number) => request<TaskPage>(`/api/fulfillment/tasks?${pageParams(status)}`),
  claim: (taskId: number, version: number, operatorId = 2001) => request<void>(`/api/fulfillment/tasks/${taskId}/claim?version=${version}`, { method: 'POST', headers: { 'X-User-Id': String(operatorId), 'Idempotency-Key': crypto.randomUUID() } }),
  pick: (taskId: number, operatorId = 2001) => request<void>(`/api/fulfillment/tasks/${taskId}/pick`, { method: 'POST', headers: { 'X-User-Id': String(operatorId), 'Idempotency-Key': crypto.randomUUID() } }),
  ship: (taskId: number, carrier: string, trackingNo: string, operatorId = 2001) => request<void>(`/api/fulfillment/tasks/${taskId}/ship`, {
    method: 'POST',
    headers: { 'X-User-Id': String(operatorId), 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ carrier, trackingNo }),
  }),
  exceptions: (status?: number) => request<ExceptionPage>(`/api/exceptions?${pageParams(status)}`),
  exceptionDetail: (id: number) => request<ExceptionItem>(`/api/exceptions/${id}`),
  resolve: (id: number, resolution: string) => request<void>(`/api/exceptions/${id}/resolve`, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ resolution }) }),
  trace: (orderId: number) => request<OrderTrace>(`/api/audit/orders/${orderId}/trace`),
  routePreview: (sql: string) => request<Record<string, unknown>>(`/api/proxy/routes/preview?sql=${encodeURIComponent(sql)}`),
  runScenario: (scenario: string, body?: string) => request<LabRunResult>(`/api/lab/scenarios/${scenario}/run`, { method: 'POST', body: body ?? JSON.stringify({}) }),
  proxySessions: () => request<ProxySessionsResult>('/api/proxy/sessions'),
  proxyPools: () => request<ProxyPoolsResult>('/api/proxy/pools'),
  proxyDecisions: (limit?: number, sessionId?: string) => request<ProxyDecisionsResult>(`/api/proxy/decisions?limit=${limit ?? 50}${sessionId ? `&sessionId=${sessionId}` : ''}`),
};

export type ProxySessionsResult = {
  sessions: Array<Record<string, unknown>>;
  count: number;
};

export type ProxyPoolsResult = {
  pools: Record<string, { active: number; idle: number; max: number; healthy: boolean }>;
  totalActive: number;
};

export type ProxyDecisionsResult = {
  decisions: Array<{ sessionId: string; sql: string; keyType: string; keyValue: string; target: string; reason: string; status: string; elapsedMs: number }>;
  count: number;
};

function pageParams(status?: number) {
  const params = new URLSearchParams({ page: '1', pageSize: '20' });
  if (status) params.set('status', String(status));
  return params;
}

export function orderStatusText(status: number, lang: 'zh' | 'en' = 'zh') {
  const zh = { 10: '待支付', 20: '已支付', 30: '已取消', 40: '待履约', 50: '已发货', 60: '已完成', 70: '退款中', 80: '已退款', 90: '异常' } as Record<number, string>;
  const en = { 10: 'Pending Payment', 20: 'Paid', 30: 'Cancelled', 40: 'Pending Fulfillment', 50: 'Shipped', 60: 'Completed', 70: 'Refunding', 80: 'Refunded', 90: 'Exception' } as Record<number, string>;
  return (lang === 'zh' ? zh : en)[status] || (lang === 'zh' ? `状态 ${status}` : `Status ${status}`);
}

export function taskStatusText(status: number, lang: 'zh' | 'en' = 'zh') {
  const zh = { 10: '待领取', 20: '拣货中', 30: '已拣货', 40: '已发货', 50: '已完成', 90: '异常' } as Record<number, string>;
  const en = { 10: 'Unclaimed', 20: 'Picking', 30: 'Picked', 40: 'Shipped', 50: 'Completed', 90: 'Exception' } as Record<number, string>;
  return (lang === 'zh' ? zh : en)[status] || (lang === 'zh' ? `状态 ${status}` : `Status ${status}`);
}
