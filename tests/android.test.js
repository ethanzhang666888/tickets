import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { reactive, ref, computed, watch, effectScope } from "vue";
import { androidConfig, androidPreparedConfig, androidLowestTier, androidTargetMatchesPrepared, connectedAndroidDevice, androidIsActive, androidScheduleTime, androidLabelKey } from "../src/services/androidValidation.js";

const now = Date.UTC(2026, 9, 3, 1);
const form = () => ({ eventTitle: "测试演出", session: "2026-10-17 周六 19:30", tier: "看台 380元", quantity: 1, buyerLabels: [], scheduled: false, startAt: "" });
const context = () => ({ backend: "legacy", desktop: true, deviceId: "usb-device", devices: [{ serial: "usb-device", state: "device", model: "HUAWEI" }], page: { kind: "picker", title: "测试演出", blockers: [], sessions: [{ label: form().session, selected: true }], tiers: [{ label: form().tier }] }, verifiedTitle: "测试演出" });

test("Android starts only with a real authorized device in the desktop app", () => {
    assert.throws(() => androidConfig(form(), { ...context(), desktop: false }, now), /桌面应用/);
    for (const state of ["offline", "unauthorized", "", "connected"]) {
        assert.throws(() => androidConfig(form(), { ...context(), devices: [{ serial: "usb-device", state }] }, now), /设备/);
    }
    assert.throws(() => androidConfig(form(), { ...context(), deviceId: "different" }, now), /设备/);
    assert.equal(connectedAndroidDevice(context().devices, "usb-device").model, "HUAWEI");
    assert.equal(connectedAndroidDevice(context().devices, ""), null);
});

test("Android requires exact event, session, tier and whole ticket count", () => {
    for (const key of ["eventTitle", "session", "tier"]) assert.throws(() => androidConfig({ ...form(), [key]: " " }, context(), now));
    for (const quantity of [0, -1, 1.5, 21, Infinity, "1", null]) assert.throws(() => androidConfig({ ...form(), quantity }, context(), now), /张数/);
    assert.throws(() => androidConfig({ ...form(), eventTitle: "另一场演出" }, context(), now), /演出/);
    for (const kind of ["home", "confirm", "risk", "login", "queue", "unknown", "reservation"]) assert.throws(() => androidConfig(form(), { ...context(), page: { kind } }, now), /页面/);
    assert.throws(() => androidConfig(form(), { ...context(), page: { kind: "picker", blockers: ["需要人工选座"] } }, now), /人工选座/);
});

test("Android defaults to the first N phone attendees and accepts exact custom names or display labels", () => {
    for (const quantity of [1, 2, 20]) {
        const config = androidConfig({ ...form(), quantity }, context(), now);
        assert.deepEqual(config.buyerLabels, []);
        assert.equal(config.startAt, null);
        assert.equal(config.timeoutSeconds, 120);
    }
    assert.deepEqual(androidConfig({ ...form(), buyerLabels: undefined }, context(), now).buyerLabels, []);
    for (const buyerLabels of [["甲"], ["甲 · 身份证 · 110***********1234"], ["甲", "乙"]]) {
        assert.deepEqual(androidConfig({ ...form(), quantity: buyerLabels.length, buyerLabels }, context(), now).buyerLabels, buyerLabels);
    }
    assert.deepEqual(androidConfig({ ...form(), buyerLabels: [" 甲 "] }, context(), now).buyerLabels, ["甲"]);
});

test("custom attendees require a matching count, unique normalized strings and safe bounded labels", () => {
    for (const buyerLabels of [["甲", "乙"], [""], [" \t "], ["甲\n乙"], ["甲\r乙"], ["甲\0乙"], ["甲".repeat(301)], [123], [null], "甲", null]) {
        assert.throws(() => androidConfig({ ...form(), buyerLabels }, context(), now), /观演人/);
    }
    for (const buyerLabels of [["甲", " 甲 "], ["Ａ B", "AB"], ["甲\u3000乙", "甲乙"]]) {
        assert.throws(() => androidConfig({ ...form(), quantity: 2, buyerLabels }, context(), now), /观演人/);
    }
    assert.doesNotThrow(() => androidConfig({ ...form(), buyerLabels: ["😀".repeat(300)] }, context(), now));
    assert.throws(() => androidConfig({ ...form(), buyerLabels: ["😀".repeat(301)] }, context(), now), /观演人/);
});

