# DeepSeek API 完整参考文档

> 本文档整合自 DeepSeek API 官方文档，涵盖指南（Guides）与 API 端点文档两部分，完整收录所有 API 参数，供 AI 开发工具查阅。

---

## 一、文档概览

- **Base URL**：`https://api.deepseek.com`
- **Beta 功能 Base URL**：`https://api.deepseek.com/beta`
- **Anthropic API Base URL**：`https://api.deepseek.com/anthropic`
- **认证方式**：请求头 `Authorization: Bearer <API_KEY>`
- **Content-Type**：`application/json`

---

## 二、通用基础信息

### 2.1 可用模型

| 模型 ID | 适用 API |
|---------|---------|
| `deepseek-v4-pro` | 对话补全 / FIM 补全 |
| `deepseek-v4-flash` | 对话补全 |

Anthropic API 不支持模型名时自动映射到 `deepseek-v4-flash`。

### 2.2 无状态 API

`/chat/completions` API 是“无状态”的，服务端不记录上下文，每次请求需将完整对话历史传入 `messages` 参数。

---

## 三、对话补全 API（Chat Completion）

### 3.1 端点信息

- **方法**：`POST`
- **路径**：`https://api.deepseek.com/chat/completions`
- **描述**：根据输入上下文让模型补全对话内容。

### 3.2 请求参数

**messages** `object[]` **必填**

消息列表，包含对话上下文。每条消息含以下字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | `string` | 角色：`system`、`user`、`assistant`、`tool` |
| `content` | `string` | 消息内容 |
| `reasoning_content` | `string` | 思考链内容（思考模式下返回，仅工具调用轮次需回传） |
| `prefix` | `boolean` | 对话前缀续写标记（Beta，仅最后一条 assistant 消息可设为 `true`） |
| `tool_calls` | `object[]` | 工具调用列表（assistant 消息使用） |
| `tool_call_id` | `string` | 工具调用 ID（tool 消息使用） |

**model** `string` **必填**

模型 ID，可选值：`deepseek-v4-flash`、`deepseek-v4-pro`。

**thinking** `object` 可选

思考模式配置对象：

| 子字段 | 类型 | 说明 |
|--------|------|------|
| `type` | `string` | 思考模式开关，可选值：`"enabled"` / `"disabled"`，默认 `"enabled"` |

> 使用 OpenAI SDK 时需通过 `extra_body` 传入：`extra_body={"thinking": {"type": "enabled"}}`。

**reasoning_effort** `string` 可选

思考强度控制。可选值：`high`、`max`。默认 `high`，复杂 Agent 类请求自动设为 `max`。`low`/`medium` 映射为 `high`，`xhigh` 映射为 `max`。

**max_tokens** `integer` 可选

限制一次请求中模型生成 completion 的最大 token 数。输入 token 和输出 token 的总长度受模型上下文长度限制。默认值详见定价文档。

**response_format** `object` 可选

JSON 输出模式配置：

| 子字段 | 类型 | 说明 |
|--------|------|------|
| `type` | `string` | `"json_object"` / `"text"` |

使用 `json_object` 时需满足：system 或 user prompt 中包含 `json` 字样并给出示例格式；需合理设置 `max_tokens` 防止截断。API 有概率返回空 `content`，可修改 prompt 缓解。

**stop** `object` 可选

停止词配置，可为单个字符串或字符串数组，当模型生成对应内容时停止输出。

**stream** `boolean` 可选

若为 `true`，以 SSE（server-sent events）形式流式发送消息增量。消息流以 `data: [DONE]` 结尾。

**stream_options** `object` 可选

流式输出的额外选项。

**temperature** `number` 可选

采样温度，取值范围 `(0, 2]`，默认 `1`。值越高输出越随机，值越低越集中确定。建议单独修改此值或 `top_p`，不建议同时修改。

**top_p** `number` 可选

核采样参数，取值范围 `(0, 1]`，默认 `1`。模型仅考虑概率累积到 `top_p` 的 token。建议单独修改此值或 `temperature`，不建议同时修改。

**tools** `object[]` 可选

可用工具列表。每个工具对象：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | `string` | 固定为 `"function"` |
| `function` | `object` | 函数定义 |
| `function.name` | `string` | 函数名称 |
| `function.description` | `string` | 函数描述 |
| `function.parameters` | `object` | 参数 JSON Schema |
| `function.strict` | `boolean` | 严格模式开关（Beta） |

