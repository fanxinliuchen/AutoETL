package com.dhp.common.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页数据返回信封 (规范 03 §3.3, 规范 07 §4.2)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> implements Serializable {

    private List<T> list;
    private long total;
    private long page;
    private long size;

    public static <T> PageResult<T> of(List<T> list, long total, long page, long size) {
        return PageResult.<T>builder()
                .list(list != null ? list : Collections.emptyList())
                .total(total)
                .page(page)
                .size(size)
                .build();
    }
}
