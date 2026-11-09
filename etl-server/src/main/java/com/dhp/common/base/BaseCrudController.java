package com.dhp.common.base;

import com.dhp.common.dto.BaseQueryDTO;
import com.dhp.common.dto.CommonQueryRequestDTO;
import com.dhp.common.result.PageResult;
import com.dhp.common.result.R;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

import java.io.Serializable;
import java.util.List;

/**
 * 基础通用 CRUD 控制器 (规范 03 §4.5, 规范 07 §2)
 */
public abstract class BaseCrudController<T> {

    protected abstract IBaseCrudService<T> getService();

    @Operation(summary = "动态分页查询")
    @PostMapping("/page")
    public R<PageResult<T>> page(@RequestBody(required = false) CommonQueryRequestDTO requestDTO) {
        if (requestDTO == null) {
            requestDTO = new CommonQueryRequestDTO();
        }
        return R.ok(getService().pageQuery(requestDTO));
    }

    @Operation(summary = "动态列表查询")
    @PostMapping("/list")
    public R<List<T>> list(@RequestBody(required = false) BaseQueryDTO queryDTO) {
        return R.ok(getService().listQuery(queryDTO));
    }

    @Operation(summary = "新增记录")
    @PostMapping
    public R<Boolean> save(@RequestBody T entity) {
        return R.ok(getService().save(entity));
    }

    @Operation(summary = "修改记录")
    @PutMapping("/{id}/update")
    public R<Boolean> update(@PathVariable Serializable id, @RequestBody T entity) {
        if (entity instanceof com.dhp.common.entity.BaseEntity baseEntity && id != null) {
            try {
                baseEntity.setId(Long.valueOf(id.toString()));
            } catch (Exception ignored) {
            }
            T existing = getService().getById(id);
            if (existing instanceof com.dhp.common.entity.BaseEntity existingBase) {
                baseEntity.setObjectVersion(existingBase.getObjectVersion());
            } else if (existing == null) {
                throw new com.dhp.common.exception.BizException(com.dhp.common.result.ErrorCode.NOT_FOUND, "修改失败，目标记录不存在或已被删除");
            }
        }
        boolean success = getService().updateById(entity);
        if (!success) {
            throw new com.dhp.common.exception.BizException(com.dhp.common.result.ErrorCode.NOT_FOUND, "修改失败，目标记录不存在或更新无匹配");
        }
        return R.ok(true);
    }

    @Operation(summary = "删除记录")
    @DeleteMapping("/{id}/del")
    public R<Boolean> delete(@PathVariable Serializable id) {
        boolean success = getService().removeById(id);
        if (!success) {
            throw new com.dhp.common.exception.BizException(com.dhp.common.result.ErrorCode.NOT_FOUND, "删除失败，目标记录不存在");
        }
        return R.ok(true);
    }

    @Operation(summary = "查询详情")
    @GetMapping("/{id}/detail")
    public R<T> detail(@PathVariable Serializable id) {
        return R.ok(getService().getById(id));
    }
}
