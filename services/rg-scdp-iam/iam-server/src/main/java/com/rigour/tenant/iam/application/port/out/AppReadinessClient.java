package com.rigour.tenant.iam.application.port.out;
import java.util.*;
import com.rigour.tenant.iam.application.service.settings.AppCutoverModels.Domain;
/** 读取六个领域的版本化准备状态，不直接连接业务数据库。 */
public interface AppReadinessClient { List<Domain> inspect(UUID tenant); }
