package com.dhp.etl.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.dhp.common.base.BaseCrudServiceImpl;
import com.dhp.etl.entity.EtlFolder;
import com.dhp.etl.mapper.EtlFolderMapper;
import com.dhp.etl.service.EtlFolderService;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class EtlFolderServiceImpl extends BaseCrudServiceImpl<EtlFolderMapper, EtlFolder> implements EtlFolderService {

    @Override
    protected Class<EtlFolder> getClaas() {
        return EtlFolder.class;
    }

    @Override
    public List<Map<String, Object>> getFolderTree() {
        List<EtlFolder> list = this.list(new LambdaQueryWrapper<EtlFolder>()
                .orderByAsc(EtlFolder::getFolderSort)
                .orderByAsc(EtlFolder::getId));

        Map<Long, List<EtlFolder>> parentMap = list.stream()
                .collect(Collectors.groupingBy(EtlFolder::getParentId));

        return buildTreeNodes(0L, parentMap);
    }

    private List<Map<String, Object>> buildTreeNodes(Long parentId, Map<Long, List<EtlFolder>> parentMap) {
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

            List<Map<String, Object>> subChildren = buildTreeNodes(folder.getId(), parentMap);
            if (!subChildren.isEmpty()) {
                node.put("children", subChildren);
            }
            result.add(node);
        }
        return result;
    }
}
