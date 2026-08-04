package com.cesia.input.stats

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

/**
 * 命令库类型
 */
enum class CommandLibraryType(val typeName: String) {
    SMART_WRITING("smart_writing"),   // 智能写作（生成类）
    SMART_EDITING("smart_editing")    // 智能修改（操作类）
}

/**
 * 命令库管理器 - 统一管理智能写作和智能修改的分类命令库
 * 支持：分类标签、常用自动归档、置顶、使用统计、语音触发记录
 */
class CommandLibrary(context: Context, private val libraryType: CommandLibraryType) {

    private val prefs: SharedPreferences = context.getSharedPreferences("cesia_command_library", Context.MODE_PRIVATE)
    private val gson = Gson()

    // ==================== 数据模型 ====================

    data class CommandItem(
        val id: String,
        val category: String,       // 大分类：翻译、语气、长度、格式、内容、特殊、润色、生成-社媒、生成-职场、生成-学术、生成-电商、生成-编程、生成-生活
        val name: String,           // 显示名称
        val instruction: String,    // 完整 prompt 指令
        val keywords: List<String> = emptyList(), // 语音触发关键词
        val isGeneration: Boolean = false,        // 是否生成类
        var isPinned: Boolean = false,            // 置顶状态
        var useCount: Int = 0,                    // 使用次数
        var lastUsed: Long = 0                    // 最后使用时间
    ) {
        fun copy(
            isPinned: Boolean = this.isPinned,
            useCount: Int = this.useCount,
            lastUsed: Long = this.lastUsed
        ): CommandItem = CommandItem(
            id = this.id, category = this.category, name = this.name,
            instruction = this.instruction, keywords = this.keywords,
            isGeneration = this.isGeneration,
            isPinned = isPinned, useCount = useCount, lastUsed = lastUsed
        )
    }

    data class Category(
        val id: String,           // 内部ID
        val displayName: String,  // 显示名称
        val icon: String = "",    // 可选图标
        val order: Int = 0,       // 排序
        val isGeneration: Boolean = false
    )

    // ==================== 初始化 ====================

    private fun ensureInitialized() {
        val initializedKey = "${libraryType.typeName}_initialized"
        if (!prefs.getBoolean(initializedKey, false)) {
            initDefaultLibrary()
            prefs.edit().putBoolean(initializedKey, true).apply()
            Log.i("CommandLibrary", "${libraryType.typeName} 初始化完成")
        }
    }

    private fun initDefaultLibrary() {
        val defaultItems = when (libraryType) {
            CommandLibraryType.SMART_WRITING -> getDefaultWritingCommands()
            CommandLibraryType.SMART_EDITING -> getDefaultEditingCommands()
        }
        saveItems(defaultItems)
        saveCategories(getDefaultCategories())
        saveFrequentIds(emptyList())
    }

    // ==================== 存储键 ====================

    private val KEY_ITEMS = "${libraryType.typeName}_items"
    private val KEY_CATEGORIES = "${libraryType.typeName}_categories"
    private val KEY_FREQUENT = "${libraryType.typeName}_frequent"

    // ==================== 公共 API ====================

    /** 获取所有命令（置顶优先，其次常用，再按使用频率/时间） */
    fun getAllItems(): List<CommandItem> {
        val items = loadItems()
        val frequentIds = loadFrequentIds().toSet()
        return items.sortedWith(
            compareByDescending<CommandItem> { it.isPinned }
                .thenByDescending { frequentIds.contains(it.id) }
                .thenByDescending { it.useCount }
                .thenByDescending { it.lastUsed }
        )
    }

    /** 获取指定分类的命令 */
    fun getItemsByCategory(categoryId: String): List<CommandItem> {
        return getAllItems().filter { it.category == categoryId }
    }

    /** 获取常用命令（前 N 个） */
    fun getFrequentItems(limit: Int = 20): List<CommandItem> {
        val frequentIds = loadFrequentIds()
        val itemsMap = loadItems().associateBy { it.id }
        return frequentIds.mapNotNull { itemsMap[it] }.take(limit)
    }

    /** 获取所有分类 */
    fun getCategories(): List<Category> {
        return loadCategories().sortedBy { it.order }
    }

    /** 获取分类显示名称列表（用于标签栏） */
    fun getCategoryNames(): List<String> {
        return listOf("常用") + getCategories().map { it.displayName }
    }