test("prepared attendee matching preserves default/custom mode and normalized label order", () => {
    const custom = { ...form(), quantity: 2, buyerLabels: ["Ａ 甲", "乙 · 身份证 · ****1234"] };
    const prepared = { id: "custom-1", readiness: "selected", config: androidConfig(custom, context(), now) };
    assert.equal(androidTargetMatchesPrepared({ ...custom, buyerLabels: ["A甲", "乙·身份证·****1234"] }, prepared), true);
    for (const buyerLabels of [[], ["丙", "乙 · 身份证 · ****1234"], [...custom.buyerLabels].reverse(), ["Ａ 甲"], null]) {
        assert.equal(androidTargetMatchesPrepared({ ...custom, buyerLabels }, prepared), false);
    }
    assert.equal(androidTargetMatchesPrepared(custom, { ...prepared, config: { ...prepared.config, buyerLabels: [] } }), false);
});

test("unresolved submission blocks preparing even when automatic submission is disabled", () => {
    assert.throws(() => androidConfig(form(), { ...context(), submissionGuard: { blocked: true, attemptId: "attempt-1", status: "unknown" } }, now), /订单/);
    assert.doesNotThrow(() => androidConfig(form(), { ...context(), submissionGuard: { blocked: false } }, now));
});

test("Android scheduling uses Beijing time and rejects past or normalized invalid dates", () => {
    assert.equal(androidScheduleTime("2026-10-03T10:30:15"), Date.UTC(2026, 9, 3, 2, 30, 15));
    for (const value of ["", "2026-02-30T10:00", "2026-13-03T10:00", "2026-10-03T25:00", "tomorrow"]) assert.equal(androidScheduleTime(value), null);
    for (const startAt of ["", "2026-10-03T08:00", "2026-02-30T10:00"]) assert.throws(() => androidConfig({ ...form(), scheduled: true, startAt }, context(), now), /时间/);
    assert.equal(androidConfig({ ...form(), scheduled: true, startAt: "2026-10-03T10:30" }, context(), now).startAt, Date.UTC(2026, 9, 3, 2, 30));
    assert.equal(androidConfig({ ...form(), scheduled: true, startAt: "2026-10-10T09:00:00" }, context(), now).startAt, now + 7 * 86400_000);
    assert.throws(() => androidConfig({ ...form(), scheduled: true, startAt: "2026-10-10T09:00:01" }, context(), now), /7 天/);
});

test("USB defaults reject submission, retain manual attendees, and cap scheduling at 24 hours", () => {
    for (const backend of ["usb", undefined]) {
        const usb = { ...context(), backend };
        assert.throws(() => androidConfig({ ...form(), autoSubmit: true }, usb, now), /USB.*提交/);
        assert.deepEqual(androidConfig({ ...form(), buyerLabels: ["甲"] }, usb, now).buyerLabels, ["甲"]);
        assert.equal(androidConfig({ ...form(), scheduled: true, startAt: "2026-10-04T09:00:00" }, usb, now).startAt, now + 86400_000);
        assert.throws(() => androidConfig({ ...form(), scheduled: true, startAt: "2026-10-04T09:00:01" }, usb, now), /24 小时/);
    }
    assert.throws(() => androidConfig(form(), { ...context(), backend: "other" }, now), /模式/);
    assert.equal(androidConfig({ ...form(), autoSubmit: true }, context(), now).autoSubmit, true);
});

function serviceHarness(invoke) {
    const script = readFileSync(new URL("../src/services/android.js", import.meta.url), "utf8").replace(/^import .*;\r?$/gm, "").replace(/^export /gm, "");
    return new Function("reactive", "computed", "invoke", "androidIsActive", "window", "setTimeout", "clearTimeout", `${script}\nreturn { androidRuntime, androidRequest, switchAndroidBackend, refreshAndroidStatuses };`)(reactive, computed, invoke, androidIsActive, { __TAURI_IPC__: true }, () => 1, () => {});
}

const deferred = () => {
    let resolve, reject;
    const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
    return { promise, resolve, reject };
};

