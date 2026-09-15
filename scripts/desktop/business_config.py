"""业务服务桌面配置生成；外部凭据只读取本机文件，不使用假密钥或访问云端。"""
import os
from pathlib import Path
import re
import secrets
import subprocess
from service_catalog import DOMAINS

ROOT = Path('/srv/rigour-dev')
NAMESPACE = '3aa03547-8948-4254-bd94-47c630db128b'


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
    if 'collaboration' in selected:
        additions = {}
        for role in ['APP', 'MIGRATOR']:
            key = 'COLLABORATION_DB_' + role + '_PASSWORD'
            if key not in env:
                user = 'rigour_collaboration_' + role.lower()
                if mysql(f"SELECT user FROM mysql.user WHERE user='{user}';").strip():
                    raise RuntimeError('协作账号已存在但配置缺失，停止自动生成密码。')
                additions[key] = secrets.token_hex(20)
        if additions:
            env_path.write_text(env_path.read_text().rstrip() + '\n# 内部协作独立数据库凭据\n'
                                + ''.join(k + '=' + v + '\n' for k, v in additions.items()))
            env_path.chmod(0o600)
            env.update(additions)
        sql = 'CREATE DATABASE IF NOT EXISTS rigour_collaboration CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;\n'
        for role in ['APP', 'MIGRATOR']:
            password = env['COLLABORATION_DB_' + role + '_PASSWORD']
            # 此处仅接受本生成器产生的口令，避免 SQL 拼接解释外部输入。
            if not re.fullmatch('[0-9a-f]{40}', password):
                raise RuntimeError('协作数据库凭据格式不符合初始化约定。')
            privileges = 'SELECT,INSERT,UPDATE,DELETE' if role == 'APP' else 'SELECT,INSERT,UPDATE,DELETE,CREATE,ALTER,INDEX,REFERENCES'
            sql += f"CREATE USER IF NOT EXISTS 'rigour_collaboration_{role.lower()}'@'%' IDENTIFIED BY '{password}';\n"
            sql += f"GRANT {privileges} ON rigour_collaboration.* TO 'rigour_collaboration_{role.lower()}'@'%';\n"
        mysql(sql)
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
        if name in ('erp', 'integration'):
            allowed += [f'RIGOUR_{name.upper()}_PRODUCT_MEDIA_COS_SESSION_TOKEN']
        if name == 'collaboration':
            allowed += ['RIGOUR_LIVEKIT_URL', 'RIGOUR_LIVEKIT_API_KEY', 'RIGOUR_LIVEKIT_API_SECRET']
        values.update({key: external[key] for key in allowed if external.get(key)})
        path = ROOT / f'apps/config/{name}.env'
        path.write_text('# 本服务所需凭据；不包含其他领域密码和数据库 root。\n' + ''.join(k + '=' + v + '\n' for k, v in values.items()))
        path.chmod(0o600)
        config = ROOT / f'apps/config/{name}.properties'
        if not config.exists():
            config.write_text(domain_properties(row))
            config.chmod(0o600)
        (ROOT / f'apps/data/{name}').mkdir(parents=True, exist_ok=True)
        print('已准备业务服务配置：' + title, flush=True)
