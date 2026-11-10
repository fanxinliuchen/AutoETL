package com.dhp.etl.controller;

import com.dhp.common.base.BaseCrudController;
import com.dhp.common.base.IBaseCrudService;
import com.dhp.common.result.R;
import com.dhp.etl.core.extractor.KingdeeMetadataClient;
import com.dhp.etl.core.extractor.RequestVarResolver;
import com.dhp.etl.core.extractor.WebApiExtractor;
import com.dhp.etl.core.guard.SqlSafetyGuard;
import com.dhp.etl.core.loop.LoopDatasetService;
import com.dhp.etl.dto.KingdeeFormFieldQueryDTO;
import com.dhp.etl.dto.LoopEvalDTO;
import com.dhp.etl.dto.SqlValidateDTO;
import com.dhp.etl.dto.VarEvalDTO;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.service.EtlPipelineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/etl/pipeline")
@RequiredArgsConstructor
@Tag(name = "ETL流程配置管理")
public class EtlPipelineController extends BaseCrudController<EtlPipeline> {

    private final EtlPipelineService pipelineService;
    private final SqlSafetyGuard sqlSafetyGuard;
    private final WebApiExtractor webApiExtractor;
    private final KingdeeMetadataClient kingdeeMetadataClient;
    private final RequestVarResolver requestVarResolver;
    private final LoopDatasetService loopDatasetService;

    @Override
    protected IBaseCrudService<EtlPipeline> getService() {
        return pipelineService;
    }

    @Override
    @Operation(summary = "新增流程配置")
    @PostMapping
    public R<Boolean> save(@RequestBody EtlPipeline entity) {
        return R.ok(pipelineService.savePipeline(entity));
    }

    @Override
    @Operation(summary = "修改流程配置")
    @PutMapping("/{id}/update")
    public R<Boolean> update(@PathVariable Serializable id, @RequestBody EtlPipeline entity) {
        if (id != null) {
            try {
                entity.setId(Long.valueOf(id.toString()));
            } catch (Exception ignored) {}
        }
        return R.ok(pipelineService.updatePipeline(entity));
    }

    @Operation(summary = "保存流程配置 (支持分步暂存草稿与全量保存)")
    @PostMapping("/save-step")
    public R<EtlPipeline> saveStep(@RequestBody EtlPipeline entity) {
        if (entity.getId() != null) {
            pipelineService.updatePipeline(entity);
        } else {
            pipelineService.savePipeline(entity);
        }
        return R.ok(entity);
    }

    @Operation(summary = "手动触发执行一次")
    @PostMapping("/{id}/run")
    public R<Void> triggerRun(@PathVariable Long id) {
        pipelineService.triggerRun(id);
        return R.ok();
    }

    @Operation(summary = "启停定时调度")
    @PutMapping("/{id}/schedule/toggle")
    public R<Void> toggleSchedule(@PathVariable Long id, @RequestParam Boolean enabled) {
        pipelineService.toggleSchedule(id, enabled);
        return R.ok();
    }

    @Operation(summary = "终止运行中的流程")
    @PostMapping("/{id}/cancel")
    public R<Boolean> cancelRunning(@PathVariable Long id) {
        return R.ok(pipelineService.cancelRunning(id));
    }

    @Operation(summary = "SQL 语法与白名单校验")
    @PostMapping("/sql/validate")
    public R<String> validateSql(@RequestBody @Valid SqlValidateDTO dto) {
        sqlSafetyGuard.validate(dto.getSql());
        return R.ok("SQL 校验通过，语法与安全规则符合要求");
    }

    @Operation(summary = "WebAPI 前置登录测试")
    @PostMapping("/webapi/test-login")
    public R<Map<String, Object>> testWebapiLogin(@RequestBody Map<String, String> params) {
        String authType = params.get("authType");
        String authConfig = params.get("authConfig");
        StringBuilder logBuffer = new StringBuilder();
        Map<String, Object> result = webApiExtractor.executePreAuth(authType, authConfig, logBuffer);
        result.put("log", logBuffer.toString());
        return R.ok(result);
    }

    @Operation(summary = "拉取金蝶表单字段元数据（字段标识/中文名/类型）")
    @PostMapping("/webapi/form-fields")
    public R<List<Map<String, Object>>> queryKingdeeFormFields(
            @RequestBody @Valid KingdeeFormFieldQueryDTO dto) {
        return R.ok(kingdeeMetadataClient.queryFormFields(dto));
    }

    @Operation(summary = "识别金蝶表单类型（单据 / 报表），用于取数方式自动选择")
    @PostMapping("/webapi/detect-form")
    public R<Map<String, Object>> detectKingdeeFormType(
            @RequestBody @Valid KingdeeFormFieldQueryDTO dto) {
        return R.ok(kingdeeMetadataClient.detectFormType(dto));
    }

    @Operation(summary = "WebAPI 抽取测试与数据预览")
    @PostMapping("/webapi/preview")
    public R<Map<String, Object>> previewWebapiExtract(@RequestBody EtlPipeline pipeline) {
        StringBuilder logBuffer = new StringBuilder();
        List<Map<String, Object>> records = webApiExtractor.extract(pipeline, logBuffer);
        Map<String, Object> res = new HashMap<>();
        res.put("total", records.size());
        res.put("records", records.stream().limit(50).toList());
        res.put("log", logBuffer.toString());
        return R.ok(res);
    }

    @Operation(summary = "变量 SQL 试算（在目标数据源执行，不落库）")
    @PostMapping("/webapi/eval-var")
    public R<Map<String, Object>> evalVariable(@RequestBody @Valid VarEvalDTO dto) {
        return R.ok(requestVarResolver.evaluate(dto.getTargetDsId(), dto.getName(), dto.getSql()));
    }

    @Operation(summary = "循环数据集试算（返回行数与预览，不落库）")
    @PostMapping("/webapi/eval-loop")
    public R<Map<String, Object>> evalLoop(@RequestBody @Valid LoopEvalDTO dto) {
        int limit = dto.getLimit() == null ? 10 : dto.getLimit();
        return R.ok(loopDatasetService.preview(dto.getTargetDsId(), dto.getSql(), limit));
    }
}
