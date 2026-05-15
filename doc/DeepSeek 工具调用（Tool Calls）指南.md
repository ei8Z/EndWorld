# DeepSeek 工具调用（Tool Calls）指南

> **注意**：本文档中提到的“Function Calling”与“Tool Calls”在 DeepSeek API 中是同一概念，文档中常混用这两个术语。

---

## 一、概述

Tool Calls（又称 Function Calling）允许模型识别用户意图并返回结构化的工具调用请求，由**用户侧**实际执行函数，再将结果返回给模型以生成最终回复。

**核心要点**：
- 模型本身**不执行**具体函数，仅返回调用指令
- 用户负责提供函数的实际实现，并将执行结果回传给模型
- 非思考模式和思考模式均支持工具调用
- Beta 版提供 **Strict 模式**，可强制模型严格遵循 JSON Schema

---

## 二、非思考模式下的工具调用

### 2.1 完整 Python 示例代码

以下示例展示了一个完整的工具调用流程——查询杭州的天气。

```python
from openai import OpenAI

# ============================================
# 模拟的 get_weather 函数（用户自行实现）
# ============================================
def get_weather(location: str) -> str:
    """
    根据传入的地点名称，模拟返回天气信息。
    
    在实际项目中，你应该在此函数内调用真实的天气 API
    （如心知天气、OpenWeatherMap 等），而非返回硬编码的温度。
    
    参数:
        location (str): 城市名称，例如 "Hangzhou, Zhejiang"
    
    返回:
        str: 包含温度的字符串，例如 "24℃"
    """
    # 模拟逻辑：为不同地点返回不同的温度（演示用途）
    weather_data = {
        "hangzhou": "24℃",
        "beijing": "18℃",
        "shanghai": "22℃",
        "shenzhen": "28℃",
        "tokyo": "20℃",
    }
    
    # 将地点转为小写进行匹配
    city = location.split(",")[0].strip().lower()
    return weather_data.get(city, "Unknown location")


def send_messages(messages):
    """向 DeepSeek API 发送消息并返回模型响应"""
    response = client.chat.completions.create(
        model="deepseek-v4-pro",
        messages=messages,
        tools=tools
    )
    return response.choices[0].message


# 初始化客户端
client = OpenAI(
    api_key="<your api key>",
    base_url="https://api.deepseek.com",
)

# 定义工具列表
tools = [
    {
        "type": "function",
        "function": {
            "name": "get_weather",
            "description": "Get weather of a location, the user should supply a location first.",
            "parameters": {
                "type": "object",
                "properties": {
                    "location": {
                        "type": "string",
                        "description": "The city and state, e.g. San Francisco, CA",
                    }
                },
                "required": ["location"]
            },
        }
    },
]

# 用户消息
messages = [{"role": "user", "content": "How's the weather in Hangzhou, Zhejiang?"}]

# 第一轮：模型返回工具调用请求
message = send_messages(messages)
print(f"User>\t {messages[0]['content']}")

# 提取工具调用信息
tool = message.tool_calls[0]

# 将模型的工具调用请求追加到对话历史
messages.append(message)

# 调用 get_weather 函数获取实际数据，并将结果以 tool 角色追加
messages.append({
    "role": "tool",
    "tool_call_id": tool.id,
    "content": get_weather(tool.function.arguments)  # 调用实际函数
})

# 第二轮：将包含工具调用结果的对话历史发回模型，获取最终自然语言回复
message = send_messages(messages)
print(f"Model>\t {message.content}")
```

### 2.2 执行流程

| 步骤 | 角色 | 行为 |
|------|------|------|
| 1 | 用户（User） | 发起提问："How's the weather in Hangzhou, Zhejiang?" |
| 2 | 模型（Assistant） | 识别意图，返回工具调用请求 `get_weather({location: 'Hangzhou, Zhejiang'})` |
| 3 | 用户代码 | 执行 `get_weather` 函数，获取结果 `"24℃"` |
| 4 | 用户代码 | 将结果以 `tool` 角色消息回传给模型 |
| 5 | 模型（Assistant） | 根据工具结果生成自然语言回复："The current temperature in Hangzhou is 24°C." |

### 2.3 关键参数说明

