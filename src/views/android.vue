<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from "vue";
import UiIcon from "../components/common/UiIcon.vue";
import { androidRuntime, androidDesktop, androidActive, androidActiveTasks, androidRequest, listAndroidDevices, androidStatusLabels, androidPageLabels } from "../services/android.js";
import { androidConfig, androidPreparedConfig, androidLowestTier, androidTargetMatchesPrepared, androidIsActive, connectedAndroidDevice, androidLabelKey } from "../services/androidValidation.js";
import { formatTime } from "../services/platforms.js";

const form = reactive({ eventTitle: "", session: "", tier: "", quantity: 1, buyerLabels: [], autoSubmit: false, scheduled: false, startAt: "" });
const readingTargets = ref(false);
const error = ref("");
const verifiedTitle = ref("");
const phoneSession = ref("");
const invalidatedPreparedId = ref("");
const submissionReviewed = ref(false);
const clock = ref(Date.now());
let timer;
let operationGeneration = 0;
let restoringTarget = false;
let targetInitialized = false;
const state = computed(() => androidRuntime.states[androidRuntime.deviceId]);
const page = computed(() => state.value?.page);
const buyerMode = computed({
    get: () => form.buyerLabels.length ? "custom" : "default",
    set: value => { if (value !== buyerMode.value) form.buyerLabels = value === "custom" ? [""] : []; },
});
const buyerText = computed({
    get: () => form.buyerLabels.join("\n"),
    set: value => { form.buyerLabels = value.split(/\r?\n/); },
});
const buyerChoices = computed(() => page.value?.kind === "confirm" ? page.value.buyers || [] : []);
const buyerChosen = item => form.buyerLabels.some(label => androidLabelKey(label) === androidLabelKey(item.label) || androidLabelKey(label) === androidLabelKey(item.name));
function toggleBuyer(item) {
    if (locked.value || page.value?.buyersComplete !== true || item.enabled === false || !buyerChoices.value.includes(item) || buyerMode.value !== "custom") return;
    const labels = buyerChosen(item)
        ? form.buyerLabels.filter(label => androidLabelKey(label) !== androidLabelKey(item.label) && androidLabelKey(label) !== androidLabelKey(item.name))
        : [...(form.buyerLabels.length === 1 && form.buyerLabels[0] === "" ? [] : form.buyerLabels), item.label];
    form.buyerLabels = labels.length ? labels : [""];
}
const device = computed(() => connectedAndroidDevice(androidRuntime.devices, androidRuntime.deviceId));
const pending = computed(() => androidRuntime.pending[androidRuntime.deviceId]);
const active = computed(() => androidIsActive(state.value));
const locked = computed(() => readingTargets.value || Boolean(pending.value) || androidActive.value);
const reservation = computed(() => page.value?.kind === "reservation");
const prepared = computed(() => state.value?.prepared);
const executionTarget = computed(() => prepared.value?.config || (['preparing', 'waiting', 'running', 'needs_action', 'interrupted', 'failed'].includes(state.value?.status) ? state.value?.config : null));
const eventOnlyPreparation = computed(() => prepared.value?.verification === "event_only");
const preparationDescription = computed(() => eventOnlyPreparation.value ? "演出已核对；场次、票档待开售后核对" : "目标已核对，选择何时开始。");
const secondsLeft = computed(() => Math.max(0, Math.ceil(((state.value?.config?.startAt || 0) - clock.value) / 1000)));
const logs = computed(() => [...(state.value?.logs || [])].reverse());
const catalog = computed(() => page.value?.kind === 'picker' || page.value?.kind === 'reservation' ? page.value : state.value?.catalog);
const sessions = computed(() => catalog.value?.sessions || []);
const tiers = computed(() => androidLabelKey(phoneSession.value) === androidLabelKey(form.session) ? catalog.value?.tiers || [] : []);
const context = () => ({ desktop: androidDesktop, devices: androidRuntime.devices, deviceId: androidRuntime.deviceId, page: page.value, catalog: catalog.value, verifiedTitle: verifiedTitle.value, invalidatedPreparedId: invalidatedPreparedId.value, submissionGuard: state.value?.submissionGuard });
const prepareProblem = computed(() => {
    try { androidConfig({ ...form, scheduled: false }, context()); return ""; }
    catch (value) { return String(value.message || value); }
});
const targetReady = computed(() => state.value?.status === "ready" && !prepareProblem.value
    && ["selected", "waiting_sale"].includes(prepared.value?.readiness)
    && androidTargetMatchesPrepared(form, prepared.value) && prepared.value.id !== invalidatedPreparedId.value);
