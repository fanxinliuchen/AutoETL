package com.dhp.common.util;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dhp.common.dto.BaseQueryDTO;
import com.dhp.common.dto.CommonQueryRequestDTO;

import java.util.List;

/**
 * 通用动态查询工具类 (规范 03 §3.6)
 */
public class PageQueryUtils {

    public static <T> Page<T> getPage(CommonQueryRequestDTO requestDTO) {
        int page = requestDTO.getCurrent() != null && requestDTO.getCurrent() > 0 ? requestDTO.getCurrent() : 1;
        int size = requestDTO.getSize() != null && requestDTO.getSize() > 0 ? requestDTO.getSize() : 10;
        return new Page<>(page, size);
    }

    public static <T> QueryWrapper<T> buildWrapper(BaseQueryDTO queryDTO) {
        QueryWrapper<T> wrapper = new QueryWrapper<>();
        if (queryDTO == null) {
            return wrapper;
        }

        // 处理筛选条件
        List<BaseQueryDTO.BaseQueryFilter> filters = queryDTO.getFilters();
        if (filters != null) {
            for (BaseQueryDTO.BaseQueryFilter filter : filters) {
                if (StrUtil.isBlank(filter.getFieldName()) || filter.getValue() == null) {
                    continue;
                }
                String column = StrUtil.toUnderlineCase(filter.getFieldName());
                String condition = StrUtil.blankToDefault(filter.getCondition(), "eq").toLowerCase();
                String val = filter.getValue();

                switch (condition) {
                    case "eq" -> wrapper.eq(column, val);
                    case "neq" -> wrapper.ne(column, val);
                    case "like" -> wrapper.like(column, val);
                    case "likeleft" -> wrapper.likeLeft(column, val);
                    case "likeright" -> wrapper.likeRight(column, val);
                    case "gte" -> wrapper.ge(column, val);
                    case "lte" -> wrapper.le(column, val);
                    case "gt" -> wrapper.gt(column, val);
                    case "lt" -> wrapper.lt(column, val);
                    case "in" -> wrapper.in(column, StrUtil.split(val, ','));
                    default -> wrapper.eq(column, val);
                }
            }
        }

        // 处理排序
        List<BaseQueryDTO.BaseQuerySoft> softs = queryDTO.getSofts();
        if (softs != null) {
            for (BaseQueryDTO.BaseQuerySoft soft : softs) {
                if (StrUtil.isBlank(soft.getFieldName())) {
                    continue;
                }
                String column = StrUtil.toUnderlineCase(soft.getFieldName());
                boolean isAsc = "asc".equalsIgnoreCase(soft.getOrderCode());
                wrapper.orderBy(true, isAsc, column);
            }
        }

        return wrapper;
    }
}
