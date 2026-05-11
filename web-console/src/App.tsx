import { DashboardOutlined, DatabaseOutlined, ExceptionOutlined, FileSearchOutlined, OrderedListOutlined, SettingOutlined, TruckOutlined } from '@ant-design/icons';
import { PageContainer, ProCard, ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { Alert, Button, Descriptions, Drawer, Input, Layout, Menu, Modal, Progress, Radio, Segmented, Select, Skeleton, Space, Statistic, Tabs, Tag, Timeline, Typography, message } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { ApiError, api, DashboardSummary, ExceptionItem, LabRunResult, OrderDetail, OrderSummary, OrderTrace, ProxyDecisionsResult, ProxyPoolsResult, ProxySessionsResult, ProxyStatus, RuntimeMode, TaskItem, clearApiKey, getApiKey, orderStatusText, setApiKey, taskStatusText } from './api';
import { Lang, tr } from './i18n';

const { Sider, Header, Content } = Layout;

type PageKey = 'dashboard' | 'orders' | 'fulfillment' | 'exceptions' | 'lab' | 'trace' | 'routePreview' | 'proxy' | 'settings';
type TerminalKey = 'business' | 'database';

const menuIcon = {
  dashboard: <DashboardOutlined />,
  orders: <OrderedListOutlined />,
  fulfillment: <TruckOutlined />,
  exceptions: <ExceptionOutlined />,
  lab: <DatabaseOutlined />,
  trace: <FileSearchOutlined />,
  routePreview: <FileSearchOutlined />,
  proxy: <DatabaseOutlined />,
  settings: <SettingOutlined />,
};

const terminalPages: Record<TerminalKey, PageKey[]> = {
  business: ['dashboard', 'orders', 'fulfillment', 'exceptions', 'settings'],
  database: ['lab', 'trace', 'routePreview', 'proxy', 'settings'],
};

const defaultPage: Record<TerminalKey, PageKey> = {
  business: 'dashboard',
  database: 'lab',
};

const pageSegments: Record<PageKey, string> = {
  dashboard: 'dashboard',
  orders: 'orders',
  fulfillment: 'fulfillment',
  exceptions: 'exceptions',
  lab: 'lab',
  trace: 'trace',
  routePreview: 'route-preview',
  proxy: 'proxy',
  settings: 'settings',
};

const businessSegmentMap: Record<string, PageKey> = {
  dashboard: 'dashboard',
  orders: 'orders',
  fulfillment: 'fulfillment',
  exceptions: 'exceptions',
  settings: 'settings',
};

const databaseSegmentMap: Record<string, PageKey> = {
  lab: 'lab',
  trace: 'trace',
  'route-preview': 'routePreview',
  proxy: 'proxy',
  settings: 'settings',
};

function parseLocation() {
  const [terminalSegment, pageSegment] = window.location.pathname.replace(/^\/+|\/+$/g, '').split('/');
  const terminal: TerminalKey = terminalSegment === 'database' ? 'database' : 'business';
  const segmentMap = terminal === 'business' ? businessSegmentMap : databaseSegmentMap;
  const mappedPage = segmentMap[pageSegment || ''] ?? defaultPage[terminal];
  const page = terminalPages[terminal].includes(mappedPage) ? mappedPage : defaultPage[terminal];
  const orderId = Number(new URLSearchParams(window.location.search).get('orderId')) || undefined;
  return { terminal, page, orderId };
}

function terminalUrl(terminal: TerminalKey, page: PageKey, orderId?: number) {
  const params = new URLSearchParams();
  if (orderId && (page === 'trace' || page === 'orders')) params.set('orderId', String(orderId));
  const query = params.toString();
  return `/${terminal}/${pageSegments[page]}${query ? `?${query}` : ''}`;
}

export default function App() {
  const initialLocation = parseLocation();
  const [lang, setLang] = useState<Lang>('zh');
  const [terminal, setTerminal] = useState<TerminalKey>(initialLocation.terminal);
  const [page, setPage] = useState<PageKey>(initialLocation.page);
  const [health, setHealth] = useState<'checking' | 'ready' | 'error'>('checking');
  const [runtimeMode, setRuntimeMode] = useState<RuntimeMode>();
  const [traceOrderId, setTraceOrderId] = useState(initialLocation.orderId ?? 1);

  useEffect(() => {
    api.runtimeMode()
      .then((mode) => {
        setRuntimeMode(mode);
        setHealth('ready');
      })
      .catch(() => api.dashboard().then(() => setHealth('ready')).catch(() => setHealth('error')));
  }, []);

  useEffect(() => {
    if (window.location.pathname === '/') {
      window.history.replaceState(null, '', terminalUrl(terminal, page, traceOrderId));
    }
    const onPopState = () => {
      const next = parseLocation();
      setTerminal(next.terminal);
      setPage(next.page);
      if (next.orderId) setTraceOrderId(next.orderId);
    };
    window.addEventListener('popstate', onPopState);
    return () => window.removeEventListener('popstate', onPopState);
  }, [page, terminal, traceOrderId]);

  const items = terminalPages[terminal].map((key) => ({
    key,
    icon: menuIcon[key],
    label: tr(lang, key),
  }));

  const navigate = (next: TerminalKey, nextPage: PageKey, orderId?: number) => {
    setTerminal(next);
    setPage(nextPage);
    if (orderId) setTraceOrderId(orderId);
    window.history.pushState(null, '', terminalUrl(next, nextPage, orderId));
  };

  const switchTerminal = (next: TerminalKey, nextPage?: PageKey) => {
    setTerminal(next);
    const targetPage = nextPage ?? defaultPage[next];
    setPage(targetPage);
    window.history.pushState(null, '', terminalUrl(next, targetPage, traceOrderId));
  };

  const updateTraceOrderId = (orderId: number) => {
    setTraceOrderId(orderId);
    if (terminal === 'database' && page === 'trace') {
      window.history.replaceState(null, '', terminalUrl('database', 'trace', orderId));
    }
  };

  return (
    <Layout className="app-shell">
      <Sider width={246} className="app-sider">
        <div className="brand">
          <DatabaseOutlined />
          <div>
            <strong>{terminal === 'business' ? tr(lang, 'businessTerminal') : tr(lang, 'databaseTerminal')}</strong>
            <span>{terminal === 'business' ? 'Order API 8080' : 'Proxy 3306 / MVCC Lab'}</span>
          </div>
        </div>
        <Menu selectedKeys={[page]} mode="inline" items={items} onClick={(e) => navigate(terminal, e.key as PageKey)} />
        <div className="side-status">
          <span>{tr(lang, 'connection')}</span>
          <Tag color={health === 'ready' ? 'green' : health === 'checking' ? 'blue' : 'red'}>
            {healthText(health, lang)}
          </Tag>
          {runtimeMode && <Tag color={runtimeMode.proxyMode ? 'purple' : 'blue'}>{runtimeMode.mode}</Tag>}
        </div>
      </Sider>
      <Layout>
        <Header className="topbar">
          <Input.Search className="global-search" placeholder={tr(lang, 'search')} allowClear />
          <div className="topbar-actions">
            <Segmented
              value={terminal}
              onChange={(v) => switchTerminal(v as TerminalKey)}
              options={[
                { label: tr(lang, 'businessTerminal'), value: 'business' },
                { label: tr(lang, 'databaseTerminal'), value: 'database' },
              ]}
            />
            <Radio.Group className="language-switch" value={lang} onChange={(e) => setLang(e.target.value)}>
              <Radio.Button value="zh">中文</Radio.Button>
              <Radio.Button value="en">EN</Radio.Button>
            </Radio.Group>
          </div>
        </Header>
        <Content>
          {runtimeMode?.warnings?.length ? (
            <Alert className="runtime-alert" type="warning" showIcon message={runtimeMode.warnings.join(' ')} />
          ) : null}
          {terminal === 'business' && page === 'dashboard' && <Dashboard lang={lang} openPage={(target) => navigate('business', target)} />}
          {terminal === 'business' && page === 'orders' && <Orders lang={lang} openTrace={(orderId) => navigate('database', 'trace', orderId)} />}
          {terminal === 'business' && page === 'fulfillment' && <Fulfillment lang={lang} />}
          {terminal === 'business' && page === 'exceptions' && <Exceptions lang={lang} />}
          {terminal === 'database' && page === 'lab' && <Lab lang={lang} />}
          {terminal === 'database' && page === 'trace' && <DatabaseTrace lang={lang} orderId={traceOrderId} setOrderId={updateTraceOrderId} backToOrders={() => navigate('business', 'orders', traceOrderId)} />}
          {terminal === 'database' && page === 'routePreview' && <RoutePreview lang={lang} />}
          {terminal === 'database' && page === 'proxy' && <ProxyPage lang={lang} />}
          {page === 'settings' && <Settings lang={lang} health={health} runtimeMode={runtimeMode} />}
        </Content>
      </Layout>
    </Layout>
  );
}

function Dashboard({ lang, openPage }: { lang: Lang; openPage: (p: PageKey) => void }) {
  const [data, setData] = useState<DashboardSummary>();
  const [loading, setLoading] = useState(false);
  const [demoLoading, setDemoLoading] = useState(false);
  const [error, setError] = useState<string>();

  const reload = async () => {
    setLoading(true);
    try {
      setData(await api.dashboard());
      setError(undefined);
    } catch (e) {
      const text = (e as Error).message;
      setError(text);
      message.error(text);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { void reload(); }, []);

  const loadDemo = async () => {
    setDemoLoading(true);
    setError(undefined);
    try {
      const loaded = await api.loadDemo();
      message.success(lang === 'zh' ? `已载入 ${loaded.orderNos.length} 条演示订单` : `${loaded.orderNos.length} demo orders loaded`);
      await reload();
    } catch (e) {
      const text = (e as Error).message;
      setError(text);
      message.error(text);
    } finally {
      setDemoLoading(false);
    }
  };

  const total = data?.ordersToday ?? 0;
  const paidRate = total ? Math.round(((data?.paidSuccess ?? 0) / total) * 100) : 0;
  const fulfillmentRate = total ? Math.round(((data?.fulfillmentTotal ?? 0) / total) * 100) : 0;
  const exceptionRate = total ? Math.round(((data?.openExceptions ?? 0) / total) * 100) : 0;

  return (
    <PageContainer title={tr(lang, 'dashboard')} extra={[
      <Button key="load" loading={demoLoading} onClick={loadDemo}>{tr(lang, 'loadDemo')}</Button>,
      <Button key="refresh" type="primary" loading={loading} onClick={reload}>{tr(lang, 'refresh')}</Button>,
    ]}>
      {error && (
        <Alert
          className="bottom-gap"
          type="error"
          showIcon
          message={lang === 'zh' ? '接口暂不可用' : 'API unavailable'}
          description={lang === 'zh' ? `请确认 order-api 已在 8080 端口运行。${error}` : error}
          action={<Button size="small" onClick={reload}>{tr(lang, 'refresh')}</Button>}
        />
      )}
      {data ? (
        <Space direction="vertical" size={16} className="full">
          <div className="metric-grid">
            <ProCard><Statistic title={lang === 'zh' ? '今日订单' : 'Orders'} value={data.ordersToday} /></ProCard>
            <ProCard><Statistic title={lang === 'zh' ? '支付成功率' : 'Payment Rate'} value={paidRate} suffix="%" /></ProCard>
            <ProCard><Statistic title={lang === 'zh' ? '履约任务' : 'Tasks'} value={data.fulfillmentTotal} /></ProCard>
            <ProCard><Statistic title={lang === 'zh' ? '开放异常' : 'Open Exceptions'} value={data.openExceptions} valueStyle={{ color: data.openExceptions ? '#dc2626' : undefined }} /></ProCard>
          </div>
          <div className="ops-grid">
            <ProCard title={lang === 'zh' ? '订单处理漏斗' : 'Order Processing Funnel'}>
              <Space direction="vertical" className="full" size={12}>
                <FunnelRow label={lang === 'zh' ? '创建订单' : 'Created'} value={100} text={`${data.ordersToday}`} />
                <FunnelRow label={lang === 'zh' ? '支付成功' : 'Paid'} value={paidRate} text={`${data.paidSuccess}`} />
                <FunnelRow label={lang === 'zh' ? '进入履约' : 'Fulfillment'} value={fulfillmentRate} text={`${data.fulfillmentTotal}`} />
                <FunnelRow label={lang === 'zh' ? '异常占比' : 'Exception'} value={exceptionRate} text={`${data.openExceptions}`} status="exception" />
              </Space>
            </ProCard>
            <ProCard title={lang === 'zh' ? '待处理队列' : 'Work Queue'} extra={<Button onClick={() => openPage('fulfillment')}>{lang === 'zh' ? '处理' : 'Open'}</Button>}>
              {data.workQueue.length === 0 ? <EmptyText lang={lang} /> : data.workQueue.map((item) => (
                <div className="queue-item" key={item.taskId}>
                  <div><strong>{item.taskNo}</strong><span>{item.orderNo}</span></div>
                  <Tag color="blue">{taskStatusText(item.status, lang)}</Tag>
                </div>
              ))}
            </ProCard>
            <ProCard title={lang === 'zh' ? '异常待办' : 'Exception Triage'} extra={<Button onClick={() => openPage('exceptions')}>{lang === 'zh' ? '进入异常中心' : 'Open'}</Button>}>
              {data.recentExceptions.length === 0 ? <EmptyText lang={lang} /> : data.recentExceptions.map((item) => (
                <div className="queue-item" key={item.id}>
                  <div><strong>{item.bizNo}</strong><span>{item.reasonCode}</span></div>
                  <Tag color="red">{item.bizType}</Tag>
                </div>
              ))}
            </ProCard>
            <ProCard title={lang === 'zh' ? '业务状态分布' : 'Business Status'}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label={lang === 'zh' ? '订单状态' : 'Order Status'}>
                  {data.statusDistribution.map((x) => <Tag key={x.status}>{orderStatusText(x.status, lang)} {x.count}</Tag>)}
                </Descriptions.Item>
                <Descriptions.Item label={lang === 'zh' ? '最近异常' : 'Recent Exceptions'}>
                  {data.recentExceptions.length === 0 ? <EmptyText lang={lang} /> : data.recentExceptions.map((x) => (
                    <Tag key={x.id} color="red">{x.bizNo}</Tag>
                  ))}
                </Descriptions.Item>
              </Descriptions>
            </ProCard>
          </div>
        </Space>
      ) : !error && (loading || demoLoading) ? <DashboardSkeleton lang={lang} /> : <Alert type="warning" message={tr(lang, 'empty')} />}
    </PageContainer>
  );
}

function DashboardSkeleton({ lang }: { lang: Lang }) {
  return (
    <Space direction="vertical" size={16} className="full">
      <div className="metric-grid">
        {[1, 2, 3, 4].map((item) => (
          <ProCard key={item}><Skeleton active paragraph={{ rows: 1 }} title={{ width: '60%' }} /></ProCard>
        ))}
      </div>
      <ProCard>
        <Skeleton active paragraph={{ rows: 5 }} title={{ width: '30%' }} />
        <Typography.Text type="secondary">{lang === 'zh' ? '正在加载订单数据' : 'Loading order data'}</Typography.Text>
      </ProCard>
    </Space>
  );
}

function Orders({ lang, openTrace }: { lang: Lang; openTrace: (orderId: number) => void }) {
  const [userId, setUserId] = useState(501);
  const [status, setStatus] = useState<number | undefined>();
  const [rows, setRows] = useState<OrderSummary[]>([]);
  const [selected, setSelected] = useState<OrderDetail>();
  const [loading, setLoading] = useState(false);
  const [cancelTarget, setCancelTarget] = useState<OrderSummary | OrderDetail>();
  const [cancelReason, setCancelReason] = useState(lang === 'zh' ? '运营确认取消，释放未支付库存' : 'Operator confirmed cancellation');
  const [cancelSubmitting, setCancelSubmitting] = useState(false);
  const [lastError, setLastError] = useState<string>();

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setRows((await api.orders(userId, status)).items);
      setLastError(undefined);
    } catch (e) {
      const text = formatApiError(e, lang);
      setLastError(text);
      message.error(text);
    } finally {
      setLoading(false);
    }
  }, [userId, status, lang]);

  useEffect(() => { void reload(); }, [reload]);

  const columns: ProColumns<OrderSummary>[] = [
    { title: 'Order No', dataIndex: 'orderNo', width: 220, ellipsis: true },
    { title: lang === 'zh' ? '状态' : 'Status', dataIndex: 'status', width: 96, render: (_, r) => <Tag color={r.status === 90 ? 'red' : 'blue'}>{orderStatusText(r.status, lang)}</Tag> },
    { title: lang === 'zh' ? '金额' : 'Amount', dataIndex: 'totalAmount', width: 80 },
    { title: lang === 'zh' ? '明细数' : 'Items', dataIndex: 'itemCount', width: 80 },
    { title: lang === 'zh' ? '创建时间' : 'Created At', dataIndex: 'createdAt', width: 190 },
    { title: lang === 'zh' ? '操作' : 'Action', valueType: 'option', width: 230, render: (_, r) => (
      <Space className="table-actions" wrap size={8}>
        <Button onClick={async () => {
          try {
            setSelected(await api.order(r.orderId, userId));
          } catch (e) {
            message.error(formatApiError(e, lang));
          }
        }}>{tr(lang, 'details')}</Button>
        <Button onClick={() => openTrace(r.orderId)}>{lang === 'zh' ? '数据库链路' : 'Trace'}</Button>
        {(r.status === 10 || r.status === 20) && <Button danger onClick={() => openCancelModal(r)}>{tr(lang, 'cancel')}</Button>}
      </Space>
    ) },
  ];

  const [cancelDetail, setCancelDetail] = useState<OrderDetail>();
  const [cancelConflict, setCancelConflict] = useState(false);

  // Load full order detail when opening cancel modal
  const openCancelModal = async (target: OrderSummary | OrderDetail) => {
    setCancelConflict(false);
    if ('items' in target) {
      setCancelDetail(target as OrderDetail);
    } else {
      try {
        const detail = await api.order(target.orderId, userId);
        setCancelDetail(detail);
      } catch {
        setCancelDetail(undefined);
      }
    }
    setCancelTarget(target);
    setCancelReason(target.status === 20
      ? (lang === 'zh' ? '已支付订单取消，进入退款或异常处理' : 'Paid order cancellation moves to refund handling')
      : (lang === 'zh' ? '待支付订单取消，释放锁定库存' : 'Pending order cancellation releases locked stock'));
  };

  const confirmCancel = async () => {
    if (!cancelTarget) return;
    if (!cancelReason.trim()) {
      message.warning(lang === 'zh' ? '请填写取消原因' : 'Cancellation reason is required');
      return;
    }
    setCancelSubmitting(true);
    setCancelConflict(false);
    try {
      await api.cancelOrder(cancelTarget.orderId, userId, cancelReason.trim());
      message.success(lang === 'zh' ? '订单已取消，库存已释放' : 'Order cancelled, inventory released');
      setCancelTarget(undefined);
      setCancelDetail(undefined);
      await reload();
      if (selected?.orderId === cancelTarget.orderId) {
        setSelected(await api.order(cancelTarget.orderId, userId));
      }
    } catch (e) {
      const msg = formatApiError(e, lang);
      if (msg.includes('ORDER_STATUS_CHANGED') || msg.includes('2PC_ABORTED')) {
        setCancelConflict(true);
      } else {
        message.error(msg);
      }
    } finally {
      setCancelSubmitting(false);
    }
  };

  return (
    <PageContainer title={tr(lang, 'orders')} extra={<Space><Input value={userId} onChange={(e) => setUserId(Number(e.target.value) || 0)} prefix="user_id" /><Button type="primary" onClick={reload}>{tr(lang, 'refresh')}</Button></Space>}>
      <Space direction="vertical" className="full" size={16}>
        {lastError && <Alert type="error" showIcon message={lang === 'zh' ? '订单列表加载失败' : 'Failed to load orders'} description={lastError} action={<Button size="small" onClick={reload}>{tr(lang, 'refresh')}</Button>} />}
        <ProCard>
          <Space wrap>
            <Segmented
              value={status ?? 'all'}
              onChange={(v) => setStatus(v === 'all' ? undefined : Number(v))}
              options={[
                { label: lang === 'zh' ? '全部' : 'All', value: 'all' },
                { label: orderStatusText(10, lang), value: 10 },
                { label: orderStatusText(40, lang), value: 40 },
                { label: orderStatusText(50, lang), value: 50 },
                { label: orderStatusText(90, lang), value: 90 },
              ]}
            />
            <Tag color="blue">user_id {userId}</Tag>
            <Tag>{lang === 'zh' ? `当前 ${rows.length} 条` : `${rows.length} rows`}</Tag>
          </Space>
        </ProCard>
        <ProTable rowKey="orderId" search={false} loading={loading} columns={columns} dataSource={rows} pagination={false} scroll={{ x: 900 }} />
      </Space>
      <OrderDrawer lang={lang} order={selected} onClose={() => setSelected(undefined)} onTrace={openTrace} onCancel={(order) => setCancelTarget(order)} />
      <Modal
        title={lang === 'zh' ? '确认取消订单' : 'Confirm Cancellation'}
        open={!!cancelTarget}
        confirmLoading={cancelSubmitting}
        okText={cancelConflict ? (lang === 'zh' ? '刷新后重试' : 'Refresh & Retry') : tr(lang, 'cancel')}
        okButtonProps={{ danger: !cancelConflict }}
        onOk={cancelConflict ? (() => { void reload(); setCancelTarget(undefined); setCancelDetail(undefined); setCancelConflict(false); }) : confirmCancel}
        onCancel={() => { setCancelTarget(undefined); setCancelDetail(undefined); setCancelConflict(false); }}
        width={560}
      >
        <Space direction="vertical" className="full" size={12}>
          {cancelConflict ? (
            <Alert
              type="error"
              showIcon
              message={lang === 'zh' ? '订单状态已变更' : 'Order status changed'}
              description={lang === 'zh'
                ? '该订单已被其他操作修改（可能已支付或已取消）。请刷新页面查看最新状态后重新操作。'
                : 'This order was modified by another operation (may have been paid or cancelled). Please refresh to see the latest status.'}
            />
          ) : (
            <Alert
              type="warning"
              showIcon
              message={lang === 'zh' ? '取消影响确认' : 'Cancellation impact'}
              description={cancelTarget?.status === 20
                ? (lang === 'zh' ? '已支付订单不会直接发货，系统会进入退款中或异常处理路径。' : 'Paid orders won\'t be shipped and move to refund or exception handling.')
                : (lang === 'zh' ? '待支付订单会释放锁定库存并写入状态日志与 outbox。' : 'Pending orders release locked stock and write status log/outbox.')}
            />
          )}
          <Descriptions column={1} size="small">
            <Descriptions.Item label="Order No">{cancelTarget?.orderNo}</Descriptions.Item>
            <Descriptions.Item label={lang === 'zh' ? '当前状态' : 'Current Status'}>
              <Tag color={cancelTarget?.status === 20 ? 'blue' : 'orange'}>
                {cancelTarget ? orderStatusText(cancelTarget.status, lang) : '-'}
              </Tag>
            </Descriptions.Item>
            {cancelTarget && <Descriptions.Item label={lang === 'zh' ? '金额' : 'Amount'}>¥{cancelTarget.totalAmount}</Descriptions.Item>}
          </Descriptions>

          {/* Stock items to be released */}
          {cancelDetail?.items && cancelDetail.items.length > 0 && !cancelConflict && (
            <ProCard size="small" title={lang === 'zh' ? '释放库存明细' : 'Inventory to Release'}>
              <Space direction="vertical" className="full" size={4}>
                {cancelDetail.items.map((item) => (
                  <div key={item.productId} className="queue-item">
                    <span>{item.productName || item.productSku}</span>
                    <Tag color="green">+{item.quantity}</Tag>
                  </div>
                ))}
              </Space>
              <Typography.Text type="secondary" className="top-gap">
                {lang === 'zh'
                  ? `共 ${cancelDetail.items.reduce((s, i) => s + i.quantity, 0)} 件商品库存将被释放`
                  : `${cancelDetail.items.reduce((s, i) => s + i.quantity, 0)} items will be released back to inventory`}
              </Typography.Text>
            </ProCard>
          )}

          {!cancelConflict && (
            <Input.TextArea
              rows={3}
              maxLength={256}
              showCount
              value={cancelReason}
              onChange={(e) => setCancelReason(e.target.value)}
              placeholder={lang === 'zh' ? '取消原因（必填）' : 'Cancellation reason (required)'}
            />
          )}
        </Space>
      </Modal>
    </PageContainer>
  );
}

