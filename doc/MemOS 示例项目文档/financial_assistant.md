---
title: 让理财助手读懂客户行为背后的偏好
desc: 借助 MemOS，将用户的操作与对话行为抽象为“记忆”，从而识别并提炼背后的投资偏好，实现更懂客户的个性化服务。
---


## 1. 概述

在智能投顾产品中，用户会留下大量**行为轨迹**：

*   **引流来源**：用户是从哪条广告或推文点进来的？（如点击“养老理财”广告）
    
*   **APP 内操作**：浏览了哪些基金产品？收藏了哪些理财？
    
*   **沟通记录**：和投顾经理的交流、与 AI 投顾助手的对话内容。
    

这些只是原始行为，如果直接保存成日志，对大模型帮助有限。**关键在于如何把行为抽象成“记忆”**：


### 1.1 行为如何抽象为记忆？

| 用户行为（原始轨迹） | 对应的记忆（语义抽象） |
| --- | --- |
| 点击「养老理财」广告进入 APP | 记忆：「用户对养老理财有潜在兴趣」 |
| 多次浏览低风险基金详情页 | 记忆：「用户风险偏好偏保守」 |
| 收藏“低风险理财产品” | 记忆：「用户倾向选择低风险理财」 |
| 在对话中说「我不想冒太大风险」 | 记忆：「明确表达低风险诉求」 |

当用户后续再问「我适合什么投资？」时，投顾助手不需要翻一堆日志，而是直接用这些语义化记忆来驱动模型生成个性化的回答。


### 1.2 为什么不用传统 RAG？

RAG 更适合做知识问答，例如解释「什么是债券」。但它不会从用户的行为里总结出偏好：

| 传统RAG | MemOS |
| --- | --- |
| 返回静态理财知识片段 | 将用户行为抽象为语义化记忆（兴趣、偏好、画像） |
| 不能回答「我适合什么投资？」 | 能结合记忆生成个性化建议 |

### 1.3 为什么不自己造轮子？

开发者当然也可以自己存行为，但会遇到三个挑战：

*   **缺乏抽象**：单纯存「点击了基金 A」没有用，要转成「风险偏好=低风险」。
    
*   **对接复杂**：在调用模型前，要自己拼 Prompt，把分散的行为抽象成语义信息。
    
*   **扩展性差**：随着渠道、产品、沟通场景增加，代码就会迅速失控。
    

### 1.4 为什么要用 MemOS？

在做选型时，可以直观对比三种方案：

| 方案 | 特点 | 局限 | MemOS 的优势 |
| --- | --- | --- | --- |
| **传统RAG** | 检索知识库文档 | 不会处理用户行为，无法形成画像 | 适合做 FAQ，但不能做个性化投顾 |
| **自研存储** | 直接存储行为日志 | 需要自行抽象行为→记忆；拼 Prompt 成本高 | 要开发大量 glue code |
| **MemOS** | 两个接口：`addMessage` 写入、`searchMemory` 检索 | —— | 自动把行为轨迹抽象成记忆，供模型直接使用 |


### 1.5 本案例会展示什么？

本案例展示如何用 MemOS 云服务，快速实现一个「会把用户行为转成记忆」的智能投顾助手。

在 Demo 中：

*   **D1 引流行为**：点击「养老理财」广告 → 生成记忆「养老理财兴趣」。
    
*   **D2 APP 行为**：浏览并收藏低风险基金 → 生成记忆「风险偏好=低风险」。
    
*   **D3 对话行为**：说「不想冒风险」 → 生成记忆「明确低风险诉求」。
    

当用户问「我适合什么投资？」时：

*   `searchMemory` 检索到上述记忆
    
*   大模型生成的回答会结合这些画像 → 输出「更适合低风险固收类产品」。
    

运行这个案例脚本时，开发者会在控制台看到：

*   每次 `addMessage` 的请求/响应（行为被存储）
    
*   每次 `searchMemory` 的请求/响应（命中的语义化记忆）
    
*   模型最终输出的个性化投资建议
    

## 2. 示例

### 2.1 环境准备

使用pip安装所需的依赖项

```shell
pip install MemoryOS -U
```

### 2.2 完整代码