test("desktop requests default to USB, prevent override and reject automatic submission before invoke", async () => {
    const calls = [];
    const service = serviceHarness(async (_command, args) => { calls.push(args.request); return { deviceId: "usb-device", status: "idle", updatedAt: 1 }; });
    assert.equal(service.androidRuntime.backend, "usb");
    await assert.rejects(service.androidRequest("prepare", "usb-device", { config: { autoSubmit: true } }), /USB.*提交/);
    assert.equal(calls.length, 0);
    await service.androidRequest("inspect", "usb-device", { backend: "legacy", action: "start", deviceId: "other" });
    assert.deepEqual(calls[0], { action: "inspect", deviceId: "usb-device", backend: "usb" });
    service.switchAndroidBackend("legacy");
    assert.deepEqual(service.androidRuntime.states, {});
    await service.androidRequest("status", "usb-device");
    assert.equal(calls[1].backend, "legacy");
});

test("backend switching rejects active tasks and all in-flight requests including status and superseded reads", async () => {
    const read = deferred();
    const status = deferred();
    const service = serviceHarness((_command, args) => args.request.action === "inspect" ? read.promise : args.request.action === "status" ? status.promise : Promise.resolve({ deviceId: "usb-device", status: "cancelled", updatedAt: 3 }));
    const readRequest = service.androidRequest("inspect", "usb-device");
    await service.androidRequest("stop", "usb-device");
    assert.throws(() => service.switchAndroidBackend("legacy"), /操作|请求/);
    read.resolve({ deviceId: "usb-device", status: "running", updatedAt: 1 });
    await readRequest;
    assert.equal(service.androidRuntime.states["usb-device"].status, "cancelled");
    const statusRequest = service.androidRequest("status", "usb-device");
    assert.throws(() => service.switchAndroidBackend("legacy"), /操作|请求/);
    status.resolve({ deviceId: "usb-device", status: "running", updatedAt: 4 });
    await statusRequest;
    assert.throws(() => service.switchAndroidBackend("legacy"), /活动|执行/);
    service.androidRuntime.states["usb-device"].status = "ready";
    service.switchAndroidBackend("legacy");
    assert.equal(service.androidRuntime.backend, "legacy");
    assert.deepEqual(service.androidRuntime.states, {});
    assert.deepEqual(service.androidRuntime.pending, {});
});

test("preparing, waiting and running tasks remain active when their page is hidden", () => {
    for (const status of ["preparing", "waiting", "running"]) assert.equal(androidIsActive({ status }), true);
    for (const status of ["idle", "ready", "needs_action", "cancelled", "interrupted", "failed"]) assert.equal(androidIsActive({ status }), false);
    assert.equal(androidIsActive(null), false);
});

test("the lowest unambiguous observed tier stays selected even if it is sold out", () => {
    const tiers = [{ label: "看台680元", enabled: true }, { label: "看台380元", enabled: false, availability: "sold_out" }, { label: "内场1680元", enabled: true }];
    assert.equal(androidLowestTier(tiers), "看台380元");
    assert.equal(androidLowestTier([{ label: "看台３８０元" }]), "看台３８０元");
    assert.equal(androidLowestTier([{ label: "无票" }, { label: "0元" }]), "");
    assert.equal(androidLowestTier([{ label: "A区380元" }, { label: "B区380元" }]), "");
    assert.equal(androidLowestTier([{ label: "原价680元，现价380元" }]), "");
});

test("execution requires the exact ready preparation, and target edits invalidate it", () => {
    const config = androidConfig(form(), context(), now);
    const state = { status: "ready", deviceId: "usb-device", prepared: { id: "ready-1", at: now, readiness: "selected", config } };
    assert.equal(androidTargetMatchesPrepared(form(), state.prepared), true);
    assert.equal(androidPreparedConfig(form(), context(), state, now).tier, "看台 380元");
    assert.equal(androidTargetMatchesPrepared({ ...form(), quantity: 2 }, state.prepared), false);
    for (const changed of [{ quantity: 2 }, { eventTitle: "另一演出" }, { session: "另一日期" }, { tier: "内场1680元" }]) assert.throws(() => androidPreparedConfig({ ...form(), ...changed }, context(), state, now));
    assert.throws(() => androidPreparedConfig(form(), { ...context(), invalidatedPreparedId: "ready-1" }, state, now), /重新/);
    assert.throws(() => androidPreparedConfig(form(), context(), { ...state, deviceId: "other-phone" }, now), /手机/);
    for (const status of ["idle", "preparing", "waiting", "needs_action"]) assert.throws(() => androidPreparedConfig(form(), context(), { ...state, status }, now), /锁定/);
    assert.throws(() => androidPreparedConfig(form(), context(), { ...state, prepared: null }, now), /锁定/);
    assert.throws(() => androidPreparedConfig(form(), { ...context(), page: { kind: "reservation", title: "测试演出" } }, state, now), /预约页面/);
    const scheduled = { ...form(), scheduled: true, startAt: "2026-10-03T10:30" };
    assert.equal(androidPreparedConfig(scheduled, context(), state, now).startAt, Date.UTC(2026, 9, 3, 2, 30));
});

