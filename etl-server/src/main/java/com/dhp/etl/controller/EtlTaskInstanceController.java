package com.dhp.etl.controller;

import com.dhp.common.base.BaseCrudController;
import com.dhp.common.base.IBaseCrudService;
import com.dhp.etl.entity.EtlTaskInstance;
import com.dhp.etl.service.EtlTaskInstanceService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/etl/instance")
@RequiredArgsConstructor
@Tag(name = "ETL运行实例与日志")
public class EtlTaskInstanceController extends BaseCrudController<EtlTaskInstance> {

    private final EtlTaskInstanceService instanceService;

    @Override
    protected IBaseCrudService<EtlTaskInstance> getService() {
        return instanceService;
    }
}