```python
import os
import uuid
from openai import OpenAI
from memos.api.client import MemOSClient

os.environ["MEMOS_API_KEY"] = "mpg-xx" # 从云服务控制台获取MemOS_API_KEY
os.environ["OPENAI_API_KEY"] = "sk-xx" # 替换为你自己的API_KEY

conversation_counter = 0

def generate_conversation_id():
    global conversation_counter
    conversation_counter += 1
    return f"conversation_{conversation_counter:03d}"

class FinancialManagementAssistant:
    """AI财务管理助手，具备记忆能力"""
    
    def __init__(self):
        self.memos_client = MemOSClient(api_key=os.getenv("MEMOS_API_KEY"))
        self.openai_client = OpenAI(api_key=os.getenv("OPENAI_API_KEY"))
    
    def search_memory(self, query, user_id, conversation_id):
        """查询相关记忆"""
        response = self.memos_client.search_memory(query, user_id, conversation_id)

        return [memory_detail.memory_value for memory_detail in response.data.memory_detail_list]

    def build_system_prompt(self, memories):
        """构建包含格式化记忆的系统提示"""
        base_prompt = """
          你是一位知识丰富且专业贴心的理财管理助手。
          你可以访问对话记忆，帮助你提供更个性化的回答。
          使用记忆来理解用户的背景、偏好和过往互动。
          如果提供了记忆，请在相关时自然地引用它们，但不要明确提及拥有记忆
        """

        if memories:
            # 将记忆格式化为编号列表
            formatted_memories = "## 记忆:\n"
            for i, memory in enumerate(memories, 1):
                formatted_memories += f"{i}. {memory}\n"
            
            return f"{base_prompt}\n\n{formatted_memories}"
        else:
            return base_prompt
        

    def add_message(self, messages, user_id, conversation_id):
        """添加消息"""
        self.memos_client.add_message(messages, user_id, conversation_id)

    def get_message(self, user_id, conversation_id):
        """获取消息"""
        response = self.memos_client.get_message(user_id, conversation_id)
        
        return response.data.message_detail_list

    def chat(self, query, user_id, conversation_id):
        """处理包含记忆集成的对话的主要聊天函数"""
        # 1. 搜索相关记忆
        memories = self.search_memory(query, user_id, conversation_id)
        
        # 构建包含记忆的系统提示
        system_prompt = self.build_system_prompt(memories)
        
        # 2. 使用OpenAI生成回答
        response = self.openai_client.chat.completions.create(
            model="gpt-4o",
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": query}
            ]
        )
        answer = response.choices[0].message.content

        # 3. 将对话保存到记忆中
        messages = [
            {"role": "user", "content": query},
            {"role": "assistant", "content": answer}
        ]
        self.memos_client.add_message(messages, user_id, conversation_id)
        
        return answer

ai_assistant = FinancialManagementAssistant()
user_id = "memos_financial_management_user_123"

def demo_questions():
    return [
      '我的风险偏好是什么',
      "推荐一些适合我的投资"
    ]

def preset_user_behaviors():
    """显示预设的用户行为记忆"""
    conversation_id = generate_conversation_id()
    
    print(f"\n📊 已预设的用户行为记忆 (conversation_id={conversation_id}):")
    print("=" * 60)

    behaviors = [{
      "role": "user",
      "content": "点击「养老理财」广告进入 APP"
    }, {
      "role": "user",
      "content": "浏览并收藏低风险基金"
    }]
    
    for i, behavior in enumerate(behaviors, 1):
        print(f"{i}. {behavior['content']}")
    ai_assistant.add_message(behaviors, user_id, conversation_id)
    
    print("=" * 60)
    print("💡 以上行为记忆已被MemOS记录，助手会基于这些信息提供个性化建议")

def main():
    print("💰 欢迎查看MemOS在理财管理助手中的使用示例！")
    print("💡 在MemOS的加持下，让您的理财助手更智能、更贴心！ 😊 \n")
    
    # 询问用户是否要先执行预配置对话
    while True:
        pre_chat = input("🤔 您想先预加载用户行为记忆吗？预计消耗1次add额度，是否执行？(y/n): ").strip().lower()
        
        if pre_chat in ['y', 'yes', '是', 'Y']:
            preset_user_behaviors()
            break
        elif pre_chat in ['n', 'no', '否', 'N']:
            print("📝 开始全新对话...")
            break
        else:
            print("⚠️  请输入 'y' 表示是或 'n' 表示否")

    print("\n⚡️ 接下来您输入的每一个问题，都将会在一个全新的会话中展开（新的 conversation id）。MemOS将会跨会话自动召回您的历史行为记忆，为您提供连续、个性化的服务。")    
    print("\n🎯 以下是一些示例问题，您可以继续跟助手对话:")
    for i, question in enumerate(demo_questions(), 1):
      print(f"  {i}. {question}")

    while True:
        user_query = input("\n🤔 请输入您的问题 (或输入 'exit' 退出): ").strip()
        
        if user_query.lower() in ['quit', 'exit', 'q', '退出']:
            print("👋 感谢使用理财管理助手！")
            break
        
        if not user_query:
            continue
        
        print("🤖 正在处理...")
        conversation_id = generate_conversation_id()
        answer = ai_assistant.chat(user_query, user_id, conversation_id)
        print(f"\n💬 conversation_id: {conversation_id}\n💡 [助手]: {answer}\n")
        print("-" * 60)


if __name__ == "__main__":
    main()
```

### 2.3 代码说明

1.   在环境变量中设置您的MemOS API秘钥以及Open AI秘钥
    
2.   实例化<code style="font-weight: bold;">FinancialManagementAssistant </code>
    
3.   选择是否执行预设值的对话，会消耗1次add和2次search的额度
    
4.   使用`main()`函数通过对话循环与助手进行交互
    
5.   助手会调用 chat， 先执行 search 检索记忆，然后再调用OpenAI进行对话，最后执行 add 存储记忆
