"""门户发布检查回归：缺文件、错误回落页和空资源不能通过验收。"""
from email.message import Message
from io import BytesIO
import unittest
from unittest.mock import patch
from urllib.error import HTTPError

from portal_health import check_portal


def response(body, content_type):
    result = BytesIO(body)
    result.status = 200
    result.headers = Message()
    result.headers['Content-Type'] = content_type
    return result


class PortalHealthTest(unittest.TestCase):
    def entry(self):
        return response(b'<script src="/assets/main.js"></script><link rel="stylesheet" href="/assets/main.css">', 'text/html')

    def test_reads_script_and_stylesheet(self):
        with patch('portal_health.urlopen', side_effect=[self.entry(), response(b'code', 'text/javascript'), response(b'css', 'text/css')]) as request:
            check_portal()
        self.assertEqual(request.call_count, 3)

    def test_missing_script_fails(self):
        with patch('portal_health.urlopen', side_effect=[self.entry(), HTTPError('http://localhost/assets/main.js', 404, 'Not found', {}, None)]):
            with self.assertRaises(HTTPError):
                check_portal()

    def test_html_fallback_fails(self):
        with patch('portal_health.urlopen', side_effect=[self.entry(), response(b'<html/>', 'text/html')]):
            with self.assertRaises(RuntimeError):
                check_portal()

    def test_empty_asset_fails(self):
        with patch('portal_health.urlopen', side_effect=[self.entry(), response(b'', 'text/javascript')]):
            with self.assertRaises(RuntimeError):
                check_portal()

    def test_empty_entry_fails(self):
        with patch('portal_health.urlopen', return_value=response(b'<div id="app"/>', 'text/html')):
            with self.assertRaises(RuntimeError):
                check_portal()


if __name__ == '__main__':
    unittest.main()