function OrderDrawer({ lang, order, onClose, onTrace, onCancel }: { lang: Lang; order?: OrderDetail; onClose: () => void; onTrace: (orderId: number) => void; onCancel: (order: OrderDetail) => void }) {
  return (
    <Drawer width={760} open={!!order} onClose={onClose} title={order?.orderNo}>
      {order && (
        <Space direction="vertical" size={16} className="full">
          <ProCard>
            <Space>
              <Button onClick={() => { onTrace(order.orderId); onClose(); }}>{lang === 'zh' ? '查看数据库链路' : 'Open Database Trace'}</Button>
              {(order.status === 10 || order.status === 20) && <Button danger onClick={() => onCancel(order)}>{tr(lang, 'cancel')}</Button>}
            </Space>
          </ProCard>
          <Tabs items={[
            { key: 'timeline', label: lang === 'zh' ? '时间线' : 'Timeline', children: <Timeline items={order.statusTimeline.map((x) => ({ children: `${orderStatusText(x.toStatus, lang)} - ${x.reason}` }))} /> },
            { key: 'items', label: lang === 'zh' ? '明细' : 'Items', children: <ProTable search={false} pagination={false} rowKey="productId" dataSource={order.items} columns={[{ title: 'SKU', dataIndex: 'productSku' }, { title: lang === 'zh' ? '商品' : 'Product', dataIndex: 'productName' }, { title: lang === 'zh' ? '数量' : 'Qty', dataIndex: 'quantity' }, { title: lang === 'zh' ? '金额' : 'Amount', dataIndex: 'lineAmount' }]} /> },
            { key: 'payment', label: lang === 'zh' ? '支付' : 'Payment', children: <PaymentSummary lang={lang} order={order} /> },
            { key: 'fulfillment', label: lang === 'zh' ? '履约' : 'Fulfillment', children: <FulfillmentSummary lang={lang} order={order} /> },
          ]} />
        </Space>
      )}
    </Drawer>
  );
}

