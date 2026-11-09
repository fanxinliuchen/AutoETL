package com.dhp.common.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 动态分页查询请求 DTO (规范 03 §3.6)
 */
@Data
public class CommonQueryRequestDTO implements Serializable {

    private Integer current = 1;
    private Integer size = 10;
    private BaseQueryDTO query;

    public Integer getPage() {
        return current;
    }

    public void setPage(Integer page) {
        this.current = page;
    }
}