| 参数 | 层级 | 类型 | 必填 | 说明 |
|------|------|------|------|------|
| `tools` | 请求顶层 | `object[]` | 否 | 可用工具列表 |
| `tools[].type` | 工具元素 | `string` | 是 | 固定值 `"function"` |
| `tools[].function` | 工具元素 | `object` | 是 | 函数定义对象 |
| `tools[].function.name` | function | `string` | 是 | 函数名称 |
| `tools[].function.description` | function | `string` | 是 | 函数功能描述 |
| `tools[].function.parameters` | function | `object` | 是 | 参数 JSON Schema |
| `tools[].function.strict` | function | `boolean` | 否 | 严格模式开关（Beta） |
| `tool_choice` | 请求顶层 | `string`/`object` | 否 | 控制工具调用策略：`"none"`、`"auto"` 或指定特定函数 |
| `tool_calls` | 响应 message | `object[]` | — | 模型返回的工具调用列表 |
| `tool_calls[].id` | tool_calls 元素 | `string` | — | 工具调用唯一标识符 |
| `tool_calls[].type` | tool_calls 元素 | `string` | — | 固定值 `"function"` |
| `tool_calls[].function.name` | function | `string` | — | 函数名称 |
| `tool_calls[].function.arguments` | function | `string` | — | 函数参数 JSON 字符串 |

### 2.4 工具调用回传消息结构

执行工具后，需要以以下结构将结果回传给模型：

```json
{
    "role": "tool",
    "tool_call_id": "<工具调用的 ID>",
    "content": "<工具执行结果的字符串>"
}
```

---

## 三、思考模式下的工具调用

从 **DeepSeek-V3.2** 开始，API 支持在思考模式（Thinking Mode）下进行工具调用，模型会在输出最终回答前进行多轮思考并调用工具。

### 3.1 重要约束

在思考模式下使用工具调用时，必须注意以下规则：

- **reasoning_content 必须保留**：在工具调用轮次中，`assistant` 消息的 `reasoning_content` 字段必须参与上下文拼接，后续所有请求中必须回传给 API
- **不支持 `tool_choice` 参数**：思考模式下传入 `tool_choice` 会返回 400 错误
- **assistant content 不能为空**：需确保工具调用消息中的 `content` 字段不为空

详细说明请参考 [思考模式文档](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)。

---

## 四、Strict 模式（Beta）

Strict 模式（Beta）使模型在输出函数调用时**严格遵循**用户定义的 JSON Schema，确保参数格式完全符合预期。该模式对思考模式和非思考模式均有效。

### 4.1 启用条件

1. 使用 Beta 端点：`base_url="https://api.deepseek.com/beta"`
2. 所有 `tools` 中的 `function` 必须设置 `strict: true`
3. 服务端会对 JSON Schema 进行校验，不符合规范或使用不支持的 Schema 类型将返回错误

### 4.2 Strict 模式下的工具定义样例

```json
{
    "type": "function",
    "function": {
        "name": "get_weather",
        "strict": true,
        "description": "Get weather of a location, the user should supply a location first.",
        "parameters": {
            "type": "object",
            "properties": {
                "location": {
                    "type": "string",
                    "description": "The city and state, e.g. San Francisco, CA"
                }
            },
            "required": ["location"],
            "additionalProperties": false
        }
    }
}
```

### 4.3 Strict 模式强制规则

- 所有 `object` 类型的 `additionalProperties` 必须为 `false`
- 所有 `object` 的属性必须全部列入 `required` 数组

### 4.4 Strict 模式支持的 JSON Schema 类型

| 类型 | 说明 |
|------|------|
| `object` | 嵌套键值对结构，需定义 `properties` |
| `string` | 字符串 |
| `number` | 数字 |
| `integer` | 整数 |
| `boolean` | 布尔值 |
| `array` | 数组 |
| `enum` | 枚举 |
| `anyOf` | 任意一种类型匹配 |

> ⚠️ 不支持的类型将在服务端校验时返回错误。

---

## 五、常见错误与排查

| 错误现象 | 可能原因 | 解决方案 |
|----------|----------|----------|
| 400 Bad Request（思考模式） | 传入了 `tool_choice` 参数 | 移除 `tool_choice` 参数 |
| 400 Bad Request（工具调用） | 未保留 `reasoning_content` | 在工具调用轮次中完整回传 `reasoning_content` |
| Strict 模式校验失败 | JSON Schema 不符合规范 | 确保所有 object 设置 `additionalProperties: false` 且所有属性列入 `required` |
| 模型未触发工具调用 | 函数描述不够清晰或 `tool_choice` 设为 `none` | 优化函数描述，或将 `tool_choice` 设为 `auto` |
| 空 content 响应 | max_tokens 不足导致截断 | 适当增加 `max_tokens` 值 |

---

## 六、参考链接

- [对话补全 API 完整参数文档](https://api-docs.deepseek.com/zh-cn/api/create-chat-completion/)
- [思考模式（Thinking Mode）文档](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode)
- [Anthropic API 兼容工具调用格式](https://api-docs.deepseek.com/zh-cn/guides/anthropic_api)
- [函数调用（Function Calling）英文文档](https://api-docs.deepseek.com/guides/function_calling)