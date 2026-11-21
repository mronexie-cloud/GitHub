# 环球收款 · 第 2 期服务器（沙盒）

App 默认是演示版，所有数据都是本机模拟的。部署这个服务器、再在 App 的「我的 → 连接服务器」填入地址和令牌后，就进入**沙盒模式**：

| 功能 | 沙盒模式走哪里 |
|---|---|
| 全球收款账户（显示真实沙盒账号）、模拟入账、电商回款 | Airwallex 沙盒（Global Accounts + 存款模拟接口） |
| 收款链接 / 账单 | Stripe 测试环境（Checkout）；没配 Stripe 就用 Airwallex 付款链接 |
| 换汇 | Airwallex 沙盒报价 + 换汇（报价锁定 1 分钟） |
| 汇款 / 提现 | Airwallex 沙盒 Transfers（收款人要先在 Airwallex 后台建好） |
| 加密货币收款 | BTCPay Server 测试网（Greenfield API） |
| 对账 | 账本余额 vs Airwallex 沙盒余额 |
| 实名认证 | 仍然是模拟流程 |

任何一家没配密钥，那一块就自动用服务器内置的模拟器，App 照样能用。服务器**只接受测试密钥**：Stripe 必须是 `sk_test_` 开头，Airwallex 不能指向正式环境，否则启动时直接报错。

## 1. 申请沙盒账号（都免费）

1. **Airwallex 沙盒**：在 Airwallex 开发者页面注册沙盒账号 → 后台「设置 → 开发者 → API 密钥」拿到 Client ID 和 API Key。在「账户 → 全球账户」开一个 USD（以及想用的其他币种）全球账户。想测汇款，就在「付款 → 收款人」新增收款人，记下收款人 ID。
2. **Stripe 测试环境**：注册 Stripe → 打开「测试模式」→「开发者 → API 密钥」拿到 `sk_test_…`。
3. **BTCPay 测试网**：在 `https://testnet.demo.btcpayserver.org` 注册 → 建一个商店并设置测试网钱包 →「账户 → API 密钥」生成密钥（勾选查看 / 创建 / 修改发票、查看商店），记下商店 ID。

## 2. 部署到 Render（免费）

1. 登录 Render → New → **Blueprint** → 选择 `mronexie-cloud/GitHub` 仓库，分支 `crosspay`。Render 会读取根目录的 `render.yaml`。
2. 按提示填入上面拿到的密钥（不用的可以留空）。`APP_TOKEN` 会自动生成，在服务的 Environment 页面能看到，App 连接时要用。
3. 部署完成后会得到地址，比如 `https://crosspay-server-xxxx.onrender.com`。

> 免费实例闲置 15 分钟会休眠，第一次打开要等几十秒；而且免费实例**没有持久磁盘**，重新部署或休眠重启后账本会清空。只是测试问题不大；想保留数据，可以升级成付费实例并挂一个磁盘到 `/data`。

## 3. 配置 Webhook（让到账自动同步）

不配也能用：服务器每 20 秒会主动查一次未完成的账单、汇款和加密收款。配了会更及时。

| 通道 | Webhook 地址 | 订阅事件 | 签名密钥填到 |
|---|---|---|---|
| Stripe | `https://你的地址/webhooks/stripe` | `checkout.session.completed` | `STRIPE_WEBHOOK_SECRET`（`whsec_…`） |
| Airwallex | `https://你的地址/webhooks/airwallex` | 存款、转账（payout.transfer.*）、payment_intent.succeeded | `AIRWALLEX_WEBHOOK_SECRET` |
| BTCPay | `https://你的地址/webhooks/btcpay` | InvoiceSettled 等发票事件 | `BTCPAY_WEBHOOK_SECRET` |

三家的签名都会校验（Stripe：`Stripe-Signature`，5 分钟内有效；Airwallex：`x-timestamp` + `x-signature`；BTCPay：`BTCPay-Sig`），签名不对一律拒绝。服务器不会只信 Webhook 内容，收到通知后会再向通道查一次真实状态，同一笔钱只会入账一次。

## 4. 在 App 里连接

「我的 → 连接服务器」→ 填地址和 `APP_TOKEN` → 测试并连接。顶部标签会从「演示版」变成「沙盒」，「资金通道」里能看到每家是「沙盒 / 测试网」还是「服务器模拟」。

- 收款链接：点「打开付款页」，在 Stripe 测试页用卡号 `4242 4242 4242 4242`（任意未来日期、任意 CVC）付款，回到 App 点「刷新付款状态」。
- 汇款：第一次给某个收款人汇款时，App 会让你填 Airwallex 收款人 ID。
- 断开服务器就回到本机演示版，演示数据还在。

## 环境变量

| 变量 | 说明 |
|---|---|
| `APP_TOKEN` | App 访问令牌（必填，不填就每次启动随机生成） |
| `PORT` / `DATA_DIR` | 端口（默认 8080）/ 账本保存目录（默认 `./data`，Docker 里是 `/data`） |
| `AIRWALLEX_CLIENT_ID` / `AIRWALLEX_API_KEY` | Airwallex 沙盒密钥 |
| `AIRWALLEX_BASE` | 默认 `https://api.sandbox.airwallex.com` |
| `AIRWALLEX_TRANSFER_REASON` | 汇款用途代码，默认 `family_support`；Airwallex 不认可时按它的报错改 |
| `STRIPE_SECRET_KEY` | Stripe 测试密钥 `sk_test_…` |
| `BTCPAY_URL` / `BTCPAY_API_KEY` / `BTCPAY_STORE_ID` | BTCPay 测试网 |
| `*_WEBHOOK_SECRET` | 三家的 Webhook 签名密钥 |
| `PUBLIC_URL` | 服务器公网地址（Render 上自动获取），用于 Stripe 付款后的跳转页 |

## 本地运行和测试

```bash
node crosspay-server/src/server.js          # 不配密钥 = 全模拟
node --test tests/crosspay-server.test.js   # 用本地假的 Airwallex / Stripe / BTCPay 跑完整流程
docker build -f crosspay-server/Dockerfile .
```

不需要安装任何依赖，Node 20 以上即可。
