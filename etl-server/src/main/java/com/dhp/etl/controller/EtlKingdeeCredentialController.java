package com.dhp.etl.controller;

import cn.hutool.core.util.StrUtil;
import com.dhp.common.result.R;
import com.dhp.etl.dto.KingdeeCredentialSaveDTO;
import com.dhp.etl.entity.EtlKingdeeCredential;
import com.dhp.etl.service.EtlKingdeeCredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 金蝶登录凭证管理
 *
 * <p>密码只进不出：保存时加密落库，读取时仅返回 hasPassword 标记。
 */
@RestController
@RequestMapping("/etl/kingdee-credential")
@RequiredArgsConstructor
@Tag(name = "金蝶登录凭证")
public class EtlKingdeeCredentialController {

    private final EtlKingdeeCredentialService credentialService;

    @Operation(summary = "保存当前金蝶登录凭证（密码 AES 加密存储）")
    @PostMapping("/save")
    public R<Map<String, Object>> save(@RequestBody @Valid KingdeeCredentialSaveDTO dto) {
        EtlKingdeeCredential input = new EtlKingdeeCredential();
        input.setLoginUrl(dto.getLoginUrl());
        input.setQueryUrl(dto.getQueryUrl());
        input.setAcctId(dto.getAcctID());
        input.setUserName(dto.getUserName());
        // DTO 中为明文，交由 Service 加密后落库
        input.setPasswordCipher(dto.getPassword());
        input.setLcid(dto.getLcid());
        return R.ok(toMasked(credentialService.saveDefaultCredential(input)));
    }

    @Operation(summary = "读取已保存的默认凭证（密码不回显）")
    @GetMapping("/default")
    public R<Map<String, Object>> getDefault() {
        EtlKingdeeCredential credential = credentialService.getDefaultCredential();
        return R.ok(credential == null ? null : toMasked(credential));
    }

    private Map<String, Object> toMasked(EtlKingdeeCredential credential) {
        Map<String, Object> masked = new LinkedHashMap<>();
        masked.put("loginUrl", credential.getLoginUrl());
        masked.put("queryUrl", credential.getQueryUrl());
        masked.put("acctID", credential.getAcctId());
        masked.put("userName", credential.getUserName());
        masked.put("lcid", credential.getLcid());
        masked.put("hasPassword", StrUtil.isNotBlank(credential.getPasswordCipher()));
        masked.put("updateTime", credential.getUpdateTime());
        return masked;
    }
}
