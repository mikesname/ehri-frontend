<script lang="ts">

import {Terminal} from "xterm";
import {FitAddon} from "xterm-addon-fit";
import {WebLinksAddon} from "xterm-addon-web-links";

export default {
  props: {
    log: Terminal,
    panelSize: {
      type: Number,
      default: -1,
    },
  },
  methods: {
    fit: function() {
      console.debug("Resize terminal...");
      this.$fitAddon.fit();
    }
  },
  watch: {
    panelSize: function() {
      this.fit();
    }
  },
  mounted() {
    const elem = this.$el as HTMLElement;
    this.$fitAddon = new FitAddon() as FitAddon;
    this.$webLinks = new WebLinksAddon();
    this.log.loadAddon(this.$fitAddon);
    this.log.loadAddon(this.$webLinks);
    this.log.open(elem);
    this.$fitAddon.fit();
  }
};
</script>

<template>
  <div v-observe-visibility="fit" class="xterm-container"></div>
</template>

<style scoped>
.xterm-container {
  height: 100%;
  width: 100%;
  padding: 5px;
}
</style>
