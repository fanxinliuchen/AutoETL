package com.dhp.etl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * SQL 校验入参 DTO
 */
@Data
public class SqlValidateDTO implements Serializable {

    @NotBlank(message = "SQL 不能为空")
    private String sql;
}
