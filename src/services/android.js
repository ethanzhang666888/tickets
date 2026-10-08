import { reactive, computed } from "vue";
import { invoke } from "@tauri-apps/api/tauri";
import { androidIsActive } from "./androidValidation.js";

export const androidDesktop = typeof window !== "undefined" && Boolean(window.__TAURI_IPC__);
export const androidRuntime = reactive({ devices: [], deviceId: "", states: {}, listing: false, listError: "", pollError: "", pending: {} });
export const androidActiveTasks = computed(() => Object.values(androidRuntime.states).filter(androidIsActive));
export const androidActive = computed(() => androidActiveTasks.value.length > 0);
export const androidStatusLabels = { idle: "等待配置", preparing: "正在锁定与预热", ready: "目标已锁定", waiting: "等待开始", running: "正在执行", needs_action: "请在手机处理", cancelled: "已停止", interrupted: "已中断", failed: "未完成" };
export const androidPageLabels = { home: "大麦首页", detail: "演出详情", picker: "选择场次与票档", reservation: "预约页面，非购票", confirm: "订单确认", payment: "支付宝 · 等待本人付款", login: "需要登录", risk: "验证或风险提示", queue: "排队中", unknown: "未识别页面" };
const generations = new Map();
const statusRequests = new Map();
let pollTimer;

function desktopOnly() {
    if (!androidDesktop) throw new Error("当前为浏览器预览，请打开 Tickets 桌面应用连接手机");
}

export async function listAndroidDevices() {
    desktopOnly();
    if (androidRuntime.listing) return androidRuntime.devices;
    androidRuntime.listing = true;
    androidRuntime.listError = "";
    try {
        const devices = await invoke("list_android_devices");
        if (!Array.isArray(devices)) throw new Error("设备列表格式无效，请重试");
        androidRuntime.devices = devices;
        const online = devices.filter(device => device.state === "device");
        if (!androidRuntime.deviceId && online.length === 1) androidRuntime.deviceId = online[0].serial;
        return devices;
    } catch (error) {
        androidRuntime.listError = String(error?.message || error);
        throw error;
    } finally { androidRuntime.listing = false; }
}

function acceptState(deviceId, state) {
    if (!state || state.deviceId !== deviceId || !Object.hasOwn(androidStatusLabels, state.status)) throw new Error("设备返回状态无效，请重新读取");
    const previous = androidRuntime.states[deviceId];
    if (previous && Number(previous.updatedAt) > Number(state.updatedAt)) return previous;
    androidRuntime.states[deviceId] = state;
    ensurePolling();
    return state;
}

export async function androidRequest(action, deviceId, extra = {}) {
    desktopOnly();
    if (!deviceId) throw new Error("请先选择手机");
    if (action === "status" && statusRequests.has(deviceId)) return statusRequests.get(deviceId);
    if (action !== "status" && action !== "stop" && androidRuntime.pending[deviceId]) throw new Error("上一步操作尚未结束，请稍候");
    const version = action === "status" ? (generations.get(deviceId) || 0) : (generations.get(deviceId) || 0) + 1;
    if (action !== "status") {
        generations.set(deviceId, version);
        androidRuntime.pending[deviceId] = action;
    }
    const request = invoke("android_request", { request: { action, deviceId, ...extra } }).then(state => {
        if ((generations.get(deviceId) || 0) !== version) return androidRuntime.states[deviceId] || state;
        return acceptState(deviceId, state);
    }).finally(() => {
        if (action === "status") statusRequests.delete(deviceId);
        else if (generations.get(deviceId) === version) delete androidRuntime.pending[deviceId];
    });
    if (action === "status") statusRequests.set(deviceId, request);
    return request;
}

export async function refreshAndroidStatuses() {
    const states = androidActiveTasks.value;
    const results = await Promise.allSettled(states.map(state => androidRequest("status", state.deviceId)));
    const failures = results.filter(result => result.status === "rejected");
    androidRuntime.pollError = failures.length ? `设备状态暂未更新：${String(failures[0].reason?.message || failures[0].reason)}。可点击停止，或在手机人工接管。` : "";
    return results;
}

function ensurePolling() {
    if (pollTimer || !androidActive.value) return;
    pollTimer = setTimeout(async () => {
        await refreshAndroidStatuses();
        pollTimer = null;
        ensurePolling();
    }, 1000);
}
