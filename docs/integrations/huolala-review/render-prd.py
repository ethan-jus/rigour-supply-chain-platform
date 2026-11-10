"""Render the reviewed product document for local browser reading without dependencies."""
from pathlib import Path
import html
import re

root = Path(__file__).parent
lines = (root / '产品需求文档.md').read_text().splitlines()

def inline(value):
    value = html.escape(value)
    value = re.sub(r'`([^`]+)`', r'<code>\1</code>', value)
    return re.sub(r'\[([^\]]+)\]\((https://[^\s)]+)\)',
                  r'<a href="\2" target="_blank" rel="noopener">\1</a>', value)

output, toc = [], []
i = 0
while i < len(lines):
    line = lines[i]
    if line.startswith('#'):
        level = len(line) - len(line.lstrip('#'))
        text, anchor = line[level:].strip(), 's' + str(i)
        output.append(f'<h{level} id="{anchor}">{inline(text)}</h{level}>')
        if level == 2:
            toc.append(f'<a href="#{anchor}">{inline(text)}</a>')
    elif line.startswith('|'):
        rows = []
        while i < len(lines) and lines[i].startswith('|'):
            cells = [x.strip() for x in lines[i].strip('|').split('|')]
            if not all(re.fullmatch(r':?-+:?', x.replace(' ', '')) for x in cells):
                rows.append(cells)
            i += 1
        output.append('<div class="table-wrap"><table>')
        for n, row in enumerate(rows):
            tag = 'th' if n == 0 else 'td'
            output.append('<tr>' + ''.join(f'<{tag}>{inline(x)}</{tag}>' for x in row) + '</tr>')
        output.append('</table></div>')
        continue
    elif re.match(r'^\d+\. ', line) or line.startswith('- '):
        ordered = bool(re.match(r'^\d+\. ', line))
        tag = 'ol' if ordered else 'ul'
        output.append('<' + tag + '>')
        while i < len(lines) and (bool(re.match(r'^\d+\. ', lines[i])) if ordered else lines[i].startswith('- ')):
            output.append('<li>' + inline(re.sub(r'^\d+\. |^- ', '', lines[i])) + '</li>')
            i += 1
        output.append('</' + tag + '>')
        continue
    elif line.strip():
        output.append('<p>' + inline(line) + '</p>')
    i += 1

existing = root / 'ui/public/prd.html'
# Keep the print/read layout in one place; regenerate content and the section links.
page = existing.read_text()
head = page.split('<nav>')[0]
version = re.search(r'版本：\s*(V[\d.]+)', '\n'.join(lines)).group(1)
title = lines[0].lstrip('# ').strip() + ' ' + version
head = re.sub(r'<title>.*?</title>', '<title>' + html.escape(title) + '</title>', head)
page = (head + '<nav><strong>产品需求文档</strong>' + ''.join(toc) + '</nav><article>'
        + '<button class="print" onclick="window.print()">打印／保存 PDF</button>'
        + ''.join(output) + '</article></html>')
existing.write_text(page)
