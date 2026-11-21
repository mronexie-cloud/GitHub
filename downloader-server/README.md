# 万能下载 · 网页版服务器

iPhone / 网页版的万能下载靠这台服务器解析和下载视频（网页不能直接读抖音、TikTok 等网站）。
引擎和安卓版一样是 yt-dlp；抖音用它的公开分享页读取无水印视频。

## 部署到 Render（免费）

1. 打开 https://render.com ，用 GitHub 账号注册 / 登录。
2. 右上角 **New → Web Service**，选仓库 `mronexie-cloud/GitHub`（第一次要点授权 Render 读这个仓库）。
3. 按下面填，其他保持默认：
   - Name：`vdl`（随意，会出现在地址里）
   - Branch：`main`
   - Root Directory：`downloader-server`
   - Language / Runtime：`Docker`
   - Instance Type：**Free**
4. 点 **Deploy Web Service**，等 3～5 分钟显示 Live。
5. 复制页面左上方的地址，比如 `https://vdl-xxxx.onrender.com`。
   - 直接用 Safari 打开这个地址就是万能下载网页版；
   - 或者在苹果版首页打开「万能下载」，把地址填到最下面的「⚙︎ 服务器地址」（只填一次）。

## 说明

- 免费服务器 15 分钟没人用会休眠，再次使用时第一次要等大约 1 分钟唤醒。
- 每次启动都会自动更新到最新的 yt-dlp。
- YouTube、Instagram 有时会拦截服务器（要求登录），这种视频请用安卓版（可以在 App 里登录后下载）。
- 服务器不保存任何视频，下载完立刻删除临时文件。
