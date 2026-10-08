"""Damai 9.0.35 page observations and guarded next-step decisions."""
import io
from decimal import Decimal, InvalidOperation
import re
import unicodedata
import xml.etree.ElementTree as ET

PACKAGE = "cn.damai"
PREFIX = PACKAGE + ":id/"
DETAIL_TITLE = "info_v2_title_tv1"
DETAIL_BUTTON = "trade_project_detail_purchase_status_bar_container_fl"
SESSION_LIST = "project_detail_perform_flowlayout"
TIER_LIST = "project_detail_perform_price_flowlayout"
SOLD_OUT = ("缺货登记", "缺货", "无票", "售罄", "售完")
UPCOMING = ("未开售", "待开售", "开售提醒", "预约抢票", "预约想看", "立即预约", "已预约")
UNAVAILABLE = ("暂不可售",)


def normalized(value):
    return re.sub(r"\s+", "", unicodedata.normalize("NFKC", str(value)))


def event_key(value):
    value = normalized(value).replace('•', '·')
    return re.sub(r'^【([^】]+)】', r'\1·', value)


def session_key(value):
    match = re.search(r'(20\d{2})[-./](\d{2})[-./](\d{2}).*?(\d{1,2})[:：](\d{2})', normalized(value))
    return tuple(map(int, match.groups())) if match else None


def money(value):
    try:
        return int(Decimal(value) * 100) if Decimal(value) > 0 else None
    except (InvalidOperation, ValueError):
        return None


def availability(words, enabled=True):
    text = " ".join(words)
    if any(term in text for term in SOLD_OUT):
        return "sold_out"
    if any(term in text for term in UPCOMING):
        return "upcoming"
    if not enabled or any(term in text for term in UNAVAILABLE):
        return "unavailable"
    return "available"


def bounds(node):
    numbers = re.findall(r"\d+", node.get("bounds", ""))
    return list(map(int, numbers)) if len(numbers) == 4 else [0, 0, 0, 0]


def within(inner, outer):
    x = (inner[0] + inner[2]) / 2
    y = (inner[1] + inner[3]) / 2
    return outer[0] <= x <= outer[2] and outer[1] <= y <= outer[3]


def selected_border(image, box):
    if image is None:
        return False
    left, top, right, bottom = box
    if right - left < 40 or bottom - top < 25 or right > image.width or bottom > image.height:
        return False
    samples = []
    for inset in (1, 2, 3, 4):
        for x in range(left + 20, right - 20, 3):
            samples.extend([image.getpixel((x, top + inset)), image.getpixel((x, bottom - 1 - inset))])
        for y in range(top + 20, bottom - 20, 3):
            samples.extend([image.getpixel((left + inset, y)), image.getpixel((right - 1 - inset, y))])
    return bool(samples) and sum(r > 200 and 30 < g < 180 and b < 125 for r, g, b in samples) / len(samples) > .16


