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

    /**
     * 获取树形目录结构
     *
     * @param withPipelines true 时把流程作为叶子节点挂在所属目录下，
     *                      叶子标题即流程名称，与右侧流程列表完全一致；
     *                      false 时只返回目录（供「所属业务目录」下拉等场景使用）
     */
    List<Map<String, Object>> getFolderTree(boolean withPipelines);
}
