# Sillon DJ: prepares the transitions of the DJ mix (beats, stems with Demucs, vocal swap).
# PyTorch built for CUDA 11.8 still runs on older NVIDIA cards (e.g. Quadro M2000); without a usable GPU
# it works on the CPU.
FROM python:3.11-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
  && rm -rf /var/lib/apt/lists/* \
  && ffmpeg -hide_banner -filters | grep -q rubberband
RUN pip install --no-cache-dir torch==2.5.1 --index-url https://download.pytorch.org/whl/cu118
RUN pip install --no-cache-dir demucs==4.1.0 librosa==1.0.1 soundfile
ENV HF_HOME=/models
# The separation model is part of the image: nothing to download at start
RUN python -c "from demucs.pretrained import get_model; get_model('htdemucs')"
COPY sillon_dj.py /app/sillon_dj.py
ENV SILLON_DJ_PORT=8085
EXPOSE 8085
HEALTHCHECK --interval=30s --timeout=5s CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8085/health')"
CMD ["python", "/app/sillon_dj.py"]
