package com.dhp.etl.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.dhp.etl.core.PipelineExecutor;
import com.dhp.etl.core.guard.PostgresSqlGuard;
import com.dhp.etl.core.guard.SqlSafetyGuard;
import com.dhp.etl.core.schedule.DynamicCronScheduler;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.enums.SourceTypeEnum;
import com.dhp.etl.service.EtlDatasourceService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 流程最近运行状态保护测试：
 * <ul>
 *   <li>编辑保存配置时，不得用请求体里的旧快照把库中最新的 lastRunStatus / lastRunTime 倒退覆盖；</li>
 *   <li>任务结束写回最近状态时，定向走条件更新而不走带乐观锁的 updateById。</li>
 * </ul>
 */
class EtlPipelineLastRunStatusTest {

    @BeforeAll
    static void initMyBatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), EtlPipeline.class);
    }

    @Test
    @DisplayName("updatePipeline 保存配置时强制保留库里原有的 lastRunStatus 和 lastRunTime")
    void updatePipelinePreservesExistingLastRunStatusAndTime() {
        EtlPipelineServiceImpl service = spy(new EtlPipelineServiceImpl(
                mock(SqlSafetyGuard.class),
                mock(PostgresSqlGuard.class),
                mock(EtlDatasourceService.class),
                mock(DynamicCronScheduler.class),
                mock(PipelineExecutor.class)));

        Date latestSuccessTime = new Date(1795254169000L);
        EtlPipeline existing = new EtlPipeline();
        existing.setId(2121110770342170626L);
        existing.setPipelineCode("PL_20261113_6818");
        existing.setObjectVersion(54);
        existing.setLastRunStatus("SUCCESS");
        existing.setLastRunTime(latestSuccessTime);

        // 模拟前端旧页面或回滚脚本提交了过时的 FAILED 快照
        EtlPipeline incoming = new EtlPipeline();
        incoming.setId(2121110770342170626L);
        incoming.setPipelineCode("PL_20261113_6818");
        incoming.setSourceType(SourceTypeEnum.WEB_API);
        incoming.setScheduleStatus(0);
        incoming.setLastRunStatus("FAILED");
        incoming.setLastRunTime(new Date(1795251720000L));

        doReturn(existing).when(service).getById(2121110770342170626L);
        doReturn(0L).when(service).count(any(Wrapper.class));
        doReturn(true).when(service).updateById(any(EtlPipeline.class));

        boolean ok = service.updatePipeline(incoming);

        assertTrue(ok);
        ArgumentCaptor<EtlPipeline> savedCaptor = ArgumentCaptor.forClass(EtlPipeline.class);
        verify(service).updateById(savedCaptor.capture());
        EtlPipeline saved = savedCaptor.getValue();
        assertEquals("SUCCESS", saved.getLastRunStatus());
        assertEquals(latestSuccessTime, saved.getLastRunTime());
        assertEquals(54, saved.getObjectVersion());
    }

    @Test
    @DisplayName("updateLastRun 定向走条件更新，不调用带乐观锁的 updateById")
    void updateLastRunUsesTargetedUpdateInsteadOfUpdateById() {
        EtlPipelineServiceImpl service = spy(new EtlPipelineServiceImpl(
                mock(SqlSafetyGuard.class),
                mock(PostgresSqlGuard.class),
                mock(EtlDatasourceService.class),
                mock(DynamicCronScheduler.class),
                mock(PipelineExecutor.class)));

        doReturn(true).when(service).update(any(Wrapper.class));
        Date now = new Date();

        service.updateLastRun(2121110770342170626L, "SUCCESS", now);

        verify(service).update(any(Wrapper.class));
        verify(service, never()).updateById(any(EtlPipeline.class));
    }
}
