# Android Agent

运行在 Android 设备上的 AI 助手，通过 Function Calling 调用设备能力和外部工具。

## 功能

### 🤖 AI 对话
- 基于 **DeepSeek API** + **Function Calling** 的多轮对话
- AI 可自主调用设备上的工具完成任务
- 对话历史持久化（Room 本地数据库）

### 🛠️ 工具集（4 个大工具，两段式调用）

对外暴露 4 个大工具，子工具用 `action` 指定，调用参数只有 `{ action, params }`：

| 大工具 | 子工具 |
|------|------|
| `system_tool` | `shell` / `python` / `ssh`（`params.action=exec` / `upload` / `download`） |
| `phone_tool` | `system_info` / `gps` / `notify` / `sound` / `sensor` / `rename_session` |
| `skill` | 已注册的自建工具（子工具名即 skill 名，逐步披露）；`delete` 注销。脚本目录 `skills/` |
| `skill_hub` | `list` / `install` / `disable` / `enable` / `remove` —— 从远程仓库（GitHub / Gitea）查看 / 下载 / 启停 / 卸载 skill |

SSH 为原生 JSch 实现（一个 `ssh` 子工具覆盖执行 / 上传 / 下载；内置 Python 无 paramiko）；
延时等待 / 对象存储 / OpenViking 读写通过 skill 提供（`wait` / `objstore` / `ov`）。

**两段式调用**（渐进披露，首次只暴露工具名与子工具名，用法按需返回）：
- 不传 `action` → 返回该大工具下全部子工具及简短说明；
- 只传 `action`（有参子工具）→ 返回该子工具的用法；无参子工具（如 `gps`）则直接执行；
- 传 `action + params` → 执行，子工具参数放进 `params` 对象（如 `{"action":"shell","params":{"command":"ls"}}`）。

> 用户发送图片时自动上传并交由 vision 模型识别，无需 LLM 调用工具。

### 🧩 skill 自建工具
- `skills/` 目录下每个技能文件夹的入口 `skill.py` 按头部元信息**自动注册**为可调用工具：放入即生效，删除即注销，无需改核心代码
- 只支持文件夹布局 `skills/<名>/skill.py`（可含 `_` 助手模块与 `config.json`，助手用相对导入引用）；扁平的单文件 `.py` 不再注册
- `skill` 工具只负责**调用**（不负责创建）：省略 action 返回已注册清单，`action=<skill 名>` 查看用法，`action=<skill 名>` + `params` 执行，`action=delete` 注销
- 新增 skill 需写入 `skills/<名>/skill.py`（可用 `system_tool` 的 `python` 动作），编写规范见 `skills/README.md`
- 脚本定义 `run(arguments)` 并返回统一信封 `{"status":"ok","result":{...}}` / `{"status":"error",...}`
- 运行在应用内置的 Python 3.13 环境；不内置任何 skill，全部从 skill 仓库安装，便于独立更新

### 🛰️ 远程 skill 仓库（skill_hub）
- `skill_hub` 工具（`list` / `install` / `disable` / `enable` / `remove`）从 GitHub / Gitea 技能仓库查看 / 下载 / 启停 / 卸载 skill
- 远程仓库根放 `index.json`（技能名 + 说明 + 平台 + 依赖索引），每个技能一个文件夹（入口 `skill.py` 与配置模板同目录）
- 下载到本地 `skills/<名>/` 后**自动注册**，无需重启；同名技能为**覆盖安装**（会保留本地 `config.json`）
- 「设置 → Skill 仓库」可直接管理本机 skill：列出已安装项、开关启用状态（关闭即对 LLM 隐藏，文件保留）、删除（二次确认），并可从远程仓库拉取列表手动下载
- 命令行/LLM 侧：`disable` 下线（文件保留）、`enable` 恢复、`remove` 直接删除（卸载）
- 仓库地址默认 `abc12524/shell-skills@main`，可在「设置 → Skill 仓库」修改
- 支持 **Gitea** 源：在「设置 → Skill 仓库」填「类型」（`github`/`gitea`，留空按基址自动推断）与「Gitea 基址」（如 `http://192.168.30.181:3000`）；私有仓库可填访问令牌
- ⚠️ 下载即等价于信任该仓库的代码（会在内置 Python 进程内执行），仅从可信来源安装

### ☁️ 对象存储（S3 兼容）
- 已下沉为 `objstore` skill，支持任何 S3 兼容服务（AWS S3、MinIO、Ceph、阿里云 OSS 等）
- 13 种操作：upload / download / delete / delete_batch / list / copy / head / list_buckets / create_bucket / delete_bucket / presign / create_folder / delete_folder
- 从 Skill 仓库安装后，在同目录 `config.json` 配置连接参数

### 🧠 嵌入式 Python 环境
- 内置 **Python 3.13** 完整标准库 + pip
- 自带清华源加速（`pypi.tuna.tsinghua.edu.cn`）
- 预装 `psutil` 等常用库
- 通过 `/system/bin/linker64` 绕过 SELinux 限制