function Fulfillment({ lang }: { lang: Lang }) {
  const [rows, setRows] = useState<TaskItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [actionKey, setActionKey] = useState<string>();
  const [shipTarget, setShipTarget] = useState<TaskItem>();
  const [carrier, setCarrier] = useState('mock_express');
  const [trackingNo, setTrackingNo] = useState('');
  const [error, setError] = useState<string>();
  const [shipError, setShipError] = useState<string>();

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setRows((await api.tasks()).items);
      setError(undefined);
    } catch (e) {
      const text = formatApiError(e, lang);
      setError(text);
      message.error(text);
    } finally {
      setLoading(false);
    }
  }, [lang]);
  useEffect(() => { void reload(); }, [reload]);

  const runTaskAction = async (key: string, action: () => Promise<void>, successText: string) => {
    setActionKey(key);
    try {
      await action();
      message.success(successText);
      await reload();
    } catch (e) {
      message.error(formatApiError(e, lang));
    } finally {
      setActionKey(undefined);
    }
  };

  const openShipModal = (task: TaskItem) => {
    setShipTarget(task);
    setCarrier('mock_express');
    setTrackingNo(`EXP${Date.now()}`);
  };

  const confirmShip = async () => {
    if (!shipTarget) return;
    if (!carrier.trim() || !trackingNo.trim()) {
      message.warning(lang === 'zh' ? '承运商和运单号必填' : 'Carrier and tracking number are required');
      return;
    }
    setShipError(undefined);
    try {
      await api.ship(shipTarget.taskId, carrier.trim(), trackingNo.trim(), shipTarget.assigneeId ?? 2001);
      message.success(lang === 'zh' ? '发货完成' : 'Shipment completed');
      setShipTarget(undefined);
      await reload();
    } catch (e) {
      setShipError(formatApiError(e, lang));
    }
  };

  return (
    <PageContainer title={tr(lang, 'fulfillment')} extra={<Button onClick={reload}>{tr(lang, 'refresh')}</Button>}>
      <Alert className="bottom-gap" type="info" showIcon message={lang === 'zh' ? '如果任务已被他人领取，刷新后再继续处理。' : 'Refresh before retrying if another operator has claimed the task.'} />
      {error && <Alert className="bottom-gap" type="error" showIcon message={lang === 'zh' ? '履约任务加载失败' : 'Failed to load fulfillment tasks'} description={error} action={<Button size="small" onClick={reload}>{tr(lang, 'refresh')}</Button>} />}
      <div className="kanban">
        {[10, 20, 30, 90].map((status) => (
          <ProCard key={status} title={taskStatusText(status, lang)} extra={<Tag>{rows.filter((x) => x.status === status).length}</Tag>}>
            {loading && rows.length === 0 && <Skeleton active paragraph={{ rows: 3 }} />}
            {!loading && rows.filter((x) => x.status === status).length === 0 && <EmptyText lang={lang} />}
            {rows.filter((x) => x.status === status).map((task) => (
              <div className="task-card" key={task.taskId}>
                <div className="task-card-main"><strong>{task.taskNo}</strong><span>{task.orderNo}</span><span>v{task.version} / user {task.userId}</span></div>
                <Space className="task-actions" wrap size={8}>
                  {task.status === 10 && <Button type="primary" loading={actionKey === `claim-${task.taskId}`} onClick={() => runTaskAction(`claim-${task.taskId}`, () => api.claim(task.taskId, task.version), lang === 'zh' ? '任务已领取' : 'Task claimed')}>{tr(lang, 'claim')}</Button>}
                  {task.status === 20 && <Button loading={actionKey === `pick-${task.taskId}`} onClick={() => runTaskAction(`pick-${task.taskId}`, () => api.pick(task.taskId, task.assigneeId ?? 2001), lang === 'zh' ? '拣货完成' : 'Picked')}>{tr(lang, 'pick')}</Button>}
                  {(task.status === 20 || task.status === 30) && <Button loading={actionKey === `ship-${task.taskId}`} onClick={() => openShipModal(task)}>{tr(lang, 'ship')}</Button>}
                </Space>
              </div>
            ))}
          </ProCard>
        ))}
      </div>
      <Modal
        title={lang === 'zh' ? '确认发货' : 'Confirm Shipment'}
        open={!!shipTarget}
        okText={shipError ? (lang === 'zh' ? '关闭并刷新' : 'Close & Refresh') : tr(lang, 'ship')}
        onOk={shipError ? (() => { setShipTarget(undefined); setShipError(undefined); void reload(); }) : confirmShip}
        onCancel={() => { setShipTarget(undefined); setShipError(undefined); }}
      >
        <Space direction="vertical" className="full">
          {shipError ? (
            <Alert
              type="error"
              showIcon
              message={lang === 'zh' ? '发货失败' : 'Shipment failed'}
              description={shipError}
            />
          ) : (
            <Alert
              type="warning"
              showIcon
              message={lang === 'zh' ? '发货会推进订单状态' : 'Shipment advances order status'}
              description={lang === 'zh' ? '校验任务归属、任务状态和订单状态，成功后写入 shipment、库存流水、状态日志和 outbox。' : 'Validates assignment, task status and order status; writes shipment, inventory journal, status log and outbox.'}
            />
          )}
          <Descriptions column={1} size="small">
            <Descriptions.Item label={lang === 'zh' ? '任务号' : 'Task No'}>{shipTarget?.taskNo}</Descriptions.Item>
            <Descriptions.Item label="Order No">{shipTarget?.orderNo}</Descriptions.Item>
            <Descriptions.Item label={lang === 'zh' ? '任务状态' : 'Task Status'}>{shipTarget ? taskStatusText(shipTarget.status, lang) : '-'}</Descriptions.Item>
            <Descriptions.Item label={lang === 'zh' ? '操作人' : 'Operator'}>{shipTarget?.assigneeId ?? 2001}</Descriptions.Item>
          </Descriptions>
          <Input maxLength={64} value={carrier} onChange={(e) => setCarrier(e.target.value)} prefix={lang === 'zh' ? '承运商' : 'Carrier'} />
          <Input maxLength={64} value={trackingNo} onChange={(e) => setTrackingNo(e.target.value)} prefix={lang === 'zh' ? '运单号' : 'Tracking No'} />
        </Space>
      </Modal>
    </PageContainer>
  );
}

