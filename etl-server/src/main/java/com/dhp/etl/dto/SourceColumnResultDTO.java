package com.dhp.etl.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * MySQL 抽取 SQL 的输出字段（列名/类型 + 首行样例）。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SourceColumnResultDTO implements Serializable {

    private List<Column> columns;

    /** 列名重复被重命名等非致命提示 */
    private List<String> warnings;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class Column implements Serializable {

        /** 查询结果列名（别名生效），重名时已加 _2/_3 后缀 */
        private String name;

        /** 归一化后的落库类型，如 VARCHAR(64)/DECIMAL(18,2)/DATETIME */
        private String type;

        /** JDBC 原始类型名，便于排查 */
        private String jdbcType;

        private Boolean nullable;

        /** 首行样例值（截断 200 字符），仅用于核对类型推断 */
        private String sample;
    }
}
