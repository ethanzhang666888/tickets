export function androidIsActive(state) {
    return Boolean(state && ["preparing", "waiting", "running"].includes(state.status));
}

export const androidLabelKey = value => String(value || "").normalize("NFKC").replace(/\s+/g, "");

export function connectedAndroidDevice(devices, deviceId) {
    return devices.find(device => device.serial === deviceId && device.state === "device") || null;
}

export function androidScheduleTime(value) {
    const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(String(value || ""));
    if (!match) return null;
    const [, year, month, day, hour, minute, second = "00"] = match;
    const local = Date.UTC(Number(year), Number(month) - 1, Number(day), Number(hour), Number(minute), Number(second));
    if (!Number.isFinite(local) || new Date(local).toISOString().slice(0, 19) !== `${year}-${month}-${day}T${hour}:${minute}:${second}`) return null;
    return local - 8 * 3600_000;
}

export function androidConfig(form, context, now = Date.now()) {
    const backend = context.backend ?? "usb";
    if (!["usb", "legacy"].includes(backend)) throw new Error("请选择有效的安卓执行模式");
    if (backend === "usb" && form.autoSubmit === true) throw new Error("USB 模式仅核验订单，不允许自动提交或付款");
    if (!context.desktop) throw new Error("请在 Tickets 桌面应用中连接手机，浏览器只提供界面预览");
    if (context.submissionGuard?.blocked) throw new Error("存在待核实的订单提交记录，请先在手机检查订单状态并明确确认。");
    if (!connectedAndroidDevice(context.devices, context.deviceId)) throw new Error("请选择已通过 USB 授权的在线设备");
    if (context.page?.kind === "reservation") throw new Error("当前是预约页面，不是购票页面。请在手机返回真正的演出详情后重新读取");
    const eventTitle = String(form.eventTitle || "").trim();
    const session = String(form.session || "").trim();
    const tier = String(form.tier || "").trim();
    if (!eventTitle) throw new Error("请先读取手机上的目标演出");
    if (!session) throw new Error("请从手机读取并选择场次");
    if (!tier) throw new Error("请从手机读取并选择票档");
    if (!Number.isInteger(form.quantity) || form.quantity < 1 || form.quantity > 20) throw new Error("购票张数须为 1–20 的整数，实际以该场限购为准");
    if (!["detail", "picker"].includes(context.page?.kind)) throw new Error("请在手机打开演出详情或选票页面，再读取页面");
    if (context.page?.blockers?.length) throw new Error(context.page.blockers.join("；"));
    const verifiedTitle = String(context.page?.title || context.verifiedTitle || "").trim();
    if (!verifiedTitle || verifiedTitle !== eventTitle) throw new Error("演出与最近读取的页面不一致，请重新读取确认");
    const observed = context.catalog || context.page;
    if (String(observed?.title || "").trim() !== eventTitle
        || (observed.sessions || []).filter(item => androidLabelKey(item.label) === androidLabelKey(session)).length !== 1
        || (observed.tiers || []).filter(item => androidLabelKey(item.label) === androidLabelKey(tier)).length !== 1
        || !observed.sessions?.some(item => item.selected && androidLabelKey(item.label) === androidLabelKey(session))) {
        throw new Error("场次或票档尚未从手机核对，请自动读取该场票档");
    }
    if (form.autoSubmit !== undefined && typeof form.autoSubmit !== "boolean") throw new Error("自动提交选项无效");
    const labels = form.buyerLabels === undefined ? [] : form.buyerLabels;
    if (!Array.isArray(labels)) throw new Error("观演人设置须为姓名或显示标签列表");
    if (labels.length && labels.length !== form.quantity) throw new Error("自定义观演人人数须与购票张数一致");
    if (labels.some(label => typeof label !== "string" || !androidLabelKey(label) || [...label].length > 300 || /[\r\n\0]/.test(label))) {
        throw new Error("每位观演人须填写非空姓名或显示标签，最多 300 个字符，不得包含换行或空字符");
    }
    if (new Set(labels.map(androidLabelKey)).size !== labels.length) throw new Error("自定义观演人不能重复，请核对姓名或显示标签");
    const buyerLabels = labels.map(label => label.trim());
    const startAt = form.scheduled ? androidScheduleTime(form.startAt) : null;
    if (form.scheduled && (!startAt || startAt <= now)) throw new Error("请选择晚于当前时间的开始时间（北京时间）");
    const scheduleLimit = backend === "usb" ? 86400_000 : 7 * 86400_000;
    if (form.scheduled && startAt > now + scheduleLimit) throw new Error(`开始时间须在未来 ${backend === "usb" ? "24 小时" : "7 天"}内（北京时间）`);
    return { eventTitle, session, tier, quantity: form.quantity, buyerLabels, autoSubmit: form.autoSubmit === true, startAt, timeoutSeconds: 120 };
}

export function androidLowestTier(tiers = []) {
    const priced = tiers.flatMap(item => {
        const label = String(item.label || "").trim();
        const prices = [...label.normalize("NFKC").matchAll(/(\d+(?:\.\d+)?)\s*元/g)];
        return prices.length === 1 && Number(prices[0][1]) > 0 ? [{ label, price: Number(prices[0][1]) }] : [];
    });
    const lowest = Math.min(...priced.map(item => item.price));
    const matches = [...new Set(priced.filter(item => item.price === lowest).map(item => item.label))];
    return matches.length === 1 ? matches[0] : "";
}

export function androidTargetMatchesPrepared(form, prepared) {
    const config = prepared?.config;
    const buyerLabels = form.buyerLabels === undefined ? [] : form.buyerLabels;
    return Boolean(prepared?.id && config
        && ["eventTitle", "session", "tier"].every(key => androidLabelKey(form[key]) === androidLabelKey(config[key]))
        && Number.isInteger(form.quantity) && form.quantity === config.quantity
        && (form.autoSubmit === true) === (config.autoSubmit === true)
        && Array.isArray(config.buyerLabels) && Array.isArray(buyerLabels)
        && buyerLabels.length === config.buyerLabels.length
        && buyerLabels.every((label, index) => typeof label === "string" && typeof config.buyerLabels[index] === "string"
            && androidLabelKey(label) === androidLabelKey(config.buyerLabels[index])));
}

export function androidPreparedConfig(form, context, state, now = Date.now()) {
    const config = androidConfig(form, context, now);
    if (state?.deviceId !== context.deviceId) throw new Error("已准备的手机与当前选择不一致，请重新锁定目标");
    if (state?.status !== "ready" || !["selected", "waiting_sale"].includes(state.prepared?.readiness)) throw new Error("请先锁定目标并完成预热，再开始执行");
    if (!androidTargetMatchesPrepared(form, state.prepared) || context.invalidatedPreparedId === state.prepared.id) throw new Error("目标已经修改，请重新锁定并预热");
    return config;
}
