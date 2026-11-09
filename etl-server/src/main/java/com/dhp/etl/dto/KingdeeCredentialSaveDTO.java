package com.dhp.etl.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 金蝶登录凭证保存入参 DTO
 *
 * <p>password 为明文，由 Service 加密后落库；更新时留空表示沿用旧密码。
 */
@Data
public class KingdeeCredentialSaveDTO implements Serializable {

    /** 金蝶登录接口地址 */
    @NotBlank(message = "金蝶登录接口地址不能为空")
    private String loginUrl;

    /** 数据中心 / 账套代码 */
    private String acctID;

    /** 登录用户名 */
    private String userName;

    /** 登录密码（明文，更新时留空表示沿用旧密码） */
    private String password;

    /** 多语言环境代码 */
    private Integer lcid;
}