const runProblem = computed(() => {
    try { androidPreparedConfig(form, context(), state.value, clock.value); return ""; }
    catch (value) { return String(value.message || value); }
});
const requestLabels = { inspect: "正在读取手机页面", open_picker: "正在打开选票页", select_session: "正在选择场次并读取票档", prepare: "正在锁定目标与预热", start: "正在进入执行状态", stop: "正在停止" };
const stateTitle = computed(() => state.value?.status === "ready" && !targetReady.value ? "准备已失效，请重新锁定"
    : targetReady.value && eventOnlyPreparation.value ? "演出已核对，等待开售" : androidStatusLabels[state.value?.status] || "尚未准备");
const deviceStateLabels = { device: "已授权", unauthorized: "请允许 USB 调试", offline: "离线" };
const metricLabels = { readMs: "最近读取", prepareMs: "本次预热", treeMs: "页面结构", screenshotMs: "截图", ocrMs: "文字识别", triggerLagMs: "定时触发延迟", firstActionMs: "首个动作", tapMs: "最近点击及校验", executionMs: "本次执行总耗时" };
const metrics = computed(() => Object.entries(metricLabels).flatMap(([key, label]) => {
    const value = state.value?.metrics?.[key];
    return typeof value === "number" && Number.isFinite(value) && value >= 0 ? [{ key, label, value }] : [];
}));
const recentRead = computed(() => metrics.value.find(metric => metric.key === "readMs"));
const canStop = computed(() => readingTargets.value || active.value || Boolean(prepared.value) || Boolean(pending.value));
const elapsedLabel = value => value >= 1000 ? `${(value / 1000).toFixed(2)} 秒` : `${Math.round(value)} ms`;
const availabilityLabel = item => ({ upcoming: "未开售", sold_out: "缺货", unavailable: "当前不可选" }[item.availability] || (item.enabled === false ? "当前不可选" : ""));

function adoptPage(snapshot, preserveDraft = false) {
    const current = snapshot?.page;
    const title = current?.title?.trim();
    if (!title || !["detail", "picker", "reservation"].includes(current.kind)) return;
    const options = current.kind === 'detail' ? snapshot.catalog || current : current;
    const selected = (options.sessions || []).filter(item => item.selected);
    const currentSession = selected.length === 1 ? selected[0].label : "";
    const restored = snapshot?.prepared?.config;
    if (!targetInitialized && snapshot.status === "ready" && snapshot.deviceId === androidRuntime.deviceId && snapshot.prepared?.id && restored) {
        restoringTarget = true;
        try {
            Object.assign(form, { eventTitle: restored.eventTitle, session: restored.session, tier: restored.tier, quantity: restored.quantity, buyerLabels: [...(restored.buyerLabels || [])], autoSubmit: restored.autoSubmit === true });
            verifiedTitle.value = title;
            phoneSession.value = currentSession;
            invalidatedPreparedId.value = "";
            targetInitialized = true;
        } finally { restoringTarget = false; }
        return;
    }
    if (preserveDraft && targetInitialized) {
        verifiedTitle.value = title;
        phoneSession.value = currentSession;
        return;
    }
    if (verifiedTitle.value && title !== verifiedTitle.value) { form.session = ""; form.tier = ""; phoneSession.value = ""; }
    verifiedTitle.value = title;
    form.eventTitle = title;
    phoneSession.value = currentSession;
    const matched = (options.sessions || []).find(item => androidLabelKey(item.label) === androidLabelKey(form.session));
    const chosen = selected.length === 1 ? selected[0] : matched || (options.sessions || []).find(item => item.enabled);
    form.session = chosen?.label || '';
    if (!options.tiers?.some(item => androidLabelKey(item.label) === androidLabelKey(form.tier))) form.tier = '';
    if (!form.tier && phoneSession.value && androidLabelKey(phoneSession.value) === androidLabelKey(form.session)) form.tier = androidLowestTier(options.tiers);
    targetInitialized = true;
}

async function refreshDevices() {
    error.value = "";
    try {
        await listAndroidDevices();
        const deviceId = androidRuntime.deviceId;
        if (device.value && !pending.value) {
            const snapshot = await androidRequest("status", deviceId);
            if (deviceId === androidRuntime.deviceId) adoptPage(snapshot, true);
        }
    } catch (value) { error.value = String(value.message || value); }
}

