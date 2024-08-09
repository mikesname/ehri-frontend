<script lang="ts">

import MixinTaskLog from "../datasets/components/_mixin-tasklog";
import PanelLogWindow from "../datasets/components/_panel-log-window";
import axios from "axios";

interface Service {
  runAudit: () => { url: string, method: string }
}

export default {
  components: {PanelLogWindow},
  mixins: [MixinTaskLog],
  props: {
    service: Object as Service,
    config: Object,
  },
  data: function () {
    return {
      running: false,
      error: null,
    }
  },
  methods: {
    runAudit: async function () {
      this.running = true;
      try {
        let r = await axios.request({
          url: this.service.runAudit().url,
          method: this.service.runAudit().method,
          data: {
            entityType: 'DocumentaryUnit',
            mandatoryOnly: false
          },
          headers: {
            "ajax-ignore-csrf": true,
            "Content-Type": "application/json",
            "Accept": "application/json; charset=utf-8",
            "X-Requested-With": "XMLHttpRequest",
          },
        });
        let data = await r.data;
        console.log("Submit data", data);
        this.monitor(data.url, data.jobId);
      } catch (exc) {
        this.setError("Failed to run audit", exc);
      } finally {
      }
    },
    cancelAudit: async function() {
      await axios.request({
        url: this.service.cancel(this.jobId).url,
        method: this.service.cancel(this.jobId).method,
        headers: {
          "ajax-ignore-csrf": true,
          "Content-Type": "application/json",
          "Accept": "application/json; charset=utf-8",
          "X-Requested-With": "XMLHttpRequest",
        },
      });
    },
    setError: function (err: string, exc?: Error) {
      this.error = err + (exc ? (": " + exc.message) : "");
    },
  },
}

</script>

<template>
    <div class="app-content-inner">
        <div v-if="error" id="app-error-notice" class="alert alert-danger alert-dismissable">
            <span class="close" v-on:click="error = null">&times;</span>
            {{ error }}
        </div>
        <h1>This is the Auditor App</h1>
        <button v-if="running" class="btn btn-default" v-on:click="cancelAudit">
            <i class="fa fa-fw fa-circle-o-notch fa-spinp"></i> Cancel Audit
        </button>
        <button v-else class="btn btn-default" v-on:click="runAudit">
            <i class="fa fa-fw fa-play"></i> Run Audit
        </button>

        <panel-log-window v-bind:log="log" v-bind:panelSize="0" v-bind:visible="true" v-bind:resize="0"/>
    </div>
</template>