function preparedView(verification = "full", request = null, buyerLabels = []) {
    const snapshot = { status: "ready", deviceId: "usb-device", prepared: { id: "ready-1", at: now, verification, readiness: verification === "event_only" ? "waiting_sale" : "selected", config: { ...androidConfig(form(), context(), now), tier: "内场1680元", quantity: 2, buyerLabels } }, page: { kind: verification === "event_only" ? "detail" : "picker", title: "测试演出", blockers: [], sessions: [{ label: form().session, selected: true }], tiers: [{ label: "看台380元" }, { label: "内场1680元" }] } };
    if (verification === "event_only") snapshot.catalog = { ...snapshot.page };
    const runtime = reactive({ backend: "legacy", inFlight: 0, deviceId: "usb-device", devices: context().devices, states: { "usb-device": snapshot }, pending: {} });
    const dependencies = { reactive, ref, computed, watch, onMounted: () => {}, onUnmounted: () => {}, androidRuntime: runtime, androidDesktop: true, androidActive: ref(false), androidActiveTasks: ref([]), switchAndroidBackend: backend => { runtime.states = {}; runtime.backend = backend; }, androidRequest: async (action, ...args) => { if (request) return request(runtime, action, ...args); assert.equal(action, "status"); return snapshot; }, listAndroidDevices: async () => runtime.devices, androidStatusLabels: {}, androidPageLabels: {}, androidConfig, androidPreparedConfig, androidLowestTier, androidTargetMatchesPrepared, androidIsActive, connectedAndroidDevice, androidLabelKey, formatTime: value => String(value) };
    const script = readFileSync(new URL("../src/views/android.vue", import.meta.url), "utf8").split("<script setup>")[1].split("</script>")[0].replace(/^import .*;\r?$/gm, "");
    const scope = effectScope();
    const view = scope.run(() => new Function(...Object.keys(dependencies), `${script}\nreturn { form, refreshDevices, adoptPage, perform, stopDevice, invalidatedPreparedId, targetReady, runProblem, stateTitle, preparationDescription, executionTarget, submissionReviewed, acknowledgeSubmission, buyerMode, buyerText, buyerChoices, toggleBuyer, usbMode, changeBackend, backendLocked };`)(...Object.values(dependencies)));
    return { view, runtime, stop: () => scope.stop() };
}

test("switching backend clears the full draft, schedule and preparation while USB never exposes phone identities", async () => {
    const { view, runtime, stop } = preparedView("full", null, ["甲", "乙"]);
    try {
        await view.refreshDevices();
        view.form.scheduled = true;
        view.form.startAt = "2026-10-04T10:00";
        view.form.autoSubmit = true;
        runtime.states = {};
        runtime.backend = "usb";
        assert.deepEqual({ ...view.form }, { eventTitle: "", session: "", tier: "", quantity: 1, buyerLabels: [], autoSubmit: false, scheduled: false, startAt: "" });
        assert.equal(view.targetReady.value, false);
        assert.equal(view.usbMode.value, true);
        runtime.states["usb-device"] = { status: "needs_action", page: { kind: "confirm", buyers: [{ label: "应仅在手机显示", enabled: true }], buyersComplete: true, selectedBuyerCount: 1, orderVerified: true } };
        assert.deepEqual(view.buyerChoices.value, []);
        view.buyerMode.value = "custom";
        view.buyerText.value = "手动姓名";
        assert.deepEqual(view.form.buyerLabels, ["手动姓名"]);
        runtime.inFlight = 1;
        assert.equal(view.backendLocked.value, true);
    } finally { stop(); }
});

