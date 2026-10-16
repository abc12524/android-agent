# Changelog

## Unreleased

- 新增 `skill_hub` 工具：从远程技能仓库（GitHub）查看 / 下载 / 卸载 skill（list / install / remove）
- `remove` 默认仅下线（改头部 `# status: disable`，保留文件，可重新 install 恢复）；`hard=true` 才真正删除
- skill 加载器支持**文件夹式布局**（`skills/<名>/skill.py` + `_` 助手 + `config.json`），与扁平 `skills/<名>.py` 混用
- 远程技能安装到 `skills/<名>/` 后自动注册；同名技能为覆盖安装（保留本地 `config.json`）
- 设置新增「Skill 仓库」页：可配置远程仓库（owner/repo）与分支（默认 `abc12524/shell-skills@main`）

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
