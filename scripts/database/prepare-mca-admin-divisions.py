#!/usr/bin/env python3
"""Convert the MCA official three-level snapshot; does not write any database."""
import argparse
import importlib.util
import json
from pathlib import Path
import re

spec = importlib.util.spec_from_file_location('admin_import', Path(__file__).with_name('import-admin-divisions.py'))
admin_import = importlib.util.module_from_spec(spec)
spec.loader.exec_module(admin_import)


def convert(raw, effective_from):
    if raw.get('status') != 200 or raw.get('data', {}).get('code') != '00':
        raise ValueError('民政部接口响应无效')
    nodes = []

    def visit(row, parent):
        code = row['code']
        # The 2025 API labels Taiwan as 资料暂缺. MCA's 2024 official table
        # explicitly retains 710000; never invent prefecture or county children.
        if code == '资料暂缺' and row['name'] == '台湾省' and row['level'] == 1:
            code = '710000000000'
        if not re.fullmatch(r'[0-9]{6}000000', code):
            raise ValueError(f'非省地县六位编码：{code}')
        code = code[:6]
        level = {1: 'PROVINCE', 2: 'PREFECTURE', 3: 'COUNTY'}.get(row['level'])
        nodes.append({'code': code, 'name': row['name'], 'level': level, 'parentCode': parent})
        for child in row.get('children') or []:
            visit(child, code)

    for province in raw['data']['children']:
        visit(province, None)
    payload = {
        'version': 'MCA_20251231',
        'sourceName': '民政部国家地名信息库（截至2025-12-31）',
        'sourceRef': 'https://dmfw.mca.gov.cn/XzqhVersionPublish.html ; 台湾省级编码参照 https://www.mca.gov.cn/mzsj/xzqh/2025/202401xzqh.html',
        'effectiveFrom': effective_from,
        'nodes': nodes,
    }
    result = admin_import.validate(payload)
    counts = {level: sum(n['level'] == level for n in result['nodes']) for level in admin_import.LEVELS}
    if counts != {'PROVINCE': 34, 'PREFECTURE': 333, 'COUNTY': 2847}:
        raise ValueError(f'2025年末目录完整性校验失败：{counts}')
    if {n['code'][:2] for n in result['nodes'] if n['level'] == 'PROVINCE'} != set(admin_import.REGIONS):
        raise ValueError('省级目录不完整')
    payload['nodes'] = result['nodes']
    return payload


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('source', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--effective-from', required=True, help='本系统启用日期；来源截止日期固定为2025-12-31')
    args = parser.parse_args()
    payload = convert(json.loads(args.source.read_text()), args.effective_from)
    args.output.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'version': payload['version'], 'count': len(payload['nodes'])}, ensure_ascii=False))
