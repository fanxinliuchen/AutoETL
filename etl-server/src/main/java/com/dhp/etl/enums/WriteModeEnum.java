package com.dhp.etl.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 目标写入模式枚举
 */
@Getter
@AllArgsConstructor
public enum WriteModeEnum {
    APPEND("APPEND", "追加写入"),
    OVERWRITE("OVERWRITE", "清空覆盖"),
    UPSERT("UPSERT", "主键更新插入"),
    SYNC("SYNC", "增量同步(软删除)");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;
}
