<script lang="ts">
import {defineComponent, PropType} from 'vue';

/**
 * Batch operations run a sequence of steps, each of which starts a
 * task for one dataset and monitors it until it finishes. Progress
 * is saved in the browser so a batch can be resumed after the page
 * is reloaded, and failed steps can be retried without repeating
 * those that succeeded.
 */

import ModalWindow from './_modal-window.vue';
import FilePicker from './_file-picker.vue';
import PanelLogWindow from './_panel-log-window.vue';
import MixinTasklog from './_mixin-tasklog.vue';
import MixinUtil from './_mixin-util.vue';
import {DatasetManagerApi} from '../api';
import {HarvestConfig, ImportConfig, ImportDataset, JobMonitor} from "../types";

type BatchOp = 'sync' | 'convert' | 'import';

interface BatchStep {
  op: BatchOp,
  id: string,
  name: string,
  status: 'pending' | 'done' | 'failed' | 'skipped',
  // Set while the step's task is running, so it can be resumed
  jobId?: string,
}

interface Batch {
  steps: BatchStep[],
  cleanupOrphans: boolean,
  forceConvert: boolean,
  commit: boolean,
}

// URL state marking a batch as running, so it resumes if the page is reloaded
export const BATCH_URL_KEY = 'batch-ops';

// Seconds to wait before each attempt to reconnect to a task's monitor
const RECONNECT_DELAYS = [1, 2, 5, 10, 20, 30];

// The task monitor's response when a task is no longer running
const NOT_FOUND_MSG = "No running task found";

// The outcome of monitoring a task: 'lost' if the connection to it was
// lost, and 'gone' if it finished without us seeing how
type Outcome = 'done' | 'error' | 'lost' | 'gone';

const OP_LABELS: Record<BatchOp, string> = {sync: "Syncing", convert: "Converting", import: "Importing"};