test("late old-backend view responses cannot restore a draft or continue navigation", async () => {
    for (const action of ["status", "inspect"]) {
        const response = deferred();
        const calls = [];
        const { view, runtime, stop } = preparedView("full", async (_runtime, request) => { calls.push(request); return response.promise; });
        try {
            const operation = action === "status" ? view.refreshDevices() : view.perform("inspect");
            await Promise.resolve();
            runtime.states = {};
            runtime.backend = "usb";
            response.resolve({ deviceId: "usb-device", status: "idle", page: { kind: "detail", title: "旧模式演出", canOpenPicker: true, blockers: [] } });
            await operation;
            assert.deepEqual(calls, [action]);
            assert.equal(view.form.eventTitle, "");
            assert.equal(view.targetReady.value, false);
        } finally { stop(); }
    }
});

test("custom attendees restore without invalidation and changed-back labels still require preparation", async () => {
    const labels = ["甲", "乙 · 身份证 · ****1234"];
    const { view, stop } = preparedView("full", null, labels);
    try {
        await view.refreshDevices();
        assert.deepEqual(view.form.buyerLabels, labels);
        assert.equal(view.buyerMode.value, "custom");
        assert.equal(view.buyerText.value, labels.join("\n"));
        assert.equal(view.targetReady.value, true);
        view.buyerText.value = "丙\n乙 · 身份证 · ****1234";
        view.buyerText.value = labels.join("\n");
        assert.equal(view.invalidatedPreparedId.value, "ready-1");
        assert.equal(view.targetReady.value, false);
        assert.match(view.runProblem.value, /重新/);
        await view.refreshDevices();
        assert.deepEqual(view.form.buyerLabels, labels);
        assert.equal(view.targetReady.value, false);
    } finally { stop(); }
});

test("attendee mode changes and in-place edits invalidate preparation even when restored", async () => {
    for (const edit of [view => { view.form.buyerLabels[0] = " 甲 "; }, view => { view.buyerMode.value = "default"; }]) {
        const { view, stop } = preparedView("full", null, ["甲", "乙"]);
        try {
            await view.refreshDevices();
            edit(view);
            view.form.buyerLabels = ["甲", "乙"];
            assert.equal(view.invalidatedPreparedId.value, "ready-1");
            assert.equal(view.targetReady.value, false);
        } finally { stop(); }
    }
});

test("custom mode can be configured before confirmation and empty custom mode is invalid", async () => {
    const { view, stop } = preparedView();
    try {
        await view.refreshDevices();
        assert.equal(view.buyerMode.value, "default");
        view.buyerMode.value = "custom";
        assert.deepEqual(view.form.buyerLabels, [""]);
        assert.match(view.runProblem.value, /观演人/);
        view.buyerText.value = "甲\r\n乙";
        assert.deepEqual(view.form.buyerLabels, ["甲", "乙"]);
        assert.doesNotThrow(() => androidConfig(view.form, { ...context(), page: { ...context().page, tiers: [{ label: "内场1680元" }] } }, now));
        view.buyerMode.value = "default";
        assert.deepEqual(view.form.buyerLabels, []);
        assert.equal(view.targetReady.value, false);
    } finally { stop(); }
});

test("switching phones clears custom attendee drafts", async () => {
    const { view, runtime, stop } = preparedView("full", null, ["甲", "乙"]);
    try {
        await view.refreshDevices();
        runtime.deviceId = "other-phone";
        await Promise.resolve();
        assert.deepEqual(view.form.buyerLabels, []);
        assert.equal(view.buyerMode.value, "default");
        assert.equal(view.buyerText.value, "");
    } finally { stop(); }
});

test("confirmation choices use masked display labels and never allow preparation from confirmation", async () => {
    const { view, runtime, stop } = preparedView();
    try {
        await view.refreshDevices();
        const buyers = [{ name: "甲", label: "甲 · 身份证 · ****1234", enabled: true, selected: true }, { name: "乙", label: "乙 · 护照 · ****5678", enabled: false, selected: false }];
        runtime.states["usb-device"].page = { kind: "confirm", buyers, buyersComplete: true };
        assert.deepEqual(view.buyerChoices.value, buyers);
        view.buyerMode.value = "custom";
        view.toggleBuyer(view.buyerChoices.value[0]);
        assert.deepEqual(view.form.buyerLabels, [buyers[0].label]);
        view.toggleBuyer(view.buyerChoices.value[1]);
        assert.deepEqual(view.form.buyerLabels, [buyers[0].label]);
        view.toggleBuyer(view.buyerChoices.value[0]);
        assert.deepEqual(view.form.buyerLabels, [""]);
        assert.equal(view.buyerMode.value, "custom");
        assert.equal(view.targetReady.value, false);
        assert.match(view.runProblem.value, /页面/);
        runtime.states["usb-device"].page = { ...context().page, buyers };
        assert.deepEqual(view.buyerChoices.value, []);
    } finally { stop(); }
});

