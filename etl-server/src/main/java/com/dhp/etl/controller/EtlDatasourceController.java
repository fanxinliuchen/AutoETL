package com.dhp.etl.controller;

import com.dhp.common.base.BaseCrudController;
import com.dhp.common.base.IBaseCrudService;
import com.dhp.common.result.R;
import com.dhp.etl.dto.DatasourceTestDTO;
import com.dhp.etl.dto.TableSchemaQueryDTO;
import com.dhp.etl.entity.EtlDatasource;
import com.dhp.etl.service.EtlDatasourceService;
import com.dhp.etl.service.TargetTableSchemaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/etl/datasource")
@RequiredArgsConstructor
@Tag(name = "ETL数据源管理")
public class EtlDatasourceController extends BaseCrudController<EtlDatasource> {

    private final EtlDatasourceService datasourceService;
    private final TargetTableSchemaService targetTableSchemaService;

    @Override
    protected IBaseCrudService<EtlDatasource> getService() {
        return datasourceService;
    }

    @Operation(summary = "测试数据源连接连通性")
    @PostMapping("/test-connection")
    public R<String> testConnection(@RequestBody @Valid DatasourceTestDTO dto) {
        return R.ok(datasourceService.testConnection(dto));
    }

    @Operation(summary = "列出目标库中的全部表（用于目标表名下拉）")
    @PostMapping("/tables")
    public R<List<String>> listTables(@RequestBody @Valid TableSchemaQueryDTO dto) {
        return R.ok(targetTableSchemaService.listTables(dto.getDatasourceId()));
    }

    @Operation(summary = "读取目标表结构；表不存在时返回空列表")
    @PostMapping("/table-columns")
    public R<List<TargetTableSchemaService.TableColumn>> tableColumns(
            @RequestBody @Valid TableSchemaQueryDTO dto) {
        return R.ok(targetTableSchemaService.describeTable(dto.getDatasourceId(), dto.getTable()));
    }

    @Operation(summary = "按映射结果变更目标表结构（新建表 / 增删列 / 改列类型 / 设置主键）")
    @PostMapping("/apply-schema")
    public R<Map<String, Object>> applySchema(
            @RequestBody TargetTableSchemaService.ApplyRequest request) {
        return R.ok(targetTableSchemaService.apply(request));
    }
}
