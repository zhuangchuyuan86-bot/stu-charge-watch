"""Personal, read-only charger viewer. Requires Python 3.10+; no packages."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin, urlparse
from urllib.request import Request, urlopen
import argparse
import hashlib
import json
import re
import socket
import threading
import time
import webbrowser

ROOT = Path(__file__).resolve().parent
BASE = "https://wx.isfdz.com/"
DEVICES = tuple(item["id"] for item in json.loads((ROOT / "data/catalog.json").read_text(encoding="utf-8"))["devices"])
MIN_INTERVAL = 60
STATUS_LABELS = {0: "粘连故障", 1: "空闲", 2: "占用", 3: "短路保护", 4: "设备暂停", 5: "高温保护", 6: "过流保护", 7: "过压保护", 8: "欠压保护"}


def utc_now():
    return datetime.now(timezone.utc).isoformat()


class ProviderError(Exception):
    pass


class ScriptParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.sources = []

    def handle_starttag(self, tag, attrs):
        if tag == "script":
            source = dict(attrs).get("src")
            if source:
                self.sources.append(source)


class Provider:
    def __init__(self):
        self.credentials = None
        self.credentials_time = 0
        self.credentials_lock = threading.Lock()
        self.types = {}
        self.cache = {}
        self.locks = {code: threading.Lock() for code in DEVICES}

    @staticmethod
    def download(url, body=None):
        request = Request(url, data=body, headers={"User-Agent": "Mozilla/5.0", "Accept": "application/json,text/html,*/*", "Content-Type": "application/json;charset=UTF-8"})
        try:
            with urlopen(request, timeout=12) as response:
                raw = response.read(3_000_001)
                if len(raw) > 3_000_000:
                    raise ProviderError("平台返回的数据过大，请稍后重试。")
                return raw
        except HTTPError as exc:
            if exc.code in (401, 403):
                raise ProviderError("平台要求正常登录或限制了访问，暂时无法更新。") from None
            raise ProviderError("平台暂时未能响应，请稍后重试。") from None
        except (URLError, TimeoutError, OSError):
            raise ProviderError("连接充电平台失败，请检查电脑网络。") from None

    def client_config(self):
        with self.credentials_lock:
            if self.credentials and time.monotonic() - self.credentials_time < 6 * 3600:
                return self.credentials
            page = self.download(BASE).decode("utf-8", errors="replace")
            parser = ScriptParser()
            parser.feed(page)
            script = next((src for src in parser.sources if re.search(r"static/js/index\.[a-f0-9]+\.js$", src)), None)
            if not script:
                raise ProviderError("平台页面已变化，需要更新连接方式。")
            script_url = urljoin(BASE, script)
            if urlparse(script_url).netloc != urlparse(BASE).netloc:
                raise ProviderError("平台资源地址已变化，需要检查连接方式。")
            source = self.download(script_url).decode("utf-8", errors="replace")
            app_id = re.search(r'app_id:"([^"\n]+)"', source)
            secret = re.search(r'app_secret:"([^"\n]+)"', source)
            if not app_id or not secret:
                raise ProviderError("平台请求格式已变化，需要更新连接方式。")
            # These values belong to the platform's public browser client.
            # Keep them in process memory; never return them to this UI or logs.
            self.credentials = app_id.group(1), secret.group(1)
            self.credentials_time = time.monotonic()
            return self.credentials

    def query(self, endpoint, params, method="GET"):
        allowed = {"charge/mobile/getEquipType", "equi/afterProtocol/getLineInfo", "equi/beforeProtocol/getLineInfo"}
        if endpoint not in allowed:
            raise ProviderError("不支持的查询。")
        app_id, secret = self.client_config()
        data = {**params, "timestamp": int(time.time()), "app_id": app_id}
        canonical = "".join(f"{key}={data[key]}" for key in sorted(data)) + secret
        data["sign"] = hashlib.md5(canonical.encode("utf-8")).hexdigest()
        url = BASE + "api/" + endpoint
        body = None
        if method == "GET":
            url += "?" + urlencode(data)
        else:
            body = json.dumps(data, separators=(",", ":")).encode("utf-8")
        try:
            reply = json.loads(self.download(url, body))
        except (ValueError, UnicodeError):
            raise ProviderError("平台返回格式发生变化。") from None
        if not isinstance(reply, dict) or reply.get("code") != 200:
            raise ProviderError("平台未能返回状态；可能需要正常登录或稍后再试。")
        return reply.get("data")

    def get_station(self, code):
        if code not in self.locks:
            raise ProviderError("尚未添加该设备。")
        with self.locks[code]:
            previous = self.cache.get(code)
            now = time.monotonic()
            if previous and now - previous["attempt"] < MIN_INTERVAL:
                return self.public_record(previous, cached=True)
            record = dict(previous) if previous else {"id": code, "ports": [], "last_success": None}
            record["attempt"] = now
            record["last_attempt"] = utc_now()
            record["error"] = None
            try:
                if code not in self.types:
                    equip_type = self.query("charge/mobile/getEquipType", {"code": code})
                    if type(equip_type) is not int:
                        raise ProviderError("平台返回了无法识别的设备类型。")
                    self.types[code] = equip_type
                equip_type = self.types[code]
                if equip_type in (2, 6, 8, 10, 11, 12, 13, 14):
                    mode = "afterProtocol"
                elif equip_type in (1, 4, 7, 3, 5, 9):
                    mode = "beforeProtocol"
                else:
                    raise ProviderError("该设备类型暂不支持状态查询。")
                ports = self.query(f"equi/{mode}/getLineInfo", {"code": code, "parentCode": ""}, "POST")
                if not isinstance(ports, list) or not 1 <= len(ports) <= 100:
                    raise ProviderError("平台未返回有效的插座列表。")
                normalized = []
                seen = set()
                for port in ports:
                    if not isinstance(port, dict) or type(port.get("num")) is not int or type(port.get("state")) is not int:
                        raise ProviderError("平台插座状态格式发生变化。")
                    num, state = port["num"], port["state"]
                    if not 1 <= num <= 100 or num in seen:
                        raise ProviderError("平台返回了重复或无效的插座编号。")
                    seen.add(num)
                    normalized.append({"num": num, "state": state, "label": STATUS_LABELS.get(state, "未知状态")})
                record["ports"] = sorted(normalized, key=lambda p: p["num"])
                record["last_success"] = utc_now()
            except ProviderError as exc:
                record["error"] = str(exc)
            self.cache[code] = record
            return self.public_record(record, cached=False)

    @staticmethod
    def public_record(record, cached):
        return {key: value for key, value in record.items() if key != "attempt"} | {"cached": cached, "source": "platform"}

    def stations(self):
        with ThreadPoolExecutor(max_workers=2) as pool:
            result = list(pool.map(self.get_station, DEVICES))
        return {"stations": result, "server_time": utc_now(), "minimum_interval_seconds": MIN_INTERVAL}

