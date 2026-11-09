package com.dhp.etl.controller;

import com.dhp.common.base.BaseCrudController;
import com.dhp.common.base.IBaseCrudService;
import com.dhp.etl.entity.EtlAlertConfig;
import com.dhp.etl.service.EtlAlertConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/etl/alert")
@RequiredArgsConstructor
@Tag(name = "ETL告警配置管理")
public class EtlAlertConfigController extends BaseCrudController<EtlAlertConfig> {

    private final EtlAlertConfigService alertConfigService;

    @Override
    protected IBaseCrudService<EtlAlertConfig> getService() {
        return alertConfigService;
    }
}
