package com.dhp.etl.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据源类型枚举 (规范 06)
 */
@Getter
@AllArgsConstructor
public enum SourceTypeEnum {
    MYSQL("MYSQL", "MySQL数据库"),
    WEB_API("WEB_API", "WebAPI接口");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;
}
