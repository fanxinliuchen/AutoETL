package com.dhp.etl.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 变量 SQL 试算入参
 *
 * <p>前端「变量定义」里点「试算」时调用：在目标数据源上执行一次取值表达式，
 * 直接返回结果，不落库、不影响流程配置。
 */
@Data
public class VarEvalDTO implements Serializable {

    /** 目标数据源 ID（变量 SQL 在其上执行） */
    @NotNull(message = "请先选择目标数据源")
    private Long targetDsId;

    /** 变量名，仅用于回显 */
    private String name;

    /** 取值 SQL 表达式 */
    @NotBlank(message = "SQL 表达式不能为空")
    private String sql;
}
