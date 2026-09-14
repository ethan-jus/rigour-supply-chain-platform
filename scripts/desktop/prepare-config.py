#!/usr/bin/env python3
"""只初始化台式机应用配置，保留已有密码，不连接旧云服务器。"""
import os
from pathlib import Path
import subprocess

root = Path('/srv/rigour-dev')
if not (root / '.env').exists():
    raise SystemExit('未找到台式机基础环境，请先完成中间件安装。')
os.umask(0o077)
config = root / 'apps/config'
config.mkdir(parents=True, exist_ok=True)
(root / 'apps/keys').mkdir(exist_ok=True)
env = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines() if line and not line.startswith('#'))
app_keys = ['IAM_DB_APP_PASSWORD','IAM_DB_MIGRATOR_PASSWORD','NACOS_PASSWORD','RIGOUR_CONTEXT_TRUST_KEY_V1','IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1']
values = {
    'app.env': '# 仅本机应用所需凭据，不包含 MySQL root 密码。\n' + ''.join(key + '=' + env[key] + '\n' for key in app_keys),
    'iam.properties': '''# 台式机 HTTP 开发配置；无云端地址，不加载原 dev/local 配置覆盖层。
server.port=26881
server.servlet.session.cookie.secure=false
spring.datasource.url=jdbc:mysql://127.0.0.1:13306/rigour_iam?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&sslMode=DISABLED&allowPublicKeyRetrieval=true
spring.datasource.username=rigour_iam_app
spring.datasource.password=${IAM_DB_APP_PASSWORD}
spring.datasource.hikari.maximum-pool-size=10
spring.flyway.url=${spring.datasource.url}
spring.flyway.user=rigour_iam_migrator
spring.flyway.password=${IAM_DB_MIGRATOR_PASSWORD}
spring.cloud.nacos.server-addr=127.0.0.1:18848
spring.cloud.nacos.config.enabled=false
spring.cloud.nacos.config.import-check.enabled=false
spring.cloud.nacos.username=nacos
spring.cloud.nacos.password=${NACOS_PASSWORD}
spring.cloud.nacos.discovery.namespace=3aa03547-8948-4254-bd94-47c630db128b
spring.cloud.nacos.discovery.ip=192.168.12.7
rigour.iam.feishu.enabled=false
rigour.iam.oidc.server.enabled=true
rigour.iam.oidc.server.issuer=http://192.168.12.7:26881
rigour.iam.oidc.server.allowed-origins[0]=http://192.168.12.7:5100
rigour.iam.oidc.server.allow-insecure-lan=true
rigour.iam.oidc.signing.enabled=true
rigour.iam.oidc.authorization-attributes.enabled=true
rigour.iam.oidc.authorization-attributes.active-key-version=v1
rigour.iam.oidc.authorization-attributes.keys-base64.v1=${IAM_OIDC_AUTH_ATTRIBUTES_KEY_V1}
rigour.iam.bootstrap.local-signing-key.enabled=true
rigour.iam.bootstrap.local-signing-key.path=/keys/iam-signing.pem
rigour.iam.bootstrap.portal-client.enabled=true
rigour.iam.bootstrap.portal-client.client-id=rigour-portal-desktop
rigour.iam.bootstrap.portal-client.redirect-uri=http://192.168.12.7:5100/oidc/callback
rigour.iam.bootstrap.portal-client.post-logout-redirect-uri=http://192.168.12.7:5100/
rigour.iam.bootstrap.portal-client.allow-insecure-http=true
''',
    'gateway.properties': '''# 台式机网关仍执行真实 Token、会话和权限校验，仅允许开发 HTTP 地址。
server.port=26880
spring.cloud.nacos.server-addr=127.0.0.1:18848
spring.cloud.nacos.config.enabled=false
spring.cloud.nacos.config.import-check.enabled=false
spring.cloud.nacos.username=nacos
spring.cloud.nacos.password=${NACOS_PASSWORD}
spring.cloud.nacos.discovery.namespace=3aa03547-8948-4254-bd94-47c630db128b
spring.cloud.nacos.discovery.ip=192.168.12.7
rigour.gateway.security.enabled=true
rigour.gateway.security.allow-insecure-http=true
rigour.gateway.security.issuer=http://192.168.12.7:26881
rigour.gateway.security.jwk-set-uri=http://127.0.0.1:26881/oauth2/jwks
rigour.gateway.security.iam-current-token-uri=http://127.0.0.1:26881/api/v1/token/current
rigour.gateway.security.current-token-validation-enabled=true
'''
}
for name, content in values.items():
    path = config / name
    if not path.exists():
        path.write_text(content, encoding='utf-8')
        path.chmod(0o600)
        print('已生成应用配置：' + name)
    else:
        print('保留已有应用配置：' + name)
# IAM 历史迁移会删除自身临时清理表，迁移账号需要本库 DROP 权限，运行账号不增加权限。
subprocess.run(['docker','exec','-i','rigour-dev-desktop-mysql-1','sh','-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot'],
    input="GRANT DROP ON rigour_iam.* TO 'rigour_iam_migrator'@'%';\n",text=True,check=True)
print('应用配置准备完成；没有修改云端配置，没有给业务运行账号授予建表权限。')
