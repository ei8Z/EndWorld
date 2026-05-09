---
title: 有记忆的写作助手更好用
desc: 借助 MemOS，你的产品将自动记住用户的写作习惯与上下文，让创作过程更连贯、更省心。
---

## 1. 概述

在写作助手这类产品中，用户往往希望助手能够**记住自己的写作风格和习惯**，而不是每次都从零开始。

*   **写作风格**  
    「帮我写总结的时候语气要轻松一点」
    
*   **常用信息**  
    「记住我在 XX 公司负责市场部」
    
*   **写作偏好**  
    「以后写邮件开头都要加上‘尊敬的客户’」
    
*   **上下文延续**  
    「昨天那份方案总结再优化一下，加上预算部分」
    

如果没有记忆，这些信息在会话结束后就会丢失。用户不得不反复提醒助手，体验会显得割裂、不专业。


### 1.1 为什么不用传统 RAG？

在写作助手场景里，RAG 并不适用

| 传统 RAG | MemOS |
| --- | --- |
| 依赖静态知识库，需要人工不断维护文档 | 对话中产生的信息可直接写入，无需额外维护 |
| 检索结果通常是通用知识片段 | 可以存储并调取个性化风格、语气、常用表达 |
| 更适合“公司文档/百科知识”类场景 | 更适合“持续迭代、个性化”写作助手 |


### 1.2 为什么不自己造轮子？

当然，你也可以尝试在数据库里保存用户偏好和上下文，但这会带来几个挑战：

*   **存储与检索逻辑复杂**：需要区分正文、偏好、用户画像，并设计检索策略。
    
*   **和大模型对接麻烦**：存下来只是第一步，还要在调用大模型前把相关信息「拼进 Prompt」。
    
*   **可扩展性差**：随着用户需求增加（写作风格、常用短语、上下文关联），代码会迅速膨胀。
    

### 1.3 为什么要用 MemOS？

在做选型时，可以直观对比三种方案：

| 方案 | 特点 | 局限 | MemOS 的优势 |
| --- | --- | --- | --- |
| **传统 RAG** | 通过向量检索知识库文档，拼接进 Prompt | 需要人工维护静态文档；不适合个性化写作习惯 | 自动捕捉用户在对话中透露的风格与偏好 |
| **自研存储方案** | 自己建表/缓存，把偏好与内容保存下来 | 逻辑复杂：要区分正文/偏好/画像；还需手动拼 Prompt；扩展困难 | MemOS 封装存储+检索+Prompt 注入，减少开发负担 |
| **MemOS** | 两个接口即可：`addMessage` 写入、`searchMemory` 检索 | —— | 支持长期追踪写作风格、复用常用信息；开箱即用、易扩展 |


### 1.4 本案例会展示什么？

本案例展示如何用 MemOS 云服务，快速实现一个“会记住用户”的写作助手。

在这个 Demo 中，用户可能会：

*   交代偏好：「帮我写总结的时候语气要轻松一点」
    
*   重用背景：「记住我在 XX 公司负责市场部」
    
*   迭代任务：「昨天那份方案总结再优化一下，加上预算部分」
    

有了 MemOS，写作助手就能：

1.  **延续风格**：保持用户要求的语气和格式一致。
    
2.  **重用信息**：自动带入用户常用的背景信息。
    
3.  **快速迭代**：基于已有内容继续修改，而不是推倒重来。
    

运行这个案例脚本时，开发者会在控制台看到：

*   每次调用 `addMessage` 和 `searchMemory` 的请求/响应
    
*   被检索出来的写作风格、背景信息等记忆
    
*   模型生成的最终回答（若未接入大模型，则会提示【未接入大模型】）
    

##  2. 示例

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

class WritingAssistant:
    """AI写作助手，帮助用户写作，具备记忆能力"""
    
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
          你是一位专业的的写作助手, 能够记住用户的写作风格和偏好。
          你可调用对话记忆，助力提供更具个性化的回复。
          请借助这些记忆，理解用户的场景背景、偏好倾向及过往互动情况。
          如果提供了记忆，请在相关时自然地引用它们，但不要明确提及拥有记忆功能
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

ai_assistant = WritingAssistant()
user_id = "memos_writing_user_123"

def demo_questions():
    return [
      "帮我写一封团队聚餐的通知邮件",
      "帮我写一封客户邮件，内容是总结我们即将上线的理财App新功能",
    ]

def pre_configured_conversations():
    """返回预配置的对话对"""
    return [
        {
            "user": "我在互联网公司市场部工作，写邮件时语气要轻松点，开头加'亲爱的XX'"
        },
        {
            "user": "写总结时我习惯先列三条要点"
        }
    ]

def execute_pre_conversations():
    """执行预配置的对话"""
    conversations = pre_configured_conversations()
    conversation_id = generate_conversation_id()
    
    print(f"\n🔄 正在执行预配置对话（conversation_id={conversation_id})...")
    print("=" * 60)
    
    for i, conv in enumerate(conversations, 1):
        print(f"\n💬 对话 {i}")
        print(f"👤 用户: {conv['user']}")
        
        # 执行对话
        answer = ai_assistant.chat(conv['user'], user_id, conversation_id)
        print(f"🤖 助手: {answer}")
        print("-" * 40)
    
    print("\n✅ 预配置对话执行完毕！")
    print("=" * 60)

def main():
    print("📝 欢迎查看MemOS在写作助手中的使用示例！")
    print("💡 在MemOS的加持下，让您的写作助手更懂您的风格和偏好！ ✍️ \n")
    
    # 询问用户是否要先执行预配置对话
    while True:
        pre_chat = input("🤔 您想先执行预配置对话吗？预计消耗2次add和2次search的调用额度，是否执行？(y/n): ").strip().lower()
        
        if pre_chat in ['y', 'yes', '是', 'Y']:
            execute_pre_conversations()
            break
        elif pre_chat in ['n', 'no', '否', 'N']:
            print("📝 开始全新的写作助手对话...")
            break
        else:
            print("⚠️  请输入 'y' 表示是或 'n' 表示否")

    print("\n⚡️ 接下来您输入的每一个问题，都将会在一个全新的会话中展开（新的 conversation id）。MemOS将会跨会话自动召回您的历史行为记忆，为您提供连续、个性化的服务。")    
    print("\n🎯 以下是一些示例问题，您可以继续跟写作助手对话:")
    for i, question in enumerate(demo_questions(), 1):
      print(f"  {i}. {question}")

    while True:
        user_query = input("\n🤔 请输入您的写作需求 (或输入 'exit' 退出): ").strip()
        
        if user_query.lower() in ['quit', 'exit', 'q', '退出']:
            print("👋 感谢使用写作助手，祝您写作愉快！")
            break
        
        if not user_query:
            continue
        
        print("🤖 正在创作...")
        conversation_id = generate_conversation_id()
        answer = ai_assistant.chat(user_query, user_id, conversation_id)
        print(f"\n💬 conversation_id: {conversation_id}\n💡 [助手]: {answer}\n")
        print("-" * 60)


if __name__ == "__main__":
    main()
```

### 2.3 代码说明

1.   在环境变量中设置您的MemOS API秘钥以及Open AI秘钥
    
2.   实例化`WritingAssistant` 
    
3.   选择是否执行预设值的对话，会消耗2次add和2次search的额度
    
4.   使用`main()`函数通过对话循环与助手进行交互
    
5.   助手会调用 chat， 先执行 search 检索记忆，然后再调用OpenAI进行对话，最后执行 add 存储记忆