async function perform(action, autoNavigate = true) {
    error.value = "";
    const deviceId = androidRuntime.deviceId;
    const generation = ++operationGeneration;
    readingTargets.value = true;
    try {
        let extra = {};
        if (action === "prepare") extra = { config: androidConfig({ ...form, scheduled: false }, context()) };
        else if (action === "start") extra = { config: androidPreparedConfig(form, context(), state.value), preparedId: prepared.value.id };
        else if (action === "open_picker") extra = { eventTitle: verifiedTitle.value };
        else if (action === "select_session") extra = { eventTitle: verifiedTitle.value, session: form.session.trim() };
        let snapshot = await androidRequest(action, deviceId, extra);
        if (generation !== operationGeneration) return;
        if (autoNavigate && action === 'inspect' && !snapshot.submissionGuard?.blocked && snapshot.page?.kind === 'detail' && snapshot.page.canOpenPicker && !snapshot.page.blockers?.length) {
            snapshot = await androidRequest('open_picker', deviceId, { eventTitle: snapshot.page.title });
            if (generation !== operationGeneration) return;
        }
        if (deviceId === androidRuntime.deviceId) adoptPage(snapshot);
        if (autoNavigate && ['inspect', 'open_picker'].includes(action) && !snapshot.submissionGuard?.blocked && snapshot.page?.kind === 'picker' && !snapshot.page.blockers?.length && form.session
            && (!snapshot.page.tiers?.length || !snapshot.page.sessions?.some(item => item.selected && androidLabelKey(item.label) === androidLabelKey(form.session)))) {
            snapshot = await androidRequest('select_session', deviceId, { eventTitle: snapshot.page.title, session: form.session });
            if (generation !== operationGeneration) return;
            if (deviceId === androidRuntime.deviceId) adoptPage(snapshot);
        }
    } catch (value) { error.value = String(value.message || value); }
    finally { readingTargets.value = false; }
}

async function stopDevice(deviceId) {
    error.value = "";
    operationGeneration += 1;
    try { await androidRequest("stop", deviceId); }
    catch (value) { error.value = String(value.message || value); }
}

async function acknowledgeSubmission() {
    error.value = "";
    const guard = state.value?.submissionGuard;
    if (!submissionReviewed.value || !guard?.blocked || !guard.attemptId || locked.value) return;
    try {
        await androidRequest("acknowledge_submission", androidRuntime.deviceId, { attemptId: guard.attemptId, acknowledged: true });
        submissionReviewed.value = false;
    } catch (value) { error.value = String(value.message || value); }
}

function logTime(value) {
    const time = typeof value === "number" ? value : Date.parse(value);
    return Number.isFinite(time) && time > 0 ? formatTime(time) : "";
}

watch(() => androidLabelKey(form.session), () => { if (!restoringTarget) form.tier = ""; }, { flush: "sync" });
watch(() => [androidLabelKey(form.eventTitle), androidLabelKey(form.session), androidLabelKey(form.tier), form.quantity, form.autoSubmit, JSON.stringify(form.buyerLabels)], (value, previous) => {
    if (restoringTarget) return;
    if (previous && value.every((item, index) => item === previous[index])) return;
    targetInitialized = true;
    if (prepared.value?.id) invalidatedPreparedId.value = prepared.value.id;
}, { flush: "sync" });
watch(() => [androidRuntime.deviceId, state.value?.submissionGuard?.attemptId, state.value?.submissionGuard?.orderState], () => {
    submissionReviewed.value = false;
}, { flush: "sync" });
watch(() => androidRuntime.deviceId, () => {
    restoringTarget = true;
    try {
        verifiedTitle.value = ""; phoneSession.value = ""; invalidatedPreparedId.value = "";
        form.eventTitle = ""; form.session = ""; form.tier = ""; form.quantity = 1; form.buyerLabels = []; form.autoSubmit = false; error.value = "";
        targetInitialized = false;
    } finally { restoringTarget = false; }
}, { flush: "sync" });
onMounted(() => {
    if (androidDesktop) refreshDevices();
    timer = setInterval(() => { clock.value = Date.now(); }, 1000);
});
onUnmounted(() => clearInterval(timer));
</script>

