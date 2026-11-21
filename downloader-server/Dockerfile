# 万能下载 网页版服务器（Render 免费版可用）
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY . /app
# Always start with the newest yt-dlp: sites change often and yt-dlp follows them.
CMD pip install --no-cache-dir -q -U yt-dlp 2>/dev/null; python server.py
