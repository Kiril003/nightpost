"""Оболонка тіла ПК: білий список, без sudo, без мережі, у пісочниці.

Команда — це argv (shlex), не рядок для `sh -c`: конвеєрів, перенаправлень і
підстановок немає за побудовою; токени `|`, `>`, `;`, `&&` відмовляються
чесно, щоб людина не думала, що вони спрацювали.

Дозволено лише те, що дивиться, а не змінює: ls, cat, grep, find, git status…
Програми, що вміють запускати інші програми або писати файли через опції
(`find -exec/-delete`, `rg --pre`, `sort -o`, `git -c`/`--ext-diff`…), —
опції відмовляються. Будь-який аргумент-шлях, що існує, мусить лежати в
схваленій теці й не бути секретом. Робоча тека — лише схвалена.

На Linux з `bwrap` команда живе в пісочниці: без мережі (`--unshare-net`),
корінь лише для читання, схвалені теки — лише для читання, секретні теки
всередині них закриті порожнім tmpfs. Без bwrap (Windows, macOS) — білий
список і чисте середовище. «Стоп усе» вбиває запущену команду.
Кожна команда йде в стрічку «Що я роблю» (через `gate`).
"""
from __future__ import annotations

import asyncio
import os
import shlex
import shutil
import sys
import time
from pathlib import Path
from typing import Any

from symbiote.pc.backend import PcRefused
from symbiote.pc.files import resolve, roots
from symbiote.pc.guard import secret_path

TIMEOUT_S = 20.0
OUTPUT_CAP = 64 * 1024

ALLOW_POSIX = {
    "ls", "cat", "head", "tail", "wc", "grep", "rg", "find", "du", "df", "stat", "file",
    "date", "uname", "uptime", "free", "whoami", "pwd", "echo", "sort", "uniq", "cut",
    "tree", "git", "which", "ps", "lsblk", "md5sum", "sha256sum", "diff", "basename",
    "dirname", "realpath", "nproc", "id", "hostname", "cal", "column", "nl", "tac", "jq",
}
ALLOW_WINDOWS = {"where", "findstr", "git", "tasklist", "whoami", "hostname", "systeminfo", "sort"}

#: Окремо — щоб відмова казала правду, чому саме.
PRIVILEGE = {"sudo", "su", "doas", "pkexec", "runas", "run0", "sudoedit"}
NETWORK = {"ip", "ifconfig", "nmcli", "nmtui", "iwctl", "iw", "iwconfig", "wpa_cli", "iptables",
           "ip6tables", "nft", "ufw", "firewall-cmd", "route", "netsh", "resolvectl", "wg",
           "wg-quick", "tailscale", "networkctl", "hostnamectl", "curl", "wget", "ssh", "scp",
           "sftp", "nc", "ncat", "netcat", "socat", "rsync", "ftp", "telnet"}

_META = {"|", "||", "&", "&&", ";", ">", ">>", "<", "<<", "2>", "2>&1", "&>", "$(", "`"}

_BAD_OPTS = {
    "find": ("-exec", "-execdir", "-ok", "-okdir", "-delete", "-fprint", "-fprintf", "-fls", "-fprint0"),
    "rg": ("--pre", "--pre-glob", "--search-zip", "-z"),
    "sort": ("-o", "--output", "--compress-program"),
    "tree": ("-o",),
    "git": ("-c", "--config-env", "--exec-path", "--ext-diff", "--output", "--upload-pack",
            "--receive-pack", "-o"),
    "jq": ("--rawfile", "--slurpfile"),
    "diff": ("--to-file", "--from-file"),
}
_GIT_READ = {"status", "log", "diff", "show", "branch", "rev-parse", "ls-files", "blame",
             "describe", "shortlog", "tag", "remote", "grep", "ls-tree", "cat-file"}

_SECRET_DIRS_IN_ROOT = (".ssh", ".gnupg", ".password-store", ".aws", ".pki", ".local/share/keyrings",
                        ".mozilla", ".config/chromium", ".config/google-chrome", ".docker", ".kube")

_running: set[asyncio.subprocess.Process] = set()


def allowlist() -> set[str]:
    return ALLOW_WINDOWS if sys.platform.startswith("win") else ALLOW_POSIX


