package com.rigour.hr.api.v1.model;

import java.time.LocalDate;

/** 仅在员工详情和维护接口返回的个人资料，不进入列表和跨服务员工身份契约。 */
public record HrEmployeeProfile(String idNumber, LocalDate contractEndDate, String education,
        String registeredAddress, String householdType, String residentialAddress,
        String bankAccount, String bankName, String socialInsurance,
        String emergencyContact, String emergencyPhone,
        String graduationSchool, String major, String regularSalary, String probationSalary, String probationPeriod) {}
