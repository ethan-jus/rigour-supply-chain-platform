"""台式机业务服务清单：构建、配置、备份及健康检查共用一个事实来源。"""

# 按基础主数据优先、后台集成最后的顺序启动；跨领域只走 HTTP。
DOMAINS = [
    ('settings', '业务设置', 'rigour-business-settings-service/business-settings-service', 26892, 'settings', 'BUSINESS_SETTINGS'),
    ('hr', '人事薪酬', 'rigour-hr-payroll-service/hr-payroll-service', 26889, 'hr', 'HR'),
    ('crm', '客户管理', 'rigour-merchant-crm-service/merchant-crm-service', 26883, 'crm', 'CRM'),
    ('erp', 'ERP核心', 'rigour-erp-core-service/erp-core-service', 26884, 'erp', 'ERP'),
    ('order', '订单中心', 'rigour-order-center-service/order-center-service', 26885, 'order', 'ORDER'),
    ('sales', '销售工作', 'rigour-sales-work-service/sales-work-service', 26886, 'sales_work', 'SALES_WORK'),
    ('ai', 'AI智能体', 'rigour-ai-agent-service/ai-agent-service', 26887, 'ai', 'AI'),
    ('bi', '分析看板', 'rigour-analytics-bi-service/analytics-bi-service', 26888, 'bi', 'BI'),
    ('city', '城市运营', 'rigour-city-operations-service/city-operations-service', 26890, 'city', 'CITY'),
    ('channel', '渠道代理', 'rigour-channel-agent-service/channel-agent-service', 26891, 'channel', 'CHANNEL'),
    ('collaboration', '内部协作', 'rigour-collaboration-service/collaboration-service', 26893, 'collaboration', 'COLLABORATION'),
    ('integration', '集成迁移', 'rigour-integration-migration-service/integration-migration-service', 26882, 'integration', 'INTEGRATION'),
]
CORE = ['iam', 'gateway', 'portal']
PORTS = {'iam': 26881, 'gateway': 26880, **{s[0]: s[3] for s in DOMAINS}}


def select_services(value=None):
    """部分发布仍保留身份与门户；未知名称直接拒绝，不静默漏部署。"""
    names = [s[0] for s in DOMAINS]
    selected = value.split(',') if value else names
    if not selected or any(name not in CORE + names for name in selected):
        raise ValueError('服务名称无效，可选：' + ','.join(names))
    return CORE + [name for name in names if name in selected]


def schemas_for(selected):
    return ['rigour_iam'] + ['rigour_' + row[4] for row in DOMAINS if row[0] in selected]


def business_compose():
    """使用同一 JRE 镜像模板，每个服务独立配置、凭据、持久化目录和内存上限。"""
    result = {}
    for name, title, module, port, suffix, prefix in DOMAINS:
        result[name] = {
            'image': 'rigour-desktop-' + name + ':${RELEASE_ID:?未设置发布版本}',
            'build': {'context': '.', 'dockerfile': 'Dockerfile.service', 'args': {'SERVICE_JAR': name + '.jar'}},
            'network_mode': 'host', 'restart': 'unless-stopped', 'mem_limit': '640m',
            'env_file': '/srv/rigour-dev/apps/config/' + name + '.env',
            'environment': {'JAVA_TOOL_OPTIONS': '-Xms64m -Xmx320m -Dfile.encoding=UTF-8'},
            'volumes': [
                f'/srv/rigour-dev/apps/config/{name}.properties:/config/service.properties:ro',
                f'/srv/rigour-dev/apps/data/{name}:/app/data',
            ],
            'logging': {'driver': 'json-file', 'options': {'max-size': '10m', 'max-file': '3'}},
            'command': ['java', '-jar', '/app/service.jar', '--spring.profiles.active=desktop',
                        '--spring.config.additional-location=file:/config/service.properties'],
        }
    return result
