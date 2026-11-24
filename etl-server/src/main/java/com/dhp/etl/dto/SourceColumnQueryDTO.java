package com.dhp.etl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 解析 MySQL 抽取 SQL 输出字段的入参。
 *
 * <p>pipelineId 可选：带上它才能解析流程里定义的自定义变量（${xxx}），
 * 否则只能用 today / yesterday / now 三个内置变量。
 */
@Data
public class SourceColumnQueryDTO implements Serializable {

    @NotNull(message = "请选择来源数据源")
    private Long datasourceId;

    @NotBlank(message = "抽取 SQL 不能为空")
    private String sql;

    private Long pipelineId;
}
