# /// script
# requires-python = ">=3.10"
# dependencies = ["uiautomator2==3.7.0"]
# ///
"""Inspect one Android device; --ui temporarily starts a UIAutomator reader."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


def choose_device(output, requested=None):
    rows = []
    for line in output.splitlines():
        parts = line.split()
        if len(parts) >= 2 and not line.startswith(("List of devices", "*")):
            rows.append((parts[0], parts[1]))
    if requested:
        rows = [row for row in rows if row[0] == requested]
    if not rows:
        raise RuntimeError("未找到指定设备，请检查 USB 数据线和 USB 调试。")
    if len(rows) != 1:
        raise RuntimeError("检测到多台设备，请用 --serial 指定本次测试的手机。")
    serial, state = rows[0]
    if state != "device":
        hint = "请在手机上确认 USB 调试授权。" if state == "unauthorized" else "请重新连接 USB。"
        raise RuntimeError(f"设备状态为 {state}；{hint}")
    return serial


def summarize_hierarchy(xml):
    root = ET.fromstring(xml)
    nodes = [n for n in root.iter("node") if n.get("package") == "cn.damai"]
    labels = {"首页", "我的", "搜索", "立即购买", "立即预订", "选座购买", "提交订单", "登录", "立即登录"}
    risk_terms = {"访问异常", "操作频繁", "安全验证", "风控", "网络异常"}
    texts = {n.get(field, "") for n in nodes for field in ("text", "content-desc")}
    return {
        "damaiNodes": len(nodes),
        "clickableNodes": sum(n.get("clickable") == "true" for n in nodes),
        "nodesWithId": sum(bool(n.get("resource-id")) for n in nodes),
        "knownLabels": sorted(labels & texts),
        "riskKeywords": sorted(term for term in risk_terms if any(term in text for text in texts)),
    }


def main():
    parser = argparse.ArgumentParser(description="华为/安卓真机检查：默认只检查连接；--ui 读取大麦页面，不点击。")
    parser.add_argument("--serial", help="多设备时明确指定 ADB 设备序列号")
    parser.add_argument("--adb", help="ADB 可执行文件路径")
    parser.add_argument("--ui", action="store_true", help="临时启动 UIAutomator2 读取当前大麦页面，结束后停止")
    args = parser.parse_args()
    adb = args.adb or shutil.which("adb")
    if not adb and Path("/opt/homebrew/bin/adb").is_file():
        adb = "/opt/homebrew/bin/adb"
    if not adb:
        parser.exit(1, "未找到 ADB，请安装 Android platform-tools 或传入 --adb。\n")

    serial = None
    report = {"schemaVersion": 1, "mode": "ui_probe" if args.ui else "connection", "status": "checking"}

    def command(*parts):
        result = subprocess.run([adb, *parts], capture_output=True, text=True, timeout=15)
        if result.returncode:
            raise RuntimeError((result.stderr or result.stdout).strip() or "ADB 命令失败")
        return result.stdout.strip()

    def shell(*parts):
        return command("-s", serial, "shell", *parts)

    try:
        serial = choose_device(command("devices"), args.serial)
        report["device"] = {
            "manufacturer": shell("getprop", "ro.product.manufacturer"),
            "model": shell("getprop", "ro.product.model"),
            "androidRelease": shell("getprop", "ro.build.version.release"),
            "sdk": shell("getprop", "ro.build.version.sdk"),
            "harmonyVersion": shell("getprop", "hw_sc.build.platform.version"),
            "display": shell("wm", "size"),
        }
        package = shell("dumpsys", "package", "cn.damai")
        version = re.search(r"^\s*versionName=(\S+)", package, re.M)
        report["damaiVersion"] = version.group(1) if version else None
        if not version:
            raise RuntimeError("这台手机没有检测到大麦 cn.damai。")
        activities = shell("dumpsys", "activity", "activities")
        resumed = [line for line in activities.splitlines() if re.search(r"mResumedActivity|topResumedActivity", line)]
        report["damaiForeground"] = any("cn.damai/" in line for line in resumed)
        samples = []
        for _ in range(5):
            start = time.monotonic()
            if shell("echo", "tickets-probe") != "tickets-probe":
                raise RuntimeError("ADB 通信响应不符合预期。")
            samples.append(round((time.monotonic() - start) * 1000, 1))
        report["adbRoundTripMs"] = {"samples": samples, "median": statistics.median(samples)}
        report["status"] = "connection_ok"
        if args.ui:
            if not report["damaiForeground"]:
                raise RuntimeError("请先手动将大麦打开至前台；检查工具不会自动切换应用。")
            if re.search(r"uiautomator|com\.wetest\.u2", shell("ps", "-A"), re.I):
                raise RuntimeError("已有 UIAutomator 服务在运行，为避免干扰现有任务，本次未启动读取。")
            if shell("sh", "-c", "test -e /data/local/tmp/u2.jar && echo exists || echo absent") == "exists":
                raise RuntimeError("已有 /data/local/tmp/u2.jar，本次不覆盖；请先确认现有自动化环境。")
            os.environ["ADBUTILS_ADB_PATH"] = adb
            import uiautomator2 as u2

            device = None
            try:
                start = time.monotonic()
                device = u2.connect(serial)
                report["driverConnectMs"] = round((time.monotonic() - start) * 1000)
                start = time.monotonic()
                summary = summarize_hierarchy(device.dump_hierarchy())
                summary["readMs"] = round((time.monotonic() - start) * 1000)
                report["ui"] = summary
                if not summary["damaiNodes"]:
                    raise RuntimeError("已连上手机，但未读到大麦页面节点；不能判定自动化可用。")
                report["status"] = "needs_review" if summary["riskKeywords"] else "ui_read_ok"
            finally:
                if device is not None:
                    device.stop_uiautomator()
                    report["readerStopped"] = True
                    shell("rm", "-f", "/data/local/tmp/u2.jar")
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if report["status"] in ("connection_ok", "ui_read_ok") else 2
    except Exception as error:
        message = str(error)
        if serial:
            message = message.replace(serial, "[device]")
        report.update(status="incomplete", error=message)
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
