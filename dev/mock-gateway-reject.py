# Copyright (C) 2026 Verlintas
# SPDX-License-Identifier: GPL-3.0-or-later
#
# This file is part of BetterAIChat2.

#!/usr/bin/env python3
"""Mock LiteLLM gateway that always rejects with an auth/budget style error.

用于真机验证「余额不足，请充值」链路（商业化方案 §344）：把 App 的 LLM 网关
请求（adb reverse 重定向到本服务）打成 401 + 网关拒绝报文，App 侧应把该失败
归类为 REASON_NO_POINTS 并在分屏提示「余额不足，请充值」（充值可点）。

Usage:
    python3 dev/mock-gateway-reject.py [port]     # default 8099
    adb reverse tcp:8081 tcp:8099                 # 测试环境网关是 127.0.0.1:8081
    # 验证完恢复真实服务：adb reverse tcp:8081 tcp:8081
"""

import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8099

# LiteLLM 风格拒绝报文：服务端积分不足（8007）吊销 key 后的真实报文特征。
BODY = (
    b'{"error":{"message":"Authentication Error - User API Key not found. '
    b'Budget has been exceeded for this key (8007 insufficient points)",'
    b'"type":"authentication_error","param":null,"code":401}}'
)


class RejectHandler(BaseHTTPRequestHandler):
    def _reject(self):
        self.send_response(401)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(BODY)))
        self.end_headers()
        self.wfile.write(BODY)

    def do_GET(self):
        self._reject()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0) or 0)
        if length:
            self.rfile.read(length)
        self._reject()

    def log_message(self, fmt, *args):
        print("[mock-gateway-reject]", fmt % args)


if __name__ == "__main__":
    print(f"[mock-gateway-reject] listening on 127.0.0.1:{PORT}")
    ThreadingHTTPServer(("127.0.0.1", PORT), RejectHandler).serve_forever()