### 💾 外置记忆（OpenViking）
- 三个开关：每轮按问题检索、每步按问题/助手/工具检索、会话自动抓取并提交（commit）
- 自动注入相关记忆作为对话上下文；召回与抓取均静默失败，不打断对话
- 同一会话内不重复注入相同记忆（强制规则，非开关）；每个会话独立，切换会话从零开始
- skill 名单、记忆召回、轮次与用户提示统一以 `inject_user` 注入（每轮合并为一条
  `<system-reminder>`），默认不在聊天界面展示；设置「显示注入信息」后可在历史会话中查看

## 构建

### 环境要求
- Android Studio Meerkat (2024.3.1+) 或命令行 Gradle
- JDK 17+
- Android SDK (compileSdk 37)

### 构建命令
```bash
# 克隆仓库
git clone git@github.com:abc12524/android-agent.git
cd android-agent

# 构建 Debug APK
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 国内网络环境
如果无法访问 `services.gradle.org` 或 `dl.google.com`，可参考桌面版配置：

```bash
# gradle-wrapper.properties
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-all.zip
validateDistributionUrl=false

# settings.gradle.kts 添加阿里云镜像
maven { setUrl("https://maven.aliyun.com/repository/public") }
maven { setUrl("https://maven.aliyun.com/repository/google") }
maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
```

## 使用

1. 安装 APK 后打开应用
2. 进入 **设置** 配置 **DeepSeek API Key**（必填）
3. （可选）配置 OpenViking 服务器地址与密钥
4. （可选）从 Skill 仓库安装所需技能（如 `objstore` / `wait` / `ov`）
5. 开始对话

### 首次启动
- Python 环境（~53MB）会在首次启动时自动解压，耗时约 10-30 秒
- 解压期间尝试调用 Python 工具会收到初始化提示

## 远程配置导入（一键导入设置）

在 **设置** 中填入远程配置地址（`configUrl`），应用会从该 URL 拉取 JSON 并一次性应用所有已知设置，无需逐项手动输入。该 JSON 即为配置格式，**建议放在 Web 服务器根目录**，通过 HTTP(s) 直接访问。

JSON 顶层 key 与应用的设置项一一对应；**顶层值为对象的 key 会被识别为账号**（多账号隔离），导入时可选择导入到哪个账号；若 JSON 为扁平结构（无嵌套对象）则直接整体导入。缺失或不存在的 key 会被忽略。

```json
{
  "kelivo": {
    "deepseek_api_key": "sk-***",
    "deepseek_base_url": "https://api.deepseek.com",
    "deepseek_model": "deepseek-v4-flash",
    "openviking_url": "http://192.168.1.100:1933",
    "openviking_key": "***",
    "openviking_user": "***",
    "ov_peer_id": "",
    "ov_peer_scope": "actor",
    "ov_workspace_peer": true,
    "max_tool_rounds": 8,
    "session_timeout_minutes": 15,
    "thinking_timeout_minutes": 0,
    "background_service_enabled": false,
    "ov_score_threshold": 0.4,
    "ov_search_display_count": 2,
    "ov_find_threshold": 0.5,
    "ov_find_limit": 2,
    "user_tips": "",
    "s3_endpoint_url": "http://192.168.1.100:9000",
    "s3_access_key": "your-access-key",
    "s3_secret_key": "your-secret-key"
  },
  "default": {
    "deepseek_api_key": "sk-xxxx",
    "deepseek_base_url": "https://api.deepseek.com",
    "deepseek_model": "deepseek-v4-flash",
    "openviking_url": "",
    "openviking_key": "",
    "openviking_user": "default",
    "ov_peer_id": "",
    "ov_peer_scope": "actor",
    "ov_workspace_peer": true,
    "max_tool_rounds": 8,
    "session_timeout_minutes": 15,
    "thinking_timeout_minutes": 0,
    "background_service_enabled": false,
    "ov_score_threshold": 0.4,
    "ov_search_display_count": 2,
    "ov_find_threshold": 0.5,
    "ov_find_limit": 2,
    "user_tips": "",
    "s3_endpoint_url": "",
    "s3_access_key": "",
    "s3_secret_key": ""
  }
}
```

## 技术栈

- **语言**: Kotlin 2.4.0
- **UI**: Jetpack Compose + Material 3
- **数据库**: Room (SQLite)
- **网络**: OkHttp + Gson
- **AI API**: DeepSeek Chat Completion (Function Calling)
- **SSH**: JSch
- **对象存储**: `objstore` skill（Python，S3 兼容）
- **CI**: GitHub Actions（自动构建 Debug APK 并发布 Release）

## License

GNU Affero General Public License v3.0 (AGPL-3.0)，详见 [LICENSE](LICENSE)。
