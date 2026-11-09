package com.dhp.common.result;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一业务错误码枚举 (规范 03 §3.4)
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {
    SUCCESS(0, "success"),
    SYSTEM_ERROR(10000, "系统异常"),
    PARAM_ERROR(10001, "参数校验失败"),
    UNAUTHORIZED(10002, "未登录或登录已过期"),
    FORBIDDEN(10003, "无权限访问"),
    NOT_FOUND(10004, "资源不存在"),

    // ETL 模块错误码 (530xx)
    ETL_SQL_SAFETY_ERROR(53001, "SQL 安全校验未通过"),
    ETL_DATASOURCE_CONNECT_FAIL(53002, "数据源连接失败"),
    ETL_PIPELINE_NOT_FOUND(53003, "ETL流程不存在"),
    ETL_PIPELINE_RUNNING(53004, "ETL流程正在运行中，无法重复触发"),
    ETL_PIPELINE_CODE_DUPLICATE(53005, "流程编码已存在"),
    ETL_CRON_INVALID(53006, "无效的 Cron 表达式"),
    ETL_EXECUTE_ERROR(53007, "ETL 执行异常");

    private final int code;
    private final String message;
}
