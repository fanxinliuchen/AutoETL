package com.dhp.etl.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 连接类型枚举 (规范 06)。
 *
 * <p>「连接」= 怎么连 + 怎么鉴权：JDBC 类（MYSQL / POSTGRESQL）给连接池，
 * 其余给鉴权会话。类型专属参数放在 {@code t_etl_datasource.config_cipher}（AES 加密 JSON）。
 */
@Getter
@AllArgsConstructor
public enum ConnTypeEnum {
    MYSQL("MYSQL", "MySQL 数据库"),
    POSTGRESQL("POSTGRESQL", "PostgreSQL 数据库"),
    KINGDEE_K3("KINGDEE_K3", "金蝶云星空"),
    IYUNBIAO("IYUNBIAO", "云表 OpenAPI");

    @EnumValue
    @JsonValue
    private final String code;
    private final String desc;

    /** 是否 JDBC 类连接：有连接池，可以作为「数据库」源库使用 */
    public boolean isJdbc() {
        return this == MYSQL || this == POSTGRESQL;
    }

    /** 是否为 API 类连接（需要鉴权会话，不是 JDBC 数据源） */
    public boolean isApi() {
        return !isJdbc();
    }

    public static ConnTypeEnum of(String code) {
        for (ConnTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return MYSQL;
    }
}
