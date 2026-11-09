package com.dhp.common.base;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dhp.common.dto.BaseQueryDTO;
import com.dhp.common.dto.CommonQueryRequestDTO;
import com.dhp.common.result.PageResult;

import java.util.List;

/**
 * 基础 CRUD 业务接口 (规范 03 §4.4)
 */
public interface IBaseCrudService<T> extends IService<T> {

    /**
     * 动态分页查询
     */
    PageResult<T> pageQuery(CommonQueryRequestDTO requestDTO);

    /**
     * 动态列表查询
     */
    List<T> listQuery(BaseQueryDTO queryDTO);
}
