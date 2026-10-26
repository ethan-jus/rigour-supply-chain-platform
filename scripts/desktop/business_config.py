"""业务服务桌面配置生成；外部凭据只读取本机文件，不使用假密钥或访问云端。"""
import os
from pathlib import Path
import subprocess
import json
import re
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from service_catalog import DOMAINS

ROOT = Path('/srv/rigour-dev')
NAMESPACE = os.environ.get('RIGOUR_DESKTOP_NACOS_NAMESPACE', 'rigour-desktop-dev')


def configure_namespace(path):
    """发布配置统一使用独立注册空间，保留数据库、密钥和其他配置。"""
    original = path.read_text()
    value = 'spring.cloud.nacos.discovery.namespace=' + NAMESPACE
    updated, count = re.subn(r'^spring\.cloud\.nacos\.discovery\.namespace\s*=.*$',
                             lambda _: value, original, flags=re.MULTILINE)
    if not count:
        updated = original.rstrip('\n') + '\n' + value + '\n'
    if updated != original:
        path.write_text(updated)
        path.chmod(0o600)


def ensure_namespace():
    """只操作本机 Nacos 的命名空间；凭据和登录令牌不进入日志。"""
    base = 'http://127.0.0.1:18848/nacos'
    try:
        env = read_env(ROOT / '.env')
        with urlopen(Request(base + '/v1/auth/login', data=urlencode({
                'username': 'nacos', 'password': env['NACOS_PASSWORD']}).encode()), timeout=10) as response:
            token = json.load(response)['accessToken']
        headers = {'accessToken': token}
        def exists():
            with urlopen(Request(base + '/v3/admin/core/namespace/list', headers=headers), timeout=10) as response:
                result = json.load(response)
                if result.get('code') != 0 or not isinstance(result.get('data'), list):
                    raise RuntimeError('Namespace query rejected')
                return any(row.get('namespace') == NAMESPACE for row in result['data'])
        if not exists():
            with urlopen(Request(base + '/v3/admin/core/namespace', headers=headers, data=urlencode({
                    'namespaceId': NAMESPACE, 'namespaceName': '台式机 DEV 发布',
                    'namespaceDesc': '独立于本机 IDEA 的应用注册空间'}).encode()), timeout=10) as response:
                result = json.load(response)
                if result.get('code') != 0 or result.get('data') is not True:
                    raise RuntimeError('Namespace creation rejected')
            if not exists():
                raise RuntimeError('Namespace not found after creation')
    except Exception:
        raise RuntimeError('本机 Nacos 独立发布空间准备失败，请检查注册中心及凭据；未启动应用。') from None
    print('已核对独立应用注册空间：' + NAMESPACE, flush=True)


def read_env(path):
    return dict(line.split('=', 1) for line in path.read_text().splitlines()
                if line and not line.startswith('#') and '=' in line)


def mysql(sql):
    return subprocess.run(['docker', 'exec', '-i', 'rigour-dev-desktop-mysql-1', 'sh', '-c',
                           'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N'],
                          input=sql, text=True, check=True, capture_output=True).stdout


