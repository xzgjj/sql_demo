import { render, screen } from '@testing-library/react';
import App from './App';

vi.mock('./api', async () => {
  const actual = await vi.importActual<typeof import('./api')>('./api');
  return {
    ...actual,
    api: {
      runtimeMode: vi.fn(async () => ({
        mode: 'single-db',
        proxyMode: false,
        demoEnabled: true,
        testProfile: true,
        shardCount: 4,
        activeProfiles: 'test',
        warnings: [],
      })),
      dashboard: vi.fn(async () => ({
        ordersToday: 0,
        paidSuccess: 0,
        fulfillmentTotal: 0,
        openExceptions: 0,
        outboxBacklog: 0,
        routeMiss: 0,
        statusDistribution: [],
        recentExceptions: [],
        workQueue: [],
      })),
      loadDemo: vi.fn(),
      orders: vi.fn(async () => ({ items: [], page: 1, pageSize: 20, total: 0 })),
      tasks: vi.fn(async () => ({ items: [], page: 1, pageSize: 20, total: 0 })),
      exceptions: vi.fn(async () => ({ items: [], page: 1, pageSize: 20, total: 0 })),
      runScenario: vi.fn(),
      routePreview: vi.fn(),
      order: vi.fn(),
      trace: vi.fn(),
      cancelOrder: vi.fn(),
      claim: vi.fn(),
      pick: vi.fn(),
      ship: vi.fn(),
      resolve: vi.fn(),
    },
  };
});

describe('App', () => {
  it('renders console shell', async () => {
    window.history.replaceState(null, '', '/business/dashboard');
    render(<App />);
    expect(await screen.findAllByText('订单履约终端')).toBeTruthy();
    expect(await screen.findAllByText('指挥台')).toBeTruthy();
  });

  it('renders database terminal from direct URL', async () => {
    window.history.replaceState(null, '', '/database/lab');
    render(<App />);
    expect(await screen.findAllByText('数据库实验终端')).toBeTruthy();
    expect(await screen.findAllByText('数据库实验台')).toBeTruthy();
  });
});
