package com.rigour.settings.api.v1;

import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictView;
import com.rigour.settings.api.v1.model.EffectiveDictView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/** 公共业务字典 V1 契约；调用方只能通过 API 读取，禁止跨库查询。 */
public interface BusinessDictionaryApi {
    String BASE_PATH = "/api/v1/business-settings/dictionaries";

    /**
     * 查询当前身份可见的字典；租户身份不会返回其他租户数据。
     *
     * @param moduleCode 可选模块编码
     * @param scopeType 可选作用域类型：SYSTEM/MODULE/TENANT
     * @param tenantId 可选租户ID；租户身份只能使用当前租户
     * @param status 可选治理状态：ACTIVE/DISABLED
     * @return 符合条件且当前身份可见的字典
     */
    @GetMapping
    ApiResponse<List<DictView>> list(
            @RequestParam(required = false) String moduleCode,
            @RequestParam(required = false) String scopeType,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String status);

    /**
     * 查询指定字典的完整条目列表，包括禁用项，供管理页面使用。
     *
     * @param dictId 字典主键
     * @return 按层级和排序号排列的字典项
     */
    @GetMapping("/{dictId}/items")
    ApiResponse<List<DictItemView>> items(@PathVariable UUID dictId);

    /**
     * 按 TENANT、MODULE、SYSTEM 顺序返回当前调用人生效的整本字典。
     *
     * @param moduleCode 业务模块编码
     * @param code 字典编码
     * @return 最终命中的字典和其全部有效条目
     */
    @GetMapping("/effective")
    ApiResponse<EffectiveDictView> effective(
            @RequestParam String moduleCode,
            @RequestParam String code);

    /**
     * 按 TENANT、MODULE、SYSTEM 顺序返回用于历史数据解析的整本字典。
     * 与 effective 不同，本接口包含已停用条目，避免历史单据因字典停用而失去显示名称。
     *
     * @param moduleCode 业务模块编码
     * @param code 字典编码
     * @return 最终命中的字典和其全部条目，包括已停用条目
     */
    @GetMapping("/resolve")
    ApiResponse<EffectiveDictView> resolve(
            @RequestParam String moduleCode,
            @RequestParam String code);

    /**
     * 新增系统级、模块级或租户级字典；实际允许范围由调用人身份决定。
     *
     * @param command 字典新增参数，version必须为0
     * @return 已保存的字典
     */
    @PostMapping
    ApiResponse<DictView> create(@RequestBody DictCommand command);

    /**
     * 修改字典名称、状态、顺序和说明；编码、作用域、模块和基础字典不可修改。
     *
     * @param dictId 字典主键
     * @param command 字典修改参数，version必须与当前数据一致
     * @return 修改后的字典
     */
    @PutMapping("/{dictId}")
    ApiResponse<DictView> update(@PathVariable UUID dictId, @RequestBody DictCommand command);

    /**
     * 在指定字典下新增字典项，层级由服务端根据父节点计算。
     *
     * @param dictId 字典主键
     * @param command 字典项新增参数，version必须为0
     * @return 已保存的字典项
     */
    @PostMapping("/{dictId}/items")
    ApiResponse<DictItemView> createItem(
            @PathVariable UUID dictId,
            @RequestBody DictItemCommand command);

    /**
     * 修改字典项；服务端拒绝跨字典移动和循环父子关系。
     *
     * @param itemId 字典项主键
     * @param command 字典项修改参数，version必须与当前数据一致
     * @return 修改后的字典项
     */
    @PutMapping("/items/{itemId}")
    ApiResponse<DictItemView> updateItem(
            @PathVariable UUID itemId,
            @RequestBody DictItemCommand command);
}
