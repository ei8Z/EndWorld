package com.ei8z.endworld.data.repository

import com.ei8z.endworld.data.remote.model.FunctionDef
import com.ei8z.endworld.data.remote.model.Tool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ======================================================
//  文件：ToolRegistry.kt
//  状态：✅ 基础架构已就绪，⚠️ 当前不启用
//
//  完整的 Function Calling 流程（DeepSeek API 规范）：
//
//  ① 请求携带 tools 定义 → API
//  ② API 返回 assistant 消息含 tool_calls（如 check_inventory）
//  ③ 客户端解析 tool_calls，调用 ToolHandler.execute() 本地执行
//  ④ 将执行结果封装为 role="tool" 消息追加到 messages
//  ⑤ 再次请求 API，模型基于工具结果生成最终叙事
//
//  启用步骤（未来）：
//  1. 在 buildChatRequest 中设置 tools = ToolRegistry.getActiveTools()
//  2. 在 processStreamChunks 中检测 ToolCallChunk → emit StreamEvent.ToolCallRequest
//  3. 在 sendActionStream 中实现多轮 tool call loop
//
//  当前 check_inventory 不需要工具调用：背包数据已包含在 User Snapshot 中。
// ======================================================

/**
 * 工具执行处理器。每个注册的工具实现此接口。
 */
interface ToolHandler {
    fun execute(arguments: String): String
}

/**
 * 工具注册中心 — 管理可用工具的定义和执行逻辑。
 * 未来添加新工具只需在这里注册即可。
 *
 * 示例用法：
 *   ToolRegistry.register("check_inventory", checkInventoryHandler)
 *   val tools = ToolRegistry.getActiveTools()
 */
object ToolRegistry {
    private val handlers = mutableMapOf<String, ToolHandler>()
    private val gson = Gson()

    fun register(name: String, handler: ToolHandler) {
        handlers[name] = handler
    }

    fun isRegistered(name: String): Boolean = handlers.containsKey(name)

    fun execute(name: String, arguments: String): String? {
        return handlers[name]?.execute(arguments)
    }

    fun getActiveTools(): List<Tool> {
        return handlers.map { (name, _) ->
            Tool(function = buildFunctionDef(name))
        }
    }

    private fun buildFunctionDef(name: String): FunctionDef = when (name) {
        // 未来扩展 — 添加新工具时在这里补充 FunctionDef
        // 目前全部 return 默认定义
        else -> FunctionDef(
            name = name,
            description = "工具: $name",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
                "required" to emptyList<String>()
            )
        )
    }

    // ======================================================
    // 预设工具定义（示例，当前不注册）
    // 如需启用，在初始化时调用 ToolRegistry.register(...)
    // ======================================================

    /** 物资查询工具的本地理赔 */
    val checkInventoryHandler = object : ToolHandler {
        override fun execute(arguments: String): String {
            // 从 arguments JSON 提取 item_name
            val mapType = object : TypeToken<Map<String, String>>() {}.type
            val params: Map<String, String> = try {
                gson.fromJson(arguments, mapType)
            } catch (e: Exception) {
                emptyMap()
            }
            val itemName = params["item_name"] ?: "未知物品"
            return """{"found": false, "item": "$itemName", "reason": "本地裁决：背包快照已包含于 User Snapshot，GM 应直接查阅而非调用工具"}"""
        }
    }

    /** check_inventory 的完整 FunctionDef */
    val CHECK_INVENTORY_FUNCTION_DEF = FunctionDef(
        name = "check_inventory",
        description = "查询玩家背包中特定物品的数量，确保行动有据可依",
        parameters = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "item_name" to mapOf(
                    "type" to "string",
                    "description" to "物品名称，如：'过期罐头', '子弹', '绷带'"
                )
            ),
            "required" to listOf("item_name"),
            "additionalProperties" to false
        )
    )
}