<template>
    <div class="workspace android-workspace">
        <div class="page-heading"><div><div class="eyebrow">ANDROID · DAMAI APP</div><h1>安卓真机工作台</h1><p>自动读取场次和票档，按你的设置推进到确认页或支付宝。</p></div><span class="pill">真人完成支付</span></div>
        <div v-if="error || androidRuntime.listError" class="notice error" role="alert"><UiIcon name="info" />{{ error || androidRuntime.listError }}</div>
        <div v-if="androidRuntime.pollError" class="notice warning" role="alert"><UiIcon name="info" />{{ androidRuntime.pollError }}</div>
        <div v-for="task in androidActiveTasks.filter(item => item.deviceId !== androidRuntime.deviceId)" :key="task.deviceId" class="notice warning"><span>另一台手机还有准备中或执行中的任务。</span><button class="button danger small" @click="stopDevice(task.deviceId)">停止该任务</button></div>

        <section class="panel android-connection">
            <div class="section-heading"><span class="android-step">1</span><div><h2>连接与准备</h2><p>手机解锁并打开目标演出；登录保持在手机。</p></div><span class="pill" :class="{ 'android-muted': !device }">{{ device ? 'USB 已连接' : androidDesktop ? '等待连接' : '浏览器预览 · 未连接' }}</span></div>
            <div class="android-connection-row"><div class="field"><label class="field-label" for="android-device">USB 设备</label><select id="android-device" class="text-input" v-model="androidRuntime.deviceId" :disabled="!androidDesktop || locked || androidRuntime.listing"><option value="">{{ androidRuntime.devices.length ? '请选择设备' : '尚未发现设备' }}</option><option v-for="item in androidRuntime.devices" :key="item.serial" :value="item.serial">{{ item.model || 'Android' }} · {{ item.serial.slice(-4) }} · {{ deviceStateLabels[item.state] || item.state }}</option><option v-if="androidRuntime.deviceId && !androidRuntime.devices.some(item => item.serial === androidRuntime.deviceId)" :value="androidRuntime.deviceId">之前选择的设备已断开</option></select></div><button class="button secondary" :disabled="!androidDesktop || androidRuntime.listing || locked" @click="refreshDevices"><UiIcon name="refresh" />{{ androidRuntime.listing ? '检查中…' : '刷新设备' }}</button><button class="button primary" :disabled="!device || locked" @click="perform('inspect')"><UiIcon name="search" />自动读取演出与票档</button><button v-if="page?.kind === 'detail'" class="button secondary" :disabled="!device || locked || !page.canOpenPicker || !verifiedTitle" @click="perform('open_picker')">进入选票<UiIcon name="arrow" /></button></div>
            <div class="android-read-status"><span v-if="pending" class="android-request" role="status">{{ requestLabels[pending] || '正在处理' }}…</span><span v-else>{{ page ? androidPageLabels[page.kind] || '未识别页面' : '连接后，点击读取当前演出' }}</span><span v-if="recentRead">最近读取 {{ elapsedLabel(recentRead.value) }}</span><span v-if="state?.metrics?.cacheHit === true">本次复用识别结果</span></div>
        </section>

        <div v-if="reservation" class="notice warning android-reservation" role="alert"><UiIcon name="info" /><div><strong>这是预约页面，不是购票页面</strong><p>可读取此处已选场次及票档作为计划。请返回正式演出详情再读取并锁定；开售后会重新核对，不会提交预约。</p></div></div>
        <div v-else-if="page?.blockers?.length" class="notice warning" role="alert"><UiIcon name="info" />{{ page.blockers.join('；') }}</div>

        <section v-if="state?.submissionGuard?.blocked" class="panel android-submission-guard" role="alert">
            <h2>请先核实上一笔订单</h2>
            <p>这台手机有未解除的提交保护。请在大麦检查待付款和已付款订单；读取、停止或重启程序都不会解除保护。</p>
            <p v-if="state.submissionGuard.orderState === 'alipay_handoff'">上次已检测到支付宝前台，这不代表已经付款或出票。</p>
            <p v-else>上次提交结果尚未确认。程序不会自动再次提交。</p>
            <label v-if="state.submissionGuard.attemptId"><input type="checkbox" v-model="submissionReviewed" :disabled="locked" /> 我已检查手机订单，确认可以开始新的购票任务，不会重复购买。</label>
            <button v-if="state.submissionGuard.attemptId" class="button secondary space-top" :disabled="locked || !submissionReviewed" @click="acknowledgeSubmission">确认核实并解除保护</button>
            <p v-else class="field-hint">提交记录无法可靠读取，保持停止；请检查本地存储，不要通过删除记录继续下单。</p>
        </section>

        <div class="android-main-grid">
            <section class="panel android-target-panel">
                <div class="section-heading"><span class="android-step">2</span><div><h2>明确目标</h2><p>场次和票档直接来自手机；切换日期后自动读取对应票档。</p></div></div>
                <div class="android-event"><span class="field-label">{{ reservation ? '当前页面' : '已读取演出' }}</span><h3>{{ page?.title || form.eventTitle || '等待从手机读取演出' }}</h3></div>
                <label class="field-label space-top" for="android-session">场次</label>
                <div class="android-session-row"><select id="android-session" class="text-input" v-model="form.session" :disabled="locked || reservation" @change="perform('select_session')"><option value="">{{ page?.sessions?.length ? '请选择明确的日期与场次' : '读取选票页面后显示场次' }}</option><option v-for="(item, index) in sessions" :key="`${item.label}-${index}`" :value="item.label">{{ item.label }}{{ availabilityLabel(item) ? ` · ${availabilityLabel(item)}` : '' }}</option></select><button class="button secondary" :disabled="locked || reservation || !device || page?.kind !== 'picker' || !form.session.trim() || !verifiedTitle" @click="perform('select_session')">重新读取票档</button></div>
                <p class="field-hint">切换场次会同步选择手机上的日期，并自动读取票档。</p>
                <label class="field-label space-top" for="android-tier">票档</label>
                <select id="android-tier" class="text-input" v-model="form.tier" :disabled="locked || reservation"><option value="">{{ tiers.length ? '请选择票档' : '先读取该场票档' }}</option><option v-for="(item, index) in tiers" :key="`${item.label}-${index}`" :value="item.label">{{ item.label }}{{ availabilityLabel(item) ? ` · ${availabilityLabel(item)}` : '' }}</option></select>
                <p class="field-hint">默认选择已读取的最低价，包括缺货票档；缺货时停止，不自动升档。</p>
                <div class="android-target-bottom space-top"><div><label class="field-label" for="android-quantity">张数</label><input id="android-quantity" type="number" min="1" max="20" step="1" class="text-input android-quantity" v-model.number="form.quantity" :disabled="locked || reservation" /></div><div class="android-buyer-note"><UiIcon name="user" /><div><strong>按配置选择观演人</strong><p>默认买 1 张选手机第 1 位，买 2 张选前 2 位，以此类推。</p></div></div></div>
                <div class="android-buyers space-top">
                    <label class="field-label" for="android-buyer-mode">观演人</label>
                    <select id="android-buyer-mode" class="text-input" v-model="buyerMode" :disabled="locked"><option value="default">默认：按手机列表顺序选择前 N 位（N 为张数）</option><option value="custom">自定义：指定姓名或显示标签</option></select>
                    <template v-if="buyerMode === 'custom'">
                        <label class="field-label space-top" for="android-buyer-labels">自定义观演人（每行一位，共 {{ form.quantity }} 位）</label>
                        <textarea id="android-buyer-labels" class="text-input android-buyer-input" rows="3" v-model="buyerText" :disabled="locked" placeholder="每行输入一个准确姓名，或手机列表中的完整显示标签" aria-describedby="android-buyer-help"></textarea>
                        <p id="android-buyer-help" class="field-hint">可以在执行前直接填写姓名，无需先进入确认页。人数须与张数一致且不能重复；同名时请使用含证件类型和脱敏号码的完整显示标签。每行最多 300 个字符，请勿填写完整证件号码。</p>
                    </template>
                    <p class="field-hint">观演人列表仅在手机订单确认页可读取。若需查看，请手动进入确认页后点击下方读取；选择后请在手机返回选票页，再重新读取演出与票档、锁定目标。</p>
                    <button class="button secondary small" :disabled="!device || locked" @click="perform('inspect', false)">读取当前手机页面 / 观演人</button>
                    <div v-if="page?.kind === 'confirm'" class="android-buyer-choices space-top">
                        <p class="field-hint">{{ page.buyersComplete === true ? '已读取完整观演人列表。' : '观演人列表尚未完整读取，暂不可勾选；以下仅为当前读取到的条目，请在手机核对。' }}</p>
                        <label v-for="(item, index) in buyerChoices" :key="`${item.label}-${index}`" class="android-buyer-choice"><input type="checkbox" :checked="buyerMode === 'custom' && buyerChosen(item)" :disabled="locked || page.buyersComplete !== true || buyerMode !== 'custom' || item.enabled === false" @change="toggleBuyer(item)" /><span>{{ item.label }}<small>{{ item.enabled === false ? ' · 当前不可选' : '' }}{{ item.selected ? ' · 手机当前已选' : '' }}</small></span></label>
                        <p v-if="!buyerChoices.length" class="field-hint">当前未读取到观演人；可先输入准确姓名，执行时再核对匹配。</p>
                        <p v-if="buyerMode === 'default' && buyerChoices.length" class="field-hint">切换为自定义后可勾选上述条目；默认模式以执行时手机列表的前 N 位为准。</p>
                    </div>
                </div>
                <div class="android-submit-option space-top"><label><input type="checkbox" v-model="form.autoSubmit" :disabled="locked" /> 自动提交订单并跳转支付宝</label><p class="field-hint">执行时会先核对日期、票价、总额和张数，再按上述配置选择观演人。未勾选则停在订单确认页，由你核对并提交；仅勾选后才会自动提交一次，进入支付宝即停止，由你付款。</p></div>
                <div class="android-prepare-footer"><button class="button primary full" :disabled="locked || Boolean(prepareProblem)" @click="perform('prepare')"><UiIcon name="lock" />{{ state?.status === 'preparing' ? '正在锁定与预热…' : prepared ? '重新锁定并预热' : '锁定目标并预热' }}</button><p class="field-hint">{{ prepareProblem || (page?.kind === 'detail' && page?.purchaseAvailability === 'upcoming' ? '先核对演出并记录计划目标；场次、票档和张数在开售后逐项核对。' : '现在核对并选择场次、票档和张数，停在下一步按钮前。') }}</p></div>
            </section>

            <section class="panel android-execution-panel" aria-live="polite">
                <div class="section-heading"><span class="android-step" :class="{ 'android-step-ready': targetReady }">3</span><div><h2>待命执行</h2><p>{{ targetReady ? preparationDescription : active ? '正在执行锁定的目标，请保持手机解锁。' : executionTarget ? '本次执行已暂停，请在手机核对结果。' : '先完成左侧锁定与预热。' }}</p></div><button v-if="canStop" class="button danger small" :disabled="pending === 'stop'" @click="stopDevice(androidRuntime.deviceId)"><UiIcon name="pause" />{{ pending === 'stop' ? '停止中…' : active || pending ? '停止' : '解除准备' }}</button></div>
                <div class="android-state" :class="{ 'is-ready': targetReady, 'needs-action': state?.status === 'needs_action' || reservation }"><div class="android-state-title"><span class="live-dot" :class="{ muted: !targetReady && !active }"></span><strong>{{ reservation ? '预约页面不可执行' : pending ? requestLabels[pending] || stateTitle : stateTitle }}</strong></div><p>{{ reservation ? '请回到购票详情重新读取。' : state?.status === 'ready' && !targetReady ? '目标或页面已改变，旧的准备结果不可继续使用。' : targetReady && eventOnlyPreparation ? '演出已核对；场次、票档待开售后核对。执行时会逐项匹配计划目标，未确认一致就不会继续。' : state?.message || '准备完成后，这里会显示已锁定目标。' }}</p><p v-if="targetReady && prepared.readiness === 'waiting_sale'">目标尚未开售，执行时仅等待该目标开放，不点击预约或缺货登记。</p><p v-if="state?.status === 'needs_action' && page?.kind === 'confirm'">已到订单确认页。请在手机核对演出、日期、价格、张数及观演人信息，再自行提交。</p></div>
                <p v-if="prepared?.config && eventOnlyPreparation" class="field-label space-top">计划目标 · 场次与票档尚未核对</p>
                <dl v-if="executionTarget" class="android-locked-target"><div><dt>{{ eventOnlyPreparation ? '已核对演出' : prepared ? '已锁定演出' : '本次演出' }}</dt><dd>{{ executionTarget.eventTitle }}</dd></div><div><dt>{{ eventOnlyPreparation ? '计划场次' : '场次' }}</dt><dd>{{ executionTarget.session }}</dd></div><div><dt>{{ eventOnlyPreparation ? '计划票档 / 张数' : '票档 / 张数' }}</dt><dd>{{ executionTarget.tier }} · {{ executionTarget.quantity }} 张</dd></div><div><dt>观演人</dt><dd>{{ executionTarget.buyerLabels?.length ? executionTarget.buyerLabels.join('；') : `默认选择手机列表前 ${executionTarget.quantity} 位` }}</dd></div><div v-if="prepared?.at"><dt>准备时间</dt><dd>{{ formatTime(prepared.at) }}</dd></div></dl>
                <div v-if="state?.status === 'waiting'" class="android-countdown"><small>距计划开始</small><strong>{{ secondsLeft }}<span>秒</span></strong><p>{{ formatTime(state.config?.startAt) }} · 北京时间</p></div>
                <div v-else class="android-start-controls"><div class="segmented-control" aria-label="执行方式"><button :class="{ selected: !form.scheduled }" :disabled="locked" @click="form.scheduled = false">立即执行</button><button :class="{ selected: form.scheduled }" :disabled="locked" @click="form.scheduled = true">定时开始</button></div><div v-if="form.scheduled" class="space-top"><label class="field-label" for="android-start">开始时间（北京时间，未来 7 天内）</label><input id="android-start" type="datetime-local" step="1" class="text-input" v-model="form.startAt" :disabled="locked" /></div><button class="button primary full space-top" :disabled="locked || Boolean(runProblem)" @click="perform('start')"><UiIcon :name="form.scheduled ? 'clock' : 'play'" />{{ form.scheduled ? '进入定时待命' : form.autoSubmit ? '执行到支付宝后暂停' : '立即执行到确认页' }}</button><p class="field-hint">{{ targetReady ? runProblem || (form.autoSubmit ? '保持手机解锁。将提交订单并在支付宝前台暂停，付款由本人完成。' : '保持手机解锁、大麦在前台。到订单确认页停止，由本人提交。') : '完成锁定与预热后才可执行；修改目标需要重新准备。' }}</p></div>
                <p class="android-execution-note"><UiIcon name="info" />切换工作台页面不会取消任务；停止可阻止后续动作。</p>
            </section>
        </div>

        <details class="panel android-diagnostics"><summary><span><UiIcon name="activity" />步骤与实测耗时</span><small>{{ logs.length ? `${logs.length} 条记录` : '暂无执行记录' }}</small></summary><div class="android-diagnostics-content"><div v-if="metrics.length" class="android-metrics"><div v-for="metric in metrics" :key="metric.key"><span>{{ metric.label }}</span><strong>{{ elapsedLabel(metric.value) }}</strong></div></div><p v-else class="field-hint">读取或准备完成后显示实际采样结果。</p><p v-if="typeof state?.metrics?.cacheHit === 'boolean'" class="field-hint">{{ state.metrics.ocrUsed === false ? '本次通过页面控件读取，无需文字识别' : state.metrics.cacheHit ? '本次复用了未变化区域的识别结果' : '本次使用本地文字识别' }}。耗时只代表本次采样。</p><div class="android-logs" role="log" aria-label="安卓执行步骤日志"><div v-for="(entry, index) in logs" :key="`${entry.time}-${index}`" class="android-log"><span>{{ entry.message }}</span><time>{{ logTime(entry.time) }}</time></div><p v-if="!logs.length" class="field-hint">当前还没有执行步骤。</p></div></div></details>
    </div>
