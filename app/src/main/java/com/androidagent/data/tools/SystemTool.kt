package com.androidagent.data.tools

/**
 * 工具族 1：系统与远程执行
 * 对外仅暴露 {action, params}：不传 action 返回子工具清单；仅传 action 返回该子工具用法；
 * 传 action + params 执行（无参子工具仅传 action 即执行）。
 */
class SystemTool : Tool {

    private val shell = ShellTool()
    private val python = PythonTool()

    private val subs = listOf(
        SubTool("shell", "执行 Shell 命令", "shell: 参数 command = 要执行的 Shell 命令"),
        SubTool("python", "执行 Python", "python: 参数 mode = code|script|pip|info（必填）；code（mode=code）、script_path（mode=script）、packages（mode=pip，空格分隔）")
    )

    override val name: String = "system_tool"

    override val description: String =
        "系统执行（SSH/SCP/延时请使用 skill）。子工具：" + subs.joinToString(" / ") { it.name } + "。"

    override val parameters: Map<String, Any> = familyParameters(subs)

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String
        if (action.isNullOrBlank()) return manifestOf(name, subs)
        val sub = subs.firstOrNull { it.name == action }
            ?: return """{"error": "未知子工具: $action"}"""
        val p = familyParams(args)
        if (sub.requiresParams && p.isEmpty()) return usageOf(name, sub)

        return when (action) {
            "shell" -> {
                val cmd = p["command"] as? String ?: return """{"error": "缺少 command 参数"}"""
                shell.execute(mapOf("command" to cmd))
            }
            "python" -> {
                val mode = p["mode"] as? String ?: return """{"error": "缺少 mode 参数"}"""
                python.execute(p + mapOf("action" to mode))
            }
            else -> """{"error": "未知子工具: $action"}"""
        }
    }
}