    /** 获取分类 ID 列表（含常用） */
    fun getCategoryIds(): List<String> {
        return listOf("frequent") + getCategories().map { it.id }
    }

    /** 记录使用 - 自动加入常用、更新统计 */
    fun recordUsage(itemId: String) {
        val items = loadItems().toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val item = items[index]
            val updated = item.copy(
                useCount = item.useCount + 1,
                lastUsed = System.currentTimeMillis()
            )
            items[index] = updated
            saveItems(items)
            addToFrequent(itemId)
        }
    }

    /** 置顶/取消置顶 */
    fun togglePin(itemId: String): Boolean {
        val items = loadItems().toMutableList()
        val index = items.indexOfFirst { it.id == itemId }
        if (index >= 0) {
            val item = items[index]
            val updated = item.copy(isPinned = !item.isPinned)
            items[index] = updated
            saveItems(items)
            if (updated.isPinned) addToFrequent(itemId)
            return updated.isPinned
        }
        return false
    }

    /** 删除命令 */
    fun removeItem(itemId: String) {
        val items = loadItems().filter { it.id != itemId }
        saveItems(items)
        removeFromFrequent(itemId)
    }

    /** 新增/更新自定义命令 */
    fun addOrUpdateCustomCommand(
        category: String,
        name: String,
        instruction: String,
        keywords: List<String> = emptyList()
    ): CommandItem {
        val items = loadItems().toMutableList()
        val existingIndex = items.indexOfFirst { it.name == name && it.category == category }
        val now = System.currentTimeMillis()
        val newItem = CommandItem(
            id = if (existingIndex >= 0) items[existingIndex].id else "custom_${System.currentTimeMillis()}",
            category = category,
            name = name,
            instruction = instruction,
            keywords = keywords,
            isGeneration = libraryType == CommandLibraryType.SMART_WRITING,
            lastUsed = now
        )
        if (existingIndex >= 0) {
            items[existingIndex] = newItem.copy(useCount = items[existingIndex].useCount)
        } else {
            items.add(0, newItem)
        }
        saveItems(items)
        return newItem
    }

    /** 语音触发记录 */
    fun recordVoiceTrigger(commandName: String) {
        val items = loadItems().toMutableList()
        val index = items.indexOfFirst { it.name == commandName }
        if (index >= 0) {
            recordUsage(items[index].id)
        }
    }

    // ==================== 内部方法 ====================

    private fun loadItems(): List<CommandItem> {
        val json = prefs.getString(KEY_ITEMS, "[]") ?: "[]"
        return try {
            val type: Type = object : TypeToken<List<CommandItem>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("CommandLibrary", "loadItems 失败", e)
            emptyList()
        }
    }

    private fun saveItems(items: List<CommandItem>) {
        try {
            prefs.edit().putString(KEY_ITEMS, gson.toJson(items)).apply()
        } catch (e: Exception) {
            Log.e("CommandLibrary", "saveItems 失败", e)
        }
    }

    private fun loadCategories(): List<Category> {
        val json = prefs.getString(KEY_CATEGORIES, "[]") ?: "[]"
        return try {
            val type: Type = object : TypeToken<List<Category>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e("CommandLibrary", "loadCategories 失败", e)
            emptyList()
        }
    }

    private fun saveCategories(categories: List<Category>) {
        try {
            prefs.edit().putString(KEY_CATEGORIES, gson.toJson(categories)).apply()
        } catch (e: Exception) {
            Log.e("CommandLibrary", "saveCategories 失败", e)
        }
    }

    private fun loadFrequentIds(): List<String> {
        val json = prefs.getString(KEY_FREQUENT, "[]") ?: "[]"
        return try {
            val type: Type = object : TypeToken<List<String>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveFrequentIds(ids: List<String>) {
        try {
            prefs.edit().putString(KEY_FREQUENT, gson.toJson(ids)).apply()
        } catch (e: Exception) {
            Log.e("CommandLibrary", "saveFrequentIds 失败", e)
        }
    }

    /** 公开加载常用ID列表（供弹窗使用） */
    fun loadFrequentIdsPublic(): List<String> = loadFrequentIds()

    private fun addToFrequent(itemId: String) {
        val frequent = loadFrequentIds().toMutableList()
        frequent.remove(itemId)
        frequent.add(0, itemId)
        if (frequent.size > 50) frequent.removeLast()
        saveFrequentIds(frequent)
    }

    private fun removeFromFrequent(itemId: String) {
        val frequent = loadFrequentIds().toMutableList()
        frequent.remove(itemId)
        saveFrequentIds(frequent)
    }

    // ==================== 默认数据 ====================

    private fun getDefaultCategories(): List<Category> {
        return when (libraryType) {
            CommandLibraryType.SMART_WRITING -> listOf(
                Category("gen_social", "社媒文案", "📱", 1, true),
                Category("gen_work", "职场办公", "💼", 2, true),
                Category("gen_academic", "学术写作", "🎓", 3, true),
                Category("gen_ecommerce", "电商运营", "🛍", 4, true),
                Category("gen_coding", "编程辅助", "💻", 5, true),
                Category("gen_life", "生活实用", "🏠", 6, true),
                Category("gen_general", "通用生成", "✨", 0, true)
            )
            CommandLibraryType.SMART_EDITING -> listOf(
                Category("translate", "翻译", "🌐", 1),
                Category("tone", "语气", "🎭", 2),
                Category("length", "长度", "📏", 3),
                Category("format", "格式", "📐", 4),
                Category("content", "内容", "📝", 5),
                Category("special", "特殊", "⚙️", 6),
                Category("polish", "润色", "✨", 7)
            )
        }
    }

    private fun getDefaultWritingCommands(): List<CommandItem> {
        // 从 InstructionSet 获取生成类指令，并按新分类归组
        val genInstructions = com.cesia.input.instruction.InstructionSet.getGenerateInstructions()
        return genInstructions.mapIndexed { index, inst ->
            // 简单归类逻辑（可后续微调）
            val category = when {
                inst.keywords.any { kw: String -> kw.contains("新闻") || kw.contains("简报") } -> "gen_general"
                inst.keywords.any { kw: String -> kw.contains("想") || kw.contains("主意") || kw.contains("建议") } -> "gen_general"
                inst.keywords.any { kw: String -> kw.contains("邮件") } -> "gen_work"
                inst.keywords.any { kw: String -> kw.contains("诗") } -> "gen_life"
                inst.keywords.any { kw: String -> kw.contains("代码") || kw.contains("编程") || kw.contains("程序") } -> "gen_coding"
                inst.keywords.any { kw: String -> kw.contains("表格") } -> "gen_work"
                inst.keywords.any { kw: String -> kw.contains("清单") || kw.contains("列表") } -> "gen_work"
                inst.keywords.any { kw: String -> kw.contains("故事") } -> "gen_life"
                inst.keywords.any { kw: String -> kw.contains("标题") } -> "gen_social"
                inst.keywords.any { kw: String -> kw.contains("摘要") || kw.contains("简介") } -> "gen_work"
                inst.keywords.any { kw: String -> kw.contains("续写") || kw.contains("接着") } -> "gen_general"
                inst.keywords.any { kw: String -> kw.contains("扩写") || kw.contains("展开") } -> "gen_general"
                inst.keywords.any { kw: String -> kw.contains("改写") || kw.contains("换种") } -> "gen_general"
                inst.keywords.any { kw: String -> kw.contains("翻译") } -> "gen_general"
                else -> "gen_general"
            }
            CommandItem(
                id = inst.id,
                category = category,
                name = inst.name,
                instruction = inst.instruction,
                keywords = inst.keywords,
                isGeneration = true
            )
        }
    }

    private fun getDefaultEditingCommands(): List<CommandItem> {
        // 从 InstructionSet 获取操作类指令（非生成类）
        val allOps = com.cesia.input.instruction.InstructionSet.allInstructions
        return allOps.map { inst ->
            CommandItem(
                id = inst.id,
                category = inst.category,
                name = inst.name,
                instruction = inst.instruction,
                keywords = inst.keywords,
                isGeneration = false
            )
        }
    }

    /** 清空并重置为默认 */
    fun resetToDefault() {
        prefs.edit()
            .remove(KEY_ITEMS)
            .remove(KEY_CATEGORIES)
            .remove(KEY_FREQUENT)
            .remove("${libraryType.typeName}_initialized")
            .apply()
        ensureInitialized()
    }
}