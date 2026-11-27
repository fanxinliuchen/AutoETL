package com.dhp.etl.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.core.PipelineExecutor;
import com.dhp.etl.core.guard.PostgresSqlGuard;
import com.dhp.etl.core.guard.SqlSafetyGuard;
import com.dhp.etl.core.schedule.DynamicCronScheduler;
import com.dhp.etl.entity.EtlDatasource;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.ConnTypeEnum;
import com.dhp.etl.enums.SourceTypeEnum;
import com.dhp.etl.enums.TriggerTypeEnum;
import com.dhp.etl.mapper.EtlPipelineMapper;
import com.dhp.etl.service.EtlDatasourceService;
import com.dhp.etl.service.EtlPipelineService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class EtlPipelineServiceImpl extends BaseCrudServiceImpl<EtlPipelineMapper, EtlPipeline> implements EtlPipelineService {

    private final SqlSafetyGuard sqlSafetyGuard;
    private final PostgresSqlGuard postgresSqlGuard;
    private final EtlDatasourceService datasourceService;
    private final DynamicCronScheduler scheduler;
    private final PipelineExecutor pipelineExecutor;

    /** 流程名称列宽（VARCHAR(128)） */
    private static final int NAME_MAX_LENGTH = 128;
    /** 副本名称后缀 */
    private static final String COPY_SUFFIX = "（副本）";

    @Override
    protected Class<EtlPipeline> getClaas() {
        return EtlPipeline.class;
    }

    @Override
    public boolean savePipeline(EtlPipeline pipeline) {
        normalizeJsonColumns(pipeline);
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
        normalizeJsonColumns(pipeline);
        // 1. 业务编码、版本号与只读运行态同步：保存配置不能用客户端旧快照把最近运行状态/时间倒退覆盖
        EtlPipeline old = this.getById(pipeline.getId());
        if (old != null) {
            if (StrUtil.isBlank(pipeline.getPipelineCode())) {
                pipeline.setPipelineCode(old.getPipelineCode());
            }
            pipeline.setObjectVersion(old.getObjectVersion());
            pipeline.setLastRunStatus(old.getLastRunStatus());
            pipeline.setLastRunTime(old.getLastRunTime());
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
    public void updateLastRun(Long id, String lastRunStatus, Date lastRunTime) {
        if (id == null) {
            return;
        }
        this.update(new LambdaUpdateWrapper<EtlPipeline>()
                .eq(EtlPipeline::getId, id)
                .set(EtlPipeline::getLastRunStatus, lastRunStatus)
                .set(EtlPipeline::getLastRunTime, lastRunTime));
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
            // 转换 SQL 允许留空（留空即按目标表结构自动生成入库脚本），
            // 这里只校验与 PipelineExecutor 一致的前置条件，别把能跑的流程挡在门外
            if (pipeline.getTargetDsId() == null || StrUtil.isBlank(pipeline.getTargetTable())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "流程配置尚未完善（缺少目标数仓连接或目标表），无法启用定时调度");
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

    @Override
    public EtlPipeline copyPipeline(Long id) {
        EtlPipeline source = this.getById(id);
        if (source == null) {
            throw new BizException(ErrorCode.ETL_PIPELINE_NOT_FOUND);
        }

        EtlPipeline copy = new EtlPipeline();
        BeanUtils.copyProperties(source, copy);

        // 主键与审计字段必须重置：沿用旧值会覆盖原记录或带着旧时间入库
        copy.setId(null);
        copy.setCreateUserId(null);
        copy.setCreateUserName(null);
        copy.setCreateTime(null);
        copy.setUpdateUserId(null);
        copy.setUpdateUserName(null);
        copy.setUpdateTime(null);
        copy.setObjectStatusCd(null);
        copy.setObjectVersion(null);
        copy.setTenantId(null);

        copy.setPipelineCode(generatePipelineCode());
        copy.setPipelineName(nextCopyName(source.getPipelineName()));
        // 副本默认停用：改完 Cron / 目标表再手动开启，避免与原流程同一时刻双跑
        copy.setScheduleStatus(0);
        copy.setLastRunStatus(null);
        copy.setLastRunTime(null);

        // 不走 savePipeline 的 SQL / Cron 校验：原流程已在跑，复制不应被历史写法挡住
        this.save(copy);
        return copy;
    }

    /**
     * 副本名称：原名 + （副本），重名时递增序号；超过列宽（128）先截断原名
     */
    private String nextCopyName(String sourceName) {
        String base = StrUtil.blankToDefault(sourceName, "未命名流程");
        int keep = NAME_MAX_LENGTH - COPY_SUFFIX.length() - 3;
        if (base.length() > keep) {
            base = base.substring(0, keep);
        }
        String name = base + COPY_SUFFIX;
        int index = 2;
        while (this.count(new LambdaQueryWrapper<EtlPipeline>()
                .eq(EtlPipeline::getPipelineName, name)) > 0) {
            name = base + "（副本" + index++ + "）";
        }
        return name;
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

    /**
     * api_headers / api_pagination 在库里是 MySQL JSON 列。
     * 前端清空输入时可能传入空字符串，MySQL 会报
     * "Invalid JSON text: The document is empty"，这里统一收敛为 {}。
     */
    private void normalizeJsonColumns(EtlPipeline pipeline) {
        if (pipeline == null) {
            return;
        }
        if (pipeline.getApiPagination() != null && StrUtil.isBlank(pipeline.getApiPagination())) {
            pipeline.setApiPagination("{}");
        }
        if (pipeline.getApiHeaders() != null && StrUtil.isBlank(pipeline.getApiHeaders())) {
            pipeline.setApiHeaders("{}");
        }
    }

    private boolean isPostgresSource(EtlPipeline pipeline) {
        if (pipeline.getSourceDsId() == null) {
            return false;
        }
        EtlDatasource ds = datasourceService.getById(pipeline.getSourceDsId());
        return ds != null && ConnTypeEnum.of(ds.getConnType()) == ConnTypeEnum.POSTGRESQL;
    }

    private void validateSqls(EtlPipeline pipeline) {
        if (StrUtil.isNotBlank(pipeline.getTransformSql())) {
            sqlSafetyGuard.validate(pipeline.getTransformSql());
        }
        if (pipeline.getSourceType() == SourceTypeEnum.MYSQL && StrUtil.isNotBlank(pipeline.getSourceQuerySql())) {
            // 源 SQL 按连接方言校验：PG 语法（:: / ILIKE / ON CONFLICT）过不了 JSqlParser
            if (isPostgresSource(pipeline)) {
                postgresSqlGuard.validateShape(pipeline.getSourceQuerySql());
            } else {
                sqlSafetyGuard.validate(pipeline.getSourceQuerySql());
            }
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