class Page:
    def __init__(self, xml, recognized=(), png=None, image=None):
        self.root = ET.fromstring(xml)
        self.nodes = [n for n in self.root.iter("node") if n.get("package") == PACKAGE]
        self.recognized = recognized
        self.image = image
        if png is not None:
            from PIL import Image
            self.image = Image.open(io.BytesIO(png)).convert("RGB")
        self.targets = {}
        self.data = self._parse()

    def ocr_regions(self):
        if self.data['kind'] in ('picker', 'detail', 'reservation'):
            ids = (SESSION_LIST, TIER_LIST, 'btn_buy_view', DETAIL_BUTTON)
            return [bounds(n) for rid in ids for n in self.nodes_by_id(rid)]
        return []

    def nodes_by_id(self, rid):
        return [n for n in self.nodes if n.get("resource-id") == PREFIX + rid]

    def node(self, rid):
        found = self.nodes_by_id(rid)
        return found[0] if len(found) == 1 else None

    def text(self, rid):
        n = self.node(rid)
        return n.get("text", "").strip() if n is not None else ""

    def words_in(self, box):
        return [r for r in self.recognized if r.get("confidence", 0) >= .5 and within(r["bounds"], box)]

    def options(self, rid, kind):
        container = self.node(rid)
        if container is None:
            return []
        choices = []
        for child in container:
            card = next((n for n in child.iter("node") if n.get("resource-id") == PREFIX + "ll_perform_item"), child)
            box = bounds(card)
            words = [n.get("text", "") for n in card.iter("node") if n.get("text")]
            words += [r["text"] for r in self.words_in(box)]
            candidates = []
            for word in words:
                label = word.strip()
                for suffix in SOLD_OUT + UPCOMING + UNAVAILABLE:
                    label = label.replace(suffix, "").strip()
                if kind == "session":
                    label = re.sub(r"^[休班]\s*", "", label)
                    if re.search(r"(?:20\d{2}[-./])?\d{1,2}[-./]\d{1,2}.*\d{1,2}[:：]\d{2}", label):
                        candidates.append(label)
                elif re.search(r"\d+(?:\.\d+)?\s*元", label):
                    candidates.append(label)
            unique = {normalized(s): s for s in candidates}
            if len(unique) != 1:
                continue
            label = next(iter(unique.values()))
            state = availability(words, card.get("enabled") != "false")
            enabled = state == "available"
            selected = any(n.get("selected") == "true" or n.get("checked") == "true" for n in card.iter("node")) or selected_border(self.image, box)
            choices.append({"label": label, "selected": selected, "enabled": enabled, "availability": state})
            self.targets[(kind, normalized(label))] = box
        return choices

    def _parse(self):
        texts = [n.get("text", "") or n.get("content-desc", "") for n in self.nodes]
        texts += [r["text"] for r in self.recognized if r.get("confidence", 0) >= .5]
        joined = "\n".join(texts)
        data = {"kind": "unknown", "title": "", "sessions": [], "tiers": [], "quantity": None,
                "buyers": [], "canOpenPicker": False, "blockers": []}
        if any(term in joined for term in ("访问异常", "操作过于频繁", "请完成安全验证", "滑动验证", "拖动滑块", "账号存在风险")):
            data.update(kind="risk", blockers=["手机出现验证或访问异常，请人工处理后重新读取。"])
            return data
        if self.node("damai_theme_dialog_confirm_btn") is not None:
            data["blockers"].append("请先在手机阅读并处理当前提示弹窗。")
        if any(term in joined for term in ('预约想看场次', '预约想看票档', '提前预约想看的场次')):
            data.update(kind='reservation', title=self.text('title_tv') or self.text('tv_title'),
                        blockers=['当前是预约想看页面，不是购票页面。请返回演出详情，打开正式选票入口。'])
            data['sessions'] = self.options(SESSION_LIST, 'session')
            data['tiers'] = self.options(TIER_LIST, 'tier')
            self.targets.clear()
            return data
        if self.text('order_activity_title') in ('确认购买', '确认订单', '订单确认'):
            self._confirmation(data)
        elif self.node(TIER_LIST) is not None or self.node(SESSION_LIST) is not None:
            data.update(kind="picker", title=self.text("tv_title") or self.text("tv_sku_state_view_base_info_title") or self.text('title_tv'))
            data["sessions"] = self.options(SESSION_LIST, "session")
            data["tiers"] = self.options(TIER_LIST, "tier")
            if not data["sessions"]:
                data["blockers"].append("场次没有被完整识别，请调整手机页面后重新读取。")
            self._quantity(data)
            button = self.node("btn_buy_view")
            if button is not None:
                labels = [r["text"].strip() for r in self.words_in(bounds(button))]
                data["nextLabel"] = next((label for label in labels if label in ("确定", "立即购买", "立即预订")), "")
                self.targets["next"] = bounds(button)
        elif self.node(DETAIL_TITLE) is not None:
            button = self.node(DETAIL_BUTTON)
            data.update(kind="detail", title=self.text(DETAIL_TITLE))
            if button is not None:
                words = [n.get("text", "") or n.get("content-desc", "") for n in button.iter("node")]
                words += [r["text"] for r in self.words_in(bounds(button))]
                state = availability(words, button.get("enabled") != "false")
                known_buy = any(normalized(word) in ('立即购票', '立即购买', '立即预订', '选票购买', '立即抢票') for word in words)
                data.update(purchaseAvailability=state, canOpenPicker=state == "available" and known_buy)
                if data["canOpenPicker"]:
                    self.targets["open_picker"] = bounds(button)
        elif any("提交订单" == t.strip() for t in texts) and any(term in joined for term in ("观演人", "确认订单", "订单确认")):
            data["kind"] = "confirm"
        elif any(term in joined for term in ("正在排队", "排队中，请稍候", "前方排队人数")):
            data["kind"] = "queue"
        elif ("手机号" in joined or "验证码" in joined) and any(term in joined for term in ("登录", "注册")):
            data.update(kind="login", blockers=["请先在手机完成登录。"])
        elif self.node("bottom_tab_item_tv") is not None or any(n.get("resource-id") == PREFIX + "bottom_tab_item_tv" for n in self.nodes):
            data["kind"] = "home"
        return data

    def _confirmation(self, data):
        data['kind'] = 'confirm'
        content = self.node('recycler_view')
        bottom = self.node('bottom_layout')
        if content is None or bottom is None or not len(content):
            return
        header = [n.get('text', '').strip() for n in content[0].iter('node') if n.get('text')]
        dates = [t for t in header if re.fullmatch(r'20\d{2}\.\d{2}\.\d{2}\s+\d{2}:\d{2}', t)]
        prices = [re.fullmatch(r'[￥¥]\s*(\d+(?:\.\d{1,2})?)票档', t) for t in header]
        prices = [money(m[1]) for m in prices if m]
        counts = [re.fullmatch(r'[×x]\s*(\d+)张', t) for t in header]
        counts = [int(m[1]) for m in counts if m]
        totals = [re.fullmatch(r'[￥¥]\s*(\d+(?:\.\d{1,2})?)', n.get('text', '')) for n in bottom.iter('node')]
        totals = [money(m[1]) for m in totals if m]
        data.update(title=header[0] if header else '', session=dates[0] if len(dates) == 1 else '',
                    unitPrice=prices[0] if len(prices) == 1 else None,
                    totalPrice=totals[0] if len(totals) == 1 else None,
                    quantity=counts[0] if len(counts) == 1 else None)
        self._buyers(data, content)
        rows = self.nodes_by_id('rl_pay')
        alipay = [n for n in rows if any(c.get('resource-id') == PREFIX + 'tv_pay_title' and c.get('text') == '支付宝' for c in n.iter('node'))]
        if len(alipay) == 1:
            markers = [c for c in alipay[0].iter('node') if c.get('resource-id') == PREFIX + 'cb_pay_check']
            data['alipaySelected'] = len(markers) == 1 and markers[0].get('text') == '\ue690'
            self.targets['select_alipay'] = bounds(alipay[0])
        buttons = [n for n in bottom.iter('node') if n.get('text') in ('立即提交', '提交订单')
                   and n.get('clickable') == 'true' and n.get('enabled') == 'true']
        if len(buttons) == 1:
            self.targets['submit'] = bounds(buttons[0])

    def _buyers(self, data, content):
        # Only the measured native attendee list is actionable. Reject partial/scrolling
        # lists rather than mistaking the first visible row for the first saved person.
        data.update(buyers=[], buyersComplete=False, selectedBuyerCount=0)
        lists = [n for n in content.iter('node') if n.get('resource-id') == PREFIX + 'recycler_main']
        if len(lists) != 1:
            return
        container = lists[0]
        def visible(node):
            return node.get('visible-to-user') == 'true' and node.get('package') == PACKAGE
        def contained(node, parent):
            a, b = bounds(node), bounds(parent)
            return b[0] <= a[0] < a[2] <= b[2] and b[1] <= a[1] < a[3] <= b[3]
        if (not visible(container) or container.get('scrollable') != 'false'
                or not contained(container, content) or not 1 <= len(container) <= 20):
            return
        buyers, targets = [], {}
        for index, row in enumerate(container):
            if (row.get('resource-id') != PREFIX + 'layout_main' or row.get('index') != str(index)
                    or not visible(row) or not contained(row, container)):
                return
            fields = {}
            for rid in ('text_name', 'text_num_type', 'text_num', 'checkbox'):
                matches = [n for n in row.iter('node') if n.get('resource-id') == PREFIX + rid]
                if len(matches) != 1 or not visible(matches[0]) or not contained(matches[0], row):
                    return
                fields[rid] = matches[0]
            name, doc_type, masked = (fields[rid].get('text', '').strip()
                                      for rid in ('text_name', 'text_num_type', 'text_num'))
            # Never export or match an unmasked identity number.
            if (not name or len(name) > 80 or not doc_type or len(doc_type) > 40
                    or any(c in name + doc_type for c in '\r\n\0')
                    or not re.fullmatch(r'[A-Za-z0-9*•·]{4,40}', masked)
                    or not re.search(r'[*•·]{2,}', masked)):
                return
            label = f'{name} · {doc_type} · {masked}'
            check = fields['checkbox']
            if check.get('checkable') != 'true' or check.get('checked') not in ('true', 'false'):
                return
            enabled = row.get('enabled') == 'true' and check.get('enabled') == 'true' and check.get('clickable') == 'true'
            buyers.append({'label': label, 'name': name, 'selected': check.get('checked') == 'true', 'enabled': enabled})
            targets[('buyer', normalized(label), check.get('checked') != 'true')] = bounds(check)
        if (len({normalized(b['label']) for b in buyers}) != len(buyers)
                or len(self.nodes_by_id('checkbox')) != len(buyers)
                or len(self.nodes_by_id('text_name')) != len(buyers)):
            return
        data.update(buyers=buyers, buyersComplete=True, selectedBuyerCount=sum(b['selected'] for b in buyers))
        self.targets.update(targets)

    def _quantity(self, data):
        container = self.node("layout_sku_num_ll")
        if container is None:
            return
        children = [n for n in container if n.tag == "node" and n.get("package") == PACKAGE]
        counts = [n for n in children if n.get("resource-id") == PREFIX + "tv_num"]
        if len(counts) != 1:
            return
        match = re.fullmatch(r"([1-9]\d?)张", counts[0].get("text", ""))
        if match is None or int(match.group(1)) > 20:
            return
        data["quantity"] = int(match.group(1))
        if container.get("enabled") == "false":
            return
        for rid, target in (("img_jian", "decrease"), ("img_jia", "increase")):
            buttons = [n for n in children if n.get("resource-id") == PREFIX + rid]
            if len(buttons) == 1 and buttons[0].get("enabled") == "true" and buttons[0].get("clickable") == "true":
                self.targets[target] = bounds(buttons[0])


