import {mount} from '@vue/test-utils';
import ModalBatchOps from './_modal-batch-ops.vue';
import {DatasetManagerApi} from "../api";
import {ImportDataset} from "../types";

/**
 * @jest-environment jsdom
 */
describe('ModalBatchOps component', () => {
  // Monitored jobs which report an error
  let failing: Set<string>;

  // Monitored jobs whose connection is lost, and how many times
  let losing: Map<string, number>;

  class FakeWorker {
    onmessage!: (e: {data: object}) => void;
    onerror!: (e: object) => void;
    postMessage({url}: {url: string}) {
      let lost = losing.get(url) || 0;
      losing.set(url, lost - 1);
      let data = lost > 0
          ? {error: "Lost connection", done: true}
          : {msg: failing.has(url) ? "Error: failed" : "Done", done: true};
      setTimeout(() => this.onmessage({data}));
    }
    terminate() {}
  }

  let job = (op: string) => (id: string) => Promise.resolve({jobId: `${op}-${id}`, url: `/ws/${op}-${id}`});
  let api = {
    repoId: 'r1',
    getHarvestConfig: jest.fn(() => Promise.resolve({})),
    getConvertConfig: jest.fn(() => Promise.resolve([])),
    getImportConfig: jest.fn(() => Promise.resolve({})),
    harvest: jest.fn(job('sync')),
    cancel: jest.fn(() => Promise.resolve()),
    convert: jest.fn(job('convert')),
    ingestFiles: jest.fn(job('import')),
  };

  function mountBatchOps() {
    return mount(ModalBatchOps, {
      shallow: true,
      props: {
        datasets: [{id: 'a', name: 'A'}, {id: 'b', name: 'B'}] as ImportDataset[],
        api: api as unknown as DatasetManagerApi,
        config: {websocketHandler: 'worker.js', monitorUrl: (jobId: string) => `/ws/${jobId}`},
      },
    });
  }

  let statuses = (vm: any) => vm.batch.steps.map((s: any) => `${s.op} ${s.id}: ${s.status}`);

  beforeEach(() => {
    (global as any).Worker = FakeWorker;
    failing = new Set();
    losing = new Map();
    window.localStorage.clear();
    jest.clearAllMocks();
  });

  test('does not run later steps for a dataset after one fails', async () => {
    failing.add('/ws/sync-b');
    let vm: any = mountBatchOps().vm;
    await vm.startBatch(['sync', 'convert']);
    expect(statuses(vm)).toEqual(['sync a: done', 'sync b: failed', 'convert a: done', 'convert b: failed']);
    expect(api.convert).toHaveBeenCalledTimes(1);
  });

  test('pauses the batch, keeping the job, if the connection to a task is lost', async () => {
    // Skip the delays between attempts to reconnect
    let timeout = jest.spyOn(global, 'setTimeout').mockImplementation(((fn: () => void) => fn()) as any);
    losing.set('/ws/sync-a', 100);
    let vm: any = mountBatchOps().vm;
    await vm.startBatch(['sync']);
    timeout.mockRestore();
    expect(statuses(vm)).toEqual(['sync a: pending', 'sync b: pending']);
    expect(vm.batch.steps[0].jobId).toBe('sync-a');
    expect(api.harvest).toHaveBeenCalledTimes(1);
  });

  test('cancels a task which starts after the batch is cancelled', async () => {
    let vm: any = mountBatchOps().vm;
    api.harvest.mockImplementationOnce((id: string) => {
      vm.cancelled = true;
      return job('sync')(id);
    });
    await vm.startBatch(['sync']);
    expect(api.ingestFiles).not.toHaveBeenCalled();
    expect(statuses(vm)).toEqual(['sync a: pending', 'sync b: pending']);
    expect(vm.batch.steps[0].jobId).toBeUndefined();
  });

  test('reconnects to a task after the connection is lost', async () => {
    let vm: any = mountBatchOps().vm;
    let timeout = jest.spyOn(global, 'setTimeout').mockImplementation(((fn: () => void) => fn()) as any);
    losing.set('/ws/sync-a', 2);
    await vm.startBatch(['sync']);
    timeout.mockRestore();
    expect(statuses(vm)).toEqual(['sync a: done', 'sync b: done']);
  });

  test('pauses the batch if a task finished while disconnected', async () => {
    window.localStorage.setItem('ehri-batch-ops-r1', JSON.stringify({
      steps: [{op: 'sync', id: 'a', name: 'A', status: 'pending', jobId: 'old'}, {op: 'sync', id: 'b', name: 'B', status: 'pending'}],
      cleanupOrphans: false,
      forceConvert: false,
      commit: false,
    }));
    failing.add('/ws/old');
    let vm: any = mountBatchOps().vm;
    let worker = (global as any).Worker;
    (global as any).Worker = class extends worker {
      postMessage({url}: {url: string}) {
        setTimeout(() => this.onmessage({data: {msg: "Error: No running task found with ID: old", done: true}}));
      }
    };
    await vm.runBatch();
    (global as any).Worker = worker;
    expect(statuses(vm)).toEqual(['sync a: pending', 'sync b: pending']);
    expect(vm.batch.steps[0].jobId).toBeUndefined();
  });

  test('retries only the failed steps', async () => {
    failing.add('/ws/sync-b');
    let vm: any = mountBatchOps().vm;
    await vm.startBatch(['sync', 'convert']);
    failing.clear();
    jest.clearAllMocks();
    await vm.retryFailed();
    expect(statuses(vm)).toEqual(['sync a: done', 'sync b: done', 'convert a: done', 'convert b: done']);
    expect(api.harvest.mock.calls.map(c => c[0])).toEqual(['b']);
    expect(api.convert.mock.calls.map(c => c[0])).toEqual(['b']);
  });

  test('resumes a saved batch, monitoring a task that was running', async () => {
    window.localStorage.setItem('ehri-batch-ops-r1', JSON.stringify({
      steps: [
        {op: 'sync', id: 'a', name: 'A', status: 'done'},
        {op: 'sync', id: 'b', name: 'B', status: 'pending', jobId: 'running'},
        {op: 'convert', id: 'a', name: 'A', status: 'pending'},
      ],
      cleanupOrphans: false,
      forceConvert: true,
      commit: false,
    }));
    let vm: any = mountBatchOps().vm;
    expect(vm.unfinished).toBe(true);
    await vm.runBatch();
    expect(statuses(vm)).toEqual(['sync a: done', 'sync b: done', 'convert a: done']);
    expect(api.harvest).not.toHaveBeenCalled();
    expect(api.convert).toHaveBeenCalledWith('a', null, {mappings: [], force: true});
    expect(JSON.parse(window.localStorage.getItem('ehri-batch-ops-r1')!).steps[1].jobId).toBeUndefined();
  });

  test('resumes a running batch when the page is reloaded', async () => {
    window.localStorage.setItem('ehri-batch-ops-r1', JSON.stringify({
      steps: [{op: 'sync', id: 'a', name: 'A', status: 'pending', jobId: 'running'}],
      cleanupOrphans: false,
      forceConvert: false,
      commit: false,
    }));
    window.history.replaceState({}, '', '?batch-ops=running');
    let vm: any = mountBatchOps().vm;
    expect(vm.inProgress).toBe(true);
    await new Promise(resolve => setTimeout(resolve, 10));
    expect(statuses(vm)).toEqual(['sync a: done']);
    expect(vm.inProgress).toBe(false);
    expect(window.location.search).toBe('');
  });
});
