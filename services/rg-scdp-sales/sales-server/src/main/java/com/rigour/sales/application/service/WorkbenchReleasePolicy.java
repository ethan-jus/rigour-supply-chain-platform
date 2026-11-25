package com.rigour.sales.application.service;

import com.rigour.shared.core.exception.RequestValidationException;
import com.rigour.shared.core.exception.StateConflictException;
import java.net.URI;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class WorkbenchReleasePolicy {
    private static final Set<String> PLATFORMS = Set.of("android", "ios", "harmony");
    private final Environment environment;

    public WorkbenchReleasePolicy(Environment environment) {
        this.environment = environment;
    }

    public record Release(int latestBuild, int minimumBuild, String installUrl, String notes) {
        public Release {
            notes = notes == null ? "" : notes;
            installUrl = installUrl == null ? "" : installUrl;
            if (minimumBuild < 0 || latestBuild < minimumBuild)
                throw new IllegalArgumentException("最低可用版本不能高于最新版本");
            if (latestBuild > 0) {
                URI uri = URI.create(installUrl);
                if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                    throw new IllegalArgumentException("发布版本需要提供 HTTPS 安装入口");
            }
        }
    }

    public Release release(String platform) {
        if (!PLATFORMS.contains(platform)) throw new RequestValidationException("未知手机平台");
        return Binder.get(environment).bind("rigour.workbench.releases." + platform, Release.class)
                .orElse(new Release(0, 0, "", ""));
    }

    public void checkBuild(String platform, String version) {
        if (platform == null && version == null) {
            if (!environment.getProperty("rigour.workbench.require-version-headers", Boolean.class, false)) return;
        } else if (platform != null && version != null && version.matches("[1-9][0-9]{0,8}")
                && Integer.parseInt(version) >= release(platform).minimumBuild()) {
            return;
        }
        throw new StateConflictException("请更新销售工作台后再开始新的作业，可从公司系统扫码安装。");
    }
}
