package com.dhp.etl.core.extractor;

import com.dhp.common.exception.BizException;
import com.dhp.etl.entity.EtlPipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附加列（filter_meta.extraColumns）解析与取值测试。
 *
 * <p>附加列用于把 begindate / enddate 这类变量额外写进数仓，不参与接口字段抽取。
 */
class ExtraColumnTest {

    private final RequestVarResolver resolver = new RequestVarResolver(null);

    private static String metaWith(String extraJson) {
        return "{\"variables\":[],\"extraColumns\":" + extraJson + "}";
    }

    @Test
    @DisplayName("解析：跳过空列名，保留变量/常量与列类型")
    void parseColumns() {
        List<RequestVarResolver.ExtraColumn> cols = RequestVarResolver.parseExtraColumns(metaWith("""
                [
                  {"name":"begindate","valueType":"VARIABLE","value":"begindate","type":"DATE"},
                  {"name":"  ","valueType":"VARIABLE","value":"x"},
                  {"name":"src_tag","valueType":"CONSTANT","value":"kingdee","type":"VARCHAR(255)"}
                ]
                """));

        assertEquals(2, cols.size());
        assertEquals("begindate", cols.get(0).getName());
        assertEquals("DATE", cols.get(0).getType());
        assertEquals("CONSTANT", cols.get(1).getValueType());
    }

    @Test
    @DisplayName("解析：非法列名直接报错，不放进 DDL")
    void rejectIllegalName() {
        BizException ex = assertThrows(BizException.class, () -> RequestVarResolver.parseExtraColumns(
                metaWith("[{\"name\":\"drop table x;\",\"valueType\":\"CONSTANT\",\"value\":\"1\"}]")));
        assertTrue(ex.getMessage().contains("列名不合法"), ex.getMessage());
    }

    @Test
    @DisplayName("没有配置时返回空，不影响老流程")
    void emptyWhenAbsent() {
        assertTrue(RequestVarResolver.parseExtraColumns("{\"variables\":[]}").isEmpty());
        assertTrue(RequestVarResolver.parseExtraColumns(null).isEmpty());
    }

    @Test
    @DisplayName("取值：常量原样返回，常量里也能引用内置变量")
    void resolveConstant() {
        EtlPipeline pipeline = new EtlPipeline();
        pipeline.setFilterMeta(metaWith("""
                [{"name":"src_tag","valueType":"CONSTANT","value":"kingdee"},
                 {"name":"load_day","valueType":"CONSTANT","value":"${today}"}]
                """));

        Map<String, String> values = resolver.resolveExtraColumns(pipeline, new StringBuilder(), Map.of());

        assertEquals("kingdee", values.get("src_tag"));
        assertEquals(LocalDate.now().toString(), values.get("load_day"));
    }

    @Test
    @DisplayName("取值：VARIABLE 引用内置变量（不需要目标库）")
    void resolveVariableFromBuiltin() {
        EtlPipeline pipeline = new EtlPipeline();
        pipeline.setFilterMeta(metaWith("[{\"name\":\"stat_date\",\"valueType\":\"VARIABLE\",\"value\":\"yesterday\"}]"));

        Map<String, String> values = resolver.resolveExtraColumns(pipeline, new StringBuilder(), Map.of());

        assertEquals(LocalDate.now().minusDays(1).toString(), values.get("stat_date"));
    }

    @Test
    @DisplayName("取值：变量未定义时直接报错，避免写入空列")
    void failOnUnknownVariable() {
        EtlPipeline pipeline = new EtlPipeline();
        pipeline.setFilterMeta(metaWith("[{\"name\":\"begindate\",\"valueType\":\"VARIABLE\",\"value\":\"nope\"}]"));

        BizException ex = assertThrows(BizException.class,
                () -> resolver.resolveExtraColumns(pipeline, new StringBuilder(), Map.of()));
        assertTrue(ex.getMessage().contains("nope"), ex.getMessage());
    }
}