def validate_config(config, now_ms):
    if not isinstance(config, dict):
        raise ValueError("缺少演练配置。")
    for key in ("eventTitle", "session", "tier"):
        value = config.get(key)
        if not isinstance(value, str) or not value.strip() or len(value) > 300 or any(c in value for c in "\r\n\0"):
            raise ValueError("请明确演出、场次与票档。")
    if type(config.get("quantity")) is not int or not 1 <= config["quantity"] <= 20:
        raise ValueError("张数必须为 1–20 的整数。")
    if type(config.get('autoSubmit', False)) is not bool:
        raise ValueError('自动提交选项必须为勾选或未勾选。')
    buyers = config.get("buyerLabels", [])
    if (not isinstance(buyers, list) or (buyers and len(buyers) != config['quantity'])
            or any(not isinstance(b, str) or not b.strip() or len(b) > 300
                   or any(c in b for c in '\r\n\0') for b in buyers)
            or len({normalized(b) for b in buyers}) != len(buyers)):
        raise ValueError('自定义观演人须与张数一致，姓名或显示标识不能为空或重复。')
    start = config.get("startAt")
    if start is not None and (type(start) is not int or start <= now_ms or start > now_ms + 7 * 86400_000):
        raise ValueError("开始时间须在未来 7 天内。")
    timeout = config.get("timeoutSeconds", 120)
    if type(timeout) is not int or not 10 <= timeout <= 600:
        raise ValueError("演练超时须为 10–600 秒。")


