import fcntl
import os
import stat
import subprocess
import threading
import time
import urllib.request
import gradio as gr

try:
    import spaces
    gpu_decorator = spaces.GPU
except Exception:
    def gpu_decorator(fn):
        return fn

os.environ["GRADIO_SSR_MODE"] = "false"

RELEASE_BASE = os.environ.get(
    "FALKEAI_RELEASE_BASE",
    "https://github.com/komfalcon/falkeai-bot/releases/download/latest",
)

bot_status = {
    "state": "Starting...",
    "started_at": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime()),
}
_started = False
_lock = threading.Lock()
_lock_file = None


def log(line: str):
    text = line.rstrip()
    if text:
        print(text, flush=True)
        if "WhatsApp connected" in text:
            bot_status["state"] = "Connected & Active (24/7)"
        elif "PAIRING CODE:" in text:
            bot_status["state"] = "Waiting for WhatsApp Pairing Code (Check Space 'Logs' tab at the top)"


def download_binary(name: str):
    url = f"{RELEASE_BASE}/{name}"
    log(f"[launcher] Downloading {name} from {url} ...")
    req = urllib.request.Request(url, headers={"User-Agent": "FalkeAI-Launcher/1.0"})
    with urllib.request.urlopen(req, timeout=60) as resp, open(name, "wb") as out:
        out.write(resp.read())
    st = os.stat(name)
    os.chmod(name, st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    log(f"[launcher] {name} ready ({st.st_size} bytes).")


def supervisor_loop():
    while True:
        try:
            if not os.path.exists("wa-bridge") or os.path.getsize("wa-bridge") < 1024:
                download_binary("wa-bridge")
            if not os.path.exists("falkeai-core") or os.path.getsize("falkeai-core") < 1024:
                download_binary("falkeai-core")

            env = os.environ.copy()
            env["WA_BRIDGE_BIN"] = "./wa-bridge"
            env["PORT"] = "0"

            log("[launcher] Starting FalkeAI Rust Core (./falkeai-core)...")
            proc = subprocess.Popen(
                ["./falkeai-core"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                env=env,
            )
            for line in proc.stdout:
                log(line)
            proc.wait()
            bot_status["state"] = f"Restarting (exit {proc.returncode})..."
            log(f"[launcher] falkeai-core exited with code {proc.returncode}. Restarting in 5s...")
        except Exception as exc:
            bot_status["state"] = f"Waiting for GitHub Release binaries ({exc})..."
            log(f"[launcher] Waiting for binaries ({exc}). Retrying in 15s...")
            time.sleep(10)
        time.sleep(5)


def ensure_supervisor():
    global _started, _lock_file
    with _lock:
        if _started:
            return
        _started = True
        try:
            _lock_file = open("/tmp/falkeai_supervisor.lock", "w")
            fcntl.flock(_lock_file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            return
        threading.Thread(target=supervisor_loop, daemon=True).start()


def get_status():
    ensure_supervisor()
    return f"Status: {bot_status['state']}\nInstance Booted: {bot_status['started_at']}"


@gpu_decorator
def gpu_healthcheck():
    return f"ZeroGPU Runtime Active — {bot_status['state']}"


with gr.Blocks(title="FalkeAI — Personal Symbiotic Agent") as demo:
    gr.Markdown(
        "# 🦅 FalkeAI — Falcon's Personal Symbiotic Agent\n"
        "Powered by **Rust Core (`falkeai-core`) + Go `whatsmeow` Bridge (`wa-bridge`) + Turso + Cloudflare Workers AI**.\n\n"
        "> **Owner Note:** Open the **Logs** button at the top of this Space to view your 8-character WhatsApp Pairing Code."
    )
    status_box = gr.Textbox(label="System Status", value=get_status, lines=3, interactive=False)
    with gr.Row():
        refresh_btn = gr.Button("Refresh Status")
        gpu_btn = gr.Button("ZeroGPU Health Check")
    refresh_btn.click(fn=get_status, outputs=status_box)
    gpu_btn.click(fn=gpu_healthcheck, outputs=status_box)

if __name__ == "__main__":
    ensure_supervisor()
    demo.launch(ssr_mode=False)
