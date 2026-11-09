package com.dhp.etl.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.common.exception.BizException;
import com.dhp.common.result.ErrorCode;
import com.dhp.etl.entity.EtlKingdeeCredential;
import com.dhp.etl.mapper.EtlKingdeeCredentialMapper;
import com.dhp.etl.service.EtlKingdeeCredentialService;
import com.dhp.etl.util.CipherUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EtlKingdeeCredentialServiceImpl
        extends BaseCrudServiceImpl<EtlKingdeeCredentialMapper, EtlKingdeeCredential>
        implements EtlKingdeeCredentialService {

    private final CipherUtil cipherUtil;

    @Override
    protected Class<EtlKingdeeCredential> getClaas() {
        return EtlKingdeeCredential.class;
    }

    @Override
    public EtlKingdeeCredential getDefaultCredential() {
        return this.getOne(new LambdaQueryWrapper<EtlKingdeeCredential>()
                .eq(EtlKingdeeCredential::getIsDefault, 1)
                .eq(EtlKingdeeCredential::getStatus, 1)
                .orderByDesc(EtlKingdeeCredential::getId)
                .last("limit 1"));
    }

    @Override
    public EtlKingdeeCredential saveDefaultCredential(EtlKingdeeCredential input) {
        if (input == null || StrUtil.isBlank(input.getLoginUrl())) {
            throw new BizException(ErrorCode.PARAM_ERROR, "金蝶登录接口地址不能为空");
        }

        EtlKingdeeCredential existing = getDefaultCredential();
        if (existing == null) {
            if (StrUtil.isBlank(input.getPasswordCipher())) {
                throw new BizException(ErrorCode.PARAM_ERROR, "首次保存凭证必须填写登录密码");
            }
            input.setId(null);
            input.setLoginUrl(input.getLoginUrl().trim());
            input.setQueryUrl(StrUtil.trim(input.getQueryUrl()));
            input.setAcctId(StrUtil.trim(input.getAcctId()));
            input.setUserName(StrUtil.trim(input.getUserName()));
            input.setPasswordCipher(cipherUtil.encrypt(input.getPasswordCipher()));
            input.setLcid(input.getLcid() == null ? 2052 : input.getLcid());
            input.setCredName(StrUtil.blankToDefault(input.getCredName(), "默认凭证"));
            input.setIsDefault(1);
            input.setStatus(1);
            this.save(input);
            return input;
        }

        existing.setLoginUrl(input.getLoginUrl().trim());
        // 查询接口地址留空时保留旧值，避免从登录步骤保存时把已存地址清掉
        if (StrUtil.isNotBlank(input.getQueryUrl())) {
            existing.setQueryUrl(input.getQueryUrl().trim());
        }
        existing.setAcctId(StrUtil.trim(input.getAcctId()));
        existing.setUserName(StrUtil.trim(input.getUserName()));
        if (input.getLcid() != null) {
            existing.setLcid(input.getLcid());
        }
        // 密码留空表示沿用旧密码，避免前端每次都要重新输入
        if (StrUtil.isNotBlank(input.getPasswordCipher())) {
            existing.setPasswordCipher(cipherUtil.encrypt(input.getPasswordCipher()));
        }
        this.updateById(existing);
        return existing;
    }

    @Override
    public String resolvePassword(String acctId, String userName) {
        EtlKingdeeCredential credential = getDefaultCredential();
        if (credential == null || StrUtil.isBlank(credential.getPasswordCipher())) {
            return null;
        }
        // 仅当账套与用户名都匹配时才兜底，防止把 A 账号的密码用到 B 账号上
        if (StrUtil.isNotBlank(acctId) && !acctId.equalsIgnoreCase(credential.getAcctId())) {
            return null;
        }
        if (StrUtil.isNotBlank(userName) && !userName.equalsIgnoreCase(credential.getUserName())) {
            return null;
        }
        return cipherUtil.decrypt(credential.getPasswordCipher());
    }
}
