# Android Agent

运行在 Android 设备上的 AI 助手，通过 Function Calling 调用设备能力和外部工具。

## 功能

### 🤖 AI 对话
- 基于 **DeepSeek API** + **Function Calling** 的多轮对话
- AI 可自主调用设备上的工具完成任务
- 对话历史持久化（Room 本地数据库）

### 🛠️ 工具集
| 工具 | 说明 |
|------|------|
| `ssh_execute` | SSH 远程命令执行 |
| `ssh_scp` | SCP 文件上传下载 |
| `execute_python` | 嵌入 Python 3.13 环境，支持 pip 安装包 |
| `shell_execute` | Android Shell 命令执行 |
| `get_system_info` | 获取设备系统信息 |
| `get_gps_location` | 获取设备 GPS 定位 |
| `read_sensors` | 读取陀螺仪、加速度计、磁力计等传感器 |
| `baidu_search` | 百度搜索引擎搜索 |
| `baidu_baike` | 百度百科查询 |
| `openviking_*` | 外置记忆读写搜索（OpenViking） |

### 🧠 嵌入式 Python 环境
- 内置 **Python 3.13** 完整标准库 + pip
- 自带清华源加速（`pypi.tuna.tsinghua.edu.cn`）
- 预装 `psutil` 等常用库
- 通过 `/system/bin/linker64` 绕过 SELinux 限制

### 💾 外置记忆（OpenViking）
- 语义搜索历史知识
- 保存用户偏好、项目配置、操作经验
- 自动注入相关记忆作为对话上下文

## 构建

### 环境要求
- Android Studio Hedgehog (2023.1.1+) 或命令行 Gradle
- JDK 17+
- Android SDK (compileSdk 34)

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
distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-8.7-all.zip
validateDistributionUrl=false

# settings.gradle.kts 添加阿里云镜像
maven { setUrl("https://maven.aliyun.com/repository/public") }
maven { setUrl("https://maven.aliyun.com/repository/google") }
maven { setUrl("https://maven.aliyun.com/repository/gradle-plugin") }
```

## 使用

1. 安装 APK 后打开应用
2. 进入 **设置** 配置 **DeepSeek API Key**（必填）
3. （可选）配置 OpenViking 服务器地址和百度千帆 API Key
4. 开始对话

### 首次启动
- Python 环境（~53MB）会在首次启动时自动解压，耗时约 10-30 秒
- 解压期间尝试调用 Python 工具会收到初始化提示

## 技术栈

- **语言**: Kotlin
- **UI**: Jetpack Compose + Material 3
- **数据库**: Room (SQLite)
- **网络**: OkHttp + Gson
- **AI API**: DeepSeek Chat Completion (Function Calling)
- **SSH**: JSch
- **CI**: GitHub Actions（自动构建 Debug APK 并发布 Release）

## License

MIT
