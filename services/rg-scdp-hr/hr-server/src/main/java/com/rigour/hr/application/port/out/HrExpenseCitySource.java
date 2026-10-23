package com.rigour.hr.application.port.out;

/** 按当前操作权限读取可用城市；未授权、停用或不存在的城市均不返回。 */
public interface HrExpenseCitySource {
    String cityName(String cityCode, String action);
}
