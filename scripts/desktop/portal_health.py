"""核验门户入口依赖的脚本和样式，避免首页 200 掩盖静态资源白屏。"""
from html.parser import HTMLParser
from urllib.parse import urljoin
from urllib.request import urlopen


class EntryAssets(HTMLParser):
    def __init__(self):
        super().__init__()
        self.assets = {}

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        path = attrs.get('src') if tag == 'script' else None
        if tag == 'link' and attrs.get('rel') == 'stylesheet':
            path = attrs.get('href')
        if path and path.startswith('/assets/'):
            self.assets[path] = ('text/css',) if tag == 'link' else ('text/javascript', 'application/javascript')


def check_portal(url='http://127.0.0.1:5100/'):
    with urlopen(url, timeout=4) as response:
        if response.status != 200:
            raise RuntimeError('门户入口状态异常')
        parser = EntryAssets()
        parser.feed(response.read().decode('utf-8'))
    if not any(path.endswith('.js') for path in parser.assets):
        raise RuntimeError('门户入口缺少构建脚本')
    for path, types in parser.assets.items():
        with urlopen(urljoin(url, path), timeout=4) as response:
            if response.status != 200 or response.headers.get_content_type() not in types or not response.read(1):
                raise RuntimeError('门户静态资源不可用：' + path)