function Exceptions({ lang }: { lang: Lang }) {
  const [rows, setRows] = useState<ExceptionItem[]>([]);
  const [bizType, setBizType] = useState<string>('all');
  const [selected, setSelected] = useState<ExceptionItem>();
  const [resolution, setResolution] = useState('');
  const [loading, setLoading] = useState(false);
  const [resolving, setResolving] = useState(false);
  const [error, setError] = useState<string>();

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setRows((await api.exceptions()).items);
      setError(undefined);
    } catch (e) {
      const text = formatApiError(e, lang);
      setError(text);
      message.error(text);
    } finally {
      setLoading(false);
    }
  }, [lang]);
  useEffect(() => { void reload(); }, [reload]);
  const filtered = bizType === 'all' ? rows : rows.filter((x) => x.bizType === bizType);

  const openDetail = async (id: number) => {
    try {
      const detail = await api.exceptionDetail(id);
      setSelected(detail);
      setResolution(suggestedAction(detail.reasonCode, lang));
    } catch (e) {
      message.error(formatApiError(e, lang));
    }
  };

  const resolveSelected = async () => {
    if (!selected) return;
    if (!resolution.trim()) {
      message.warning(lang === 'zh' ? '请填写处理说明' : 'Resolution note is required');
      return;
    }
    setResolving(true);
    try {
      await api.resolve(selected.id, resolution.trim());
      message.success(lang === 'zh' ? '异常已解决，列表已刷新' : 'Exception resolved');
      setSelected(undefined);
      await reload();
    } catch (e) {
      message.error(formatApiError(e, lang));
    } finally {
      setResolving(false);
    }
  };

  return (
    <PageContainer title={tr(lang, 'exceptions')} extra={<Button onClick={reload}>{tr(lang, 'refresh')}</Button>}>
      <Space direction="vertical" className="full" size={16}>
        {error && <Alert type="error" showIcon message={lang === 'zh' ? '异常列表加载失败' : 'Failed to load exceptions'} description={error} action={<Button size="small" onClick={reload}>{tr(lang, 'refresh')}</Button>} />}
        <ProCard>
          <Space wrap>
            <Segmented value={bizType} onChange={(v) => setBizType(String(v))} options={[
              { label: lang === 'zh' ? '全部' : 'All', value: 'all' },
              { label: 'PAYMENT', value: 'PAYMENT' },
              { label: 'ROUTING', value: 'ROUTING' },
              { label: 'OUTBOX', value: 'OUTBOX' },
            ]} />
            <Tag color="red">{lang === 'zh' ? `待处理 ${rows.length}` : `${rows.length} open`}</Tag>
          </Space>
        </ProCard>
        <ProTable rowKey="id" search={false} dataSource={filtered} columns={[
        { title: lang === 'zh' ? '业务号' : 'Biz No', dataIndex: 'bizNo' },
        { title: lang === 'zh' ? '类型' : 'Type', dataIndex: 'bizType' },
        { title: lang === 'zh' ? '原因' : 'Reason', dataIndex: 'reasonCode' },
        { title: lang === 'zh' ? '建议动作' : 'Suggested Action', render: (_, r) => suggestedAction(r.reasonCode, lang) },
        { title: lang === 'zh' ? '状态' : 'Status', dataIndex: 'status', render: (_, r) => <Tag color={r.status === 10 ? 'red' : 'green'}>{exceptionStatusText(r.status, lang)}</Tag> },
        { title: lang === 'zh' ? '操作' : 'Action', valueType: 'option', render: (_, r) => <Button onClick={() => openDetail(r.id)}>{tr(lang, 'details')}</Button> },
        ]} pagination={false} loading={loading} />
      </Space>
      <Drawer
        width={720}
        open={!!selected}
        onClose={() => setSelected(undefined)}
        title={selected ? `${selected.bizType} / ${selected.bizNo}` : undefined}
        extra={selected?.status === 10 ? <Button type="primary" loading={resolving} onClick={resolveSelected}>{tr(lang, 'resolve')}</Button> : undefined}
      >
        {selected && (
          <Space direction="vertical" className="full" size={16}>
            <Alert
              type={selected.status === 10 ? 'warning' : 'success'}
              showIcon
              message={selected.reasonCode}
              description={suggestedAction(selected.reasonCode, lang)}
            />
            <Descriptions bordered column={1} size="small">
              <Descriptions.Item label={lang === 'zh' ? '业务类型' : 'Biz Type'}>{selected.bizType}</Descriptions.Item>
              <Descriptions.Item label={lang === 'zh' ? '业务号' : 'Biz No'}>{selected.bizNo}</Descriptions.Item>
              <Descriptions.Item label={lang === 'zh' ? '状态' : 'Status'}>{exceptionStatusText(selected.status, lang)}</Descriptions.Item>
              <Descriptions.Item label={lang === 'zh' ? '创建时间' : 'Created At'}>{selected.createdAt}</Descriptions.Item>
            </Descriptions>
            <ProCard title={lang === 'zh' ? '异常证据' : 'Exception Evidence'}>
              <pre>{prettyJson(selected.detail)}</pre>
            </ProCard>
            {selected.status === 10 ? (
              <ProCard title={lang === 'zh' ? '处理说明' : 'Resolution Note'}>
                <Input.TextArea
                  rows={5}
                  maxLength={512}
                  showCount
                  value={resolution}
                  onChange={(e) => setResolution(e.target.value)}
                />
              </ProCard>
            ) : (
              <Alert type="info" message={lang === 'zh' ? '该异常已处理，不能重复解决。' : 'This exception is already handled and cannot be resolved again.'} />
            )}
          </Space>
        )}
      </Drawer>
    </PageContainer>
  );
}

function Lab({ lang }: { lang: Lang }) {
  const [scenario, setScenario] = useState('mvcc-rc-rr');
  const [result, setResult] = useState<LabRunResult>();
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string>();
  // Custom scenario state
  const [customTxns, setCustomTxns] = useState<Array<{ alias: string; isolationLevel: string }>>([
    { alias: 't1', isolationLevel: 'REPEATABLE_READ' },
    { alias: 't2', isolationLevel: 'READ_COMMITTED' },
  ]);
  const [customSteps, setCustomSteps] = useState<Array<{ txnAlias: string; action: string; key: string; value: string }>>([
    { txnAlias: 't1', action: 'PUT', key: 'k1', value: 'hello' },
    { txnAlias: 't1', action: 'COMMIT', key: '', value: '' },
    { txnAlias: 't2', action: 'GET', key: 'k1', value: '' },
    { txnAlias: 't2', action: 'COMMIT', key: '', value: '' },
  ]);
  const [customAssertions, setCustomAssertions] = useState('T2 should read "hello"');

  const run = async () => {
    setRunning(true);
    try {
      if (scenario === 'custom') {
        const body = JSON.stringify({
          transactions: customTxns,
          steps: customSteps.filter(s => s.txnAlias && s.action),
          assertions: customAssertions.split('\n').filter(a => a.trim()),
        });
        setResult(await api.runScenario('custom', body));
      } else {
        setResult(await api.runScenario(scenario));
      }
      setError(undefined);
    } catch (e) {
      const text = formatApiError(e, lang);
      setError(text);
      message.error(text);
    } finally {
      setRunning(false);
    }
  };

  const scenarioOptions = [
    { value: 'create-order', label: lang === 'zh' ? '创建订单：库存锁定、幂等、outbox、路由表' : 'Create Order' },
    { value: 'payment-callback', label: lang === 'zh' ? '支付回调：验签、金额校验、异常工单' : 'Payment Callback' },
    { value: 'mvcc-rc-rr', label: lang === 'zh' ? 'MVCC：RC / RR Read View 对比' : 'MVCC RC vs RR' },
    { value: 'mvcc-write-conflict', label: lang === 'zh' ? 'MVCC：写写冲突保护最新版本' : 'MVCC Write Conflict' },
    { value: 'mvcc-rollback', label: lang === 'zh' ? 'MVCC：ROLLBACK 与版本链恢复' : 'MVCC Rollback & Chain Restore' },
    { value: 'mvcc-delete', label: lang === 'zh' ? 'MVCC：DELETE 可见性（RC vs RR）' : 'MVCC Delete Visibility' },
    { value: 'custom', label: lang === 'zh' ? '自定义实验（构建你自己的 MVCC 场景）' : 'Custom Experiment' },
  ];

  return (
    <PageContainer title={tr(lang, 'lab')} extra={<Button type="primary" loading={running} onClick={run}>{tr(lang, 'run')}</Button>}>
      <Space direction="vertical" size={16} className="full">
        <div className="db-status-grid">
          <ProCard title="Proxy 3306"><Tag color="green">{lang === 'zh' ? '运行中' : 'running'}</Tag><p>{lang === 'zh' ? '查询协议 / 路由策略' : 'COM_QUERY / route policy'}</p></ProCard>
          <ProCard title="API 8080"><Tag color="green">{lang === 'zh' ? '就绪' : 'ready'}</Tag><p>{lang === 'zh' ? '订单接口 / 控制台接口' : 'order-api / console API'}</p></ProCard>
          <ProCard title="PRIMARY"><Tag color="blue">{lang === 'zh' ? '元数据' : 'metadata'}</Tag><p>products / outbox / idempotency / order_route</p></ProCard>
          <ProCard title="shard_0~3"><Tag color="purple">user_id % 4</Tag><p>orders / payments / fulfillment</p></ProCard>
        </div>
        <div className={scenario === 'custom' ? 'full' : 'two-col'}>
          <ProCard title={lang === 'zh' ? '场景选择' : 'Scenario'}>
            <Space direction="vertical" className="full">
              <Select value={scenario} className="full" onChange={(v) => setScenario(v)} options={scenarioOptions} />
            </Space>
          </ProCard>
          {scenario !== 'custom' && (
            <ProCard title={lang === 'zh' ? '接口契约' : 'API Contract'}>
              <Descriptions column={1} size="small">
                <Descriptions.Item label={lang === 'zh' ? '运行' : 'Run'}><code>POST /api/lab/scenarios/{'{scenario}'}/run</code></Descriptions.Item>
                <Descriptions.Item label={lang === 'zh' ? '返回' : 'Return'}><code>steps, mvccSteps, mvccChains, readViews, assertions, errors</code></Descriptions.Item>
                <Descriptions.Item label={lang === 'zh' ? '状态' : 'Status'}><Tag color="green">{lang === 'zh' ? 'MVCC 场景 + 自定义实验' : 'MVCC scenarios + custom experiments'}</Tag></Descriptions.Item>
              </Descriptions>
            </ProCard>
          )}
        </div>

        {/* Custom Scenario Builder */}
        {scenario === 'custom' && (
          <ProCard title={lang === 'zh' ? '自定义实验定义' : 'Custom Experiment Definition'}>
            <Space direction="vertical" className="full" size={12}>
              <ProCard size="small" title={lang === 'zh' ? '事务定义 (max 4)' : 'Transactions (max 4)'}>
                {customTxns.map((txn, i) => (
                  <Space key={i} className="bottom-gap-small" wrap>
                    <Input placeholder="alias" value={txn.alias} style={{ width: 80 }}
                      onChange={(e) => { const n = [...customTxns]; n[i] = { ...n[i], alias: e.target.value }; setCustomTxns(n); }} />
                    <Select value={txn.isolationLevel} style={{ width: 180 }}
                      onChange={(v) => { const n = [...customTxns]; n[i] = { ...n[i], isolationLevel: v }; setCustomTxns(n); }}
                      options={[{ value: 'READ_COMMITTED', label: 'READ_COMMITTED (RC)' }, { value: 'REPEATABLE_READ', label: 'REPEATABLE_READ (RR)' }]} />
                    <Button size="small" danger onClick={() => setCustomTxns(customTxns.filter((_, j) => j !== i))}>×</Button>
                  </Space>
                ))}
                <Button size="small" onClick={() => setCustomTxns([...customTxns, { alias: `t${customTxns.length + 1}`, isolationLevel: 'READ_COMMITTED' }])}
                  disabled={customTxns.length >= 4}>
                  + {lang === 'zh' ? '添加事务' : 'Add Transaction'}
                </Button>
              </ProCard>

              <ProCard size="small" title={lang === 'zh' ? '步骤定义 (max 20)' : 'Steps (max 20)'}>
                {customSteps.map((step, i) => (
                  <Space key={i} className="bottom-gap-small" wrap>
                    <Input placeholder="txn" value={step.txnAlias} style={{ width: 70 }}
                      onChange={(e) => { const n = [...customSteps]; n[i] = { ...n[i], txnAlias: e.target.value }; setCustomSteps(n); }} />
                    <Select value={step.action} style={{ width: 110 }}
                      onChange={(v) => { const n = [...customSteps]; n[i] = { ...n[i], action: v }; setCustomSteps(n); }}
                      options={['PUT', 'GET', 'DELETE', 'COMMIT', 'ROLLBACK'].map(a => ({ value: a, label: a }))} />
                    {['PUT', 'GET', 'DELETE'].includes(step.action) && (
                      <Input placeholder="key" value={step.key} style={{ width: 120 }}
                        onChange={(e) => { const n = [...customSteps]; n[i] = { ...n[i], key: e.target.value }; setCustomSteps(n); }} />
                    )}
                    {step.action === 'PUT' && (
                      <Input placeholder="value" value={step.value} style={{ width: 100 }}
                        onChange={(e) => { const n = [...customSteps]; n[i] = { ...n[i], value: e.target.value }; setCustomSteps(n); }} />
                    )}
                    <Button size="small" danger onClick={() => setCustomSteps(customSteps.filter((_, j) => j !== i))}>×</Button>
                  </Space>
                ))}
                <Button size="small" onClick={() => setCustomSteps([...customSteps, { txnAlias: '', action: 'GET', key: '', value: '' }])}
                  disabled={customSteps.length >= 20}>
                  + {lang === 'zh' ? '添加步骤' : 'Add Step'}
                </Button>
              </ProCard>

              <ProCard size="small" title={lang === 'zh' ? '断言（每行一个）' : 'Assertions (one per line)'}>
                <Input.TextArea rows={3} value={customAssertions}
                  onChange={(e) => setCustomAssertions(e.target.value)}
                  placeholder={lang === 'zh' ? 'T2 should read "hello"\nT3 should get WriteConflictException' : 'T2 should read "hello"\nT3 should get WriteConflictException'} />
              </ProCard>
            </Space>
          </ProCard>
        )}

        {error && <Alert type="error" showIcon message={lang === 'zh' ? '实验运行失败' : 'Lab scenario failed'} description={error} />}
        {result && <LabResult lang={lang} result={result} />}
      </Space>
    </PageContainer>
  );
}

