import os
import stat
import subprocess
import threading
import time
import urllib.request
import gradio as gr

try:
    import spaces  # noqa: F401
except ImportError:
    spaces = None

RELEASE_BASE = os.environ.get(
    "FALKEAI_RELEASE_BASE",
    "https://github.com/komfalcon/falkeai-bot/releases/download/latest",
)

bot_status = {"state": "Starting...", "started_at": time.strftime("%Y-%m-%d %H:%M:%S UTC", time.gmtime())}


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
    urllib.request.urlretrieve(url, name)
    st = os.stat(name)
    os.chmod(name, st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    log(f"[launcher] {name} ready ({st.st_size} bytes).")


def supervisor_loop():
    while True:
        try:
            if not os.path.exists("wa-bridge"):
                download_binary("wa-bridge")
            if not os.path.exists("falkeai-core"):
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
            bot_status["state"] = "Waiting for GitHub Release binaries..."
            log(f"[launcher] Error: {exc}. Retrying in 10s...")
            time.sleep(5)
        time.sleep(5)


threading.Thread(target=supervisor_loop, daemon=True).start()


def get_status():
    return f"Status: {bot_status['state']}\nInstance Booted: {bot_status['started_at']}"


with gr.Blocks(title="FalkeAI — Personal Symbiotic Agent") as demo:
    gr.Markdown(
        "# 🦅 FalkeAI — Falcon's Personal Symbiotic Agent\n"
        "Powered by **Rust Core (`falkeai-core`) + Go `whatsmeow` Bridge (`wa-bridge`) + Turso + Cloudflare Workers AI**.\n\n"
        "> **Owner Note:** Open the **Logs** button at the top of this Space to view your 8-character WhatsApp Pairing Code."
    )
    status_box = gr.Textbox(label="System Status", value=get_status, lines=3, interactive=False)
    refresh_btn = gr.Button("Refresh Status")
    refresh_btn.click(fn=get_status, outputs=status_box)

if __name__ == "__main__":
    demo.launch()
