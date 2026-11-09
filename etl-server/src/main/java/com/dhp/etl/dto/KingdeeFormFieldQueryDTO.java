package com.dhp.etl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 金蝶表单字段元数据查询入参 DTO
 *
 * <p>用于「从金蝶拉字段」：登录后调用 QueryBusinessInfo 取表单全部字段，
 * 自动带出字段标识、中文名与数据类型，避免手工拼写 FieldKeys。
 */
@Data
public class KingdeeFormFieldQueryDTO implements Serializable {

    /** 金蝶登录接口地址 (ValidateUser) */
    @NotBlank(message = "登录接口地址不能为空")
    private String loginUrl;

    /** 账套 ID */
    private String acctID;

    /** 登录用户名 */
    private String userName;

    /** 登录密码 */
    private String password;

    /** 语言 ID，默认 2052 (简体中文) */
    private Integer lcid;

    /** 业务对象表单标识，如 PUR_PurchaseOrder / BD_MATERIAL */
    @NotBlank(message = "业务对象表单标识 FormId 不能为空")
    private String formId;
}
