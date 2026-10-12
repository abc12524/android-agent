# android-agent skill 编写指南

本目录（`filesDir/skills/`）是 **skill 自建工具** 的存放位置。每个 `*.py` 脚本就是一个
可被 LLM 调用的工具；`skill` 工具会自动扫描本目录并注册，**新增脚本即自动生效，无需改核心代码**。

> 以 `_` 开头的文件（如 `_skill_runner.py`、`_envelope.py`）是运行时内部文件，不会被注册。

## 目录与运行环境

- 运行 Python：应用内置的 **Python 3.13**（完整标准库 + pip，清华源）。可用 `system_tool` 的
  `python_pip` 安装第三方包（如 `requests`）。
- 脚本工作目录即本目录，可直接 `import` 同目录下的其他脚本或 `_envelope`。
- 执行方式：`skill` 工具的 `action=run` 会在独立进程中导入脚本并调用 `run(arguments)`。

## 脚本结构

### 1. 头部元信息（文件最前面连续的 `#` 注释）

```python
# skill: diskusage            # 名称（唯一，供 LLM 调用；同时是文件名，建议一致）
# description: 查看各分区磁盘占用   # 一句话说明（列表与工具描述里展示）
# status: enable              # 可选，enable（默认）/ disable；disable 不向 LLM 披露
# usage:
#   arguments.path = 要统计的目录（默认 "/"）
#   示例: {"path": "/data"}
```

### 2. `run(arguments)` 函数

脚本体必须定义 `run(arguments: dict)`，返回：

- **dict**：直接作为结果，runner 包成 `{"status":"ok","result":{...}}`；
- **规范信封 dict/字符串**：形如 `{"status":"ok","result":{...}}` 或 `{"status":"error","error":"..."}`，
  runner 原样透传；
- 其他类型：包成 `{"status":"ok","result":{"output": ...}}`。

推荐 `from _envelope import ok, error` 返回信封。**不要在 `run` 里 `print`**（runner 会捕获以免
污染输出，但更应直接 return）。

```python
from _envelope import ok, error

def run(arguments):
    path = arguments.get("path") or "/"
    if not isinstance(path, str):
        return error("path 必须是字符串", code="invalid_argument")
    # ... 业务逻辑 ...
    return ok({"path": path, "result": "..."})
```

## 通过 `skill` 工具管理（LLM 用法）

`skill` 工具通过 `action` 分发：

| action | 参数 | 说明 |
|--------|------|------|
| `list` | — | 列出所有已注册 skill（含绝对目录 `dir` 与每个脚本 `path`）、说明、状态 |
| `usage` | `skill` | 查看某 skill 的用法 |
| `run` | `skill`, `arguments` | 执行 skill，把 `arguments` 传给 `run()` |
| `write` | `skill`, `description`, `usage`, `code` | 新建/覆盖一个 skill 脚本（自动注册） |
| `delete` | `skill` | 删除 skill 脚本 |
| `guide` | — | 返回本指南 |

### 自建一个 skill（示例）

调用 `skill` 工具：

```json
{
  "action": "write",
  "skill": "text_stats",
  "description": "统计一段文本的字符数、词数、行数",
  "usage": "arguments.text = 要统计的文本",
  "code": "from _envelope import ok, error\n\ndef run(arguments):\n    text = arguments.get(\"text\")\n    if not isinstance(text, str):\n        return error(\"缺少 text 参数\", code=\"invalid_argument\")\n    return ok({\n        \"chars\": len(text),\n        \"words\": len(text.split()),\n        \"lines\": text.count(chr(10)) + 1,\n    })\n"
}
```

随后即可：

```json
{"action": "run", "skill": "text_stats", "arguments": {"text": "hello world"}}
```

## 约定与建议

- **返回值即工具结果**：LLM 只看到 `run` 的返回值，尽量返回结构化 dict。
- **错误用信封**：失败返回 `error("原因", code="...")`，便于 LLM 自纠。
- **错误码**：`invalid_argument` / `not_found` / `unknown_skill` / `io_error` / `internal` 等。
- **幂等与安全**：涉及删除/写文件等危险操作时，校验参数并在说明中写明。
- **依赖**：优先标准库；需要第三方库时用 `system_tool` 的 `python_pip` 安装。
- **状态控制**：临时下线某 skill，把头部 `# status:` 改为 `disable`（仍可 `usage`/`run`，只是不主动披露）。
