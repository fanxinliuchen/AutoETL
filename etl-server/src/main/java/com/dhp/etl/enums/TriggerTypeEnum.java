package com.dhp.etl.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 触发方式枚举
 */
@Getter
@AllArgsConstructor
public enum TriggerTypeEnum {
    MANUAL("MANUAL", "手动触发"),
    CRON("CRON", "定时调度");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;
}
