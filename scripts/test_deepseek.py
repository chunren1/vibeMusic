"""DeepSeek V4 Flash 测试"""
from openai import OpenAI

client = OpenAI(
    api_key="sk-19700b3bf1b44c6f8ca03f3d88b58e34",
    base_url="https://api.deepseek.com"
)

def chat(msg):
    r = client.chat.completions.create(
        model="deepseek-v4-flash",
        messages=[{"role": "user", "content": msg}],
        max_tokens=200,
        extra_body={"thinking": {"type": "disabled"}}
    )
    return r.choices[0].message.content

print("=== Simple ===")
print(chat("用一句话介绍你自己"))
print()

print("=== Music ===")
print(chat("推荐三首适合下雨天听的歌，简要说明理由"))
print()

print("Done!")
