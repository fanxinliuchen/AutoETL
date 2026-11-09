package com.dhp.etl.service;

import com.dhp.common.base.IBaseCrudService;
import com.dhp.etl.entity.EtlKingdeeCredential;

/**
 * 金蝶登录凭证服务
 */
public interface EtlKingdeeCredentialService extends IBaseCrudService<EtlKingdeeCredential> {

    /**
     * 保存（或更新）默认凭证。首次保存必须提供密码；已存在时密码留空表示沿用旧密码。
     */
    EtlKingdeeCredential saveDefaultCredential(EtlKingdeeCredential input);

    /**
     * 取默认凭证（含密文），无则返回 null
     */
    EtlKingdeeCredential getDefaultCredential();

    /**
     * 按 acctID / userName 匹配默认凭证并返回解密后的密码；不匹配或无凭证返回 null。
     * 用于「密码留空时自动兜底」。
     */
    String resolvePassword(String acctId, String userName);
}
