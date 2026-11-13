package com.dhp.etl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.etl.entity.EtlFolder;
import com.dhp.etl.entity.EtlPipeline;
import com.dhp.etl.mapper.EtlFolderMapper;
import com.dhp.etl.mapper.EtlPipelineMapper;
import com.dhp.etl.service.EtlFolderService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class EtlFolderServiceImpl extends BaseCrudServiceImpl<EtlFolderMapper, EtlFolder> implements EtlFolderService {

    /** 目录节点标识 */
    private static final String NODE_FOLDER = "FOLDER";
    /** 流程叶子节点标识 */
    private static final String NODE_PIPELINE = "PIPELINE";
    /** 目录被删除 / 流程从未归属目录时的兜底节点 key */
    private static final String UNFILED_KEY = "UNFILED";
    private static final String UNFILED_TITLE = "未分类流程";

    private final EtlPipelineMapper pipelineMapper;

    @Override
    protected Class<EtlFolder> getClaas() {
        return EtlFolder.class;
    }

    @Override
    public List<Map<String, Object>> getFolderTree() {
        return getFolderTree(false);
    }

    @Override
    public List<Map<String, Object>> getFolderTree(boolean withPipelines) {
        List<EtlFolder> list = this.list(new LambdaQueryWrapper<EtlFolder>()
                .orderByAsc(EtlFolder::getFolderSort)
                .orderByAsc(EtlFolder::getId));

        Map<Long, List<EtlFolder>> parentMap = list.stream()
                .collect(Collectors.groupingBy(EtlFolder::getParentId));

        if (!withPipelines) {
            return buildTreeNodes(0L, parentMap, Collections.emptyMap());
        }

        // 流程按更新时间倒序，与右侧列表默认排序保持一致
        List<EtlPipeline> pipelines = pipelineMapper.selectList(new LambdaQueryWrapper<EtlPipeline>()
                .orderByDesc(EtlPipeline::getUpdateTime)
                .orderByAsc(EtlPipeline::getPipelineName));

        Map<Long, List<EtlPipeline>> pipelinesByFolder = new LinkedHashMap<>();
        List<EtlPipeline> orphans = new ArrayList<>();
        Set<Long> folderIds = list.stream().map(EtlFolder::getId).collect(Collectors.toSet());
        for (EtlPipeline pipeline : pipelines) {
            Long folderId = pipeline.getFolderId();
            // folderId 为空 / 为 0 / 指向已删除目录的流程，统一归入「未分类流程」，避免在树上凭空消失
            if (folderId == null || folderId == 0L || !folderIds.contains(folderId)) {
                orphans.add(pipeline);
            } else {
                pipelinesByFolder.computeIfAbsent(folderId, k -> new ArrayList<>()).add(pipeline);
            }
        }

        List<Map<String, Object>> roots = buildTreeNodes(0L, parentMap, pipelinesByFolder);
        if (!orphans.isEmpty()) {
            Map<String, Object> unfiled = new LinkedHashMap<>();
            unfiled.put("id", UNFILED_KEY);
            unfiled.put("key", UNFILED_KEY);
            unfiled.put("value", UNFILED_KEY);
            unfiled.put("title", UNFILED_TITLE);
            unfiled.put("label", UNFILED_TITLE);
            unfiled.put("parentId", "0");
            unfiled.put("nodeType", NODE_FOLDER);
            unfiled.put("synthetic", true);
            unfiled.put("children", buildPipelineNodes(orphans, UNFILED_KEY));
            roots.add(unfiled);
        }
        return roots;
    }

    private List<Map<String, Object>> buildTreeNodes(Long parentId,
                                                     Map<Long, List<EtlFolder>> parentMap,
                                                     Map<Long, List<EtlPipeline>> pipelinesByFolder) {
        List<EtlFolder> children = parentMap.get(parentId);
        if (children == null || children.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (EtlFolder folder : children) {
            Map<String, Object> node = new LinkedHashMap<>();
            String idStr = String.valueOf(folder.getId());
            node.put("id", idStr);
            node.put("key", idStr);
            node.put("value", idStr);
            node.put("title", folder.getFolderName());
            node.put("label", folder.getFolderName());
            node.put("parentId", String.valueOf(folder.getParentId()));
            node.put("sort", folder.getFolderSort());
            node.put("nodeType", NODE_FOLDER);

            List<Map<String, Object>> subChildren = new ArrayList<>(
                    buildTreeNodes(folder.getId(), parentMap, pipelinesByFolder));
            subChildren.addAll(buildPipelineNodes(pipelinesByFolder.get(folder.getId()), idStr));
            if (!subChildren.isEmpty()) {
                node.put("children", subChildren);
            }
            result.add(node);
        }
        return result;
    }

    /**
     * 目录下的流程叶子节点：标题即流程名称，与右侧流程列表逐字一致
     */
    private List<Map<String, Object>> buildPipelineNodes(List<EtlPipeline> pipelines, String parentKey) {
        if (pipelines == null || pipelines.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> nodes = new ArrayList<>(pipelines.size());
        for (EtlPipeline pipeline : pipelines) {
            String key = "PIPE_" + pipeline.getId();
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("id", key);
            leaf.put("key", key);
            leaf.put("value", key);
            leaf.put("title", pipeline.getPipelineName());
            leaf.put("label", pipeline.getPipelineName());
            leaf.put("parentId", parentKey);
            leaf.put("nodeType", NODE_PIPELINE);
            leaf.put("pipelineId", String.valueOf(pipeline.getId()));
            leaf.put("pipelineCode", pipeline.getPipelineCode());
            leaf.put("isLeaf", true);
            nodes.add(leaf);
        }
        return nodes;
    }
}
