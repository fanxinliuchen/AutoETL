package com.dhp.etl.core.extractor;

import com.dhp.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 请求体变量解析：变量定义解析、替换与 SQL 安全校验
 */
class RequestVarResolverTest {

    private Map<String, String> values() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("today", "2026-11-09");
        map.put("startdate", "2026-10-30");
        return map;
    }

    @Test
    @DisplayName("已知变量全部替换")
    void substituteKnown() {
        String body = "{\"FilterString\":\"FModifyDate >= '${startdate}' AND FModifyDate < '${today}'\"}";
        String result = RequestVarResolver.substitute(body, values());
        assertEquals("{\"FilterString\":\"FModifyDate >= '2026-10-30' AND FModifyDate < '2026-11-09'\"}", result);
    }

    @Test
    @DisplayName("未定义变量直接报错，并提示已废弃的偏移写法")
    void substituteUnknownThrows() {
        BizException e = assertThrows(BizException.class,
                () -> RequestVarResolver.substitute("FDate >= '${today-60d}'", values()));
        assertTrue(e.getMessage().contains("today-60d"), e.getMessage());
        assertTrue(e.getMessage().contains("变量定义"), e.getMessage());
    }

    @Test
    @DisplayName("无变量时原样返回")
    void substituteNoVar() {
        String body = "{\"data\":{\"FormId\":\"PUR_PurchaseOrder\"}}";
        assertEquals(body, RequestVarResolver.substitute(body, values()));
    }

    @Test
    @DisplayName("同名变量多处出现全部替换")
    void substituteRepeated() {
        String text = "'${startdate}' AND '${startdate}'";
        assertEquals("'2026-10-30' AND '2026-10-30'", RequestVarResolver.substitute(text, values()));
    }

    @Test
    @DisplayName("解析 filter_meta 中的变量定义")
    void parseDefinitions() {
        String meta = "{\"mode\":\"table\",\"variables\":["
                + "{\"name\":\"startdate\",\"sql\":\"DATE_SUB(CURDATE(), INTERVAL 7 DAY)\"},"
                + "{\"name\":\"enddate\",\"sql\":\"CURDATE()\"}],"
                + "\"conditions\":[]}";
        List<RequestVarResolver.VarDef> defs = RequestVarResolver.parseDefinitions(meta);
        assertEquals(2, defs.size());
        assertEquals("startdate", defs.get(0).getName());
        assertEquals("DATE_SUB(CURDATE(), INTERVAL 7 DAY)", defs.get(0).getSql());
        assertEquals("enddate", defs.get(1).getName());
    }

    @Test
    @DisplayName("空 filter_meta 或空 variables 返回空列表")
    void parseDefinitionsEmpty() {
        assertTrue(RequestVarResolver.parseDefinitions(null).isEmpty());
        assertTrue(RequestVarResolver.parseDefinitions("").isEmpty());
        assertTrue(RequestVarResolver.parseDefinitions("{\"mode\":\"table\"}").isEmpty());
        assertTrue(RequestVarResolver.parseDefinitions("{\"variables\":[]}").isEmpty());
    }

    @Test
    @DisplayName("缺名称或缺 SQL 的变量被忽略")
    void parseDefinitionsSkipIncomplete() {
        String meta = "{\"variables\":[{\"name\":\"a\",\"sql\":\"\"},{\"name\":\"\",\"sql\":\"CURDATE()\"},"
                + "{\"name\":\"b\",\"sql\":\"CURDATE()\"}]}";
        List<RequestVarResolver.VarDef> defs = RequestVarResolver.parseDefinitions(meta);
        assertEquals(1, defs.size());
        assertEquals("b", defs.get(0).getName());
    }

    @Test
    @DisplayName("变量名为空时用占位，便于前端编辑中的草稿不报错")
    void parseDefinitionsKeepsDraftName() {
        // 解析阶段不做名称校验，校验发生在执行前，避免编辑中途保存报错
        List<RequestVarResolver.VarDef> defs = RequestVarResolver.parseDefinitions(
                "{\"variables\":[{\"name\":\"1bad\",\"sql\":\"CURDATE()\"}]}");
        assertEquals(1, defs.size());
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(defs.get(0)));
    }

    @Test
    @DisplayName("合法变量定义通过校验")
    void validateOk() {
        assertDoesNotThrow(() -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("startdate",
                        "DATE_SUB(CURDATE(), INTERVAL ((WEEKDAY(CURDATE()) + 3) % 7 + 7) DAY)")));
        assertDoesNotThrow(() -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("_v1", "CURDATE()")));
    }

    @Test
    @DisplayName("多语句被拒")
    void validateRejectsMultiStatement() {
        BizException e = assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("v", "CURDATE(); DROP TABLE x")));
        assertTrue(e.getMessage().contains("分号"), e.getMessage());
    }

    @Test
    @DisplayName("文件写入与读文件被拒")
    void validateRejectsFileOps() {
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("v", "1 INTO OUTFILE '/tmp/x'")));
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("v", "LOAD_FILE('/etc/passwd')")));
    }

    @Test
    @DisplayName("拖库类函数与系统库被拒")
    void validateRejectsHeavyOps() {
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("v", "SLEEP(100)")));
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("v", "SELECT 1 FROM INFORMATION_SCHEMA.TABLES")));
    }

    @Test
    @DisplayName("变量名非法被拒")
    void validateRejectsBadName() {
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("1abc", "CURDATE()")));
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("start date", "CURDATE()")));
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("开始日期", "CURDATE()")));
    }

    @Test
    @DisplayName("超长 SQL 被拒")
    void validateRejectsTooLong() {
        String longSql = "1+" + "1".repeat(1100);
        assertThrows(BizException.class, () -> RequestVarResolver.validateDefinition(
                new RequestVarResolver.VarDef("v", longSql)));
    }
}