**strict 模式**（Beta）：设置 `base_url="https://api.deepseek.com/beta"`，所有 function 的 `strict` 设为 `true`，且 `additionalProperties` 必须为 `false`，所有属性设为 `required`。支持的 JSON Schema 类型：`object`、`string`、`number`、`integer`、`boolean`、`array`、`enum`、`anyOf`。

**tool_choice** `object` 可选

工具选择策略，可设为 `"none"`、`"auto"` 或指定工具。

**logprobs** `boolean` 可选

是否返回输出 token 的对数概率。为 `true` 时在 message 的 `content` 中返回每个输出 token 的对数概率。

**top_logprobs** `integer` 可选

每个输出位置返回概率最高的前 N 个 token 及对数概率，取值范围 `[0, 20]`。指定此参数时 `logprobs` 必须为 `true`。

**user_id** `string` 可选

自定义用户 ID，可选字符集：`[a-zA-Z0-9\-_]`，最大长度 512。用于内容安全审查和 KVCache 缓存隔离。

**frequency_penalty** （已弃用）

不再支持，传入无效。

**presence_penalty** （已弃用）

不再支持，传入无效。

### 3.3 响应参数（200 非流式）

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 对话唯一标识符 |
| `object` | `string` | 固定为 `"chat.completion"` |
| `created` | `integer` | 创建时的 Unix 时间戳（秒） |
| `model` | `string` | 使用的模型名 |
| `system_fingerprint` | `string` | 后端配置指纹 |
| `choices` | `object[]` | 补全结果列表 |

**choices 对象**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `index` | `integer` | 结果序号 |
| `finish_reason` | `string` | 停止原因，如 `"stop"` |
| `message` | `object` | 补全消息 |

**message 对象**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | `string` | 固定为 `"assistant"` |
| `content` | `string` | 模型生成的回答 |
| `reasoning_content` | `string` | 思维链内容（思考模式） |
| `tool_calls` | `object[]` | 工具调用列表 |

**tool_calls 元素**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 工具调用 ID |
| `type` | `string` | 固定为 `"function"` |
| `function.name` | `string` | 函数名 |
| `function.arguments` | `string` | 函数参数 JSON 字符串 |

**logprobs 对象**（当 `logprobs=true` 时返回）：

| 字段 | 类型 | 说明 |
|------|------|------|
| `content` | `object[]` | 各 token 的对数概率 |

**content 元素**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `token` | `string` | 输出 token |
| `logprob` | `number` | 对数概率 |
| `bytes` | `integer[]` | Token 的 UTF-8 字节表示 |
| `top_logprobs` | `object[]` | 前 N 个候选 token 的对数概率 |

**usage 对象**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `completion_tokens` | `integer` | 输出 token 数 |
| `prompt_tokens` | `integer` | 输入 token 数 |
| `total_tokens` | `integer` | 总 token 数 |
| `prompt_cache_hit_tokens` | `integer` | 缓存命中 token 数 |
| `prompt_cache_miss_tokens` | `integer` | 缓存未命中 token 数 |
| `completion_tokens_details` | `object` | 输出 token 详情 |
| `completion_tokens_details.reasoning_tokens` | `integer` | 推理 token 数（思考模式） |

### 3.4 响应参数（200 流式）

每个 chunk 对象：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 对话唯一标识符 |
| `object` | `string` | 固定为 `"chat.completion.chunk"` |
| `created` | `integer` | 创建时的 Unix 时间戳（秒），每个 chunk 相同 |
| `model` | `string` | 使用的模型名 |
| `system_fingerprint` | `string` | 后端配置指纹 |
| `choices` | `object[]` | 补全 chunk 列表 |

**choices 元素**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `index` | `integer` | 结果序号 |
| `delta` | `object` | 本次增量内容 |
| `finish_reason` | `string` 或 `null` | 停止原因，最后一个 chunk 为具体值 |
| `logprobs` | `object` 或 `null` | 对数概率 |

**delta 对象**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `role` | `string` | 角色（仅在首 chunk 出现） |
| `content` | `string` | 本次增量文本 |
| `reasoning_content` | `string` | 本次增量思维链内容 |

---

## 四、FIM 补全 API（Beta）

### 4.1 端点信息

