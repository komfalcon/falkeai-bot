import os
import stat
import subprocess
import threading
import time
import urllib.request
from collections import deque
import gradio as gr

RELEASE_BASE = os.environ.get(
    "FALKEAI_RELEASE_BASE",
    "https://github.com/komfalcon/falkeai-bot/releases/download/latest",
)

log_buffer = deque(maxlen=200)


def append_log(line: str):
    text = line.rstrip()
    if text:
        print(text, flush=True)
        log_buffer.append(text)


def download_binary(name: str):
    url = f"{RELEASE_BASE}/{name}"
    append_log(f"[launcher] Downloading {name} from {url} ...")
    urllib.request.urlretrieve(url, name)
    st = os.stat(name)
    os.chmod(name, st.st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)
    append_log(f"[launcher] {name} ready ({st.st_size} bytes).")


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

            append_log("[launcher] Starting FalkeAI Rust Core (./falkeai-core)...")
            proc = subprocess.Popen(
                ["./falkeai-core"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=1,
                env=env,
            )
            for line in proc.stdout:
                append_log(line)
            proc.wait()
            append_log(f"[launcher] falkeai-core exited with code {proc.returncode}. Restarting in 5s...")
        except Exception as exc:
            append_log(f"[launcher] Error: {exc}. Retrying in 10s...")
            time.sleep(5)
        time.sleep(5)


threading.Thread(target=supervisor_loop, daemon=True).start()


def get_logs():
    return "\n".join(log_buffer) if log_buffer else "Starting FalkeAI..."


with gr.Blocks(title="FalkeAI — Personal Symbiotic Agent") as demo:
    gr.Markdown(
        "# 🦅 FalkeAI — Falcon's Personal Symbiotic Agent\n"
        "Running **Rust Core (`falkeai-core`) + Go `whatsmeow` Bridge (`wa-bridge`) + Turso**."
    )
    refresh_btn = gr.Button("Refresh Live Logs / Pairing Code")
    logs_box = gr.Textbox(label="Live Logs", value=get_logs, lines=22, interactive=False)
    refresh_btn.click(fn=get_logs, outputs=logs_box)

if __name__ == "__main__":
    demo.launch(server_name="0.0.0.0", server_port=int(os.environ.get("PORT", "7860")))
