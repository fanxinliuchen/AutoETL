package com.dhp.etl.controller;

import com.dhp.common.base.BaseCrudController;
import com.dhp.common.base.IBaseCrudService;
import com.dhp.common.result.R;
import com.dhp.etl.entity.EtlFolder;
import com.dhp.etl.service.EtlFolderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/etl/folder")
@RequiredArgsConstructor
@Tag(name = "ETL目录分类管理")
public class EtlFolderController extends BaseCrudController<EtlFolder> {

    private final EtlFolderService folderService;

    @Override
    protected IBaseCrudService<EtlFolder> getService() {
        return folderService;
    }

    @Operation(summary = "获取左侧树形目录结构")
    @GetMapping("/tree")
    public R<List<Map<String, Object>>> getTree() {
        return R.ok(folderService.getFolderTree());
    }
}