function LabResult({ lang, result }: { lang: Lang; result: LabRunResult }) {
  const mvccRows = (result.mvccSteps ?? []).map((step, i) => ({ ...step, rowKey: `${i}-${step.operation}` }));
  const hasReadView = mvccRows.some(r => r.readView);
  return (
    <Space direction="vertical" className="full" size={16}>
      <div className="metric-grid">
        <ProCard><Statistic title={lang === 'zh' ? '步骤数' : 'Steps'} value={result.steps.length} /></ProCard>
        <ProCard><Statistic title={lang === 'zh' ? '版本链' : 'Version Chains'} value={Object.keys(result.mvccChains ?? {}).length} /></ProCard>
        <ProCard><Statistic title={lang === 'zh' ? '断言' : 'Assertions'} value={(result.assertions ?? []).length} /></ProCard>
        <ProCard><Statistic title={lang === 'zh' ? '错误位置' : 'Errors'} value={(result.errors ?? []).length} valueStyle={{ color: result.errors?.length ? '#dc2626' : undefined }} /></ProCard>
      </div>
      <Tabs items={[
        {
          key: 'timeline',
          label: lang === 'zh' ? '步骤解释' : 'Step Explanation',
          children: <Timeline items={result.steps.map((x) => ({ children: x }))} />,
        },
        {
          key: 'mvcc',
          label: lang === 'zh' ? 'MVCC 细节' : 'MVCC Details',
          children: mvccRows.length ? (
            <ProTable rowKey="rowKey" search={false} pagination={false} dataSource={mvccRows} columns={[
              { title: '#', dataIndex: 'sequence', width: 48 },
              { title: 'Txn', dataIndex: 'txnId', width: 64, render: (_, r) => <Tag>T{r.txnId}</Tag> },
              { title: lang === 'zh' ? '动作' : 'Op', dataIndex: 'operation', width: 80, render: (_, r) => {
                const colors: Record<string, string> = { BEGIN: 'blue', PUT: 'orange', GET: 'green', DELETE: 'red', COMMIT: 'purple', ROLLBACK: 'magenta', ERROR: 'red' };
                return <Tag color={colors[r.operation] || 'default'}>{r.operation}</Tag>;
              }},
              { title: 'Key', dataIndex: 'key', width: 100, ellipsis: true },
              { title: lang === 'zh' ? '值' : 'Value', dataIndex: 'value', width: 100, ellipsis: true },
              { title: lang === 'zh' ? '解释' : 'Why', dataIndex: 'explanation', ellipsis: true },
            ]} />
          ) : <Alert message={lang === 'zh' ? '无步骤数据' : 'No step data'} type="info" />,
        },
        {
          key: 'readview',
          label: 'Read View',
          children: hasReadView ? (
            <ProTable rowKey="sequence" search={false} pagination={false}
              dataSource={mvccRows.filter(r => r.readView).map(r => ({
                sequence: r.sequence,
                txnId: `T${r.txnId}`,
                operation: r.operation,
                key: r.key || '-',
                isolation: r.readView!.isolationLevel,
                creator: `T${r.readView!.creatorTxnId}`,
                low: r.readView!.lowWatermark,
                high: r.readView!.highWatermark,
                activeCount: r.readView!.activeTxnIds.length,
                activeTxns: r.readView!.activeTxnIds.join(', '),
                detail: r.detail,
              }))}
              columns={[
                { title: '#', dataIndex: 'sequence', width: 40 },
                { title: 'Txn', dataIndex: 'txnId', width: 56 },
                { title: lang === 'zh' ? '隔离级别' : 'Isolation', dataIndex: 'isolation', width: 56, render: (_, r) => <Tag color={r.isolation === 'REPEATABLE_READ' ? 'purple' : 'blue'}>{r.isolation === 'REPEATABLE_READ' ? 'RR' : 'RC'}</Tag> },
                { title: 'Creator', dataIndex: 'creator', width: 60 },
                { title: lang === 'zh' ? '下限' : 'Low', dataIndex: 'low', width: 52 },
                { title: lang === 'zh' ? '上限' : 'High', dataIndex: 'high', width: 52 },
                { title: lang === 'zh' ? '活跃数' : 'Active#', dataIndex: 'activeCount', width: 60 },
                { title: lang === 'zh' ? '活跃事务' : 'Active Txns', dataIndex: 'activeTxns', width: 180, ellipsis: true },
              ]}
            />
          ) : (
            <Space direction="vertical" className="full">
              {(result.readViews ?? []).map((item) => <Alert key={item} type="info" message={item} />)}
            </Space>
          ),
        },
        {
          key: 'assertions',
          label: lang === 'zh' ? '断言 & 错误' : 'Assertions & Errors',
          children: (
            <Space direction="vertical" className="full">
              {(result.assertions ?? []).map((item) => <Alert key={item} type="success" message={item} />)}
              {(result.errors ?? []).map((item) => <Alert key={item} type="error" message={item} />)}
              {!result.assertions?.length && !result.errors?.length && <Alert type="info" message={lang === 'zh' ? '无断言或错误' : 'No assertions or errors'} />}
            </Space>
          ),
        },
        {
          key: 'chains',
          label: lang === 'zh' ? '版本链' : 'Version Chains',
          children: <pre className="trace-pre">{Object.entries(result.mvccChains ?? {}).map(([key, value]) => `${key}\n${value}`).join('\n\n') || (lang === 'zh' ? '无版本链数据' : 'No version chain data')}</pre>,
        },
      ]} />
    </Space>
  );
}

function DatabaseTrace({ lang, orderId, setOrderId, backToOrders }: { lang: Lang; orderId: number; setOrderId: (id: number) => void; backToOrders: () => void }) {
  const [trace, setTrace] = useState<OrderTrace>();
  const [decisions, setDecisions] = useState<ProxyDecisionsResult>();
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [t, d] = await Promise.all([
        api.trace(orderId),
        api.proxyDecisions(20),
      ]);
      setTrace(t);
      setDecisions(d);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => { void load(); }, [load]);

  return (
    <PageContainer title={tr(lang, 'trace')} extra={[
      <Button key="back" onClick={backToOrders}>{lang === 'zh' ? '返回订单终端' : 'Back to Orders'}</Button>,
      <Button key="query" type="primary" loading={loading} onClick={load}>{lang === 'zh' ? '查询链路' : 'Query Trace'}</Button>,
    ]}>
      <Space direction="vertical" size={16} className="full">
        <div className="two-col">
          <ProCard title={lang === 'zh' ? '追踪入口' : 'Trace Entry'}>
            <Space direction="vertical" className="full">
              <Space.Compact className="full">
                <Input value={orderId} onChange={(e) => setOrderId(Number(e.target.value) || 0)} prefix="order_id" />
                <Button type="primary" loading={loading} onClick={load}>{lang === 'zh' ? '查询' : 'Query'}</Button>
              </Space.Compact>
            </Space>
          </ProCard>
          <ProCard title={lang === 'zh' ? '证据覆盖' : 'Evidence Coverage'}>
            <div className="evidence-grid">
              <Tag color={trace?.routeTable?.length ? 'green' : 'default'}>Route Table</Tag>
              <Tag color={trace?.idempotency?.length ? 'green' : 'default'}>Idempotency</Tag>
              <Tag color={trace?.outbox?.length ? 'green' : 'default'}>Outbox</Tag>
              <Tag color={trace?.timeline?.length ? 'green' : 'default'}>Timeline</Tag>
              {decisions?.decisions?.length ? <Tag color="green">Proxy Log</Tag> : <Tag>Proxy Log</Tag>}
            </div>
          </ProCard>
        </div>
        {trace?.order && <TraceSummary trace={trace} lang={lang} />}
        <Tabs items={[
          {
            key: 'overview', label: lang === 'zh' ? '链路总览' : 'Overview',
            children: <TraceTimelineView trace={trace} decisions={decisions} lang={lang} />,
          },
          {
            key: 'route', label: lang === 'zh' ? '路由表' : 'Route Table',
            children: <ProTable rowKey="orderNo" search={false} pagination={false}
              dataSource={(trace?.routeTable ?? []).map((r) => r as Record<string, unknown>)}
              columns={[
                { title: 'order_no', dataIndex: 'orderNo' }, { title: 'payment_no', dataIndex: 'paymentNo' },
                { title: 'user_id', dataIndex: 'userId' }, { title: 'biz_type', dataIndex: 'bizType' },
              ]}
            />,
          },
          {
            key: 'idempotency', label: lang === 'zh' ? '幂等记录' : 'Idempotency',
            children: <ProTable rowKey="key" search={false} pagination={false}
              dataSource={(trace?.idempotency ?? []).map((r) => r as Record<string, unknown>)}
              columns={[
                { title: 'Key', dataIndex: 'key', ellipsis: true }, { title: 'actor', dataIndex: 'actorType' },
                { title: 'status', dataIndex: 'status' }, { title: 'created', dataIndex: 'createdAt' },
              ]}
            />,
          },
          {
            key: 'outbox', label: lang === 'zh' ? '事件箱' : 'Outbox',
            children: <ProTable rowKey="id" search={false} pagination={false}
              dataSource={(trace?.outbox ?? []).map((r) => r as Record<string, unknown>)}
              columns={[
                { title: 'ID', dataIndex: 'id' }, { title: 'Event', dataIndex: 'eventType' },
                { title: 'Aggregate', dataIndex: 'aggregateType' }, { title: 'Status', dataIndex: 'status' },
                { title: 'Retry', dataIndex: 'retryCount' }, { title: 'Created', dataIndex: 'createdAt' },
              ]}
            />,
          },
          {
            key: 'sql', label: lang === 'zh' ? 'SQL 历史' : 'SQL History',
            children: <pre className="trace-pre">{(trace?.sqlHistory ?? []).join('\n\n')}</pre>,
          },
          {
            key: 'audit', label: lang === 'zh' ? '审计日志' : 'Audit Log',
            children: trace?.sqlAuditLogs?.length ? (
              <ProTable rowKey="traceId" search={false} pagination={{ defaultPageSize: 10 }}
                dataSource={trace.sqlAuditLogs.map((e, i) => ({ ...e, _key: i }))}
                columns={[
                  { title: 'Trace ID', dataIndex: 'traceId', ellipsis: true, width: 180,
                    render: (_, r) => <Typography.Text code>{String(r.traceId).substring(0, 20)}</Typography.Text> },
                  { title: lang === 'zh' ? '目标' : 'Target', dataIndex: 'targetDs', width: 110,
                    render: (_, r) => <Tag color={r.targetDs === 'COORDINATOR' ? 'purple' : r.targetDs === 'PRIMARY' ? 'blue' : 'green'}>{String(r.targetDs)}</Tag> },
                  { title: lang === 'zh' ? '摘要' : 'Summary', dataIndex: 'sqlSummary', ellipsis: true },
                  { title: lang === 'zh' ? '状态' : 'Status', dataIndex: 'status', width: 90,
                    render: (_, r) => <Tag color={r.status === 'OK' || r.status === 'COMMITTED' ? 'green' : 'red'}>{String(r.status)}</Tag> },
                  { title: lang === 'zh' ? '耗时' : 'Time', dataIndex: 'elapsedMs', width: 80,
                    render: (_, r) => `${r.elapsedMs}ms` },
                  { title: lang === 'zh' ? '时间' : 'Created', dataIndex: 'createdAt', width: 180 },
                ]}
              />
            ) : <Alert type="info" message={lang === 'zh' ? '暂无审计日志（需要 V004 迁移和真实 MySQL）' : 'No audit logs (needs V004 migration and real MySQL)'} />,
          },
        ]} />
      </Space>
    </PageContainer>
  );
}