- **方法**：`POST`
- **路径**：`https://api.deepseek.com/beta/completions`
- **描述**：FIM（Fill-In-the-Middle）补全，提供前缀和后缀（可选），模型补全中间内容。常用于内容续写、代码补全。

### 4.2 请求参数

**model** `string` **必填**

模型 ID，可选值：`deepseek-v4-pro`。

**prompt** `string` **必填**

完成的前缀提示文本，默认值 `"Once upon a time"`。

**suffix** `string` 可选

完成的后缀文本。

**max_tokens** `integer` 可选

最大生成 token 数量。系统限制 FIM 补全输出最大 4K。

**temperature** `number` 可选

采样温度，取值范围 `(0, 2]`，默认 `1`。建议单独修改此值或 `top_p`，不建议同时修改。

**top_p** `number` 可选

核采样参数，取值范围 `(0, 1]`，默认 `1`。建议单独修改此值或 `temperature`，不建议同时修改。

**stop** `object` 可选

停止词配置。

**stream** `boolean` 可选

若为 `true`，以 SSE 形式流式输出，以 `data: [DONE]` 结尾。

**stream_options** `object` 可选

流式输出额外选项。

**echo** `boolean` 可选

是否在输出中附带 prompt 内容。

**logprobs** `integer` 可选

输出最可能 token 的对数概率数量，最大值 20。始终返回采样 token 的对数概率，因此响应中最多 `logprobs+1` 个元素。

**frequency_penalty** （已弃用）

不再支持，传入无效。

**presence_penalty** （已弃用）

不再支持，传入无效。

### 4.3 响应参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | `string` | 补全响应 ID |
| `object` | `string` | 固定为 `"text_completion"` |
| `created` | `integer` | 补全请求开始时的 Unix 时间戳（秒） |
| `model` | `string` | 使用的模型名 |
| `system_fingerprint` | `string` | 后端配置指纹 |
| `choices` | `object[]` | 补全结果列表 |

**choices 元素**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `index` | `integer` | 结果序号 |
| `text` | `string` | 补全文本 |
| `finish_reason` | `string` | 停止原因 |
| `logprobs` | `object` | 对数概率 |

**usage 对象**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `completion_tokens` | `integer` | 输出 token 数 |
| `prompt_tokens` | `integer` | 输入 token 数 |
| `total_tokens` | `integer` | 总 token 数 |
| `prompt_cache_hit_tokens` | `integer` | 缓存命中 token 数 |
| `prompt_cache_miss_tokens` | `integer` | 缓存未命中 token 数 |
| `completion_tokens_details` | `object` | 输出 token 详情 |
| `completion_tokens_details.reasoning_tokens` | `integer` | 推理 token 数 |

---

## 五、查询余额 API

### 5.1 端点信息

- **方法**：`GET`
- **路径**：`https://api.deepseek.com/user/balance`
- **描述**：查询账号余额。

### 5.2 响应参数

| 字段 | 类型 | 说明 |
|------|------|------|
| `is_available` | `boolean` | 当前账户是否有余额可供 API 调用 |
| `balance_infos` | `object[]` | 余额详情列表 |

**balance_infos 元素**：

| 字段 | 类型 | 说明 |
|------|------|------|
| `currency` | `string` | 货币类型（如 `"CNY"`） |
| `total_balance` | `string` | 总余额（字符串格式） |
| `granted_balance` | `string` | 赠送余额（字符串格式） |
| `topped_up_balance` | `string` | 充值余额（字符串格式） |

响应示例：

```json
{
  "is_available": true,
  "balance_infos": [
    {
      "currency": "CNY",
      "total_balance": "110.00",
      "granted_balance": "10.00",
      "topped_up_balance": "100.00"
    }
  ]
}
```


---

## 六、思考模式（Thinking Mode）

### 6.1 概述

DeepSeek 模型支持思考模式，在输出最终回答前先输出思维链内容，提升答案准确性。

### 6.2 控制参数

**思考模式开关**：

- OpenAI 格式：`{"thinking": {"type": "enabled"}}` 或 `{"thinking": {"type": "disabled"}}`
- 默认状态：`enabled`

**思考强度控制**：

- OpenAI 格式：`reasoning_effort` 取值为 `high` / `max`
- Anthropic 格式：`{"output_config": {"effort": "high/max"}}`
- 普通请求默认 `high`，复杂 Agent 类请求自动设为 `max`
- 兼容映射：`low`/`medium` → `high`，`xhigh` → `max`

