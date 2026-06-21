"""
Android Agent - Python 脚本入口
通过 Chaquopy 嵌入的 CPython 解释器运行
"""

import sys
import os
import json
import traceback
import subprocess
from pathlib import Path

# pip 镜像源：清华大学 TUNA（中国大陆加速）
PIP_INDEX_URL = "https://pypi.tuna.tsinghua.edu.cn/simple"
PIP_TRUSTED_HOST = "pypi.tuna.tsinghua.edu.cn"


def execute_python(code: str) -> str:
    """执行 Python 代码片段并返回结果"""
    local_vars = {}
    try:
        exec(code, {"__builtins__": __builtins__}, local_vars)
        return json.dumps({"success": True, "result": "执行完成"}, ensure_ascii=False)
    except Exception as e:
        tb = traceback.format_exc()
        return json.dumps({
            "success": False, "error": str(e), "traceback": tb
        }, ensure_ascii=False)


def execute_script(script_path: str, args: list = None) -> str:
    """运行指定路径的 Python 脚本文件"""
    try:
        import runpy
        runpy.run_path(script_path, init_globals={"__args__": args or []})
        return json.dumps({"success": True, "result": "脚本执行完成"}, ensure_ascii=False)
    except Exception as e:
        tb = traceback.format_exc()
        return json.dumps({
            "success": False, "error": str(e), "traceback": tb
        }, ensure_ascii=False)


def pip_install(packages: str, cache_dir: str = "") -> str:
    """
    通过 pip 安装 Python 包，使用清华镜像源。
    packages: 空格分隔的包名列表
    cache_dir: pip 缓存目录（可选，默认使用 Chaquopy 内置缓存）
              下载的 .whl/.tar.gz 会缓存在此目录
    """
    try:
        pkg_list = packages.strip().split()
        if not pkg_list:
            return json.dumps({"success": False, "error": "未指定包名"}, ensure_ascii=False)

        cmd = [
            sys.executable, "-m", "pip", "install",
            "-i", PIP_INDEX_URL,
            "--trusted-host", PIP_TRUSTED_HOST,
        ] + pkg_list

        env = os.environ.copy()
        if cache_dir:
            cache_path = Path(cache_dir) / "pip_cache"
            cache_path.mkdir(parents=True, exist_ok=True)
            cmd.extend(["--cache-dir", str(cache_path)])
            env["PIP_CACHE_DIR"] = str(cache_path)

        result = subprocess.run(
            cmd, capture_output=True, text=True, timeout=180,
            env=env
        )
        out = result.stdout.strip()
        err = result.stderr.strip()

        if result.returncode == 0:
            installed = [p for p in pkg_list if p.lower() in out.lower()]
            msg = f"已安装: {', '.join(installed) if installed else packages}"
            if cache_dir:
                msg += f"\n缓存目录: {cache_path}"
            return json.dumps({"success": True, "result": msg}, ensure_ascii=False)
        else:
            return json.dumps({
                "success": False, "error": err or "pip install 失败",
                "stdout": out
            }, ensure_ascii=False)
    except subprocess.TimeoutExpired:
        return json.dumps({
            "success": False, "error": "pip install 超时（180秒）"
        }, ensure_ascii=False)
    except Exception as e:
        tb = traceback.format_exc()
        return json.dumps({
            "success": False, "error": str(e), "traceback": tb
        }, ensure_ascii=False)


def system_info() -> str:
    """返回 Python 环境信息（含已安装包和缓存状态）"""
    try:
        result = subprocess.run(
            [sys.executable, "-m", "pip", "list", "--format=json"],
            capture_output=True, text=True, timeout=15
        )
        pip_packages = json.loads(result.stdout) if result.returncode == 0 else []
    except Exception:
        pip_packages = []

    info = {
        "python_version": sys.version,
        "platform": sys.platform,
        "pip_index": PIP_INDEX_URL,
        "pip_packages": pip_packages
    }
    return json.dumps({"success": True, "result": info}, ensure_ascii=False, indent=2)