def next_step(page, config):
    data = page.data
    if data["blockers"]:
        return "pause", data["blockers"][0]
    kind = data["kind"]
    if kind == "confirm":
        # Even attendee-only changes require the order to match the authorized target.
        prices = re.findall(r'(\d+(?:\.\d{1,2})?)元', normalized(config['tier']))
        price = money(prices[0]) if len(prices) == 1 else None
        if (event_key(data['title']) != event_key(config['eventTitle'])
                or not session_key(data.get('session', ''))
                or session_key(data['session']) != session_key(config['session'])
                or not price or data.get('unitPrice') != price or data['quantity'] != config['quantity']
                or not data.get('totalPrice') or data['totalPrice'] > price * config['quantity']):
            return 'pause', '确认页的演出、场次、金额或张数未能核对一致，请在手机检查。'
        buyers = data.get('buyers', [])
        if not data.get('buyersComplete') or len(buyers) < config['quantity']:
            return 'pause', '观演人列表未完整识别或人数不足，请在手机检查；不会换人或猜测。'
        labels = config.get('buyerLabels', [])
        desired = buyers[:config['quantity']]
        if labels:
            matches = [[b for b in buyers if normalized(label) in (normalized(b['label']), normalized(b['name']))]
                       for label in labels]
            if len(labels) != config['quantity'] or any(len(group) != 1 for group in matches):
                return 'pause', '指定观演人不存在或同名信息有歧义，请使用完整显示标识；不会改选其他人。'
            desired = [group[0] for group in matches]
        keys = {normalized(b['label']) for b in desired}
        if len(keys) != config['quantity'] or any(not b['enabled'] for b in desired):
            return 'pause', '指定观演人重复或不可选，已停止；不会跳过或替换。'
        # Clear unwanted choices first to avoid exceeding the platform's attendee limit.
        changes = [(b, False) for b in buyers if b['selected'] and normalized(b['label']) not in keys]
        changes += [(b, True) for b in desired if not b['selected']]
        if changes:
            buyer, selected = changes[0]
            action = ('buyer', normalized(buyer['label']), selected)
            if not buyer['enabled'] or action not in page.targets:
                return 'pause', '观演人勾选控件不可用，已停止。'
            return action, '勾选目标观演人后重新核对' if selected else '取消非目标观演人的勾选后重新核对'
        if data.get('selectedBuyerCount') != config['quantity']:
            return 'pause', '观演人勾选人数与张数不一致，已停止。'
        if config.get('autoSubmit') is True:
            if not data.get('alipaySelected'):
                return ('select_alipay', '选择支付宝支付方式') if 'select_alipay' in page.targets else ('pause', '未找到支付宝支付方式，请在手机处理。')
            return ('submit', '提交一次订单，等待支付宝打开') if 'submit' in page.targets else ('pause', '没有唯一可用的提交按钮，请在手机处理。')
        return 'confirm', '已按配置选好观演人并核对订单，请本人检查后手动提交。'
    if kind == 'payment':
        return 'payment', '已打开支付宝，自动操作已停止，请本人核对金额并完成支付。'
    if kind == "queue":
        return "wait", "手机正在排队，等待官方页面结果。"
    if kind not in ("detail", "picker"):
        return "pause", "当前页面无法继续自动操作，请在手机处理后重新读取。"
    if normalized(data["title"]) != normalized(config["eventTitle"]):
        return "pause", "手机上的演出与配置不一致，已停止。"
    if kind == "detail":
        if data.get("purchaseAvailability") == "upcoming":
            return "wait", "目标演出尚未开售，只读取页面等待开放，不设置开售提醒。"
        return ("open_picker", "打开选票页面") if data["canOpenPicker"] else ("pause", "当前详情页没有可识别的选票入口。")
    for key, options in (("session", data["sessions"]), ("tier", data["tiers"])):
        matches = [option for option in options if normalized(option["label"]) == normalized(config[key])]
        if len(matches) != 1:
            return "pause", "目标场次或票档不可见或存在歧义，请重新读取。"
        choice = matches[0]
        if choice.get("availability") == "upcoming":
            return "wait", "目标场次或票档尚未开售，只读取页面等待开放，不设置提醒或缺货登记。"
        if not choice["enabled"]:
            return "pause", "目标场次或票档当前无票或不可选；未换档，未提交缺货登记。"
        if not choice["selected"]:
            return (key, normalized(choice["label"])), "选择" + ("场次" if key == "session" else "票档")
    quantity = data["quantity"]
    if quantity is None:
        return "pause", "无法确认当前张数，请在手机选好数量后重新读取。"
    if quantity != config["quantity"]:
        direction = "increase" if quantity < config["quantity"] else "decrease"
        if direction in page.targets:
            return direction, "按当前数量调整一张后重新核对"
        return "pause", "当前张数与目标不一致且数量按钮不可用，请在手机调整后重新读取。"
    if data.get("nextLabel") and "next" in page.targets:
        return "next", "进入订单确认页"
    return "pause", "确认入口不可识别或当前不能购买，请在手机处理。"
