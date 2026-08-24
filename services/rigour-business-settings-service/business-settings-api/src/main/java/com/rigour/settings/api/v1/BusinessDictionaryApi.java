package com.rigour.settings.api.v1;

import com.rigour.settings.api.v1.model.DictCommand;
import com.rigour.settings.api.v1.model.DictItemCommand;
import com.rigour.settings.api.v1.model.DictItemView;
import com.rigour.settings.api.v1.model.DictView;
import com.rigour.settings.api.v1.model.EffectiveDictView;
import com.rigour.shared.core.api.ApiResponse;
import java.util.List;
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
     * 查询字典。
     *
     * @param dictionaryType 可选字典类型：COMMON/ERP/CRM/ORDER
     * @param dictionaryCode 可选字典编码
     * @return 符合条件且当前身份可见的字典
     */
    @GetMapping
    ApiResponse<List<DictView>> list(
            @RequestParam(required = false) String dictionaryType,
            @RequestParam(required = false) String dictionaryCode);

    /**
     * 查询指定字典的完整条目列表，包括禁用项，供管理页面使用。
     *
     * @param dictionaryId 字典主键
     * @return 按层级和排序号排列的字典项
     */
    @GetMapping("/{dictionaryId}/items")
    ApiResponse<List<DictItemView>> items(@PathVariable Long dictionaryId);

    /**
     * 按 dictionaryCode 返回当前启用的整本字典。
     *
     * @param dictionaryCode 字典编码
     * @return 最终命中的字典和其全部有效条目
     */
    @GetMapping("/effective")
    ApiResponse<EffectiveDictView> effective(@RequestParam String dictionaryCode);

    /**
     * 返回用于历史数据解析的整本字典。
     *
     * @param dictionaryCode 字典编码
     * @return 最终命中的字典和其全部条目，包括已停用条目
     */
    @GetMapping("/resolve")
    ApiResponse<EffectiveDictView> resolve(@RequestParam String dictionaryCode);

    /**
     * 新增字典。
     *
     * @param command 字典新增参数，revision必须为0
     * @return 已保存的字典
     */
    @PostMapping
    ApiResponse<DictView> create(@RequestBody DictCommand command);

    /**
     * 修改字典名称、类型和说明；编码不可修改。
     *
     * @param dictionaryId 字典主键
     * @param command 字典修改参数，revision必须与当前数据一致
     * @return 修改后的字典
     */
    @PutMapping("/{dictionaryId}")
    ApiResponse<DictView> update(@PathVariable Long dictionaryId, @RequestBody DictCommand command);

    /**
     * 在指定字典下新增字典项，层级由服务端根据父节点计算。
     *
     * @param dictionaryId 字典主键
     * @param command 字典项新增参数，revision必须为0
     * @return 已保存的字典项
     */
    @PostMapping("/{dictionaryId}/items")
    ApiResponse<DictItemView> createItem(
            @PathVariable Long dictionaryId,
            @RequestBody DictItemCommand command);

    /**
     * 修改字典项；服务端拒绝跨字典移动和循环父子关系。
     *
     * @param itemId 字典项主键
     * @param command 字典项修改参数，revision必须与当前数据一致
     * @return 修改后的字典项
     */
    @PutMapping("/items/{itemId}")
    ApiResponse<DictItemView> updateItem(
            @PathVariable Long itemId,
            @RequestBody DictItemCommand command);
}