export default defineComponent({
  components: {FilePicker, ModalWindow, PanelLogWindow},
  mixins: [MixinTasklog, MixinUtil],
  props: {
    datasets: Array as PropType<ImportDataset[]>,
    api: DatasetManagerApi,
    config: Object,
  },
  data: function () {
    return {
      tab: 'copy',
      current: null,
      inProgress: false,
      cleanupOrphans: false,
      copyFrom: null,
      copyType: 'convert',
      forceConvert: false,
      commit: false,
      cancelled: false,
      working: {},
      batch: null as Batch | null,
      timestamp: (new Date()).getTime(),
    }
  },
  computed: {
    // Batch operations don't apply to inactive datasets
    activeDatasets: function (): ImportDataset[] {
      return (this.datasets || []).filter(ds => ds.status !== 'inactive');
    },
    storageKey: function (): string {
      return `ehri-batch-ops-${this.api.repoId}`;
    },
    unfinished: function (): boolean {
      return this.batch !== null && this.batch.steps.some((s: BatchStep) => s.status === 'pending' || s.status === 'failed');
    },
    batchSummary: function (): Record<string, number> {
      let counts: Record<string, number> = {pending: 0, done: 0, failed: 0, skipped: 0};
      (this.batch ? this.batch.steps : []).forEach((s: BatchStep) => counts[s.status]++);
      return counts;
    },
  },
  methods: {
    cancelOperation: async function () {
      this.println("Cancelling...");
      this.cancelled = true;
      await this.cancelJob();
    },
    cleanup: function () {
      if (this.cancelled) {
        this.println("Cancelled");
      }
      this.cancelled = false;
      this.inProgress = false;
    },
    setRunning: function () {
      this.inProgress = true;
    },
    loadBatch: function () {
      try {
        let saved = window.localStorage.getItem(this.storageKey);
        this.batch = saved ? JSON.parse(saved) : null;
      } catch (e) {
        this.batch = null;
      }
    },
    saveBatch: function () {
      try {
        if (this.batch) {
          window.localStorage.setItem(this.storageKey, JSON.stringify(this.batch));
        } else {
          window.localStorage.removeItem(this.storageKey);
        }
      } catch (e) {
        console.warn("Unable to save batch progress", e);
      }
    },
    discardBatch: function () {
      this.batch = null;
      this.saveBatch();
    },
    startBatch: async function (ops: BatchOp[]) {
      if (this.unfinished && !window.confirm("Discard the unfinished batch?")) {
        return;
      }
      let datasets: ImportDataset[] = this.activeDatasets;
      let steps: BatchStep[] = [];
      ops.forEach(op => datasets.forEach(set => steps.push({op, id: set.id, name: set.name, status: 'pending'})));
      this.batch = {
        steps,
        cleanupOrphans: this.cleanupOrphans,
        forceConvert: this.forceConvert,
        commit: this.commit,
      };
      this.saveBatch();
      await this.runBatch();
    },
    retryFailed: async function () {
      this.batch.steps.filter((s: BatchStep) => s.status === 'failed').forEach((s: BatchStep) => s.status = 'pending');
      this.saveBatch();
      await this.runBatch();
    },
    runBatch: async function () {
      this.setRunning();
      this.replaceUrlState(BATCH_URL_KEY, 'running');
      let steps: BatchStep[] = this.batch.steps;
      try {
        let carryOn = true;
        for (let i = 0; i < steps.length && carryOn && !this.cancelled; i++) {
          let step = steps[i];
          if (step.status === 'pending') {
            this.println(`[${i + 1}/${steps.length}]`, OP_LABELS[step.op], step.name);
            carryOn = await this.runStep(step);
            this.saveBatch();
          }
        }
        let {done, failed, skipped} = this.batchSummary;
        if (carryOn && !this.cancelled) {
          this.println(`Finished: ${done} succeeded, ${failed} failed, ${skipped} skipped`);
          steps.filter(s => s.status === 'failed').forEach(s => this.println(" - failed:", s.op, s.name));
        }
      } finally {
        this.removeUrlState(BATCH_URL_KEY);
        this.cleanup();
      }
    },
    /**
     * Run a step, returning false if the batch cannot continue.
     */
    runStep: async function (step: BatchStep): Promise<boolean> {
      // Don't convert or import a dataset after an earlier step for it failed
      let failedEarlier = this.batch.steps.find((s: BatchStep) => s.id === step.id && s.status === 'failed');
      if (failedEarlier) {
        this.println(`... not run because ${failedEarlier.op} failed`);
        step.status = 'failed';
        return true;
      }

      this.working[step.id] = true;
      this.$emit('processing', step.id);
      try {
        if (step.jobId) {
          this.println("... resuming monitoring of the running task");
        } else {
          let job: JobMonitor | null = await this.startStep(step);
          if (!job) {
            step.status = 'skipped';
            return true;
          }
          if (this.cancelled) {
            // Cancelled while the task was starting: tasks only
            // start once monitored, so this one won't run
            return false;
          }
          step.jobId = job.jobId;
          this.saveBatch();
        }
        let outcome: Outcome = await this.watchTask(step.jobId);
        if (outcome === 'lost' && !this.cancelled) {
          // Keep the job so resuming the batch can reconnect to it
          this.println("... the batch has been paused: resume it to reconnect to the task");
          return false;
        }
        if (outcome === 'lost') {
          await this.api.cancel(step.jobId);
        }
        delete step.jobId;
        if (outcome === 'gone') {
          this.println("... the task finished while disconnected, so its outcome is unknown. " +
              "The batch has been paused: resume it to run the task again");
          return false;
        }
        if (this.cancelled && outcome !== 'done') {
          // leave the step pending so it is rerun if the batch is resumed
          return false;
        }
        step.status = outcome === 'done' ? 'done' : 'failed';
        if (outcome === 'done' && step.op === 'sync' && this.batch.cleanupOrphans) {
          try {
            await this.cleanupOrphansForSet(step);
          } catch (e) {
            this.println("... unable to clean up orphaned files:", e.message);
          }
        }
      } catch (e) {
        this.println("Error:", e.message);
        delete step.jobId;
        step.status = 'failed';
      } finally {
        delete this.working[step.id];
        this.$emit('processing-done', step.id);
      }
      return true;
    },
    /**
     * Monitor a task until it finishes, reconnecting to it if the
     * connection is lost.
     */
    watchTask: async function (jobId: string): Promise<Outcome> {
      // The task's final message, if we receive it
      let final: {msg?: string} = {};
      let onMsg = (msg: string) => {
        if (msg.includes(DatasetManagerApi.DONE_MSG) || msg.includes(DatasetManagerApi.ERR_MSG)) {
          final.msg = msg;
        }
      };
      await this.monitor(this.config.monitorUrl(jobId), jobId, onMsg);
      for (let delay of RECONNECT_DELAYS) {
        if (final.msg !== undefined || this.cancelled) {
          break;
        }
        this.println(`Reconnecting in ${delay} seconds...`);
        await new Promise(resolve => setTimeout(resolve, delay * 1000));
        await this.monitor(this.config.monitorUrl(jobId), jobId, onMsg);
      }
      if (final.msg === undefined) {
        return 'lost';
      } else if (final.msg.includes(NOT_FOUND_MSG)) {
        return 'gone';
      }
      return final.msg.includes(DatasetManagerApi.ERR_MSG) ? 'error' : 'done';
    },
    startStep: async function (step: BatchStep): Promise<JobMonitor | null> {
      if (step.op === 'sync') {
        let config: HarvestConfig | null = await this.api.getHarvestConfig(step.id);
        if (!config) {
          this.println("... no sync config found");
          return null;
        }
        return await this.api.harvest(step.id, config);
      } else if (step.op === 'convert') {
        let config = await this.api.getConvertConfig(step.id);
        return await this.api.convert(step.id, null, {mappings: config, force: this.batch.forceConvert});
      } else {
        let config: ImportConfig | null = await this.api.getImportConfig(step.id);
        if (!config) {
          this.println("... no import config found");
          return null;
        }
        return await this.api.ingestFiles(step.id, [], config, this.batch.commit);
      }
    },
    cleanupOrphansForSet: async function (step: BatchStep) {
      let config: HarvestConfig = await this.api.getHarvestConfig(step.id);
      let orphans = await this.checkOrphansForSet(step.id, config);
      if (orphans.length > 0) {
        let {deleted: input} = await this.api.deleteFiles(step.id, this.config.input, orphans);
        let {deleted: output} = await this.api.deleteFiles(step.id, this.config.output, orphans);
        this.println(`... deleted ${input} input and ${output} output file(s)`);
      }
    },
    checkOrphansForSet: async function (id: string, config: HarvestConfig): Promise<string[]> {
      let files = await this.api.cleanHarvestConfig(id, config);
      if (files.length === 0) {
        this.println("... no orphans found");
      } else {
        this.println("..." + files.length, "orphaned file(s) found");
        files.forEach(f => this.println(" -", f));
      }
      return files;
    },
    checkForOrphans: async function () {
      let datasets: ImportDataset[] = this.activeDatasets;
      this.setRunning();

      for (let set of datasets) {
        if (this.cancelled) {
          break;
        }
        let config = await this.api.getHarvestConfig(set.id);
        if (config) {
          this.println("Checking", set.name + "...");
          this.working[set.id] = true;
          this.$emit('processing', set.id);
          try {
            await this.checkOrphansForSet(set.id, config);
          } catch (e) {
            this.println(e.message);
          } finally {
            delete this.working[set.id];
            this.$emit('processing-done', set.id);
          }
        } else {
          this.println("No config found for", set.name);
        }
      }
      this.cleanup();
    },
    copySyncSettings: async function() {
      // Copy sync settings from one dataset to all the others, excluding
      // e.g. the URL (which is likely to change between datasets)...
      if (window.confirm(`Copy sync settings from ${this.copyFrom}?`)) {
        let config: HarvestConfig = await this.api.getHarvestConfig(this.copyFrom);
        let others: ImportDataset[] = this.activeDatasets.filter(s => s.id !== this.copyFrom);
        this.inProgress = true;
        for (let set of others) {
          if (this.cancelled) {
            break;
          }
          let other: HarvestConfig = await this.api.getHarvestConfig(set.id);
          other.delay = config.delay;
          await this.api.saveHarvestConfig(set.id, other);
          this.println("Copied sync delay to", set.name);
        }
        this.cleanup();
      }

    },
    copyConvertSettings: async function () {
      // Copy convert settings from one dataset to all the others...
      if (window.confirm(`Copy convert settings from ${this.copyFrom}?`)) {
        let config = await this.api.getConvertConfig(this.copyFrom);
        let others: ImportDataset[] = this.activeDatasets.filter(s => s.id !== this.copyFrom);
        this.inProgress = true;
        for (let set of others) {
          if (this.cancelled) {
            break;
          }
          await this.api.saveConvertConfig(set.id, config);
          this.println("Copied", set.name);
        }
        this.cleanup();
      }
    },
    copyImportSettings: async function () {
      // Copy convert settings from one dataset to all the others...
      if (window.confirm(`Copy import settings from ${this.copyFrom}?`)) {
        let config: ImportConfig = await this.api.getImportConfig(this.copyFrom);
        let others: ImportDataset[] = this.activeDatasets.filter(s => s.id !== this.copyFrom);
        this.inProgress = true;
        for (let set of others) {
          if (this.cancelled) {
            break;
          }
          let existing = await this.api.getImportConfig(set.id);
          let newConfig = {...config, batchSize: existing?.batchSize} as ImportConfig;
          await this.api.saveImportConfig(set.id, newConfig);
          if (config.properties) {
            // We need to copy the properties file
            this.println("Copying properties file to", set.name + "...")
            let copyRes = await this.api.copyFile(this.copyFrom, this.config.config, config.properties, set.id);
            if (!copyRes.message) {
              this.println("Error copying properties file to", set.name);
            } else {
              this.println("...", copyRes.message);
            }
          } else {
            this.println("No properties file to copy for", config);
          }
          this.println("Copied", set.name);
        }
        this.cleanup();
      }
    },
    copySettings: function () {
      if (this.copyType === 'sync') {
        this.copySyncSettings();
      } else if (this.copyType === 'import') {
        this.copyImportSettings();
      } else if (this.copyType === 'convert') {
        this.copyConvertSettings();
      }
    },
    resize: function () {
      this.timestamp = (new Date()).getTime();
    }
  },
  created: function () {
    this.loadBatch();
    if (this.getQueryParam(window.location.search, BATCH_URL_KEY)) {
      if (this.batchSummary.pending > 0) {
        this.println("Resuming batch after the page was reloaded...");
        this.runBatch();
      } else {
        this.removeUrlState(BATCH_URL_KEY);
      }
    }
  },
});
</script>

