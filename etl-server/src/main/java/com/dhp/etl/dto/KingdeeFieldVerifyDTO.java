package com.dhp.etl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 字段存在性实测入参。
 *
 * <p>用 {@code ExecuteBillQuery} 真打一次金蝶，让它逐个点名「元数据中标识为 X 的字段不存在」。
 * 为什么不能只靠 {@code QueryBusinessInfo}：返回的元数据并不包含表单的全部可查询字段
 * （实测 SAL_RETURNSTOCK 返回 249 个字段，但 FMaterialName / F_BGJ_* 等字段能用却不在其中），
 * 只比元数据会把合法字段误判成不存在。
 */
@Data
public class KingdeeFieldVerifyDTO implements Serializable {

    /** 金蝶登录接口地址 (ValidateUser) */
    @NotBlank(message = "登录接口地址不能为空")
    private String loginUrl;

    /** 账套 ID */
    private String acctID;

    /** 登录用户名 */
    private String userName;

    /** 登录密码；留空时由后端取已保存凭证 */
    private String password;

    /** 语言 ID，默认 2052 */
    private Integer lcid;

    /** 业务对象表单标识 */
    @NotBlank(message = "业务对象表单标识 FormId 不能为空")
    private String formId;

    /** 单据查询接口地址（ExecuteBillQuery）；留空由登录地址推导 */
    private String billQueryUrl;

    /** 待校验的字段清单，逗号分隔 */
    @NotBlank(message = "字段清单不能为空")
    private String fieldKeys;

    /** 最多剔除多少轮，防止一个配错的清单把金蝶打到超时；默认 20 */
    private Integer maxRounds;
}
