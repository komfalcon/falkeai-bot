# FalkeAI Own-Voice Training Guide ($0 Cost)

Train FalkeAI to speak in **your own voice** (including your exact **"Fall-K"** cadence) for **$0**, running **100% offline** on your Infinix Hot 10T CPU.

## 4 Simple Steps

1. **Record Your Script in the App (Split Across Sessions Anytime)**
   - Open **FalkeAI -> Voice -> Clone Voice**.
   - Record through the 8 chapters (120 prompts total, ~15 prompts per chapter — progress auto-saves after every clip).
   - Tip: Record in a quiet room holding the phone ~18 cm from your mouth at a natural, relaxed speaking volume.
   - Tap **Export Dataset ZIP (`falke_voice_dataset.zip`)** when ready (60+ clips already gives a recognizable voice; all 120 gives the cleanest clarity).

2. **Fine-Tune Free on Google Colab or Kaggle GPU (~35 Minutes, $0)**
   - Open [`train_falcon_voice_colab_kaggle.ipynb`](./train_falcon_voice_colab_kaggle.ipynb) in **Google Colab** (Runtime -> **T4 GPU**) or **Kaggle** (Accelerator -> **GPU T4 x2**).
   - Upload `falke_voice_dataset.zip` to `/content/falke_voice_dataset.zip`.
   - Click **Run All**.

3. **Download `falcon_custom_voice.zip`**
   - When Cell 5 finishes, download `/content/falcon_custom_voice.zip` to your phone.

4. **Import Into FalkeAI (1 Tap)**
   - Open **FalkeAI -> Voice -> Clone Voice -> Import Voice ZIP** and pick `falcon_custom_voice.zip`.
   - Tap **Test "Fall-K"** — FalkeAI now speaks in your own voice offline!
