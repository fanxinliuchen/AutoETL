package com.dhp.etl.service;

import com.dhp.common.base.IBaseCrudService;
import com.dhp.etl.entity.EtlFolder;

import java.util.List;
import java.util.Map;

public interface EtlFolderService extends IBaseCrudService<EtlFolder> {

    /**
     * 获取树形目录结构
     */
    List<Map<String, Object>> getFolderTree();
}
