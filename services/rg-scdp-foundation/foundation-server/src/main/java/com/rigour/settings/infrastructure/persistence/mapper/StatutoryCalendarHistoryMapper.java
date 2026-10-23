package com.rigour.settings.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rigour.settings.infrastructure.persistence.entity.StatutoryCalendarHistoryEntity;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface StatutoryCalendarHistoryMapper
        extends BaseMapper<StatutoryCalendarHistoryEntity> {}
