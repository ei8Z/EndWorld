
---

### 🔗 基础信息

- **Base URL**：`https://memos.memtensor.cn/api/openmem/v1`
- **鉴权方式**：在请求头中携带 `Token`。  
  `Authorization: Token {你的MEMOS_API_KEY}`  
  *API Key 获取地址：[MemOS 控制台 - API Keys](https://memos-dashboard.openmem.net/apikeys/)*
- **请求方法**：所有接口均为 `POST`，请求体使用 `application/json`。

---

### 📡 API 接口总览

#### 1. 记忆检索与对话

| 接口 | 端点 | 说明 |
| :--- | :--- | :--- |
| 检索记忆 | `/search/memory` | 根据问题检索相关的事实与偏好记忆。 |
| 获取全量记忆 | `/get/memory` | 分页拉取用户的所有记忆条目。 |
| 记忆对话 | `/chat` | 结合历史记忆进行多轮对话式回答。 |

**① 检索记忆**
- **请求体示例**：
  ```json
  {
    "user_id": "user777",
    "conversation_id": "conv777",
    "query": "我喜欢什么品牌？"
  }
  ```
- **响应结构**：  
  `memory_detail_list`（事实记忆）、`preference_detail_list`（偏好记忆）。

**② 获取全量记忆**
- **请求体示例**：
  ```json
  {
    "user_id": "user777",
    "page": 1,
    "size": 20,
    "include_preference": true
  }
  ```

**③ 记忆对话**
- **请求体示例**：
  ```json
  {
    "user_id": "user777",
    "conversation_id": "conv777",
    "query": "推荐我没住过的酒店品牌"
  }
  ```
- **返回**：包含历史记忆上下文的文本回答。

---

#### 2. 消息管理

| 接口 | 端点 | 说明 |
| :--- | :--- | :--- |
| 添加消息 | `/add/message` | 上传对话历史，系统自动从中抽取并生成记忆。 |
| 获取消息 | `/get/message` | 拉取指定会话的原始消息记录。 |
| 消息反馈 | `/add/feedback` | 对历史记忆或回答进行标注修正。 |

**添加消息示例**：
```json
{
  "user_id": "user777",
  "conversation_id": "conv777",
  "messages": [
    {"role": "user", "content": "我暑假想去广州，住什么连锁酒店？"},
    {"role": "assistant", "content": "七天、全季、希尔顿都不错"},
    {"role": "user", "content": "那选七天吧"}
  ]
}
```
返回 `task_id`，可通过它追踪记忆抽取进度。

---

#### 3. 知识库管理

| 接口 | 端点 | 说明 |
| :--- | :--- | :--- |
| 创建知识库 | `/create/kb` | 新建知识库，返回知识库ID。 |
| 管理文档 | *(端点待补充)* | 文档中暂未公开具体端点，请留意官方更新。 |

---

1、在该文件中，已经封装好了核心API的使用方法：
/Library/Frameworks/Python.framework/Versions/3.13/lib/python3.13/site-packages/memos/api/client.py

2、在项目下的“MemOS示例项目”中可以看到使用的例子