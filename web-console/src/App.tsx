import { DashboardOutlined, DatabaseOutlined, ExceptionOutlined, FileSearchOutlined, OrderedListOutlined, SettingOutlined, TruckOutlined } from '@ant-design/icons';
import { PageContainer, ProCard, ProTable } from '@ant-design/pro-components';
import type { ProColumns } from '@ant-design/pro-components';
import { Alert, Button, Descriptions, Drawer, Input, Layout, Menu, Modal, Progress, Radio, Segmented, Select, Skeleton, Space, Statistic, Tabs, Tag, Timeline, Typography, message } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { api, DashboardSummary, ExceptionItem, LabRunResult, OrderDetail, OrderSummary, OrderTrace, TaskItem, orderStatusText, taskStatusText } from './api';
import { Lang, tr } from './i18n';

const { Sider, Header, Content } = Layout;

type PageKey = 'dashboard' | 'orders' | 'fulfillment' | 'exceptions' | 'lab' | 'trace' | 'routePreview' | 'settings';
type TerminalKey = 'business' | 'database';

const menuIcon = {
  dashboard: <DashboardOutlined />,
  orders: <OrderedListOutlined />,
  fulfillment: <TruckOutlined />,
  exceptions: <ExceptionOutlined />,
  lab: <DatabaseOutlined />,
  trace: <FileSearchOutlined />,
  routePreview: <FileSearchOutlined />,
  settings: <SettingOutlined />,
};

