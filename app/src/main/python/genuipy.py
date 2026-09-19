"""GenUI Python 运行入口 —— Kotlin 侧经 Chaquopy 调用这里。

    exec_python(code)   执行完整脚本，print/报错实时捕获
    request_cancel()    中断当前脚本（settrace 协作式取消）
    version_info()      版本字符串
"""
import sys
import threading
import time
import traceback

_cancel = threading.Event()

# ------------------------------------------------ 输出捕获（stdout/stderr → 桥日志）
class ConsoleWriter:
    def __init__(self, stream, push):
        self._stream = stream
        self._push = push
        self._buf = []

    def write(self, s):
        if s:
            self._buf.append(s)
        return len(s)

    def flush(self):
        if self._buf:
            self._push(self._stream, "".join(self._buf))
            self._buf = []

    def isatty(self):
        return False

    def __iter__(self):
        return iter([])

# ------------------------------------------------ 取消
def _tracer(frame, event, arg):
    if _cancel.is_set():
        raise KeyboardInterrupt("用户取消")
    return _tracer

def request_cancel():
    _cancel.set()

def version_info():
    return "{} · Chaquopy".format(sys.version.split()[0])

# ------------------------------------------------ 核心：exec
def exec_python(code: str, push) -> str:
    """执行完整脚本；push(stream, text) 回调实时输出；返回总结行"""
    _cancel.clear()
    out_w, err_w = ConsoleWriter("out", push), ConsoleWriter("err", push)
    old_out, old_err = sys.stdout, sys.stderr
    sys.stdout, sys.stderr = out_w, err_w
    g = {"__name__": "__main__", "__file__": "<genui>", "__builtins__": __builtins__}
    t0 = time.time()
    status = "完成"
    try:
        try:
            compiled = compile(code, "<脚本>", "exec")
        except SyntaxError:
            traceback.print_exc(limit=0)
            status = "语法错误"
            return "语法错误 · %.2fs" % (time.time() - t0)
        sys.settrace(_tracer)
        try:
            exec(compiled, g)
        except KeyboardInterrupt:
            status = "已取消"
            traceback.print_exc(limit=1)
        finally:
            sys.settrace(None)
    except SystemExit as e:
        status = "退出(%s)" % (e.code if e.code is not None else 0)
    except BaseException:
        status = "异常"
        traceback.print_exc()
    finally:
        sys.stdout, sys.stderr = old_out, old_err
        out_w.flush()
        err_w.flush()
    return "{} · %.2fs".format(status) % (time.time() - t0)