function TraceTimelineView({ trace, decisions, lang }: { trace?: OrderTrace; decisions?: ProxyDecisionsResult; lang: Lang }) {
  const items: Array<{ color: string; children: React.ReactNode }> = [];

  if (trace?.route) {
    items.push({ color: 'blue', children: <span><strong>{lang === 'zh' ? '路由策略' : 'Route'}:</strong> {trace.route}</span> });
  }
  if (trace?.transactionContext) {
    const is2pc = trace.transactionContext.includes('2PC');
    items.push({ color: is2pc ? 'purple' : 'blue', children: <span>
      <strong>{lang === 'zh' ? '事务模式' : 'TX Mode'}:</strong>{' '}
      <Tag color={is2pc ? 'purple' : 'blue'}>{is2pc ? '2PC' : 'Single-DB'}</Tag>
      {' '}{trace.transactionContext}
    </span> });
  }

  trace?.timeline?.forEach((t) => {
    const item = t as Record<string, unknown>;
    items.push({ color: 'green', children: <span>{lang === 'zh' ? '状态变更' : 'Status'}: <Tag>{String(item.toStatus)}</Tag> {String(item.reason ?? '')} <Typography.Text type="secondary">{String(item.createdAt ?? '')}</Typography.Text></span> });
  });

  trace?.idempotency?.forEach((r) => {
    const item = r as Record<string, unknown>;
    items.push({ color: 'orange', children: <span>{lang === 'zh' ? '幂等' : 'Idempotency'}: <Tag>{String(item.status)}</Tag> key={String(item.key ?? '').substring(0, 24)}...</span> });
  });

  trace?.outbox?.forEach((r) => {
    const item = r as Record<string, unknown>;
    items.push({ color: 'cyan', children: <span>Outbox: <Tag>{String(item.eventType)}</Tag> status={String(item.status)} retry={String(item.retryCount)}</span> });
  });

  // SQL Audit Logs with 2PC details
  trace?.sqlAuditLogs?.forEach((entry) => {
    const is2pc = entry.targetDs === 'COORDINATOR';
    const isError = entry.status === 'ABORTED' || entry.errorCode;
    items.push({
      color: is2pc ? 'purple' : isError ? 'red' : 'blue',
      children: <span>
        {is2pc ? '⚡ 2PC' : 'SQL'}: <Tag color={is2pc ? 'purple' : isError ? 'red' : 'green'}>{entry.targetDs}</Tag>
        {' '}{entry.sqlSummary.length > 100 ? entry.sqlSummary.substring(0, 100) + '...' : entry.sqlSummary}
        {entry.elapsedMs > 0 && <Typography.Text type="secondary"> ({entry.elapsedMs}ms)</Typography.Text>}
        {entry.traceId && <Typography.Text type="secondary"> trace:{entry.traceId.substring(0, 20)}</Typography.Text>}
      </span>,
    });
  });

  decisions?.decisions?.slice(0, 10).forEach((d) => {
    items.push({ color: d.status === 'ERR' ? 'red' : 'blue', children: <span>Proxy: <Tag color={d.target === 'REJECTED' ? 'red' : 'blue'}>{d.keyType}</Tag> {d.sql} → <strong>{d.target}</strong> <Typography.Text type="secondary">{d.elapsedMs}ms</Typography.Text></span> });
  });

  if (items.length === 0) {
    return <Alert message={lang === 'zh' ? '暂无链路数据' : 'No trace data'} type="info" />;
  }

  return <Timeline items={items} />;
}

function RoutePreview({ lang }: { lang: Lang }) {
  const [sql, setSql] = useState('SELECT * FROM orders WHERE user_id = 501');
  const [preview, setPreview] = useState<Record<string, unknown>>();
  return (
    <PageContainer title={tr(lang, 'routePreview')} extra={<Button onClick={async () => setPreview(await api.routePreview(sql))}>{lang === 'zh' ? '预览路由' : 'Preview Route'}</Button>}>
      <div className="two-col wide-left">
        <ProCard title={lang === 'zh' ? 'SQL 输入' : 'SQL Input'}>
          <Space direction="vertical" className="full">
            <Input.TextArea value={sql} onChange={(e) => setSql(e.target.value)} rows={8} />
            <Tag color="orange">{lang === 'zh' ? '只解析，不执行' : 'Parse only'}</Tag>
          </Space>
        </ProCard>
        <ProCard title={lang === 'zh' ? '路由规则' : 'Routing Rules'}>
          <Space direction="vertical">
            <Rule label="user_id" value={lang === 'zh' ? '直接 user_id % 4 分片' : 'direct modulo shard'} />
            <Rule label="order_no/payment_no" value="PRIMARY order_route -> user_id -> shard" />
            <Rule label="PRIMARY-only" value="products / idempotency / exception / outbox / order_route" />
            <Rule label="ERR 5001~5004" value={lang === 'zh' ? '跨分片、缺键、路由缺失、查找失败' : 'cross-shard, missing key, not found, lookup failed'} />
          </Space>
        </ProCard>
      </div>
      {preview && (
        <ProCard title={lang === 'zh' ? '预览结果' : 'Preview Result'} className="top-gap">
          <Descriptions column={3} bordered size="small">
            <Descriptions.Item label="keyType">{String(preview.keyType ?? '-')}</Descriptions.Item>
            <Descriptions.Item label="target">{String(preview.target ?? '-')}</Descriptions.Item>
            <Descriptions.Item label="executed">{String(preview.executed ?? false)}</Descriptions.Item>
            <Descriptions.Item label="reason" span={3}>{String(preview.reason ?? '-')}</Descriptions.Item>
          </Descriptions>
          <pre className="top-gap">{JSON.stringify(preview, null, 2)}</pre>
        </ProCard>
      )}
    </PageContainer>
  );
}

function Settings({ lang, health, runtimeMode }: { lang: Lang; health: string; runtimeMode?: RuntimeMode }) {
  const [apiKey, setApiKeyLocal] = useState(getApiKey() ?? '');
  const [keyStatus, setKeyStatus] = useState<'idle' | 'testing' | 'valid' | 'invalid'>('idle');
  const [showKey, setShowKey] = useState(false);

  const testApiKey = async (key: string) => {
    if (!key.trim()) {
      setKeyStatus('idle');
      return;
    }
    setKeyStatus('testing');
    try {
      setApiKey(key.trim());
      await api.runtimeMode();
      setKeyStatus('valid');
      message.success(lang === 'zh' ? 'API Key 验证通过' : 'API Key validated');
    } catch (e) {
      clearApiKey();
      setKeyStatus('invalid');
      const msg = e instanceof ApiError ? e.message : (e as Error).message;
      message.error(msg);
    }
  };

  const handleKeyChange = (value: string) => {
    setApiKeyLocal(value);
    setKeyStatus('idle');
  };

  const handleKeyBlur = () => {
    if (apiKey.trim()) {
      setApiKey(apiKey.trim());
    } else {
      clearApiKey();
    }
  };

  return (
    <PageContainer title={tr(lang, 'settings')}>
      <Space direction="vertical" size={16} className="full">
        <ProCard title={tr(lang, 'apiKey')}>
          <Space direction="vertical" className="full" size={12}>
            <Alert
              type={keyStatus === 'valid' ? 'success' : keyStatus === 'invalid' ? 'error' : 'info'}
              showIcon
              message={
                keyStatus === 'testing' ? tr(lang, 'apiKeyTesting') :
                keyStatus === 'valid' ? tr(lang, 'apiKeyValid') :
                keyStatus === 'invalid' ? tr(lang, 'apiKeyInvalid') :
                apiKey ? tr(lang, 'apiKeySaved') : tr(lang, 'apiKeyMissing')
              }
            />
            <Input
              type={showKey ? 'text' : 'password'}
              value={apiKey}
              onChange={(e) => handleKeyChange(e.target.value)}
              onBlur={handleKeyBlur}
              placeholder={tr(lang, 'apiKeyPlaceholder')}
              prefix={showKey ? null : null}
              suffix={
                <Button
                  type="text"
                  size="small"
                  onClick={() => setShowKey(!showKey)}
                >
                  {showKey ? (lang === 'zh' ? '隐藏' : 'Hide') : (lang === 'zh' ? '显示' : 'Show')}
                </Button>
              }
              maxLength={128}
            />
            <Typography.Text type="secondary">{tr(lang, 'apiKeyHint')}</Typography.Text>
            <Space>
              <Button
                type="primary"
                loading={keyStatus === 'testing'}
                onClick={() => testApiKey(apiKey)}
              >
                {tr(lang, 'testKey')}
              </Button>
              <Button
                danger
                onClick={() => {
                  setApiKeyLocal('');
                  clearApiKey();
                  setKeyStatus('idle');
                  message.info(lang === 'zh' ? 'API Key 已清除' : 'API Key cleared');
                }}
              >
                {lang === 'zh' ? '清除' : 'Clear'}
              </Button>
            </Space>
          </Space>
        </ProCard>

        <div className="two-col">
          <ProCard title={lang === 'zh' ? '运行设置' : 'Runtime'}>
            <Descriptions column={1}>
              <Descriptions.Item label="API Base">/api</Descriptions.Item>
              <Descriptions.Item label={tr(lang, 'connection')}>{healthText(health, lang)}</Descriptions.Item>
              <Descriptions.Item label={lang === 'zh' ? '数据模式' : 'Data Mode'}>{runtimeMode?.mode ?? (health === 'ready' ? tr(lang, 'realMode') : tr(lang, 'demoMode'))}</Descriptions.Item>
              <Descriptions.Item label="proxyMode">{String(runtimeMode?.proxyMode ?? false)}</Descriptions.Item>
              <Descriptions.Item label="demoEnabled">{String(runtimeMode?.demoEnabled ?? false)}</Descriptions.Item>
              <Descriptions.Item label="shardCount">{runtimeMode?.shardCount ?? '-'}</Descriptions.Item>
            </Descriptions>
          </ProCard>
          <ProCard title={lang === 'zh' ? '交付验收清单' : 'Acceptance Checklist'}>
            <Space direction="vertical">
              <Tag color="green">{lang === 'zh' ? '订单履约终端 / 数据库实验终端' : 'Business / Database terminal'}</Tag>
              <Tag color="green">{lang === 'zh' ? '加载 / 空态 / 错误态' : 'Loading / Empty / Error states'}</Tag>
              <Tag color="green">{lang === 'zh' ? '写操作携带 Idempotency-Key' : 'Write ops with Idempotency-Key'}</Tag>
              <Tag color={keyStatus === 'valid' ? 'green' : 'orange'}>{lang === 'zh' ? 'API Key 认证' : 'API Key auth'}</Tag>
              <Tag color="orange">Proxy metrics</Tag>
            </Space>
          </ProCard>
        </div>
      </Space>
    </PageContainer>
  );
}
function PaymentSummary({ lang, order }: { lang: Lang; order: OrderDetail }) {
  if (!order.payment) return <EmptyText lang={lang} />;
  return (
    <Descriptions column={2} bordered size="small">
      <Descriptions.Item label={lang === 'zh' ? '支付单号' : 'Payment No'}>{order.payment.paymentNo}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '渠道' : 'Channel'}>{order.payment.channel}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '金额' : 'Amount'}>{order.payment.amount}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '状态' : 'Status'}>{order.payment.status}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '支付时间' : 'Paid At'} span={2}>{order.payment.paidAt ?? '-'}</Descriptions.Item>
    </Descriptions>
  );
}

