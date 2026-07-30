package com.rigour.tenant.iam.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.tenant.iam.infrastructure.persistence.dataobject.ApplicationDO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 统一门户应用目录Mapper；标准单表操作使用BaseMapper，复杂查询保留显式SQL。 */
public interface ApplicationMapper extends BaseMapper<ApplicationDO> {

    /** 按稳定应用编码查询未删除记录。 */
    ApplicationDO selectByCode(@Param("appCode") String appCode);

    /** 按应用范围查询启用记录，结果按门户展示顺序返回。 */
    List<ApplicationDO> selectActiveByScope(@Param("appScope") String appScope);
}