def check(cmd: str, cwd: Path) -> list[str]:
    """Розібрати й перевірити. Повертає argv або кидає PcRefused з причиною."""
    try:
        argv = shlex.split(cmd, posix=not sys.platform.startswith("win"))
    except ValueError as exc:
        raise PcRefused(f"не розібрав команду: {exc}") from exc
    if not argv:
        raise PcRefused("порожня команда")
    prog = Path(argv[0]).name.lower()
    if prog.endswith(".exe"):
        prog = prog[:-4]
    if "/" in argv[0] or "\\" in argv[0]:
        raise PcRefused("програму — лише на ім'я, без шляху")
    if prog in PRIVILEGE:
        raise PcRefused("підвищення прав (sudo й подібні) тілу ПК заборонене")
    if prog in NETWORK:
        raise PcRefused("мережеві команди тілу ПК заборонені")
    if prog not in allowlist():
        raise PcRefused(f"«{prog}» немає в білому списку оболонки PHANTOM")
    for token in argv[1:]:
        if token in _META or token.startswith(("$(", "`")) or token.endswith(("|", ";", "&")):
            raise PcRefused("конвеєри, перенаправлення й підстановки тут не працюють — лише одна команда")
    bad = _BAD_OPTS.get(prog, ())
    for token in argv[1:]:
        if any(token == b or token.startswith(b + "=") for b in bad):
            raise PcRefused(f"опція «{token}» у {prog} може запускати програми чи писати файли")
    if prog == "git":
        sub = next((t for t in argv[1:] if not t.startswith("-")), "")
        if sub not in _GIT_READ:
            raise PcRefused(f"git {sub or '…'}: дозволено лише читати (status, log, diff, show…)")
        if sub in ("branch", "tag", "remote") and any(
            t in ("-d", "-D", "-m", "-M", "--delete", "add", "remove", "rm", "rename", "set-url", "-f")
            for t in argv[2:]
        ):
            raise PcRefused(f"git {sub}: лише перегляд")
    try:
        from linux.dangerous_patterns import find_violation

        hit = find_violation(cmd)
    except Exception:  # noqa: BLE001
        hit = None
    if hit:
        raise PcRefused(f"небезпечний шаблон: {hit}")
    for token in argv[1:]:
        if token.startswith("-") and "=" not in token:
            continue
        value = token.split("=", 1)[1] if token.startswith("-") else token
        if not value:
            continue
        candidate = Path(value).expanduser()
        if not candidate.is_absolute():
            candidate = cwd / candidate
        looks_path = value.startswith(("/", "~", ".")) or os.sep in value or candidate.exists()
        if not looks_path:
            continue
        real = candidate.resolve(strict=False)
        if real.exists() or value.startswith(("/", "~")):
            allowed = roots.list()
            if not any(real == r or r in real.parents for r in allowed):
                raise PcRefused(f"«{value}» поза схваленими теками")
            why = secret_path(real)
            if why:
                raise PcRefused(why)
    return argv


def _bwrap_argv(argv: list[str], cwd: Path) -> list[str]:
    out = ["bwrap", "--die-with-parent", "--unshare-all", "--new-session",
           "--ro-bind", "/", "/", "--dev", "/dev", "--proc", "/proc", "--tmpfs", "/tmp"]
    home = Path.home()
    out += ["--tmpfs", str(home)]  # дім закрито, крім схвалених тек
    for root in roots.list():
        out += ["--ro-bind", str(root), str(root)]
        for secret in _SECRET_DIRS_IN_ROOT:
            hidden = root / secret
            if hidden.is_dir():
                out += ["--tmpfs", str(hidden)]
    # Дані PHANTOM (стрічка, вимикач, ключі) — після коренів: корінь, що їх
    # містить, не має їх відкрити.
    try:
        from paths import data_root

        data = data_root().resolve()
        if data.is_dir():
            out += ["--tmpfs", str(data)]
    except Exception:  # noqa: BLE001
        pass
    out += ["--chdir", str(cwd), "--setenv", "HOME", str(cwd), "--"]
    return out + argv


def _env(cwd: Path) -> dict[str, str]:
    env = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "LANG": os.environ.get("LANG", "C.UTF-8"),
        "HOME": str(cwd),
        "TERM": "dumb",
        "PAGER": "cat",
        "GIT_PAGER": "cat",
        "GIT_CONFIG_NOSYSTEM": "1",
        "GIT_CONFIG_GLOBAL": os.devnull,
        "GIT_TERMINAL_PROMPT": "0",
        "NO_COLOR": "1",
    }
    if sys.platform.startswith("win"):
        env["SYSTEMROOT"] = os.environ.get("SYSTEMROOT", r"C:\Windows")
    return env


