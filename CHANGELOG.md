# Changelog

## Unreleased

- 聊天消息与工具结果支持媒体展示：Markdown 图片、`[image:路径]`、`[audio:路径]` 与音频链接；
  图片支持 http/data/file/应用沙盒路径，音频内联播放（点击才播放）
- skill 注册取消扁平单文件布局：只识别文件夹式 `skills/<名>/skill.py`；`skills/<名>.py` 不再注册（runner、扫描、删除逻辑同步收紧）
- 移除内置 `script` 技能：技能不再随应用打包，统一从 skill 仓库安装，便于独立更新；旧版本已部署到设备上的副本不会自动删除，可在「设置 → Skill 仓库」手动删除
- 工具集精简为 4 个大工具；`system_tool` 保留 `shell` / `python` / `ssh`，SSH 恢复为原生 JSch 并合并 SCP
  （`params.action=exec|upload|download`）；`wait` / `objstore` / `ov` 改由 skill 提供
- 移除 `phone_tool` 的 `sensor` 子工具（原 `get_sensor_data`）：传感器读取统一由 `sensor` skill
  走 libandroid.so 完成，可枚举全部传感器、按名称读值，不再受固定 9 种类型与白名单限制
- OpenViking 改为内构自动记忆：`ov_recall_question` / `ov_recall_step` / `ov_commit` 三个开关；
  同一会话强制去重，按历史重建、换会话清空
- 注入消息统一 role=`inject_user`（skill 名单 / OV 召回），默认不在 UI 展示；
  设置「显示注入信息」可在历史会话查看
- 每轮上下文注入合并为单条 `<system-reminder>`：`user_turn`（第 N 轮）→ skill 名单 →
  OV 召回（`<available_openviking>`）→ `user_tips`；设置新增「用户提示」`user_tips`
  （每会话注入一次）
- 工具轮耗尽提示改为 `<user_warning>` 并入库重放；OV 召回去掉边界说明，标记改为 `## 相关记忆`
- 移除设置中的系统提示词配置，固定为内置提示（android-agent 应用助手、可读写
  `/data/user/0/com.androidagent/files/`、中文回答）；每次请求统一置顶，历史旧 system 消息忽略
- 工具 schema 改为完全静态（移除 skill 名单与仓库地址），保护前缀缓存
- Room v7：新增 `sessions.ovSessionId`，用于恢复 OV session
- 新增 `skill_hub` 工具：从远程技能仓库（GitHub / Gitea）查看 / 下载 / 卸载 skill（list / install / remove）
- 新增 `disable` / `enable` 子工具；「设置 → Skill 仓库」可直接列出已安装 skill、开关启用状态（对 LLM 隐藏）、删除，并从远程仓库拉取列表手动下载
- `remove` 直接删除技能文件（卸载）；仅需临时隐藏时用 `disable`（保留文件，可 `enable` 恢复）
- 远程技能安装到 `skills/<名>/` 后自动注册；同名技能为覆盖安装（保留本地 `config.json`）
- 设置新增「Skill 仓库」页：可配置远程仓库（owner/repo）与分支（默认 `abc12524/shell-skills@main`）
- skill_hub 支持 **Gitea** 源：新增类型（`github`/`gitea`，留空按 Gitea 基址自动推断）、Gitea 基址与私有仓库访问令牌配置

## v1.0.0 (2024-xx-xx)

- 初始版本
- AI 对话功能（DeepSeek API + Function Calling）
- 百度搜索/百科查询
- OpenViking 外置记忆系统
- GPS 定位获取
- 传感器数据读取（陀螺仪、加速度计、磁力计等）
- Shell 命令执行
- 系统信息查询
- 聊天记录持久化（Room 数据库）
- 设置页面（API Key 配置）
