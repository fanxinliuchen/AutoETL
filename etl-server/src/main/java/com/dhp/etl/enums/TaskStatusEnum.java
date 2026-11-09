package com.dhp.etl.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务状态枚举
 */
@Getter
@AllArgsConstructor
public enum TaskStatusEnum {
    RUNNING("RUNNING", "运行中"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    TIMEOUT("TIMEOUT", "超时"),
    CANCELED("CANCELED", "已终止");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;
}