function FulfillmentSummary({ lang, order }: { lang: Lang; order: OrderDetail }) {
  if (!order.fulfillment) return <EmptyText lang={lang} />;
  return (
    <Descriptions column={2} bordered size="small">
      <Descriptions.Item label={lang === 'zh' ? '履约单号' : 'Task No'}>{order.fulfillment.taskNo}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '状态' : 'Status'}>{taskStatusText(order.fulfillment.status, lang)}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '承运商' : 'Carrier'}>{order.fulfillment.carrier ?? '-'}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '运单号' : 'Tracking No'}>{order.fulfillment.trackingNo ?? '-'}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '领取时间' : 'Claimed At'}>{order.fulfillment.claimedAt ?? '-'}</Descriptions.Item>
      <Descriptions.Item label={lang === 'zh' ? '发货时间' : 'Shipped At'}>{order.fulfillment.shippedAt ?? '-'}</Descriptions.Item>
    </Descriptions>
  );
}

function FunnelRow({ label, value, text, status }: { label: string; value: number; text: string; status?: 'exception' }) {
  return (
    <div>
      <div className="funnel-row">
        <span>{label}</span>
        <strong>{text}</strong>
      </div>
      <Progress percent={Math.min(value, 100)} showInfo={false} status={status} />
    </div>
  );
}

function TraceSummary({ trace, lang }: { trace: OrderTrace; lang: Lang }) {
  return (
    <ProCard title={lang === 'zh' ? '关联业务对象' : 'Linked Business Object'}>
      <Descriptions column={4} size="small">
        <Descriptions.Item label="Order No">{trace.order.orderNo}</Descriptions.Item>
        <Descriptions.Item label="user_id">{trace.order.userId}</Descriptions.Item>
        <Descriptions.Item label={lang === 'zh' ? '订单状态' : 'Status'}>{orderStatusText(trace.order.status, lang)}</Descriptions.Item>
        <Descriptions.Item label={lang === 'zh' ? '金额' : 'Amount'}>{trace.order.totalAmount}</Descriptions.Item>
      </Descriptions>
    </ProCard>
  );
}

function Rule({ label, value }: { label: string; value: string }) {
  return (
    <div className="rule-row">
      <Tag color="blue">{label}</Tag>
      <Typography.Text>{value}</Typography.Text>
    </div>
  );
}

function suggestedAction(reasonCode: string, lang: Lang) {
  if (reasonCode.includes('AMOUNT')) return lang === 'zh' ? '核对支付流水，禁止自动置成功' : 'Verify payment ledger';
  if (reasonCode.includes('ROUTE')) return lang === 'zh' ? '检查 order_route 并按 user_id 复查' : 'Check order_route and user_id';
  if (reasonCode.includes('OUTBOX')) return lang === 'zh' ? '检查重试次数和下游处理器' : 'Check retry and consumers';
  return lang === 'zh' ? '人工复核证据后处理' : 'Manual review';
}

function healthText(health: string, lang: Lang) {
  if (health === 'ready') return lang === 'zh' ? '接口就绪' : 'API Ready';
  if (health === 'checking') return lang === 'zh' ? '检查中' : 'Checking';
  return lang === 'zh' ? '接口异常' : 'API Error';
}

function EmptyText({ lang }: { lang: Lang }) {
  return <Typography.Text type="secondary">{tr(lang, 'empty')}</Typography.Text>;
}

function CodeBlock({ cmd, lang }: { cmd: string; lang: Lang }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(cmd);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // fallback for non-HTTPS
      const ta = document.createElement('textarea');
      ta.value = cmd;
      ta.style.position = 'fixed'; ta.style.opacity = '0';
      document.body.appendChild(ta); ta.select();
      document.execCommand('copy');
      document.body.removeChild(ta);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    }
  };
  return (
    <div style={{ position: 'relative', marginTop: 8 }}>
      <pre style={{ margin: 0, paddingRight: 60 }}>{cmd}</pre>
      <Button
        size="small"
        type={copied ? 'primary' : 'default'}
        style={{ position: 'absolute', top: 4, right: 4, fontSize: 12, height: 24 }}
        onClick={copy}
      >
        {copied ? (lang === 'zh' ? '已复制' : 'Copied') : (lang === 'zh' ? '复制' : 'Copy')}
      </Button>
    </div>
  );
}

function formatApiError(error: unknown, lang: Lang) {
  if (error instanceof ApiError) {
    const prefix = error.errorCode ? `[${error.errorCode}] ` : `[HTTP ${error.status}] `;
    return prefix + error.message;
  }
  return error instanceof Error ? error.message : (lang === 'zh' ? '未知错误' : 'Unknown error');
}

function exceptionStatusText(status: number, lang: Lang) {
  const zh = { 10: '待处理', 20: '处理中', 30: '已解决', 40: '已关闭' } as Record<number, string>;
  const en = { 10: 'Open', 20: 'In Progress', 30: 'Resolved', 40: 'Closed' } as Record<number, string>;
  return (lang === 'zh' ? zh : en)[status] ?? String(status);
}