test("a recreated Android view restores the exact prepared tier and count without invalidating readiness", async () => {
    const { view, stop } = preparedView();
    try {
        await view.refreshDevices();
        assert.equal(view.form.tier, "内场1680元");
        assert.equal(view.form.quantity, 2);
        assert.equal(view.invalidatedPreparedId.value, "");
        assert.equal(view.targetReady.value, true);
        assert.equal(view.runProblem.value, "");
    } finally { stop(); }
});

test("event-only preparation remains executable from the detail page without claiming verified session and tier", async () => {
    const { view, stop } = preparedView("event_only");
    try {
        await view.refreshDevices();
        assert.equal(view.targetReady.value, true);
        assert.equal(view.runProblem.value, "");
        assert.equal(view.stateTitle.value, "演出已核对，等待开售");
        assert.equal(view.preparationDescription.value, "演出已核对；场次、票档待开售后核对");
    } finally { stop(); }
});

test("refreshing devices does not overwrite a user-edited target or restore invalidated preparation", async () => {
    const { view, stop } = preparedView();
    try {
        await view.refreshDevices();
        view.form.quantity = 1;
        view.form.tier = "看台380元";
        assert.equal(view.invalidatedPreparedId.value, "ready-1");
        await view.refreshDevices();
        assert.equal(view.form.quantity, 1);
        assert.equal(view.form.tier, "看台380元");
        assert.equal(view.targetReady.value, false);
        view.form.session = "";
        await view.refreshDevices();
        assert.equal(view.form.session, "");
        assert.equal(view.form.tier, "");
    } finally { stop(); }
});

test("equivalent OCR spacing preserves the selected tier and ready plan", async () => {
    const { view, stop } = preparedView();
    try {
        await view.refreshDevices();
        const session = view.form.session.replaceAll(' ', '');
        view.adoptPage({ page: { kind: 'picker', title: '测试演出', sessions: [{ label: session, selected: true }], tiers: [{ label: '内场1680元' }, { label: '看台380元' }] } });
        assert.equal(view.form.tier, '内场1680元');
        assert.equal(view.targetReady.value, true);
    } finally { stop(); }
});


test("automatic target reads replace malformed typed values with phone labels and the lowest tier", async () => {
    const { view, stop } = preparedView();
    try {
        view.form.session = "2026年6月5日，周一，19点";
        view.form.tier = "内场 980 元";
        view.adoptPage({ page: { kind: "picker", title: "测试演出", sessions: [{ label: "2026-10-05 周一19:00", selected: true }], tiers: [{ label: "内场980元" }, { label: "看台380元" }] } });
        assert.equal(view.form.session, "2026-10-05 周一19:00");
        assert.equal(view.form.tier, "看台380元");
    } finally { stop(); }
});

test("only observed session and tier may be prepared, and payment opt-in invalidates a plan", async () => {
    assert.throws(() => androidConfig({ ...form(), session: "随便填写" }, context(), now), /核对/);
    assert.throws(() => androidConfig({ ...form(), tier: "内场980元" }, context(), now), /核对/);
    const { view, stop } = preparedView();
    try {
        await view.refreshDevices();
        assert.equal(view.form.autoSubmit, false);
        view.form.autoSubmit = true;
        assert.equal(view.targetReady.value, false);
        assert.equal(view.invalidatedPreparedId.value, "ready-1");
    } finally { stop(); }
    assert.equal(androidConfig({ ...form(), autoSubmit: true }, context(), now).autoSubmit, true);
});


