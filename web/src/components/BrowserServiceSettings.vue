<template>
  <el-dialog
    title="浏览器服务"
    :visible="value"
    :width="$store.state.miniInterface ? '94%' : '520px'"
    :close-on-click-modal="false"
    @close="$emit('input', false)"
  >
    <div v-loading="loading">
      <p class="service-description">
        为需要 WebView 的书源加载网页。此配置对所有用户生效，保存后立即生效。
      </p>
      <el-form label-position="top" :model="form" @submit.native.prevent>
        <el-form-item label="启用浏览器服务">
          <el-switch
            v-model="form.enabled"
            aria-label="启用浏览器服务"
            :disabled="!loaded || busy"
          />
        </el-form-item>
        <el-form-item label="服务类型">
          <el-select
            v-model="form.provider"
            aria-label="服务类型"
            :disabled="!loaded || busy"
            style="width:100%"
          >
            <el-option label="Trawl（原生接口）" value="trawl" />
            <el-option
              label="FlareSolverr / Byparr（兼容接口）"
              value="flaresolverr"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="服务地址">
          <el-input
            v-model="form.url"
            label="服务地址"
            placeholder="http://主机名:8191"
            :disabled="!loaded || busy"
            clearable
          />
        </el-form-item>
        <el-form-item label="请求超时（秒）">
          <el-input-number
            v-model="form.timeoutSeconds"
            aria-label="请求超时（秒）"
            :min="5"
            :max="120"
            :disabled="!loaded || busy"
          />
        </el-form-item>
      </el-form>
      <p class="service-description" v-if="form.provider === 'trawl'">
        Trawl 支持网页 GET/POST、请求头和页面内脚本取值。
      </p>
      <p class="service-description" v-else>
        适用于 FlareSolverr、Byparr 及兼容 /v1 的服务。支持渲染 HTML 和表单
        POST；页面内脚本取值、自定义请求头及 JSON/XML POST 请使用 Trawl。
      </p>
      <p class="service-description">
        地址需能从 reader 容器访问。暂不支持资源嗅探（sourceRegex）和直接载入
        HTML。
      </p>
      <p v-if="feedback" role="status" :class="{ 'service-error': failed }">
        {{ feedback }}
      </p>
      <el-button v-if="!loaded && !loading" size="small" @click="load"
        >重新加载</el-button
      >
    </div>
    <span slot="footer">
      <el-button
        size="small"
        :loading="testing"
        :disabled="!loaded || busy || !form.url.trim()"
        @click="testConnection"
        >测试连接</el-button
      >
      <el-button size="small" :disabled="busy" @click="$emit('input', false)"
        >取消</el-button
      >
      <el-button
        type="primary"
        size="small"
        :loading="saving"
        :disabled="!loaded || busy"
        @click="save"
        >保存</el-button
      >
    </span>
  </el-dialog>
</template>

<script>
import Axios from "../plugins/axios";

export default {
  props: { value: Boolean },
  data() {
    return {
      form: { enabled: false, provider: "trawl", url: "", timeoutSeconds: 45 },
      loaded: false,
      loading: false,
      saving: false,
      testing: false,
      feedback: "",
      failed: false
    };
  },
  computed: {
    busy() {
      return this.loading || this.saving || this.testing;
    }
  },
  watch: {
    form: {
      deep: true,
      handler() {
        this.feedback = "";
      }
    }
  },
  mounted() {
    this.load();
  },
  methods: {
    async load() {
      this.loading = true;
      this.loaded = false;
      try {
        const response = await Axios.get(
          this.$store.getters.api + "/getBrowserServiceConfig"
        );
        if (response && response.data.isSuccess) {
          this.form = response.data.data;
          this.loaded = true;
        }
      } catch (e) {
        this.feedback = "配置加载失败，请重新加载";
        this.failed = true;
      } finally {
        this.loading = false;
      }
    },
    valid() {
      if ((this.form.enabled || this.testing) && !this.form.url.trim()) {
        this.feedback = "请填写服务地址";
        this.failed = true;
        return false;
      }
      if (
        !Number.isInteger(this.form.timeoutSeconds) ||
        this.form.timeoutSeconds < 5 ||
        this.form.timeoutSeconds > 120
      ) {
        this.feedback = "超时时间须为 5 至 120 秒";
        this.failed = true;
        return false;
      }
      return true;
    },
    async testConnection() {
      if (!this.valid()) return;
      this.testing = true;
      this.feedback = "";
      try {
        const response = await Axios.post(
          this.$store.getters.api + "/testBrowserServiceConnection",
          this.form,
          { timeout: 20000 }
        );
        this.failed = !response || !response.data.isSuccess;
        this.feedback = this.failed
          ? (response && response.data.errorMsg) ||
            "连接测试未通过，请检查服务类型和地址"
          : response.data.data;
      } catch (e) {
        this.failed = true;
        this.feedback = "连接测试失败，请检查 reader 到服务的网络连接";
      } finally {
        this.testing = false;
      }
    },
    async save() {
      if (!this.valid()) return;
      this.saving = true;
      try {
        const response = await Axios.post(
          this.$store.getters.api + "/saveBrowserServiceConfig",
          this.form
        );
        if (response && response.data.isSuccess) {
          this.$message.success("浏览器服务配置已保存并生效");
          this.$emit("input", false);
        }
      } catch (e) {
        this.failed = true;
        this.feedback = "保存失败，配置未生效，请重试";
      } finally {
        this.saving = false;
      }
    }
  }
};
</script>

<style scoped>
.service-description {
  line-height: 1.6;
  color: #606266;
}
.service-error {
  color: #f56c6c;
}
</style>
