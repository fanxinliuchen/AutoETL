package com.dhp.common.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 动态查询过滤条件 (规范 03 §3.6)
 */
@Data
public class BaseQueryDTO implements Serializable {

    private List<BaseQueryFilter> filters;
    private List<BaseQuerySoft> softs;

    @Data
    public static class BaseQueryFilter implements Serializable {
        /** 字段名（驼峰或下划线） */
        private String fieldName;
        /** 条件类型: eq / like / likeLeft / likeRight / in / gte / lte / neq */
        private String condition;
        /** 条件值 */
        private String value;
    }

    @Data
    public static class BaseQuerySoft implements Serializable {
        /** 排序字段 */
        private String fieldName;
        /** 排序方式: asc / desc */
        private String orderCode;
    }
}
