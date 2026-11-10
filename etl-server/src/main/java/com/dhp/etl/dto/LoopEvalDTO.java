package com.dhp.etl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 循环数据集试算入参
 */
@Data
public class LoopEvalDTO implements Serializable {

    /** 目标数据源 ID（循环 SQL 在其上执行） */
    @NotNull(message = "请先选择目标数据源")
    private Long targetDsId;

    /** 循环数据集 SQL（返回多行参数） */
    @NotBlank(message = "循环 SQL 不能为空")
    private String sql;

    /** 预览行数上限 */
    private Integer limit = 10;
}
