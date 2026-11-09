package com.dhp.etl.service.impl;

import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.etl.entity.EtlAlertConfig;
import com.dhp.etl.mapper.EtlAlertConfigMapper;
import com.dhp.etl.service.EtlAlertConfigService;
import org.springframework.stereotype.Service;

@Service
public class EtlAlertConfigServiceImpl extends BaseCrudServiceImpl<EtlAlertConfigMapper, EtlAlertConfig> implements EtlAlertConfigService {

    @Override
    protected Class<EtlAlertConfig> getClaas() {
        return EtlAlertConfig.class;
    }
}
