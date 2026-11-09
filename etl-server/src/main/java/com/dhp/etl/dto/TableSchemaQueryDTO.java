package com.dhp.etl.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 目标表结构查询入参
 */
@Data
public class TableSchemaQueryDTO implements Serializable {

    @NotNull(message = "请选择目标数据源")
    private Long datasourceId;

    /** 表名；不传时仅列出表清单 */
    private String table;
}