<template>
  <modal-window v-bind:resizable="true" v-on:close="$emit('close')" v-on:move="resize">
    <template v-slot:title><i class="fa fa-warning"></i> Batch Operations</template>


    <ul class="nav nav-tabs">
      <li class="nav-item">
        <a v-bind:class="{active: tab === 'copy'}" v-on:click.prevent="tab = 'copy'" href="#" class="nav-link">
          Copy Settings
        </a>
      </li>
      <li class="nav-item">
        <a v-bind:class="{active: tab === 'sync'}" v-on:click.prevent="tab = 'sync'" href="#" class="nav-link">
          Synchronise
        </a>
      </li>
      <li class="nav-item">
        <a v-bind:class="{active: tab === 'convert'}" v-on:click.prevent="tab = 'convert'" href="#" class="nav-link">
          Convert
        </a>
      </li>
      <li class="nav-item">
        <a v-bind:class="{active: tab === 'import'}" v-on:click.prevent="tab = 'import'" href="#" class="nav-link">
          Import
        </a>
      </li>
      <li class="nav-item">
        <a v-bind:class="{active: tab === 'all'}" v-on:click.prevent="tab = 'all'" href="#" class="nav-link">
          All
        </a>
      </li>
    </ul>

    <div class="batch-ops-options">
      <fieldset v-bind:disabled="inProgress" class="options-form">
        <div v-if="tab === 'copy'" class="form-group">
          <label class="form-label" for="from-dataset">
            Source dataset
            <span class="input-mandatory">*</span>
          </label>
          <select v-model="copyFrom" class="form-control" id="from-dataset">
            <option v-bind:value="null" disabled selected hidden>(required)</option>
            <option v-for="ds in activeDatasets" v-bind:value="ds.id">{{ ds.name }}</option>
          </select>
        </div>

        <div v-if="tab === 'copy'" class="form-group">
          <label class="form-label" for="copy-type">
            Copy configuration
            <span class="input-mandatory">*</span>
          </label>
          <select v-model="copyType" class="form-control" id="copy-type">
            <option v-bind:value="null" disabled>(required)</option>
            <option v-bind:value="'sync'">Sync delay</option>
            <option v-bind:value="'convert'">Convert settings</option>
            <option v-bind:value="'import'">Import settings</option>
          </select>
        </div>

        <div v-if="tab === 'sync' || tab === 'all'" class="form-group form-check">
          <input class="form-check-input" id="opt-clean" type="checkbox" v-model="cleanupOrphans"/>
          <label class="form-check-label" for="opt-clean">
            Cleanup orphaned files during synchronisation
          </label>
        </div>

        <div v-if="tab === 'convert' || tab === 'all'" class="form-group form-check">
          <input class="form-check-input" id="opt-force-check" type="checkbox" v-model="forceConvert"/>
          <label class="form-check-label" for="opt-force-check">
            Rerun existing conversions
          </label>
        </div>

        <div v-if="tab === 'import' || tab === 'all'" class="form-group form-check">
          <input class="form-check-input" id="opt-commit" type="checkbox" v-model="commit"/>
          <label class="form-check-label" for="opt-commit">
            Commit import
          </label>
        </div>
      </fieldset>

      <div v-if="unfinished && !inProgress" class="alert alert-warning batch-ops-unfinished">
        <span>
          Unfinished batch:
          {{ batchSummary.done }} done, {{ batchSummary.failed }} failed, {{ batchSummary.pending }} not yet run
          <span v-if="batch.commit">(committing imports)</span>
        </span>
        <div>
          <button v-if="batchSummary.pending > 0" v-on:click="runBatch" type="button" class="btn btn-sm btn-secondary">
            <i class="fa fa-fw fa-play"></i>
            Resume
          </button>
          <button v-if="batchSummary.failed > 0" v-on:click="retryFailed" type="button" class="btn btn-sm btn-secondary">
            <i class="fa fa-fw fa-repeat"></i>
            Retry Failed
          </button>
          <button v-on:click="discardBatch" type="button" class="btn btn-sm btn-default">
            <i class="fa fa-fw fa-times"></i>
            Discard
          </button>
        </div>
      </div>
    </div>

    <div class="log-container" id="batch-ops-log">
      <panel-log-window v-bind:log="log" v-bind:resize="timestamp" v-bind:visible="true"/>
    </div>
    <template v-slot:footer>
      <button v-bind:disabled="!inProgress" v-on:click="cancelOperation"
              v-bind:class="{'btn-default': !inProgress, 'btn-warning': inProgress}"
              type="button" class="btn">
        <i v-if="cancelled" class="fa fa-fw fa-circle-o-notch" v-bind:class="{'fa-spin': inProgress}"></i>
        <i v-else class="fa fa-fw fa-stop-circle"></i>
        Cancel Operation
      </button>
      <button v-if="tab === 'copy'" v-bind:disabled="inProgress || !(copyFrom && copyType)" v-on:click="copySettings"
              type="button" class="btn btn-secondary">
        <i v-if="!inProgress" class="fa fa-fw fa-copy"></i>
        <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
        Copy Settings
      </button>
      <template v-if="tab === 'sync'">
        <button v-bind:disabled="inProgress || activeDatasets.find(d => d.src === 'rs') === undefined"
                v-on:click="checkForOrphans" type="button" class="btn btn-default">
          <i class="fa fa-fw fa-trash-o"></i>
          Check for Orphaned Files
        </button>
        <button v-bind:disabled="inProgress || activeDatasets.find(d => d.src === 'rs') === undefined"
                v-on:click="startBatch(['sync'])" type="button" class="btn btn-secondary">
          <i v-if="!inProgress" class="fa fa-fw fa-clone"></i>
          <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
          Synchronise ResourceSync Datasets
        </button>
      </template>
      <button v-if="tab === 'convert'" v-bind:disabled="inProgress" v-on:click="startBatch(['convert'])" type="button"
              class="btn btn-secondary">
        <i v-if="!inProgress" class="fa fa-fw fa-file-code-o"></i>
        <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
        Run Convert
      </button>
      <template v-if="commit">
        <button v-if="tab === 'import'" v-bind:disabled="inProgress" v-on:click="startBatch(['import'])" type="button"
                class="btn btn-danger">
          <i v-if="!inProgress" class="fa fa-fw fa-database"></i>
          <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
          Run Import
        </button>
        <button v-if="tab === 'all'" v-bind:disabled="inProgress" v-on:click="startBatch(['sync', 'convert', 'import'])" type="button"
                class="btn btn-danger">
          <i v-if="!inProgress" class="fa fa-fw fa-database"></i>
          <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
          Synchronise, Convert &amp; Import
        </button>
      </template>
      <template v-else>
        <button v-if="tab === 'import'" v-bind:disabled="inProgress" v-on:click="startBatch(['import'])" type="button"
                class="btn btn-secondary">
          <i v-if="!inProgress" class="fa fa-fw fa-database"></i>
          <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
          Run Dry Run
        </button>
        <button v-if="tab === 'all'" v-bind:disabled="inProgress" v-on:click="startBatch(['sync', 'convert', 'import'])" type="button"
                class="btn btn-secondary">
          <i v-if="!inProgress" class="fa fa-fw fa-database"></i>
          <i v-else class="fa fa-fw fa-circle-o-notch fa-spin"></i>
          Synchronise, Convert &amp; Dry Run Import
        </button>
      </template>
    </template>
  </modal-window>
</template>

<style scoped>
/* Overlay the unfinished batch alert so it doesn't change the modal's size */
.batch-ops-options {
  flex: 1;
  position: relative;
}

.batch-ops-unfinished {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  margin: 0 0 0.25rem;
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
