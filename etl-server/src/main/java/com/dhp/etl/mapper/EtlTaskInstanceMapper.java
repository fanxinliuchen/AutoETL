package com.dhp.etl.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.dhp.etl.entity.EtlTaskInstance;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EtlTaskInstanceMapper extends BaseMapper<EtlTaskInstance> {
}