const terminalPages: Record<TerminalKey, PageKey[]> = {
  business: ['dashboard', 'orders', 'fulfillment', 'exceptions', 'settings'],
  database: ['lab', 'trace', 'routePreview', 'settings'],
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
  const [traceOrderId, setTraceOrderId] = useState(initialLocation.orderId ?? 1);

  useEffect(() => {
    api.dashboard().then(() => setHealth('ready')).catch(() => setHealth('error'));
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
          {terminal === 'business' && page === 'dashboard' && <Dashboard lang={lang} openPage={(target) => navigate('business', target)} />}
          {terminal === 'business' && page === 'orders' && <Orders lang={lang} openTrace={(orderId) => navigate('database', 'trace', orderId)} />}
          {terminal === 'business' && page === 'fulfillment' && <Fulfillment lang={lang} />}
          {terminal === 'business' && page === 'exceptions' && <Exceptions lang={lang} />}
          {terminal === 'database' && page === 'lab' && <Lab lang={lang} />}
          {terminal === 'database' && page === 'trace' && <DatabaseTrace lang={lang} orderId={traceOrderId} setOrderId={updateTraceOrderId} backToOrders={() => navigate('business', 'orders', traceOrderId)} />}
          {terminal === 'database' && page === 'routePreview' && <RoutePreview lang={lang} />}
          {page === 'settings' && <Settings lang={lang} health={health} />}
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

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setRows((await api.orders(userId, status)).items);
    } catch (e) {
      message.error((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [userId, status]);

  useEffect(() => { void reload(); }, [reload]);

  const columns: ProColumns<OrderSummary>[] = [
    { title: 'Order No', dataIndex: 'orderNo' },
    { title: lang === 'zh' ? '状态' : 'Status', dataIndex: 'status', render: (_, r) => <Tag color={r.status === 90 ? 'red' : 'blue'}>{orderStatusText(r.status, lang)}</Tag> },
    { title: lang === 'zh' ? '金额' : 'Amount', dataIndex: 'totalAmount' },
    { title: lang === 'zh' ? '明细数' : 'Items', dataIndex: 'itemCount' },
    { title: lang === 'zh' ? '创建时间' : 'Created At', dataIndex: 'createdAt' },
    { title: lang === 'zh' ? '操作' : 'Action', valueType: 'option', render: (_, r) => [
      <Button key="detail" onClick={async () => setSelected(await api.order(r.orderId, userId))}>{tr(lang, 'details')}</Button>,
      <Button key="trace" onClick={() => openTrace(r.orderId)}>{lang === 'zh' ? '数据库链路' : 'Trace'}</Button>,
      r.status === 10 && <Button key="cancel" danger onClick={() => cancelOrder(r.orderId, userId, reload, lang)}>{tr(lang, 'cancel')}</Button>,
    ] },
  ];

  return (
    <PageContainer title={tr(lang, 'orders')} extra={<Space><Input value={userId} onChange={(e) => setUserId(Number(e.target.value) || 0)} prefix="user_id" /><Button type="primary" onClick={reload}>{tr(lang, 'refresh')}</Button></Space>}>
      <Space direction="vertical" className="full" size={16}>
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
        <ProTable rowKey="orderId" search={false} loading={loading} columns={columns} dataSource={rows} pagination={false} />
      </Space>
      <OrderDrawer lang={lang} order={selected} onClose={() => setSelected(undefined)} onTrace={openTrace} />
    </PageContainer>
  );
}

function OrderDrawer({ lang, order, onClose, onTrace }: { lang: Lang; order?: OrderDetail; onClose: () => void; onTrace: (orderId: number) => void }) {
  return (
    <Drawer width={760} open={!!order} onClose={onClose} title={order?.orderNo}>
      {order && (
        <Space direction="vertical" size={16} className="full">
          <ProCard>
            <Button onClick={() => { onTrace(order.orderId); onClose(); }}>{lang === 'zh' ? '查看数据库链路' : 'Open Database Trace'}</Button>
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
  const reload = async () => setRows((await api.tasks()).items);
  useEffect(() => { void reload(); }, []);
  return (
    <PageContainer title={tr(lang, 'fulfillment')} extra={<Button onClick={reload}>{tr(lang, 'refresh')}</Button>}>
      <Alert className="bottom-gap" type="info" showIcon message={lang === 'zh' ? '如果任务已被他人领取，刷新后再继续处理。' : 'Refresh before retrying if another operator has claimed the task.'} />
      <div className="kanban">
        {[10, 20, 30, 90].map((status) => (
          <ProCard key={status} title={taskStatusText(status, lang)} extra={<Tag>{rows.filter((x) => x.status === status).length}</Tag>}>
            {rows.filter((x) => x.status === status).length === 0 && <EmptyText lang={lang} />}
            {rows.filter((x) => x.status === status).map((task) => (
              <div className="task-card" key={task.taskId}>
                <div><strong>{task.taskNo}</strong><span>{task.orderNo}</span><span>v{task.version} / user {task.userId}</span></div>
                <Space>
                  {task.status === 10 && <Button type="primary" onClick={async () => { await api.claim(task.taskId, task.version); await reload(); }}>{tr(lang, 'claim')}</Button>}
                  {task.status === 20 && <Button onClick={async () => { await api.pick(task.taskId); await reload(); }}>{tr(lang, 'pick')}</Button>}
                  {task.status === 30 && <Button onClick={async () => { await api.ship(task.taskId); await reload(); }}>{tr(lang, 'ship')}</Button>}
                </Space>
              </div>
            ))}
          </ProCard>
        ))}
      </div>
    </PageContainer>
  );
}

function Exceptions({ lang }: { lang: Lang }) {
  const [rows, setRows] = useState<ExceptionItem[]>([]);
  const [bizType, setBizType] = useState<string>('all');
  const reload = async () => setRows((await api.exceptions()).items);
  useEffect(() => { void reload(); }, []);
  const filtered = bizType === 'all' ? rows : rows.filter((x) => x.bizType === bizType);
  return (
    <PageContainer title={tr(lang, 'exceptions')} extra={<Button onClick={reload}>{tr(lang, 'refresh')}</Button>}>
      <Space direction="vertical" className="full" size={16}>
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
        { title: lang === 'zh' ? '状态' : 'Status', dataIndex: 'status', render: (_, r) => <Tag color={r.status === 10 ? 'red' : 'blue'}>{r.status}</Tag> },
        { title: lang === 'zh' ? '操作' : 'Action', valueType: 'option', render: (_, r) => <Button onClick={async () => { await api.resolve(r.id, 'console resolved'); await reload(); }}>{tr(lang, 'resolve')}</Button> },
        ]} pagination={false} />
      </Space>
    </PageContainer>
  );
}

function Lab({ lang }: { lang: Lang }) {
  const [scenario, setScenario] = useState('create-order');
  const [result, setResult] = useState<LabRunResult>();
  const [running, setRunning] = useState(false);
  const run = async () => {
    setRunning(true);
    try {
      setResult(await api.runScenario(scenario));
    } finally {
      setRunning(false);
    }
  };
  return (
    <PageContainer title={tr(lang, 'lab')} extra={<Button type="primary" loading={running} onClick={run}>{tr(lang, 'run')}</Button>}>
      <Space direction="vertical" size={16} className="full">
        <div className="db-status-grid">
          <ProCard title="Proxy 3306"><Tag color="green">{lang === 'zh' ? '运行中' : 'running'}</Tag><p>{lang === 'zh' ? '查询协议 / 路由策略' : 'COM_QUERY / route policy'}</p></ProCard>
          <ProCard title="API 8080"><Tag color="green">{lang === 'zh' ? '就绪' : 'ready'}</Tag><p>{lang === 'zh' ? '订单接口 / 控制台接口' : 'order-api / console API'}</p></ProCard>
          <ProCard title="PRIMARY"><Tag color="blue">{lang === 'zh' ? '元数据' : 'metadata'}</Tag><p>products / outbox / idempotency / order_route</p></ProCard>
          <ProCard title="shard_0~3"><Tag color="purple">user_id % 4</Tag><p>orders / payments / fulfillment</p></ProCard>
        </div>
        <div className="two-col">
          <ProCard title={lang === 'zh' ? '场景选择' : 'Scenario'}>
            <Space direction="vertical" className="full">
              <Select value={scenario} className="full" onChange={setScenario} options={[
                { value: 'create-order', label: lang === 'zh' ? '创建订单：库存锁定、幂等、outbox、路由表' : 'Create Order' },
                { value: 'payment-callback', label: lang === 'zh' ? '支付回调：验签、金额校验、异常工单' : 'Payment Callback' },
                { value: 'mvcc-rc-rr', label: lang === 'zh' ? 'MVCC：RC / RR Read View 对比' : 'MVCC RC vs RR' },
              ]} />
            </Space>
          </ProCard>
          <ProCard title={lang === 'zh' ? '接口契约' : 'API Contract'}>
            <Descriptions column={1} size="small">
              <Descriptions.Item label={lang === 'zh' ? '运行' : 'Run'}><code>POST /api/lab/scenarios/{'{scenario}'}/run</code></Descriptions.Item>
              <Descriptions.Item label={lang === 'zh' ? '返回' : 'Return'}><code>steps, routeTrace, transactionContext, idempotency, outbox, mvccChains</code></Descriptions.Item>
              <Descriptions.Item label={lang === 'zh' ? '状态' : 'Status'}><Tag color="green">{lang === 'zh' ? '阶段六接口就绪' : 'Phase 6 API Ready'}</Tag></Descriptions.Item>
            </Descriptions>
          </ProCard>
        </div>
        {result && (
          <div className="two-col wide-left">
            <ProCard title={lang === 'zh' ? '执行步骤' : 'Step Timeline'}>
              <Timeline items={result.steps.map((x) => ({ children: x }))} />
            </ProCard>
            <ProCard title={lang === 'zh' ? '机制证据' : 'Evidence'}>
              <TracePanel lang={lang} trace={{ transactionContext: result.transactionContext, sqlHistory: [], outbox: result.outbox, idempotency: result.idempotency, routeTable: result.routeTrace, timeline: [], route: '', order: { orderId: 0, orderNo: '', userId: 0, status: 0, totalAmount: 0, paidAmount: 0 } }} />
            </ProCard>
          </div>
        )}
      </Space>
    </PageContainer>
  );
}

function DatabaseTrace({ lang, orderId, setOrderId, backToOrders }: { lang: Lang; orderId: number; setOrderId: (id: number) => void; backToOrders: () => void }) {
  const [trace, setTrace] = useState<OrderTrace>();
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      setTrace(await api.trace(orderId));
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
              <Tag color={trace?.routeTable?.length ? 'green' : 'default'}>SQL Route</Tag>
              <Tag color={trace?.idempotency?.length ? 'green' : 'default'}>Idempotency</Tag>
              <Tag color={trace?.outbox?.length ? 'green' : 'default'}>Outbox</Tag>
              <Tag color={trace?.sqlHistory?.length ? 'green' : 'default'}>SQL History</Tag>
            </div>
          </ProCard>
        </div>
        {trace?.order && <TraceSummary trace={trace} lang={lang} />}
        <TracePanel lang={lang} trace={trace} />
      </Space>
    </PageContainer>
  );
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

function Settings({ lang, health }: { lang: Lang; health: string }) {
  return (
    <PageContainer title={tr(lang, 'settings')}>
      <div className="two-col">
        <ProCard title={lang === 'zh' ? '运行设置' : 'Runtime'}>
          <Descriptions column={1}>
            <Descriptions.Item label="API Base">/api</Descriptions.Item>
            <Descriptions.Item label={tr(lang, 'connection')}>{healthText(health, lang)}</Descriptions.Item>
            <Descriptions.Item label={lang === 'zh' ? '数据模式' : 'Data Mode'}>{health === 'ready' ? tr(lang, 'realMode') : tr(lang, 'demoMode')}</Descriptions.Item>
          </Descriptions>
        </ProCard>
        <ProCard title={lang === 'zh' ? '交付验收清单' : 'Acceptance Checklist'}>
          <Space direction="vertical">
            <Tag color="green">订单履约终端 / 数据库实验终端</Tag>
            <Tag color="green">加载 / 空态 / 错误态</Tag>
            <Tag color="green">写操作携带 Idempotency-Key</Tag>
            <Tag color="orange">Proxy metrics</Tag>
          </Space>
        </ProCard>
      </div>
    </PageContainer>
  );
}

type TraceView = Partial<Omit<OrderTrace, 'outbox' | 'idempotency' | 'routeTable'>> & {
  routeTable?: unknown[];
  idempotency?: unknown[];
  outbox?: unknown[];
};

function TracePanel({ lang, trace }: { lang: Lang; trace?: TraceView }) {
  if (!trace) return <Alert message={lang === 'zh' ? '链路加载中' : 'Trace loading'} type="info" />;
  return (
    <Space direction="vertical" className="full">
      <Alert message={trace.route || trace.transactionContext} type="success" />
      <Tabs items={[
        { key: 'route', label: lang === 'zh' ? 'SQL 路由' : 'SQL Route', children: <pre>{JSON.stringify(trace.routeTable ?? [], null, 2)}</pre> },
        { key: 'idempotency', label: lang === 'zh' ? '幂等记录' : 'Idempotency', children: <pre>{JSON.stringify(trace.idempotency ?? [], null, 2)}</pre> },
        { key: 'outbox', label: lang === 'zh' ? '事件箱' : 'Outbox', children: <pre>{JSON.stringify(trace.outbox ?? [], null, 2)}</pre> },
        { key: 'sql', label: lang === 'zh' ? 'SQL 历史' : 'SQL History', children: <pre>{(trace.sqlHistory ?? []).join('\n')}</pre> },
      ]} />
    </Space>
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

function cancelOrder(orderId: number, userId: number, after: () => void, lang: Lang) {
  Modal.confirm({
    title: lang === 'zh' ? '取消订单' : 'Cancel Order',
    content: lang === 'zh' ? '提交后会刷新订单状态，重复点击不会产生重复处理。' : 'The order status will refresh after submission; repeated clicks are handled safely.',
    onOk: async () => {
      await api.cancelOrder(orderId, userId, 'console cancel');
      after();
    },
  });
}
