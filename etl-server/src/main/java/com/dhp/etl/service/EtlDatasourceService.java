package com.dhp.etl.service;

import com.dhp.common.base.IBaseCrudService;
import com.dhp.etl.dto.DatasourceTestDTO;
import com.dhp.etl.entity.EtlDatasource;

import javax.sql.DataSource;

public interface EtlDatasourceService extends IBaseCrudService<EtlDatasource> {

    /**
     * 测试数据源连通性
     */
    String testConnection(DatasourceTestDTO testDTO);

    /**
     * 获取指定配置的实际 javax.sql.DataSource 连接池
     */
    DataSource getDataSource(Long datasourceId);
}