**OpenAI SDK 使用方式**（必须通过 `extra_body` 传入）：

```python
response = client.chat.completions.create(
    model="deepseek-v4-pro",
    reasoning_effort="high",
    extra_body={"thinking": {"type": "enabled"}}
)
```


### 6.3 限制

思考模式下 **不支持** `temperature`、`top_p`、`presence_penalty`、`frequency_penalty` 参数。设置不会报错但不会生效。

### 6.4 输出参数

思维链内容通过 `reasoning_content` 字段返回，与 `content` 同级。

### 6.5 多轮对话中的 reasoning_content 拼接规则

- **无工具调用**：中间 assistant 的 `reasoning_content` 无需参与上下文拼接，传入也会被忽略。
- **有工具调用**：中间 assistant 的 `reasoning_content` 必须参与上下文拼接，后续所有 user 交互轮次中必须回传给 API。

---

## 七、多轮对话（Multi-Round Chat）

`/chat/completions` 是无状态 API，需每次传入完整对话历史。

**拼接方式**：
1. 第一轮将用户提问放入 `messages`
2. 获取响应后，将 assistant 的 message 追加到 `messages` 末尾
3. 将新的用户提问追加到 `messages` 末尾
4. 将完整的 `messages` 传给下一轮请求

```python
# Round 1
messages = [{"role": "user", "content": "What's the highest mountain in the world?"}]
response = client.chat.completions.create(model="deepseek-v4-pro", messages=messages)
messages.append(response.choices[0].message)

# Round 2
messages.append({"role": "user", "content": "What is the second?"})
response = client.chat.completions.create(model="deepseek-v4-pro", messages=messages)
```


---

## 八、对话前缀续写（Beta）

沿用 Chat Completion API，提供 assistant 开头消息让模型补全。

**要求**：
- `messages` 列表最后一条消息 `role` 为 `assistant`
- 最后一条消息设置 `prefix` 参数为 `true`
- 设置 `base_url="https://api.deepseek.com/beta"`