function prettyJson(value: string) {
  if (!value) return '{}';
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

function ProxyPage({ lang }: { lang: Lang }) {
  const [pools, setPools] = useState<ProxyPoolsResult>();
  const [sessions, setSessions] = useState<ProxySessionsResult>();
  const [decisions, setDecisions] = useState<ProxyDecisionsResult>();
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [proxyStatus, setProxyStatus] = useState<ProxyStatus>();
  const [showGuide, setShowGuide] = useState(false);

  const reload = async () => {
    setLoading(true);
    const minWait = new Promise(r => setTimeout(r, 400));
    try {
      const [p, s, d, st] = await Promise.all([
        api.proxyPools(), api.proxySessions(), api.proxyDecisions(30),
        api.proxyStatus(),
        minWait,
      ]);
      setPools(p); setSessions(s); setDecisions(d);
      setProxyStatus(st);
      setLoaded(true);
    } catch (e) {
      message.error((e as Error).message);
      setLoaded(true);
    }
    finally { setLoading(false); }
  };

  useEffect(() => { void reload(); }, []);

  const poolEntries = pools ? Object.entries(pools.pools) : [];
  const totalMax = poolEntries.reduce((sum, [, v]) => sum + v.max, 0);
  const totalUsed = pools?.totalActive ?? 0;

  const hasData = poolEntries.length > 0 || (sessions?.count ?? 0) > 0 || (decisions?.count ?? 0) > 0;

  return (
    <PageContainer title={
      <Space>
        {tr(lang, 'proxy')}
        {proxyStatus && (
          <Tag color={proxyStatus.proxyMode && proxyStatus.proxyReachable ? 'green'
            : proxyStatus.proxyReachable ? 'orange' : 'default'} style={{ fontSize: 13 }}>
            {proxyStatus.proxyMode && proxyStatus.proxyReachable ? 'Proxy 模式'
              : proxyStatus.proxyReachable ? 'Proxy 在线' : '直连模式'}
          </Tag>
        )}
      </Space>
    } extra={
      <Space size={8}>
        <Button size="middle" onClick={() => setShowGuide(true)}>
          {lang === 'zh' ? '如何启用 Proxy' : 'Enable Proxy'}
        </Button>
        <Button size="middle" type="primary" loading={loading} onClick={reload}>{tr(lang, 'refresh')}</Button>
      </Space>
    }>
      <Space direction="vertical" size={16} className="full">
        {/* Status banner */}
        {loaded && !hasData && proxyStatus && (
          <Alert
            type={proxyStatus.proxyReachable ? 'warning' : 'info'}
            showIcon
            message={proxyStatus.proxyReachable
              ? (lang === 'zh' ? 'Proxy 在线但 order-api 为直连模式' : 'Proxy online but order-api in direct mode')
              : (lang === 'zh' ? 'Proxy 未运行或不可达' : 'Proxy not running or unreachable')}
            description={proxyStatus.guide}
            action={
              proxyStatus.proxyReachable && !proxyStatus.proxyMode
                ? <Button size="small" type="primary" onClick={() => setShowGuide(true)}>
                    {lang === 'zh' ? '查看切换步骤' : 'Switch Guide'}
                  </Button>
                : <Button size="small" onClick={() => setShowGuide(true)}>
                    {lang === 'zh' ? '查看启动教程' : 'Setup Guide'}
                  </Button>
            }
          />
        )}
        {loaded && hasData && (
          <Alert
            type="success"
            showIcon
            message={lang === 'zh' ? 'Proxy 模式运行中' : 'Proxy mode active'}
            description={lang === 'zh'
              ? `mini-proxy 管理接口 ${proxyStatus?.proxyMgmtUrl || ':4307'} 响应正常`
              : `mini-proxy management at ${proxyStatus?.proxyMgmtUrl || ':4307'} is responding`}
          />
        )}
        <div className="metric-grid">
          <ProCard><Statistic title={lang === 'zh' ? '会话数' : 'Sessions'} value={sessions?.count ?? '-'} /></ProCard>
          <ProCard><Statistic title={lang === 'zh' ? '活跃连接' : 'Active Connections'} value={totalUsed} suffix={`/ ${totalMax}`} /></ProCard>
          <ProCard><Statistic title={lang === 'zh' ? '数据源' : 'Data Sources'} value={poolEntries.length} /></ProCard>
          <ProCard><Statistic title={lang === 'zh' ? '路由决策' : 'Route Decisions'} value={decisions?.count ?? '-'} /></ProCard>
        </div>

        <ProCard title={lang === 'zh' ? '连接池状态' : 'Connection Pools'}>
          <ProTable rowKey="name" search={false} pagination={false}
            dataSource={poolEntries.map(([name, v]) => ({ name, ...v }))}
            columns={[
              { title: lang === 'zh' ? '数据源' : 'Source', dataIndex: 'name', render: (_, r) => <Tag color={r.healthy ? 'green' : 'red'}>{r.name}</Tag> },
              { title: lang === 'zh' ? '活跃' : 'Active', dataIndex: 'active' },
              { title: lang === 'zh' ? '空闲' : 'Idle', dataIndex: 'idle' },
              { title: lang === 'zh' ? '最大' : 'Max', dataIndex: 'max' },
              { title: lang === 'zh' ? '使用率' : 'Usage', render: (_, r) => <Progress percent={r.max > 0 ? Math.round((r.active / r.max) * 100) : 0} size="small" /> },
            ]}
          />
        </ProCard>

        <ProCard title={lang === 'zh' ? '路由决策日志' : 'Route Decision Log'}>
          <ProTable rowKey="id" search={false} pagination={{ defaultPageSize: 15, pageSizeOptions: ['10', '20', '50'] }}
            dataSource={(decisions?.decisions ?? []).map((d, i) => ({ ...d, _key: i }))}
            columns={[
              { title: 'SQL', dataIndex: 'sql', ellipsis: true, width: 280 },
              { title: lang === 'zh' ? '键类型' : 'Key Type', dataIndex: 'keyType', render: (_, r) => <Tag color={r.keyType === 'USER_ID' ? 'blue' : r.keyType === 'PRIMARY_ONLY' ? 'purple' : 'default'}>{r.keyType}</Tag> },
              { title: lang === 'zh' ? '键值' : 'Key Value', dataIndex: 'keyValue' },
              { title: lang === 'zh' ? '目标' : 'Target', dataIndex: 'target', render: (_, r) => r.target === 'REJECTED' ? <Tag color="red">{r.target}</Tag> : <Tag color="green">{r.target}</Tag> },
              { title: lang === 'zh' ? '状态' : 'Status', dataIndex: 'status', render: (_, r) => <Tag color={r.status === 'OK' ? 'green' : 'red'}>{r.status}</Tag> },
              { title: lang === 'zh' ? '耗时' : 'Time', dataIndex: 'elapsedMs', render: (_, r) => `${r.elapsedMs}ms` },
            ]}
          />
        </ProCard>

        {sessions && sessions.count > 0 && (
          <ProCard title={lang === 'zh' ? '活跃会话' : 'Active Sessions'}>
            <ProTable rowKey="sessionId" search={false} pagination={false}
              dataSource={sessions.sessions.map((s) => ({ sessionId: String(s.sessionId ?? '-'), clientAddr: String(s.clientAddr ?? '-'), inTransaction: Boolean(s.inTransaction), boundShardId: Number(s.boundShardId ?? -1), hasBackendConnection: Boolean(s.hasBackendConnection) }))}
              columns={[
                { title: 'Session ID', dataIndex: 'sessionId', ellipsis: true },
                { title: lang === 'zh' ? '来源' : 'Client', dataIndex: 'clientAddr' },
                { title: lang === 'zh' ? '事务中' : 'In TX', dataIndex: 'inTransaction', render: (_, r) => r.inTransaction ? <Tag color="orange">YES</Tag> : <Tag>NO</Tag> },
                { title: lang === 'zh' ? '绑定分片' : 'Bound Shard', dataIndex: 'boundShardId', render: (_, r) => r.boundShardId >= 0 ? <Tag color="blue">shard_{r.boundShardId}</Tag> : <Tag>-</Tag> },
                { title: lang === 'zh' ? '后端连接' : 'Backend', dataIndex: 'hasBackendConnection', render: (_, r) => r.hasBackendConnection ? <Tag color="green">YES</Tag> : <Tag>NO</Tag> },
              ]}
            />
          </ProCard>
        )}
      </Space>

      <Modal
        title={lang === 'zh' ? '如何启用 Proxy 模式' : 'How to Enable Proxy Mode'}
        open={showGuide}
        width={640}
        onCancel={() => setShowGuide(false)}
        footer={<Button type="primary" onClick={() => setShowGuide(false)}>{lang === 'zh' ? '知道了' : 'Got it'}</Button>}
      >
        <Space direction="vertical" size={16} className="full">
          <Alert
            type="info"
            showIcon
            message={lang === 'zh' ? '当前状态' : 'Current Status'}
            description={proxyStatus?.guide || (lang === 'zh' ? '正在检测...' : 'Checking...')}
          />

          <ProCard size="small" title={lang === 'zh' ? 'Proxy 模式完整架构' : 'Proxy Mode Architecture'}>
            <Descriptions column={1} size="small" bordered>
              <Descriptions.Item label={lang === 'zh' ? '工作原理' : 'How it works'}>
                {lang === 'zh'
                  ? 'order-api 不再直连 MySQL，而是通过 mini-proxy（MySQL 协议代理）转发所有 SQL。Proxy 根据 user_id % 4 将订单相关 SQL 路由到不同分片，同时记录路由决策供本页观测。'
                  : 'order-api no longer connects directly to MySQL. Instead, all SQL goes through mini-proxy, which routes order queries to different shards based on user_id % 4, logging every routing decision for observability.'}
              </Descriptions.Item>
            </Descriptions>
          </ProCard>

          <ProCard size="small" title={lang === 'zh' ? '直连 ↔ Proxy 模式切换' : 'Switch: Direct ↔ Proxy'}>
            <Timeline items={[
              { color: 'orange', children: <span>
                <strong>{lang === 'zh' ? '切到 Proxy 模式' : 'Switch to Proxy mode'}</strong>
                <p>{lang === 'zh'
                  ? '先 Ctrl+C 停掉当前 order-api（直连模式），再执行：'
                  : 'First Ctrl+C to stop the current order-api (direct mode), then run:'}</p>
                <CodeBlock cmd={'$env:MINIDB_PROXY_PORT=13306\nmvn -pl order-api clean spring-boot:run "-Dspring-boot.run.profiles=proxy"'} lang={lang} />
              </span> },
              { color: 'green', children: <span>
                <strong>{lang === 'zh' ? '切回直连模式' : 'Switch back to Direct mode'}</strong>
                <p>{lang === 'zh'
                  ? '先 Ctrl+C 停掉当前 order-api（proxy 模式），再执行：'
                  : 'First Ctrl+C to stop the current order-api (proxy mode), then run:'}</p>
                <CodeBlock cmd='mvn -pl order-api spring-boot:run -Dspring-boot.run.profiles=test' lang={lang} />
              </span> },
              { color: 'blue', children: <span>
                <Typography.Text>
                  {lang === 'zh'
                    ? '两种模式共用 localhost:8080，前端无需改任何配置。刷新页面即可。'
                    : 'Both modes share localhost:8080. No frontend config changes needed. Just refresh.'}
                </Typography.Text>
              </span> },
            ]} />
          </ProCard>

          <ProCard size="small" title={lang === 'zh' ? '首次启动步骤（4 步）' : 'First-time Setup (4 steps)'}>
            <Timeline items={[
              { color: 'gray', children: <span>
                <strong>{lang === 'zh' ? '第 0 步：进入项目根目录' : 'Step 0: Enter project root'}</strong>
                <CodeBlock cmd="cd D:\\vsc_code\\mysql\\sql_demo" lang={lang} />
                <Typography.Text type="secondary">
                  {lang === 'zh' ? '所有命令必须在项目根目录执行（docker-compose.yml 和 pom.xml 所在位置）' : 'All commands must run from the project root (where docker-compose.yml and pom.xml live)'}
                </Typography.Text>
              </span> },
              { color: 'blue', children: <span>
                <strong>{lang === 'zh' ? '第 1 步：启动 MySQL 容器（6 个实例）' : 'Step 1: Start MySQL containers (6 instances)'}</strong>
                <CodeBlock cmd="docker compose up -d" lang={lang} />
                <Typography.Text type="secondary">
                  {lang === 'zh' ? '启动 PRIMARY、REPLICA、shard_0~3 共 6 个 MySQL 8.4 容器，端口 4407~4412' : 'Starts PRIMARY, REPLICA, shard_0~3 (6 MySQL 8.4 containers on ports 4407-4412)'}
                </Typography.Text>
              </span> },
              { color: 'blue', children: <span>
                <strong>{lang === 'zh' ? '第 2 步：启动 mini-proxy' : 'Step 2: Start mini-proxy'}</strong>
                <CodeBlock cmd={'# 如果本机 MySQL 已占用 3306，先换端口：' + '\n$env:MINIDB_PROXY_PORT=13306' + '\n\n' + 'mvn -pl mini-proxy exec:java "-Dexec.mainClass=com.minidb.proxy.MiniProxyServer"'} lang={lang} />
                <Typography.Text type="secondary">
                  {lang === 'zh'
                    ? 'Proxy 默认监听 3306。如果端口冲突（本机已装 MySQL），用 $env:MINIDB_PROXY_PORT 换到 13306。管理接口自动变为 13307。'
                    : 'Proxy defaults to port 3306. If port conflicts (local MySQL installed), set $env:MINIDB_PROXY_PORT to change it (e.g. 13306). Management port auto-follows.'}
                </Typography.Text>
              </span> },
              { color: 'green', children: <span>
                <strong>{lang === 'zh' ? '第 3 步：order-api 切 proxy 模式（新终端）' : 'Step 3: order-api proxy mode (new terminal)'}</strong>
                <CodeBlock cmd={'# 如果第 2 步换了端口，这里也必须一致：' + '\n$env:MINIDB_PROXY_PORT=13306' + '\n\n' + 'mvn -pl order-api spring-boot:run "-Dspring-boot.run.profiles=proxy"'} lang={lang} />
                <Typography.Text type="secondary">
                  {lang === 'zh'
                    ? '新开一个终端，cd 到项目根目录后执行。如果第 2 步改了端口，必须同步设置 MINIDB_PROXY_PORT。'
                    : 'Open a new terminal, cd to project root, then run. If you changed the port in step 2, set MINIDB_PROXY_PORT to the same value.'}
                </Typography.Text>
              </span> },
            ]} />
          </ProCard>

          <Alert
            type="warning"
            showIcon
            message={lang === 'zh' ? '注意事项' : 'Notes'}
            description={lang === 'zh'
              ? '• port 冲突：本机 MySQL 默认占用 3306 → 用 $env:MINIDB_PROXY_PORT=13306 换端口\n'
                + '• 第 2、3 步必须在不同终端窗口分别运行（都是长驻进程）\n'
                + '• proxy 模式下订单写操作（创建/取消）通过 2PC 协调器写入，读操作通过 proxy 路由\n'
                + '• Dashboard 全局聚合在 proxy 模式下不可用，需按 user_id 查询'
              : '• Port conflict: local MySQL uses 3306 → set $env:MINIDB_PROXY_PORT=13306\n'
                + '• Steps 2 & 3 must run in separate terminal windows (long-running processes)\n'
                + '• In proxy mode, order writes use 2PC coordinator, reads go through proxy routing\n'
                + '• Dashboard global aggregation unavailable in proxy mode — query by user_id'}
          />
        </Space>
      </Modal>
    </PageContainer>
  );
}
