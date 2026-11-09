package com.dhp.common.base;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dhp.common.dto.BaseQueryDTO;
import com.dhp.common.dto.CommonQueryRequestDTO;
import com.dhp.common.result.PageResult;
import com.dhp.common.util.PageQueryUtils;

import java.util.List;

/**
 * 基础 CRUD 业务实现类 (规范 03 §4.4)
 */
public abstract class BaseCrudServiceImpl<M extends BaseMapper<T>, T>
        extends ServiceImpl<M, T>
        implements IBaseCrudService<T> {

    protected abstract Class<T> getClaas();

    @Override
    public PageResult<T> pageQuery(CommonQueryRequestDTO requestDTO) {
        Page<T> page = PageQueryUtils.getPage(requestDTO);
        QueryWrapper<T> wrapper = PageQueryUtils.buildWrapper(requestDTO.getQuery());
        Page<T> resultPage = this.page(page, wrapper);
        return PageResult.of(resultPage.getRecords(), resultPage.getTotal(), resultPage.getCurrent(), resultPage.getSize());
    }

    @Override
    public List<T> listQuery(BaseQueryDTO queryDTO) {
        QueryWrapper<T> wrapper = PageQueryUtils.buildWrapper(queryDTO);
        return this.list(wrapper);
    }
}