def domain_properties(row):
    name, title, module, port, suffix, prefix = row
    migrator = 'rigour_settings_mig' if name == 'settings' else f'rigour_{suffix}_migrator'
    content = f'''# {title}：台式机独立配置，不加载指向旧云端的 dev/local 覆盖层。
server.port={port}
spring.datasource.url=jdbc:mysql://127.0.0.1:13306/rigour_{suffix}?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true
spring.datasource.username=rigour_{suffix}_app
spring.datasource.password=${{{prefix}_DB_APP_PASSWORD}}
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=1
spring.flyway.url=${{spring.datasource.url}}
spring.flyway.user={migrator}
spring.flyway.password=${{{prefix}_DB_MIGRATOR_PASSWORD}}
spring.flyway.baseline-on-migrate=false
spring.flyway.clean-disabled=true
spring.cloud.nacos.server-addr=127.0.0.1:18848
spring.cloud.nacos.config.enabled=false
spring.cloud.nacos.config.import-check.enabled=false
spring.cloud.nacos.username=nacos
spring.cloud.nacos.password=${{NACOS_PASSWORD}}
spring.cloud.nacos.discovery.namespace={NAMESPACE}
spring.cloud.nacos.discovery.ip=192.168.12.7
spring.data.redis.host=127.0.0.1
spring.data.redis.port=16379
spring.data.redis.password=${{REDIS_PASSWORD}}
rocketmq.name-server=192.168.12.7:19876
rigour.context.trust.active-key-id=v1
rigour.context.trust.keys-base64.v1=${{RIGOUR_CONTEXT_TRUST_KEY_V1}}
'''
    # 克隆数据上的自动任务先关闭，业务 API、真实身份和数据库读写不替换为 Mock。
    extra = {
        'integration': '''rigour.integration.product-media.worker-enabled=false
rigour.integration.dhb.orchestration.enabled=false
rigour.integration.product-media.cos.region=${RIGOUR_INTEGRATION_PRODUCT_MEDIA_COS_REGION}
rigour.integration.product-media.cos.bucket=${RIGOUR_INTEGRATION_PRODUCT_MEDIA_COS_BUCKET}
''',
        'erp': '''rigour.erp.product-media.cos.region=${RIGOUR_ERP_PRODUCT_MEDIA_COS_REGION}
rigour.erp.product-media.cos.bucket=${RIGOUR_ERP_PRODUCT_MEDIA_COS_BUCKET}
''',
        'sales': '''sales.recording.storage-type=filesystem
sales.recording.storage-dir=/app/data/sales-recordings
rigour.sales.temporary-checkin.enabled=false
rigour.sales.temporary-checkin.ai.enabled=false
''',
        'bi': 'rigour.analytics.supply-dashboard.refresh.enabled=false\n',
    }
    return content + extra.get(name, '')


def external_keys(name):
    if name in ('hr', 'order'):
        return ['RIGOUR_COS_' + tail for tail in ('REGION', 'BUCKET', 'SECRET_ID', 'SECRET_KEY')]
    if name in ('erp', 'integration'):
        return [f'RIGOUR_{name.upper()}_PRODUCT_MEDIA_COS_{tail}' for tail in
                ('REGION', 'BUCKET', 'SECRET_ID', 'SECRET_KEY')]
    return []


def prepare(selected):
    """调用方先备份数据库、.env、config；本函数不重置任何已有账号密码。"""
    os.umask(0o077)
    env_path = ROOT / '.env'
    env = read_env(env_path)
    extra_path = ROOT / 'apps/config/external.env'
    external = read_env(extra_path) if extra_path.exists() else {}
    missing = [key for name in selected for key in external_keys(name) if not external.get(key)]
    if missing:
        raise RuntimeError('缺少真实外部配置，请补齐 apps/config/external.env：' + ','.join(missing))
    for row in DOMAINS:
        name, title, module, port, suffix, prefix = row
        if name not in selected:
            continue
        keys = [prefix + '_DB_APP_PASSWORD', prefix + '_DB_MIGRATOR_PASSWORD',
                'NACOS_PASSWORD', 'REDIS_PASSWORD', 'RIGOUR_CONTEXT_TRUST_KEY_V1']
        absent = [key for key in keys if not env.get(key)]
        if absent:
            raise RuntimeError(title + '缺少数据库或内部凭据：' + ','.join(absent))
        values = {key: env[key] for key in keys}
        allowed = external_keys(name)
        if name in ('hr', 'order'):
            allowed += ['RIGOUR_COS_SESSION_TOKEN']
        elif name in ('erp', 'integration'):
            allowed += [f'RIGOUR_{name.upper()}_PRODUCT_MEDIA_COS_SESSION_TOKEN']
        values.update({key: external[key] for key in allowed if external.get(key)})
        if name == 'order':
            values['RIGOUR_ORDER_FUND_ATTACHMENT_COS_ENABLED'] = 'true'
        path = ROOT / f'apps/config/{name}.env'
        path.write_text('# 本服务所需凭据；不包含其他领域密码和数据库 root。\n' + ''.join(k + '=' + v + '\n' for k, v in values.items()))
        path.chmod(0o600)
        config = ROOT / f'apps/config/{name}.properties'
        if not config.exists():
            config.write_text(domain_properties(row))
            config.chmod(0o600)
        configure_namespace(config)
        (ROOT / f'apps/data/{name}').mkdir(parents=True, exist_ok=True)
        print('已准备业务服务配置：' + title, flush=True)
