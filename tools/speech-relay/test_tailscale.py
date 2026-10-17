"""Tailscale Serve 专用端口配置：幂等、拒绝覆盖及拒绝公开 Funnel。"""

import json
import unittest
from unittest.mock import patch

from tailscale_link import TailscaleLink


class TailscaleTests(unittest.TestCase):
    status = json.dumps({"BackendState": "Running", "Self": {"DNSName": "pc.tailtest.ts.net."}})

    def test_creates_private_https_proxy_on_dedicated_port(self):
        link = TailscaleLink("tailscale", 21835)
        with patch.object(link, "command", side_effect=[self.status, "{}", "configured"]) as command:
            self.assertEqual(link.start(), "https://pc.tailtest.ts.net:8443")
            self.assertEqual(command.call_args.args, ("serve", "--bg", "--https=8443", "http://127.0.0.1:21835"))

    def test_existing_matching_proxy_needs_no_mutation(self):
        config = {"TCP": {"8443": {"HTTPS": True}}, "Web": {"pc.tailtest.ts.net:8443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:21835"}}}}}
        link = TailscaleLink("tailscale", 21835)
        with patch.object(link, "command", side_effect=[self.status, json.dumps(config)]) as command:
            link.start()
            self.assertEqual(command.call_count, 2)

    def test_refuses_other_routes_and_public_funnel(self):
        for config in ({"TCP": {"8443": {"TCPForward": "127.0.0.1:22"}}}, {"AllowFunnel": {"pc.tailtest.ts.net:8443": True}}):
            link = TailscaleLink("tailscale", 21835)
            with patch.object(link, "command", side_effect=[self.status, json.dumps(config)]) as command:
                with self.assertRaises(RuntimeError):
                    link.start()
                self.assertEqual(command.call_count, 2)

    def test_login_is_required_before_configuring_serve(self):
        link = TailscaleLink("tailscale", 21835)
        with patch.object(link, "command", return_value=json.dumps({"BackendState": "NeedsLogin"})) as command:
            with self.assertRaises(RuntimeError):
                link.start()
            self.assertEqual(command.call_count, 1)