```python
client = OpenAI(api_key="", base_url="https://api.deepseek.com/beta")
messages = [
    {"role": "user", "content": "Please write quick sort code"},
    {"role": "assistant", "content": "```python\n", "prefix": True}
]
response = client.chat.completions.create(
    model="deepseek-v4-pro",
    messages=messages,
    stop=["```"],
)
```


---

## 九、JSON 输出模式

**使用方式**：
1. 设置 `response_format={'type': 'json_object'}`
2. system 或 user prompt 中必须包含 `json` 字样并给出期望输出格式示例
3. 合理设置 `max_tokens` 防止 JSON 被截断
4. API 有概率返回空 `content`，可修改 prompt 缓解

```python
response = client.chat.completions.create(
    model="deepseek-v4-pro",
    messages=messages,
    response_format={'type': 'json_object'}
)
```


---

## 十、Tool Calls（工具调用）

### 10.1 流程

1. 用户提问
2. 模型返回 function 调用
3. 用户执行函数，将结果以 `role: "tool"` 消息回传
4. 模型返回自然语言回答

**消息角色**：
- `user`：用户提问
- `assistant`（含 `tool_calls`）：模型工具调用请求
- `tool`（含 `tool_call_id` 和 `content`）：工具执行结果

```python
messages.append({"role": "tool", "tool_call_id": tool.id, "content": "24℃"})
```


### 10.2 Strict 模式（Beta）

- 设置 `base_url="https://api.deepseek.com/beta"`
- 所有 function 的 `strict` 属性设为 `true`
- `additionalProperties` 必须为 `false`，所有属性设为 `required`

```json
{
  "type": "function",
  "function": {
    "name": "get_weather",
    "strict": true,
    "description": "Get weather of a location",
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
Strict 模式支持的 JSON Schema 类型：`object`、`string`、`number`、`integer`、`boolean`、`array`、`enum`、`anyOf`。

### 10.3 思考模式下的工具调用

支持。模型在输出最终答案前进行多轮思考与工具调用。进行工具调用的轮次，后续所有请求必须完整回传 `reasoning_content` 给 API。

---

## 十一、上下文硬盘缓存（KV Cache）

### 11.1 概述

默认开启，无需修改代码。请求前缀重复时从缓存中读取，计入“缓存命中”。

### 11.2 缓存落盘与命中规则

三种落盘时机：
1. **请求结束位置落盘**：每次请求的用户输入结束位置与模型输出结束位置产生两个缓存前缀单元
2. **公共前缀检测落盘**：多次请求间的公共前缀作为独立缓存单元落盘
3. **固定 token 间隔落盘**：长输入/输出中以固定 token 间隔截取缓存单元

命中条件：后续请求必须**完整匹配**已落盘的缓存前缀单元才能命中。

### 11.3 查询缓存命中

usage 字段新增：
- `prompt_cache_hit_tokens`：缓存命中的 tokens 数
- `prompt_cache_miss_tokens`：缓存未命中的 tokens 数

缓存系统是“尽力而为”，不保证 100% 命中。缓存仅影响输入，输出仍受 temperature 等参数影响。

---

## 十二、Anthropic API 兼容性

### 12.1 概览

Base URL：`https://api.deepseek.com/anthropic`

```python
import anthropic

client = anthropic.Anthropic(
    base_url="https://api.deepseek.com/anthropic",
    api_key="your_api_key"
)
message = client.messages.create(
    model="deepseek-v4-pro",
    max_tokens=1000,
    system="You are a helpful assistant.",
    messages=[{"role": "user", "content": [{"type": "text", "text": "Hi, how are you?"}]}]
)
```
不支持模型名时，自动映射到 `deepseek-v4-flash`。

### 12.2 字段兼容一览

#### HTTP Header

| Header | 状态 |
|--------|------|
| `anthropic-beta` | 忽略 |
| `anthropic-version` | 忽略 |
| `x-api-key` | 完全支持 |

#### 简单字段

| 字段 | 状态 |
|------|------|
| `model` | 使用 DeepSeek 模型 ID |
| `max_tokens` | 完全支持 |
| `container` | 忽略 |
| `mcp_servers` | 忽略 |
| `metadata` | 忽略 |
| `service_tier` | 忽略 |
| `stop_sequences` | 完全支持 |
| `stream` | 完全支持 |
| `system` | 完全支持 |
| `temperature` | 完全支持（范围 `[0.0, 2.0]`） |
| `thinking` | 支持（`budget_tokens` 忽略） |
| `output_config` | 仅 `effort` 支持 |
| `top_k` | 忽略 |
| `top_p` | 完全支持 |

#### 工具字段

| 字段 | 状态 |
|------|------|
| `name` | 完全支持 |
| `input_schema` | 完全支持 |
| `description` | 完全支持 |
| `cache_control` | 忽略 |

#### tool_choice

| 值 | 状态 |
|----|------|
| `none` | 完全支持 |
| `auto` | 支持（`disable_parallel_tool_use` 忽略） |
| `any` | 支持（`disable_parallel_tool_use` 忽略） |
| `tool` | 支持（`disable_parallel_tool_use` 忽略） |

#### Message 字段

| 内容类型 | 子字段 | 状态 |
|----------|--------|------|
| `string` | | 完全支持 |
| `array, type="text"` | `text` | 完全支持 |
| | `cache_control` | 忽略 |
| | `citations` | 忽略 |
| `array, type="image"` | | 不支持 |
| `array, type="document"` | | 不支持 |
| `array, type="search_result"` | | 不支持 |
| `array, type="thinking"` | | 支持 |
| `array, type="redacted_thinking"` | | 不支持 |
| `array, type="tool_use"` | `id` | 完全支持 |
| | `input` | 完全支持 |
| | `name` | 完全支持 |
| | `cache_control` | 忽略 |
| `array, type="tool_result"` | `tool_use_id` | 完全支持 |
| | `content` | 完全支持 |
| | `cache_control` | 忽略 |
| | `is_error` | 忽略 |
| `array, type="server_tool_use"` | | 不支持 |
| `array, type="web_search_tool_result"` | | 不支持 |
| `array, type="code_execution_tool_result"` | | 不支持 |
| `array, type="mcp_tool_use"` | | 不支持 |
| `array, type="mcp_tool_result"` | | 不支持 |
| `array, type="container_upload"` | | 不支持 |


---

## 十三、补充说明

### 13.1 FIM 补全限制

模型的最大补全长度为 4K，需设置 `base_url="https://api.deepseek.com/beta"`。

### 13.2 temperature 和 top_p

建议单独修改其中之一，不建议同时修改。