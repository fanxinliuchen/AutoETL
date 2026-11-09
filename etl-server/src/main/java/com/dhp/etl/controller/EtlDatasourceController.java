package com.dhp.etl.controller;

import com.dhp.common.base.BaseCrudController;
import com.dhp.common.base.IBaseCrudService;
import com.dhp.common.result.R;
import com.dhp.etl.dto.DatasourceTestDTO;
import com.dhp.etl.entity.EtlDatasource;
import com.dhp.etl.service.EtlDatasourceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/etl/datasource")
@RequiredArgsConstructor
@Tag(name = "ETL数据源管理")
public class EtlDatasourceController extends BaseCrudController<EtlDatasource> {

    private final EtlDatasourceService datasourceService;

    @Override
    protected IBaseCrudService<EtlDatasource> getService() {
        return datasourceService;
    }

    @Operation(summary = "测试数据源连接连通性")
    @PostMapping("/test-connection")
    public R<String> testConnection(@RequestBody @Valid DatasourceTestDTO dto) {
        return R.ok(datasourceService.testConnection(dto));
    }
}
