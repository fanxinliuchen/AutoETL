package com.dhp.etl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 金蝶报表字段推断入参 DTO
 *
 * <p>报表类表单（如 STK_StockSummaryRpt）没有单据元数据，QueryBusinessInfo 取不到列定义。
 * 这里改用「分页报表取数」接口试取 1 行：FieldKeys 留空时返回方案的全部列，
 * 行对象的 key 就是字段标识，据此反推字段清单。
 */
@Data
public class KingdeeReportFieldQueryDTO implements Serializable {

    /** 金蝶登录接口地址 (ValidateUser) */
    @NotBlank(message = "登录接口地址不能为空")
    private String loginUrl;

    /** 账套 ID */
    private String acctID;

    /** 登录用户名 */
    private String userName;

    /** 登录密码（留空则使用已保存的默认凭证） */
    private String password;

    /** 语言 ID，默认 2052 */
    private Integer lcid;

    /** 报表 FORMID，如 STK_StockSummaryRpt */
    @NotBlank(message = "报表 FORMID 不能为空")
    private String formId;

    /** 过滤方案 ID (FSCHEMEID)，必须是已保存的具体方案 */
    @NotBlank(message = "过滤方案 ID 不能为空")
    private String schemeId;

    /** 报表取数接口地址（可选，留空按登录地址推导） */
    private String reportUrl;
}