</template>

<style scoped>
.android-submission-guard { padding: 20px 24px; margin-bottom: 20px; border-color: var(--warning-border); }
.android-submission-guard p, .android-submission-guard label { display: block; margin-top: 10px; line-height: 1.7; }
.android-submit-option label { display: flex; align-items: center; gap: 8px; font-weight: 600; line-height: 1.6; }
.android-submit-option input { accent-color: var(--accent); width: 17px; height: 17px; flex-shrink: 0; }
.android-connection, .android-target-panel, .android-execution-panel { padding: 24px; }
.android-connection { margin-bottom: 20px; }
.section-heading { margin-bottom: 20px; }
.android-step { display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; width: 31px; height: 31px; border-radius: 50%; background: var(--accent-soft); color: var(--accent); font-size: 12px; font-weight: 650; }
.android-step-ready { background: var(--accent); color: var(--accent-contrast); }
.android-connection-row { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 10px; }
.android-connection-row .field { flex: 1; min-width: 210px; }
.android-connection-row .text-input { min-height: 40px; }
.android-read-status { display: flex; gap: 18px; flex-wrap: wrap; color: var(--muted); font-size: 10px; line-height: 1.8; margin-top: 12px; }
.android-request { color: var(--accent); }
.android-muted { color: var(--muted); background: var(--surface-subtle); }
.android-reservation p { margin-top: 5px; }
.android-main-grid { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr); align-items: start; gap: 20px; }
.android-event { padding: 14px 16px; background: var(--surface-subtle); border-radius: 8px; }
.android-event h3 { font-size: 14px; line-height: 1.7; overflow-wrap: anywhere; }
.android-session-row { display: flex; gap: 8px; align-items: center; }
.android-session-row select { min-width: 0; }
.android-target-bottom { display: flex; align-items: flex-end; gap: 22px; }
.android-quantity { width: 83px; }
.android-buyer-note { display: flex; gap: 8px; align-items: flex-start; color: var(--secondary); font-size: 11px; padding-bottom: 3px; }
.android-buyer-note .ui-icon { width: 16px; height: 16px; margin-top: 1px; }
.android-buyer-note strong { font-weight: 550; }
.android-buyer-note p { color: var(--muted); margin-top: 5px; font-size: 10px; line-height: 1.7; }
.android-buyer-input { min-height: 88px; resize: vertical; line-height: 1.7; }
.android-buyer-choices { display: grid; gap: 8px; }
.android-buyer-choice { display: flex; align-items: flex-start; gap: 8px; font-size: 11px; line-height: 1.7; overflow-wrap: anywhere; }
.android-buyer-choice input { margin-top: 3px; accent-color: var(--accent); flex-shrink: 0; }
.android-buyer-choice small { color: var(--muted); }
.android-details summary { color: var(--muted); font-size: 10px; cursor: pointer; }
.android-details { border-top: 1px solid var(--border); padding-top: 17px; }
.android-prepare-footer { margin-top: 22px; }
.android-state { padding: 16px; background: var(--surface-subtle); border: 1px solid var(--border); border-radius: 8px; }
.android-state.is-ready { background: var(--success-bg); border-color: var(--success-border); }
.android-state.needs-action { background: var(--warning-bg); border-color: var(--warning-border); }
.android-state-title { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.android-state-title strong { font-weight: 550; }
.android-state p { margin-top: 9px; line-height: 1.8; font-size: 11px; color: var(--secondary); overflow-wrap: anywhere; }
.android-locked-target { padding: 0; margin: 18px 0; font-size: 11px; line-height: 1.8; }
.android-locked-target > div { display: flex; gap: 15px; margin-top: 8px; }
.android-locked-target dt { min-width: 72px; flex-shrink: 0; color: var(--muted); }
.android-locked-target dd { margin: 0; color: var(--ink); overflow-wrap: anywhere; }
.android-start-controls { margin-top: 20px; }
.android-start-controls .segmented-control { display: flex; }
.android-start-controls .segmented-control button { flex: 1; }
.android-countdown { padding: 23px 0; }
.android-countdown small, .android-countdown p { font-size: 10px; color: var(--muted); }
.android-countdown strong { display: block; font-size: 42px; line-height: 1.6; color: var(--accent); font-variant-numeric: tabular-nums; }
.android-countdown strong span { font-size: 13px; margin-left: 8px; font-weight: 450; }
.android-execution-note { display: flex; align-items: flex-start; gap: 6px; color: var(--muted); font-size: 10px; line-height: 1.8; margin-top: 18px; }
.android-execution-note .ui-icon { width: 13px; height: 16px; }
.android-diagnostics { margin-top: 20px; padding: 17px 22px; }
.android-diagnostics > summary { display: flex; justify-content: space-between; gap: 15px; align-items: center; cursor: pointer; font-size: 11px; color: var(--secondary); }
.android-diagnostics > summary > span { display: flex; gap: 8px; align-items: center; }
.android-diagnostics > summary .ui-icon { width: 15px; height: 15px; }
.android-diagnostics > summary small { font-size: 10px; color: var(--muted); }
.android-diagnostics-content { padding-top: 18px; }
.android-metrics { display: flex; gap: 12px; flex-wrap: wrap; }
.android-metrics > div { min-width: 95px; background: var(--surface-subtle); border-radius: 6px; padding: 10px 13px; }
.android-metrics span { display: block; color: var(--muted); font-size: 10px; }
.android-metrics strong { display: block; color: var(--ink); font-size: 13px; font-weight: 550; margin-top: 6px; }
.android-logs { margin-top: 15px; max-height: 270px; overflow-y: auto; }
.android-log { display: flex; justify-content: space-between; gap: 15px; padding: 10px 0; border-top: 1px solid var(--border); font-size: 11px; line-height: 1.7; color: var(--secondary); }
.android-log > span { overflow-wrap: anywhere; }
.android-log time { flex-shrink: 0; font-size: 9px; color: var(--muted); }
.android-workspace > .notice > span { flex: 1; }
@media (max-width: 1100px) { .android-session-row { flex-direction: column; align-items: stretch; } .android-target-bottom { gap: 12px; } .android-main-grid { gap: 15px; } .android-connection, .android-target-panel, .android-execution-panel { padding: 19px; } }
@media (max-width: 860px) { .android-main-grid { grid-template-columns: 1fr; } .android-connection-row > .field { flex-basis: 100%; } .android-log { flex-direction: column; gap: 3px; } }
</style>
