package com.dhp.etl.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.core.PipelineExecutor;
import com.dhp.etl.core.guard.SqlSafetyGuard;
import com.dhp.etl.core.schedule.DynamicCronScheduler;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.SourceTypeEnum;
import com.dhp.etl.enums.TriggerTypeEnum;
import com.dhp.etl.mapper.EtlPipelineMapper;
import com.dhp.etl.service.EtlPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class EtlPipelineServiceImpl extends BaseCrudServiceImpl<EtlPipelineMapper, EtlPipeline> implements EtlPipelineService {

    private final SqlSafetyGuard sqlSafetyGuard;
    private final DynamicCronScheduler scheduler;
    private final PipelineExecutor pipelineExecutor;

    @Override
    protected Class<EtlPipeline> getClaas() {
        return EtlPipeline.class;
    }

    @Override
    public boolean savePipeline(EtlPipeline pipeline) {
        // 1. 业务编码自动生成 (若未指定则自动生成唯一标识)
        if (StrUtil.isBlank(pipeline.getPipelineCode())) {
            pipeline.setPipelineCode(generatePipelineCode());
        } else {
            checkCodeUnique(pipeline.getPipelineCode(), null);
        }

        // 2. SQL 语法与白名单校验
        validateSqls(pipeline);

        // 3. Cron 表达式语法校验
        validateCron(pipeline);

        boolean ok = this.save(pipeline);
        if (ok && pipeline.getScheduleStatus() != null && pipeline.getScheduleStatus() == 1) {
            scheduler.registerTask(pipeline.getId(), pipeline.getCronExpr());
        }
        return ok;
    }

    @Override
    public boolean updatePipeline(EtlPipeline pipeline) {
        // 1. 业务编码与版本号同步
        EtlPipeline old = this.getById(pipeline.getId());
        if (old != null) {
            if (StrUtil.isBlank(pipeline.getPipelineCode())) {
                pipeline.setPipelineCode(old.getPipelineCode());
            }
            pipeline.setObjectVersion(old.getObjectVersion());
        }

        if (StrUtil.isNotBlank(pipeline.getPipelineCode())) {
            checkCodeUnique(pipeline.getPipelineCode(), pipeline.getId());
        }

        // 2. SQL 语法与白名单校验
        validateSqls(pipeline);

        // 3. Cron 校验
        validateCron(pipeline);

        boolean ok = this.updateById(pipeline);
        if (ok) {
            if (pipeline.getScheduleStatus() != null && pipeline.getScheduleStatus() == 1) {
                scheduler.registerTask(pipeline.getId(), pipeline.getCronExpr());
            } else {
                scheduler.stopTask(pipeline.getId());
            }
        }
        return ok;
    }

    @Override
    public boolean removeById(Serializable id) {
        Long longId = id instanceof Long l ? l : Long.valueOf(id.toString());
        scheduler.stopTask(longId);
        return super.removeById(longId);
    }

    @Override
    public void toggleSchedule(Long id, Boolean enabled) {
        EtlPipeline pipeline = this.getById(id);
        if (pipeline == null) {
            throw new BizException(ErrorCode.ETL_PIPELINE_NOT_FOUND);
        }

        int status = Boolean.TRUE.equals(enabled) ? 1 : 0;
        pipeline.setScheduleStatus(status);
        this.updateById(pipeline);

        if (status == 1) {
            if (pipeline.getTargetDsId() == null || StrUtil.isBlank(pipeline.getTargetTable()) || StrUtil.isBlank(pipeline.getTransformSql())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "流程配置尚未完善（缺少目标数仓连接或转换SQL），无法启用定时调度");
            }
            validateCron(pipeline);
            scheduler.registerTask(pipeline.getId(), pipeline.getCronExpr());
        } else {
            scheduler.stopTask(pipeline.getId());
        }
    }

    @Override
    public void triggerRun(Long id) {
        EtlPipeline pipeline = this.getById(id);
        if (pipeline == null) {
            throw new BizException(ErrorCode.ETL_PIPELINE_NOT_FOUND);
        }
        // 异步后台启动执行
        CompletableFuture.runAsync(() -> pipelineExecutor.execute(id, TriggerTypeEnum.MANUAL));
    }

    @Override
    public boolean cancelRunning(Long id) {
        return pipelineExecutor.cancelRunning(id);
    }

    private void checkCodeUnique(String code, Long excludeId) {
        if (StrUtil.isBlank(code)) {
            throw new BizException(ErrorCode.PARAM_ERROR, "流程编码不能为空");
        }
        LambdaQueryWrapper<EtlPipeline> wrapper = new LambdaQueryWrapper<EtlPipeline>()
                .eq(EtlPipeline::getPipelineCode, code);
        if (excludeId != null) {
            wrapper.ne(EtlPipeline::getId, excludeId);
        }
        if (this.count(wrapper) > 0) {
            throw new BizException(ErrorCode.ETL_PIPELINE_CODE_DUPLICATE, "流程编码 [" + code + "] 已存在");
        }
    }

    private void validateSqls(EtlPipeline pipeline) {
        if (StrUtil.isNotBlank(pipeline.getTransformSql())) {
            sqlSafetyGuard.validate(pipeline.getTransformSql());
        }
        if (pipeline.getSourceType() == SourceTypeEnum.MYSQL && StrUtil.isNotBlank(pipeline.getSourceQuerySql())) {
            sqlSafetyGuard.validate(pipeline.getSourceQuerySql());
        }
    }

    private void validateCron(EtlPipeline pipeline) {
        if (pipeline.getScheduleStatus() != null && pipeline.getScheduleStatus() == 1) {
            if (StrUtil.isBlank(pipeline.getCronExpr()) || !CronExpression.isValidExpression(pipeline.getCronExpr())) {
                throw new BizException(ErrorCode.ETL_CRON_INVALID, "Cron 调度表达式无效: " + pipeline.getCronExpr());
            }
        }
    }

    private String generatePipelineCode() {
        for (int i = 0; i < 10; i++) {
            String code = "PL_" + DateUtil.format(new Date(), "yyyyMMdd") + "_" + RandomUtil.randomNumbers(4);
            long count = this.count(new LambdaQueryWrapper<EtlPipeline>()
                    .eq(EtlPipeline::getPipelineCode, code));
            if (count == 0) {
                return code;
            }
        }
        return "PL_" + System.currentTimeMillis();
    }
}