def _git_hardening(argv: list[str]) -> list[str]:
    """Локальний .git/config може оголосити fsmonitor чи pager, що запускають
    програми навіть на `git status`. Перекриваємо їх своїми -c ДО підкоманди."""
    if Path(argv[0]).name.lower().removesuffix(".exe") != "git":
        return argv
    return [argv[0], "-c", "core.fsmonitor=false", "-c", "core.pager=cat", "-c", "core.hooksPath=" + os.devnull,
            "-c", "diff.external=", "-c", "core.sshCommand=false", "--no-pager", *argv[1:]]


def sandbox_kind() -> str:
    if sys.platform.startswith("linux") and shutil.which("bwrap"):
        return "bwrap"
    return "allowlist"


async def run(cmd: str, cwd_raw: str = "") -> dict[str, Any]:
    allowed = roots.list()
    if not allowed:
        raise PcRefused("оболонка працює лише в схвалених теках, а їх ще немає")
    cwd = resolve(cwd_raw) if cwd_raw else allowed[0]
    if not cwd.is_dir():
        raise PcRefused("робоча тека — не тека")
    argv = _git_hardening(check(cmd, cwd))
    kind = sandbox_kind()
    final = _bwrap_argv(argv, cwd) if kind == "bwrap" else argv
    from symbiote.ladder import stop_switch

    t0 = time.monotonic()
    try:
        proc = await asyncio.create_subprocess_exec(
            *final, cwd=str(cwd), env=_env(cwd),
            stdin=asyncio.subprocess.DEVNULL, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.STDOUT, start_new_session=not sys.platform.startswith("win"),
        )
    except FileNotFoundError as exc:
        raise PcRefused(f"на комп'ютері немає «{argv[0]}»") from exc
    _running.add(proc)
    async def read_all() -> bytes:
        # `read(n)` віддає перший шматок, а не n байтів: читаємо до кінця або до стелі.
        chunks: list[bytes] = []
        size = 0
        while size <= OUTPUT_CAP:
            chunk = await proc.stdout.read(OUTPUT_CAP + 1 - size)  # type: ignore[union-attr]
            if not chunk:
                break
            chunks.append(chunk)
            size += len(chunk)
        return b"".join(chunks)

    reader = asyncio.ensure_future(read_all())
    stopped = timed_out = False
    try:
        while not reader.done():
            if stop_switch.is_on():
                stopped = True
                break
            if time.monotonic() - t0 > TIMEOUT_S:
                timed_out = True
                break
            await asyncio.wait({reader}, timeout=0.15)
        if stopped or timed_out:
            _kill(proc)
        out = await reader if reader.done() else b""
        if not reader.done():
            reader.cancel()
        try:
            code = await asyncio.wait_for(proc.wait(), timeout=3.0)
        except asyncio.TimeoutError:
            _kill(proc)
            code = -9
    finally:
        _running.discard(proc)
    if stopped:
        raise PcRefused("зупинено власником («Стоп усе») посеред команди")
    text = out[:OUTPUT_CAP].decode("utf-8", errors="replace")
    return {
        "argv": argv,
        "cwd": str(cwd), "exit": code, "output": text,
        "truncated": len(out) > OUTPUT_CAP, "timed_out": timed_out,
        "sandbox": kind, "elapsed_ms": int((time.monotonic() - t0) * 1000),
    }


def _kill(proc: asyncio.subprocess.Process) -> None:
    if proc.returncode is not None:
        return
    try:
        if not sys.platform.startswith("win"):
            import signal

            os.killpg(proc.pid, signal.SIGKILL)
        else:
            proc.kill()
    except (ProcessLookupError, PermissionError, OSError):
        try:
            proc.kill()
        except ProcessLookupError:
            pass


def kill_all() -> int:
    """«Стоп усе»: усе, що оболонка ще виконує, — зараз же."""
    n = 0
    for proc in list(_running):
        _kill(proc)
        n += 1
    return n


def summary(cmd: str) -> str:
    """Рядок для стрічки: сама команда, обрізана, без вмісту виводу."""
    return cmd.strip().replace("\n", " ")[:200]


__all__ = ["check", "run", "kill_all", "sandbox_kind", "allowlist", "summary"]