test("a submission fence stops automatic page advancement and requires explicit review", async () => {
    const calls = [];
    const { view, stop } = preparedView("full", async (runtime, action, deviceId, extra) => {
        calls.push({ action, extra });
        const state = { deviceId, status: "needs_action", submissionGuard: action === "acknowledge_submission" ? { blocked: false } : { blocked: true, attemptId: "attempt-1", status: "unknown" }, page: { kind: "detail", title: "测试演出", canOpenPicker: true, blockers: [] } };
        runtime.states[deviceId] = state;
        return state;
    });
    try {
        await view.perform("inspect");
        assert.deepEqual(calls.map(c => c.action), ["inspect"]);
        await view.acknowledgeSubmission();
        assert.equal(calls.length, 1);
        view.submissionReviewed.value = true;
        await view.acknowledgeSubmission();
        assert.deepEqual(calls[1], { action: "acknowledge_submission", extra: { attemptId: "attempt-1", acknowledged: true } });
        assert.equal(view.submissionReviewed.value, false);
        assert.equal(view.targetReady.value, false);
    } finally { stop(); }
});

test("attendee-only reads inspect without opening the picker or selecting a session", async () => {
    for (const page of [
        { kind: "detail", title: "测试演出", canOpenPicker: true, blockers: [] },
        { kind: "picker", title: "测试演出", blockers: [], sessions: [{ label: form().session, enabled: true, selected: false }], tiers: [] },
    ]) {
        const calls = [];
        const { view, stop } = preparedView("full", async (runtime, action, deviceId, extra) => {
            calls.push({ action, extra });
            const snapshot = { deviceId, status: "idle", page };
            runtime.states[deviceId] = snapshot;
            return snapshot;
        });
        try {
            await view.perform("inspect", false);
            assert.deepEqual(calls, [{ action: "inspect", extra: {} }]);
        } finally { stop(); }
    }
});

test("incomplete attendee lists cannot change custom choices", async () => {
    for (const buyersComplete of [false, undefined]) {
        const { view, runtime, stop } = preparedView();
        try {
            await view.refreshDevices();
            const buyer = { name: "甲", label: "甲 · 身份证 · ****1234", enabled: true, selected: false };
            runtime.states["usb-device"].page = { kind: "confirm", buyers: [buyer], buyersComplete };
            view.buyerMode.value = "custom";
            view.toggleBuyer(view.buyerChoices.value[0]);
            assert.deepEqual(view.form.buyerLabels, [""]);
        } finally { stop(); }
    }
});

test("one read automatically opens the picker and loads tiers for the observed date", async () => {
    const calls = [];
    const selected = { label: form().session, selected: true, enabled: true };
    const { view, stop } = preparedView("full", async (runtime, action) => {
        calls.push(action);
        const page = action === "inspect" ? { kind: "detail", title: "测试演出", canOpenPicker: true, blockers: [] }
            : { kind: "picker", title: "测试演出", blockers: [], sessions: [{ ...selected, selected: action === "select_session" }], tiers: action === "select_session" ? [{ label: form().tier }] : [] };
        const state = { deviceId: "usb-device", status: "idle", page };
        runtime.states["usb-device"] = state;
        return state;
    });
    try {
        await view.perform("inspect");
        assert.deepEqual(calls, ["inspect", "open_picker", "select_session"]);
        assert.equal(view.form.session, form().session);
        assert.equal(view.form.tier, form().tier);
    } finally { stop(); }
});

test("stop during automatic reading prevents a late response from opening the picker", async () => {
    const calls = [];
    let complete;
    const pending = new Promise(resolve => { complete = resolve; });
    const { view, stop } = preparedView("full", async (_runtime, action) => {
        calls.push(action);
        if (action === "inspect") return pending;
        return { deviceId: "usb-device", status: "cancelled" };
    });
    try {
        const read = view.perform("inspect");
        await view.stopDevice("usb-device");
        complete({ page: { kind: "detail", title: "测试演出", canOpenPicker: true, blockers: [] } });
        await read;
        assert.deepEqual(calls, ["inspect", "stop"]);
    } finally { stop(); }
});


test("the consumed plan remains visible as the current execution target", async () => {
    const { view, stop } = preparedView("full", async (runtime, action) => {
        const previous = runtime.states["usb-device"];
        if (action === "status") return previous;
        assert.equal(action, "start");
        const snapshot = { ...previous, status: "running", config: previous.prepared.config, prepared: null };
        runtime.states["usb-device"] = snapshot;
        return snapshot;
    });
    try {
        await view.refreshDevices();
        await view.perform("start");
        assert.equal(view.targetReady.value, false);
        assert.equal(view.executionTarget.value.tier, "内场1680元");
        assert.equal(view.executionTarget.value.quantity, 2);
    } finally { stop(); }
});
