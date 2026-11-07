# Source data for the 东南亚旅游攻略 app: regions, attractions, curated routes and start towns.
# build.py turns this into the app's data file, adding Wikipedia coordinates/photos and road times.
#
# Prices: researched November 2026. Each priced place keeps its source ("src") and the source's date ("asof").
# approx=True marks reseller, blog or tour-operator figures that could not be confirmed with an official page.
# None means the price could not be verified; the app then says so instead of showing a number.

# ---------- regions: key, country, name, lat, lng, airport (IATA) or None, land group, ferry access
# group: places in the same group are connected by road. "main" = Peninsular Malaysia + Singapore + mainland Thailand.
# ferry: (gateway town name, gateway lat, gateway lng, hours on the boat)
REGIONS = [
    # Malaysia
    ("kl", "MY", "吉隆坡", 3.1478, 101.6953, "KUL", "main", None),
    ("putrajaya", "MY", "布城", 2.9264, 101.6964, None, "main", None),
    ("kselangor", "MY", "瓜拉雪兰莪", 3.3400, 101.2550, None, "main", None),
    ("genting", "MY", "云顶高原", 3.4236, 101.7932, None, "main", None),
    ("fraser", "MY", "福隆港", 3.7120, 101.7380, None, "main", None),
    ("cameron", "MY", "金马伦高原", 4.4720, 101.3800, None, "main", None),
    ("ipoh", "MY", "怡保", 4.5975, 101.0901, "IPH", "main", None),
    ("taiping", "MY", "太平", 4.8500, 100.7400, None, "main", None),
    ("pangkor", "MY", "邦咯岛", 4.2160, 100.5600, None, "pangkor", ("红土坎 Lumut 码头", 4.2318, 100.6290, 0.7)),
    ("penang", "MY", "槟城", 5.4141, 100.3288, "PEN", "main", None),
    ("alorsetar", "MY", "亚罗士打", 6.1210, 100.3670, "AOR", "main", None),
    ("langkawi", "MY", "兰卡威", 6.3300, 99.8000, "LGK", "langkawi", ("玻璃市 Kuala Perlis 码头", 6.3996, 100.1288, 1.25)),
    ("melaka", "MY", "马六甲", 2.1940, 102.2490, None, "main", None),
    ("pd", "MY", "波德申", 2.5220, 101.7960, None, "main", None),
    ("jb", "MY", "新山", 1.4927, 103.7414, "JHB", "main", None),
    ("desaru", "MY", "迪沙鲁", 1.5550, 104.2600, None, "main", None),
    ("kuantan", "MY", "关丹", 3.8077, 103.3260, "KUA", "main", None),
    ("tamannegara", "MY", "大汉山国家公园", 4.3800, 102.4000, None, "main", None),
    ("tioman", "MY", "刁曼岛", 2.8167, 104.1667, None, "tioman", ("丰盛港 Mersing 码头", 2.4339, 103.8393, 2.0)),
    ("kt", "MY", "瓜拉登嘉楼", 5.3302, 103.1408, "TGG", "main", None),
    ("redang", "MY", "热浪岛", 5.7790, 103.0170, None, "redang", ("Merang / Shahbandar 码头", 5.3366, 103.1435, 1.5)),
    ("perhentian", "MY", "停泊岛", 5.9100, 102.7400, None, "perhentian", ("瓜拉勿述 Kuala Besut 码头", 5.8297, 102.5600, 0.7)),
    ("kb", "MY", "哥打巴鲁", 6.1254, 102.2381, "KBR", "main", None),
    # Singapore
    ("sg", "SG", "新加坡", 1.2903, 103.8520, "SIN", "main", None),
    # Thailand
    ("hatyai", "TH", "合艾 / 宋卡", 7.0084, 100.4747, "HDY", "main", None),
    ("bangkok", "TH", "曼谷", 13.7563, 100.5018, "BKK", "main", None),
    ("ayutthaya", "TH", "大城", 14.3532, 100.5689, None, "main", None),
    ("pattaya", "TH", "芭提雅", 12.9236, 100.8825, "UTP", "main", None),
    ("chiangmai", "TH", "清迈", 18.7883, 98.9853, "CNX", "main", None),
    ("chiangrai", "TH", "清莱", 19.9105, 99.8406, "CEI", "main", None),
    ("phuket", "TH", "普吉岛", 7.8804, 98.3923, "HKT", "main", None),
    ("krabi", "TH", "甲米", 8.0300, 98.8250, "KBV", "main", None),
    # Indonesia
    ("batam", "ID", "巴淡岛", 1.1300, 104.0500, "BTH", "batam", ("新加坡港湾 HarbourFront 码头", 1.2647, 103.8203, 1.0)),
    ("bintan", "ID", "民丹岛", 1.1500, 104.3500, "TNJ", "bintan", ("新加坡丹那美拉 Tanah Merah 码头", 1.3122, 103.9874, 1.25)),
    ("jakarta", "ID", "雅加达", -6.2000, 106.8166, "CGK", "java", None),
    ("yogya", "ID", "日惹", -7.7956, 110.3695, "YIA", "java", None),
    ("bali", "ID", "巴厘岛", -8.5069, 115.2625, "DPS", "bali", None),
    ("toba", "ID", "多巴湖", 2.6845, 98.8756, "KNO", "sumatra", None),
]

# ---------- price helpers
def free(note=None):
    return {"free": True, "note": note}

def myr(loc=None, intl=None, note=None, src=None, asof=None, approx=False, group=None):
    """Malaysia: loc = MyKad (adult, child), intl = non-MyKad (adult, child)."""
    return {"cur": "MYR", "loc": loc, "intl": intl, "note": note, "src": src, "asof": asof, "approx": approx, "group": group}

def fx(cur, adult, child=None, note=None, src=None, asof=None, approx=False, group=None):
    """Other countries: the price a Malaysian visitor (foreigner) pays."""
    return {"cur": cur, "intl": (adult, child), "note": note, "src": src, "asof": asof, "approx": approx, "group": group}

# cat: city 城市地标 / culture 文化古迹 / nature 自然风光 / theme 主题乐园 / beach 海岛海滩 / food 美食夜市
# slot: day / morning / evening (sunset) / night / sunrise
# closed: weekdays the place is closed, 0 = Sunday … 6 = Saturday
PLACES = []

def P(id, region, name, en, wiki, lat, lng, cat, rank, dur, hours, price, desc, tip="", closed=None, slot="day",
      eat=None, spots=None, geo=None, src=None, asof=None):
    """eat: must-eat list [(dish, note, price text)]; spots: roaming markets [(weekday, name, map search text)];
    geo: OpenStreetMap search text used to fix the position; src/asof: where the food details come from."""
    d = dict(id=id, region=region, name=name, en=en, wiki=wiki, lat=lat, lng=lng, cat=cat, rank=rank,
             dur=dur, hours=hours, price=price, desc=desc, tip=tip, closed=closed or [], slot=slot)
    if eat:
        d["eat"] = [dict(name=e[0], note=e[1], price=e[2] if len(e) > 2 else "") for e in eat]
    if spots:
        d["spots"] = [dict(day=x[0], name=x[1], q=x[2]) for x in spots]
    if geo:
        d["geo"] = geo
    if src:
        d["info"] = {"src": src, "asof": asof}
    PLACES.append(d)

WEEK = [0, 1, 2, 3, 4, 5, 6]
def only(*days):
    """closed on every day except these (0 = Sunday)"""
    return [d for d in WEEK if d not in days]

S_TSL_KLT = "https://thesmartlocal.my/kl-tower-reopens/"
S_MMOVE_BATU = "https://the-malaysia-move.pages.dev/kl-batu-caves/"
S_MMOVE_KLCC = "https://the-malaysia-move.pages.dev/kl-petronas-klcc/"

# ===================== MALAYSIA =====================
P("petronas", "kl", "国油双峰塔", "Petronas Twin Towers", "Petronas Towers", 3.1579, 101.7116, "city", 5, 1.5,
  "周二至周日 9:00–21:00，周一休息；周五 13:00–14:30 暂停",
  myr(loc=(55, None), intl=(127, 65), approx=True, src=S_MMOVE_KLCC, asof="2026-05",
      note="含 41 楼空中天桥 + 86 楼观景台。外国游客平日约 RM127，周末略高；MyKad 价为转售平台折算，务必在官网 eticket.petronastwintowers.com.my 确认"),
  "吉隆坡最有名的地标，452 米双塔由 41 楼的空中天桥相连，86 楼可俯瞰全城。",
  "热门时段常售罄，提前一两天网上订票；晚上到 KLCC 公园看双塔夜景和音乐喷泉。", closed=[1])
P("kltower", "kl", "吉隆坡塔", "KL Tower (Menara KL)", "Kuala Lumpur Tower", 3.1528, 101.7038, "city", 4, 1.5,
  "约 9:00–22:00",
  myr(loc=(30, 20), intl=(80, 50), src=S_TSL_KLT, asof="2025-04",
      note="观景台价格。露天 Sky Deck（含玻璃 Sky Box）：MyKad RM50/28，外国游客 RM140/80"),
  "421 米电视塔，露天 Sky Deck 和悬空玻璃盒 Sky Box 是拍照热点，能同时看到双峰塔。",
  "傍晚上去可以同时看日落和夜景。")
P("batucaves", "kl", "黑风洞", "Batu Caves", "Batu Caves", 3.2379, 101.6840, "culture", 5, 2,
  "约 6:00–21:00",
  free("主洞免费；罗摩衍那洞约 RM5；Cave Villa 约 MyKad RM7 / 外国 RM15（2026 年参考价）"),
  "272 级彩虹阶梯通往石灰岩洞里的兴都庙，洞口有 42.7 米高的金色战神像。",
  "须遮住肩膀和膝盖，门口可租纱笼；小心猴子抢食物；坐 KTM 电车可直达。")
P("merdeka", "kl", "独立广场", "Merdeka Square", "Merdeka Square, Kuala Lumpur", 3.1488, 101.6934, "culture", 4, 1,
  "全天开放", free(),
  "1957 年马来亚宣布独立的地方，对面是摩尔式建筑苏丹阿都沙末大厦。",
  "傍晚到晚上灯光最美，可以顺路走到中央艺术坊和茨厂街。", slot="evening")
P("birdpark", "kl", "吉隆坡飞禽公园", "KL Bird Park", "Kuala Lumpur Bird Park", 3.1430, 101.6880, "nature", 3, 2,
  "每日 9:00–18:00",
  myr(loc=(45, 20), intl=(85, 60), src="https://www.malaysia-traveller.com/kl-bird-park.html", asof="2024",
      note="网上购票约 9 折"),
  "号称世界最大的自由飞行鸟园之一，犀鸟、孔雀、火烈鸟在头顶飞来飞去。",
  "早上 10 点前后有喂食和表演，较凉快。")
P("aquaria", "kl", "吉隆坡水族馆", "Aquaria KLCC", "Aquaria KLCC", 3.1535, 101.7135, "theme", 3, 2,
  "每日 10:00–20:00（19:00 停止入场）",
  myr(loc=(54, 44), intl=(80, 70), src="https://www.malaysiatravel.com/articles/post/aquaria-klcc-visitor-guide-tickets-tips-and-is-it-worth-it", asof="2026-05"),
  "位于会展中心地下，90 米长的海底隧道能近看鲨鱼和魟鱼，适合带小孩。", "和双峰塔安排在同一天最顺路。")
P("theanhou", "kl", "天后宫", "Thean Hou Temple", "Thean Hou Temple", 3.1218, 101.6872, "culture", 4, 1,
  "约 8:00–22:00", free(),
  "东南亚最大的华人庙宇之一，层层飞檐和红灯笼，晚上亮灯特别好拍。", "农历新年和元宵前后挂满灯笼，人也最多。", slot="evening")
P("petaling", "kl", "茨厂街（唐人街）", "Petaling Street", "Petaling Street", 3.1436, 101.6976, "food", 4, 1.5,
  "白天至深夜；夜市约 18:00 开始", free(),
  "吉隆坡老唐人街，有罗汉果茶、板面、咖喱面和各种小摊，附近还有关帝庙和兴都庙。", "下午 5 点后夜市最热闹。", slot="night",
  eat=[("冰镇龙眼罗汉果水", "牌楼入口的老字号，逛街解渴", ""), ("板面 · 咖喱面", "附近老店，早午餐更好", ""),
       ("小吃", "夜市小吃大多 RM2–10", "RM2–10")],
  src="https://the-malaysia-move.pages.dev/kl-night-markets/", asof="2026-05")
P("iamm", "kl", "伊斯兰艺术博物馆", "Islamic Arts Museum Malaysia", "Islamic Arts Museum Malaysia", 3.1414, 101.6896, "culture", 3, 1.5,
  "每日 9:30–18:00（仅开斋节、哈芝节休息）",
  myr(loc=(20, 10), intl=(20, 10), src="https://iamm.org.my/visit-us", asof="2026",
      note="成人 RM20；学生 RM10；6 岁以下免费；马来西亚乐龄 RM10"),
  "东南亚最大的伊斯兰艺术博物馆，穹顶、手稿和清真寺模型都很精美，馆内有冷气。", "下雨天的好去处。")
P("muzium", "kl", "国家博物馆", "National Museum", "National Museum (Malaysia)", 3.1374, 101.6870, "culture", 2, 1.5,
  "每日 9:00–17:00",
  myr(loc=(2, 0), intl=(5, 2), src="https://www.jmm.gov.my/en/node/1204",
      note="马来西亚儿童（12 岁以下）免费"),
  "了解马来西亚从远古到独立的历史，门票很便宜。")
P("bukitbintang", "kl", "武吉免登 · 亚罗街美食街", "Bukit Bintang & Jalan Alor", "Jalan Alor", 3.1456, 101.7090, "food", 4, 2,
  "每晚约 17:00 至深夜（19:30–22:00 最热闹）", free(),
  "吉隆坡最热闹的购物区，旁边的亚罗街一入夜就摆满烧烤、炒粿条和榴莲摊。",
  "海鲜按重量算，点之前先问每 100 克多少钱、确认总价。", slot="night",
  eat=[("炭烤鸡翅", "亚罗街招牌，排队的那几家最出名", ""), ("沙爹", "鸡肉、牛肉串配花生酱", ""),
       ("炒粿条 · 福建面", "吉隆坡式黑福建面", ""), ("蚝煎", "", ""), ("烤魔鬼鱼", "参巴酱烧烤", "按重量"),
       ("两人晚餐（连饮料）", "", "约 RM50–90")],
  src="https://the-malaysia-move.pages.dev/kl-night-markets/", asof="2026-05")
P("istana", "kl", "国家皇宫（外观）", "Istana Negara", "Istana Negara, Jalan Duta", 3.1726, 101.6648, "culture", 2, 0.5,
  "大门外全天可拍照", free(), "马来西亚最高元首官邸，大门有骑兵和卫兵站岗。", "整点前后可能看到换岗。")
P("sunwaylagoon", "kl", "双威水上乐园", "Sunway Lagoon", "Sunway Lagoon", 3.0710, 101.6075, "theme", 4, 6,
  "10:00–18:00；夜间乐园 18:00–23:00；周二休息（学校和公共假期除外）",
  myr(loc=(193, 145), intl=(268, 200), src="https://pickyourtrail.com/attractions/malaysia/sunway-lagoon", asof="2026",
      note="门票含 6 个主题区；身高 90 cm 以下免费；官网常有优惠"),
  "雪兰莪最大的主题乐园，水上乐园、过山车、野生动物园和极限公园一票玩到底。", "带泳衣，早上开门就进场。", closed=[2])
P("bluemosque", "kl", "莎阿南蓝色清真寺", "Blue Mosque, Shah Alam", "Sultan Salahuddin Abdul Aziz Mosque", 3.0784, 101.5206, "culture", 3, 1,
  "礼拜时间以外开放参观", free(), "全马最大的清真寺，蓝白色的巨大穹顶非常壮观。", "入内要穿长袍，寺方免费提供。")

P("putramosque", "putrajaya", "布城粉红清真寺", "Putra Mosque", "Putra Mosque", 2.9360, 101.6895, "culture", 4, 1,
  "礼拜时间以外开放参观（周五中午不开放）", free(), "粉红色花岗岩建成的清真寺，倒映在布城湖上，是布城的代表景点。", "游客须穿上寺方提供的长袍。")
P("putracruise", "putrajaya", "布城湖游船", "Cruise Tasik Putrajaya", "Putrajaya Lake", 2.9370, 101.6900, "nature", 2, 1,
  "约 10:00–20:00，每小时一班",
  myr(loc=(30, 20), intl=(50, 35), src="https://malaysia-traveller.com/cruise-tasik-putrajaya.html", asof="2024",
      note="45 分钟双体船观光；晚上 7 点有较便宜的 30 分钟班次"),
  "坐船绕湖一圈，看首相署、清真寺和各式桥梁。")
P("fireflies", "kselangor", "瓜拉雪兰莪萤火虫", "Kampung Kuantan Fireflies", "Kuala Selangor", 3.3600, 101.3010, "nature", 3, 1.5,
  "每晚 19:30–22:30",
  myr(loc=(60, None), intl=(100, None), group=4, src="https://selangor.travel/listing/kampung-kuantan-fireflies-park/",
      note="按船计价（一艘小舢舨约坐 4 人）"),
  "乘小舢舨在红树林河上看成千上万只萤火虫在树上同步闪烁。", "选没有月亮的晚上更亮；不要开闪光灯；周末先订位。", slot="night")
P("melawati", "kselangor", "马六甲眺望山", "Bukit Melawati", "Bukit Melawati", 3.3410, 101.2470, "culture", 2, 1,
  "全天", free(), "古堡遗迹和灯塔所在的小山，山上有很多银叶猴。", "可以和看萤火虫安排在同一天下午。", slot="evening")

P("skyworlds", "genting", "云顶 SkyWorlds 主题乐园", "Genting SkyWorlds", "Genting SkyWorlds", 3.4220, 101.7930, "theme", 4, 6,
  "10:00–18:00；周二休息（公共假期和学校假期除外）",
  myr(loc=(88, 38), intl=(168, 65), src="https://gentingskyworlds.com/en/tickets/malaysian-rate.html", asof="2026-11",
      note="至少提前一天网上买的价格；现场购票 MyKad RM168、外国游客 RM198"),
  "高原上的户外主题乐园，有过山车和各种电影主题游乐设施。", "山上比较冷，带件外套。", closed=[2])
P("awana", "genting", "云顶缆车 Awana SkyWay", "Awana SkyWay", "Awana Skyway", 3.4040, 101.7870, "city", 3, 0.5,
  "7:00–23:00",
  myr(loc=(11, None), intl=(11, None), src="https://www.rwgenting.com/gettinghere/awanaskyway/", asof="2026",
      note="标准车厢单程 RM11、来回 RM19；玻璃底车厢单程 RM32；身高 90 cm 以下免费"),
  "约 10 分钟穿越雨林云海直上云顶，中途可在清水岩庙站下车。")
P("chinswee", "genting", "清水岩庙", "Chin Swee Caves Temple", "Chin Swee Caves Temple", 3.4290, 101.7950, "culture", 3, 1,
  "约 7:00–18:00", free(), "建在山腰的道教寺庙，九层宝塔和大佛俯瞰云海。", "坐 Awana 缆车在 Chin Swee 站下车最方便。")
P("colmar", "genting", "Colmar 法国村", "Colmar Tropicale", "Colmar Tropicale", 3.4040, 101.8380, "city", 2, 1.5,
  "约 9:00–18:00", None, "仿法国阿尔萨斯小镇的度假村，附近还有日本村。", "非住客入场费请以现场为准。")
P("frasershill", "fraser", "福隆港", "Fraser's Hill", "Fraser's Hill", 3.7120, 101.7380, "nature", 2, 3,
  "全天", free(), "英殖民时期的山中避暑小镇，石头小屋、钟楼和观鸟步道，比云顶清静。", "上山路窄弯多，开车小心。")

P("bohtea", "cameron", "BOH 茶园（双溪巴拉）", "BOH Sungei Palas Tea Centre", "Boh Tea", 4.5230, 101.4200, "nature", 4, 1.5,
  "约 8:30–16:30（周一休息，以现场为准）", free(), "整片山坡的茶园像绿色波浪，茶室阳台边喝茶边看风景。",
  "周末上午车多，早点去；茶园山路很窄。", closed=[1])
P("mossy", "cameron", "苔藓森林", "Mossy Forest", "Gunung Brinchang", 4.5180, 101.3830, "nature", 3, 2,
  "白天", None, "海拔 2000 米的云雾森林，树干长满苔藓和兰花，像童话世界。",
  "木栈道近年多次关闭维修，出发前先确认；建议参加当地吉普车团。")
P("strawberry", "cameron", "草莓园与花园", "Strawberry farms", "Cameron Highlands", 4.4993, 101.3907, "nature", 2, 1,
  "约 8:00–18:00", free("免费入场，摘草莓按重量付费"), "金马伦高原的草莓园、玫瑰园和蔬菜市场，适合亲子。")
P("cameronmarket", "cameron", "金马伦夜市", "Brinchang Night Market", "Brinchang", 4.4950, 101.3880, "food", 2, 1.5,
  "周五、周六晚上（约 17:00–23:00）", free(), "高原上的夜市，烤玉米、草莓和各种小吃。", "只在周末开，晚上很冷。", slot="night",
  closed=only(5, 6), eat=[("烤玉米 · 烤红薯", "", ""), ("新鲜草莓 · 草莓雪糕", "", ""), ("炸物小吃", "", "")])

P("ipoholdtown", "ipoh", "怡保旧街场 · 二奶巷", "Ipoh Old Town & Concubine Lane", "Concubine Lane", 4.5960, 101.0790, "culture", 4, 2,
  "白天", free(), "保留大量英殖民时期店屋，二奶巷文青小店林立，还有壁画街。",
  "顺便吃芽菜鸡、白咖啡和鸡丝河粉。")
P("keklotong", "ipoh", "极乐洞", "Kek Lok Tong", "Kek Lok Tong", 4.5603, 101.1263, "culture", 3, 1,
  "约 8:00–17:00", free(), "石灰岩洞内的佛寺，穿过洞穴后是一片湖光山色的花园。")
P("sampohtong", "ipoh", "三宝洞", "Sam Poh Tong", "Sam Poh Tong", 4.5560, 101.1060, "culture", 3, 1,
  "约 8:00–16:00", free(), "怡保最古老的洞庙，洞后有池塘和满池的乌龟。")
P("kellies", "ipoh", "凯利古堡", "Kellie's Castle", "Kellie's Castle", 4.4744, 101.0889, "culture", 3, 1,
  "每日 9:00–18:00",
  myr(loc=(5, 3), intl=(10, 8), src="https://www.malaysia.travel/index.php/explore/kellie-s-castle"),
  "苏格兰园主百年前建造却未完成的城堡，传说中有鬼魂出没。")
P("tambun", "ipoh", "打扪失落世界", "Lost World of Tambun", "Lost World of Tambun", 4.6250, 101.1550, "theme", 3, 5,
  "周一、周三至周五 11:00–18:00；周末 10:00–18:00；周二休息（假期除外）；温泉 18:00–23:00",
  myr(loc=(95, 95), intl=(140, 140), src="https://www.pelago.com/articles/lost-world-of-tambun-theme-park-guide/",
      note="日间票含所有园区；身高 90 cm 以下免费；夜间温泉票 MyKad RM74 / 外国 RM90"),
  "被石灰岩山包围的水上乐园、动物园和天然温泉，晚上泡温泉最舒服。", closed=[2])
P("tempurung", "ipoh", "坦布隆洞", "Gua Tempurung", "Gua Tempurung", 4.4160, 101.1880, "nature", 3, 2,
  "约 9:00–16:00",
  myr(loc=(8, 4), intl=(20, 10), src="https://www.malaysia-traveller.com/gua-tempurung.html", asof="2024-01",
      note="最简单的「金色石流」干线路线；涉水探险路线 RM40–50（需 8 人成团）"),
  "马来半岛最大的石灰岩洞之一，钟乳石和石笋规模惊人。")
P("taipinglake", "taiping", "太平湖", "Taiping Lake Gardens", "Taiping Lake Gardens", 4.8560, 100.7510, "nature", 3, 1.5,
  "全天", free(), "马来西亚最古老的公园，湖边雨树枝干垂到水面，清晨最美。", slot="morning")
P("zootaiping", "taiping", "太平动物园与夜间动物园", "Zoo Taiping & Night Safari", "Zoo Taiping", 4.8530, 100.7570, "nature", 3, 2.5,
  "动物园白天开放；夜间动物园晚上开放",
  myr(loc=(25, 12), intl=(40, 20), src="https://www.thestar.com.my/metro/metro-news/2026/03/13/new-ticket-rates-for-taiping-zoo-night-safari", asof="2026-03",
      note="夜间动物园：MyKad RM30/15，外国 RM50/25；票价含园内电车"),
  "大马最早的动物园，就在太平湖旁，夜间动物园尤其受欢迎。")

P("pangkor", "pangkor", "邦咯岛", "Pangkor Island", "Pangkor Island", 4.2160, 100.5600, "beach", 3, 5,
  "全天", free("渡轮票另计"), "节奏悠闲的小岛，渔村、荷兰古堡和清澈海滩，可环岛骑摩托。")

P("georgetown", "penang", "乔治市壁画街 · 打铁街", "George Town street art", "Armenian Street, Penang", 5.4150, 100.3370, "culture", 5, 3,
  "全天", free(), "世界文化遗产古城，「姐弟共骑」等壁画散布在老街巷，还有众多百年宗祠和庙宇。",
  "早上 9 点前人少好拍照；租脚踏车或步行最方便。", slot="morning")
P("penanghill", "penang", "升旗山", "Penang Hill", "Penang Hill", 5.4240, 100.2690, "nature", 5, 3,
  "缆车 6:30–23:00",
  myr(loc=(16, 8), intl=(40, 20), src="https://www.penanghill.gov.my/index.php/en/tickets", asof="2026",
      note="缆车来回普通道票价；快速通道 MyKad RM40 / 外国 RM80"),
  "坐百年登山缆车上到 833 米山顶，俯瞰乔治市和槟威大桥。", "傍晚上山看日落和夜景。", slot="evening")
P("habitat", "penang", "升旗山 The Habitat 雨林步道", "The Habitat Penang Hill", "The Habitat Penang Hill", 5.4230, 100.2660, "nature", 3, 2,
  "9:00–17:30（17:30 后为夜间步行）",
  myr(loc=None, intl=(54, None), approx=True, src="https://book.thehabitat.my/tours/malaysia/penang/standard-entrance-pass",
      note="标准门票起价 RM54；提前 7 天网上订 9 折；儿童与 MyKad 价以官网为准"),
  "山顶雨林里的树冠步道和 360° 观景台 Curtis Crest。", "与升旗山同一天安排。")
P("keklosi", "penang", "极乐寺", "Kek Lok Si Temple", "Kek Lok Si", 5.3999, 100.2736, "culture", 5, 2,
  "约 9:00–18:00",
  free("寺庙免费；万佛塔 RM2；上观音像斜坡电梯单程 RM2"),
  "东南亚最大的佛寺之一，七层万佛塔融合中泰缅建筑风格，山上有 30 米高观音像。",
  "农历新年前后晚上亮灯万盏。")
P("bluemansion", "penang", "张弼士故居（蓝屋）", "Cheong Fatt Tze Mansion", "Cheong Fatt Tze Mansion", 5.4214, 100.3355, "culture", 3, 1,
  "导览约每周五至周日 11:00、14:00（以官网为准）",
  myr(loc=(25, 12.5), intl=(25, 12.5), src="https://www.travelswithsun.com/cheong-fatt-tze-mansion-penang/", asof="2024-08"),
  "靛蓝色的客家豪宅，参加导览才能入内，了解南洋富商的故事。")
P("peranakan", "penang", "槟城娘惹博物馆", "Pinang Peranakan Mansion", "Pinang Peranakan Mansion", 5.4190, 100.3410, "culture", 3, 1,
  "每日 9:30–17:00",
  myr(loc=(20, None), intl=(20, None), src="https://www.malaysia.travel/explore/pinang-peranakan-mansion", note="6 岁以下免费"),
  "金碧辉煌的峇峇娘惹大宅，展出上千件古董和娘惹服饰。")
P("khookongsi", "penang", "邱公司", "Khoo Kongsi", "Khoo Kongsi", 5.4145, 100.3374, "culture", 4, 1,
  "每日 9:00–17:00",
  myr(loc=(10, 1), intl=(10, 1), src="https://www.malaysia-traveller.com/leong-san-tong-khoo-kongsi.html"),
  "全马最华丽的宗祠，屋顶和梁柱雕工精细到让人惊叹。")
P("clanjetty", "penang", "姓氏桥（周桥）", "Clan Jetties", "Chew Jetty", 5.4130, 100.3410, "culture", 3, 1,
  "全天", free(), "建在海上的高脚木屋村落，至今仍有华人家族居住。", "傍晚的海景最好看，注意保持安静。", slot="evening")
P("entopia", "penang", "Entopia 蝴蝶园", "Entopia", "Entopia by Penang Butterfly Farm", 5.4480, 100.2150, "nature", 3, 2,
  "9:00–18:00（17:00 停止入场），周三休息",
  myr(loc=(65, 45), intl=(75, 55), src="https://thebackpackingfamily.com/entopia-penang-everything-to-know/", asof="2026"),
  "室内外结合的昆虫与蝴蝶生态馆，适合亲子。", closed=[3])
P("batuferringhi", "penang", "峇都丁宜海滩与夜市", "Batu Ferringhi", "Batu Ferringhi", 5.4720, 100.2500, "beach", 3, 2,
  "海滩全天；夜市傍晚开", free(), "槟城最有名的海滩，有拖伞等水上活动，晚上沿路是夜市。", slot="evening")
P("penangnp", "penang", "槟城国家公园（猴子海滩）", "Penang National Park", "Penang National Park", 5.4590, 100.2050, "nature", 3, 3,
  "约 8:00–18:00", free("免费入园，需登记；坐船往返海滩另付"), "全球最小的国家公园之一，徒步穿越雨林到猴子海滩和灯塔。",
  "带足饮用水，下雨天步道很滑。", slot="morning")
P("gurney", "penang", "新关仔角美食中心", "Gurney Drive Hawker Centre", "Gurney Drive", 5.4380, 100.3110, "food", 4, 1.5,
  "约 16:30–23:00（部分摊位周二休息）", free(), "槟城小吃大集合：炒粿条、亚参叻沙、蚝煎、红豆冰。",
  "大部分小吃 RM5–25。", slot="night",
  eat=[("炒粿条", "", ""), ("亚参叻沙", "", ""), ("蚝煎", "", ""), ("红豆冰 · 煎蕊", "", ""), ("罗惹", "", "")],
  src="https://thebackpackingfamily.com/?p=5641", asof="2025-12")

P("alorsetartower", "alorsetar", "亚罗士打塔", "Alor Setar Tower", "Alor Setar Tower", 6.1230, 100.3690, "city", 2, 1,
  "约 9:00–22:00", None, "165 米高的通讯塔，可眺望吉打州一望无际的稻田。")

P("skycab", "langkawi", "兰卡威缆车与天空之桥", "Langkawi SkyCab & SkyBridge", "Langkawi Cable Car", 6.3860, 99.6620, "nature", 5, 3,
  "每日 9:30–18:00（天气不好会暂停）",
  myr(loc=(47, 36), intl=(89, 68), src="https://myhalalxplorer.com/blog/2025/harga-tiket-waktu-operasi-segala-yang-anda-patut-tahu-berkenaan-skycab-skybridge-langkawi/", asof="2025",
      note="SkyBridge 套票（含缆车、SkyDome、SkyRex、3D 艺术馆）；只坐缆车 MyKad RM43/33、外国 RM85/65"),
  "坡度最陡的缆车之一直上 708 米山顶，再走上悬空的弧形天空之桥。", "早上去云雾较少；雨天缆车可能停运。")
P("kilim", "langkawi", "基林红树林游船", "Kilim Geoforest Park", "Kilim Karst Geoforest Park", 6.4070, 99.8580, "nature", 4, 2.5,
  "9:00–17:00",
  myr(loc=(350, None), intl=(350, None), group=8, approx=True, src="https://foreverbreak.com/travel/malaysia/langkawi/kilim-geoforest-park",
      note="包船 2 小时约 RM350（3 小时 RM450）；拼船更便宜；蝙蝠洞入场另付 RM2"),
  "坐船穿过石灰岩峭壁和红树林，看老鹰喂食、蝙蝠洞和鱼排。")
P("underwater", "langkawi", "兰卡威海底世界", "Underwater World Langkawi", "Underwater World Langkawi", 6.2870, 99.7290, "theme", 2, 1.5,
  "每日 10:00–18:00",
  myr(loc=(50, 39), intl=(62, 50), src="https://malaysia-traveller.com/underwater-world-langkawi.html", asof="2024"),
  "就在珍南海滩旁的水族馆，有企鹅馆和海底隧道。", "适合雨天或带小孩。")
P("cenang", "langkawi", "珍南海滩", "Pantai Cenang", "Pantai Cenang", 6.2920, 99.7280, "beach", 4, 2.5,
  "全天", free(), "兰卡威最热闹的白沙滩，日落时海面一片金黄，沿街有餐厅和免税店。", slot="evening")
P("eaglesq", "langkawi", "鹰广场", "Eagle Square", "Dataran Lang", 6.3150, 99.8510, "city", 3, 0.5,
  "全天", free(), "瓜镇码头旁 12 米高的红褐色巨鹰雕像，兰卡威的象征。")
P("islandhop", "langkawi", "跳岛游（孕妇岛）", "Island hopping", "Pulau Dayang Bunting", 6.2050, 99.8130, "beach", 3, 4,
  "约 9:00–17:00",
  myr(loc=(350, None), intl=(350, None), group=8, approx=True,
      note="包船价约 RM350 一艘（最多 8 人）；孕妇岛湖区另收门票成人 RM6、儿童 RM3。拼团每人更便宜，各家价格不同",
      src="https://travelynnfamily.com/island-hopping-langkawi/", asof="2026-10"),
  "坐船游孕妇岛淡水湖、喂老鹰和在小岛白沙滩游泳。", "跳岛团价格各家不同，现场比较后再订。")

P("afamosa", "melaka", "圣地亚哥古城门 · 圣保罗山", "A Famosa & St Paul's Hill", "A Famosa", 2.1918, 102.2500, "culture", 5, 1,
  "全天", free(), "葡萄牙人 1511 年建的古堡仅存的城门，山顶有圣保罗教堂遗址。")
P("dutchsquare", "melaka", "荷兰红屋 · 基督教堂", "Dutch Square", "Christ Church, Malacca", 2.1941, 102.2490, "culture", 5, 1,
  "全天", free("广场免费；红屋内的博物馆另收门票"), "整片砖红色的荷兰殖民建筑，是马六甲最经典的拍照点。")
P("jonker", "melaka", "鸡场街夜市", "Jonker Walk", "Jonker Street", 2.1960, 102.2470, "food", 5, 2,
  "夜市：周五至周日傍晚至深夜（老街白天也能逛）", free(), "古董店、娘惹糕、鸡饭粒、榴莲煎蕊，一条街吃个够。", "夜市只在周五至周日开。",
  slot="night", closed=only(5, 6, 0),
  eat=[("榴莲煎蕊", "马六甲甜品", ""), ("沙爹朱律（Satay Celup）", "串串放进花生酱锅里烫", ""),
       ("娘惹叻沙 · 娘惹糕", "", ""), ("烤鱿鱼 · 各式小吃", "", "")])
P("rivercruise", "melaka", "马六甲河游船", "Melaka River Cruise", "Malacca River", 2.1990, 102.2480, "city", 4, 1,
  "每日 9:00–23:00，约 30 分钟一班",
  myr(loc=(25, 15), intl=(30, 25), src="https://www.malaysia-traveller.com/melaka-river-cruise.html", asof="2024-01",
      note="全程约 45 分钟；2 岁以下免费"),
  "沿河欣赏两岸彩色老屋、壁画和水车，夜晚灯光更美。", slot="night")
P("tamingsari", "melaka", "马六甲旋转塔", "Menara Taming Sari", "Menara Taming Sari", 2.1910, 102.2470, "city", 3, 0.5,
  "平日 9:00–22:00；周末 9:00–23:00",
  myr(loc=(17, 11), intl=(23, 15), src="https://www.malaysia-traveller.com/menara-taming-sari.html"),
  "旋转升到 80 米高空，7 分钟看遍古城和马六甲海峡。")
P("babanyonya", "melaka", "峇峇娘惹古屋博物馆", "Baba & Nyonya Heritage Museum", "Baba & Nyonya Heritage Museum", 2.1958, 102.2460, "culture", 3, 1,
  "每日 10:00–17:00",
  myr(loc=(18, 13), intl=(18, 13), src="https://malaysia-traveller.com/nyonya-heritage-museum.html",
      note="导览 RM25"),
  "保存完好的娘惹大宅，了解土生华人的生活与婚嫁习俗。")
P("straitsmosque", "melaka", "马六甲海峡清真寺", "Melaka Straits Mosque", "Malacca Straits Mosque", 2.1830, 102.2410, "culture", 3, 1,
  "礼拜时间以外开放", free(), "建在海上的清真寺，涨潮时像浮在海面上。", "日落时分最美。", slot="evening")
P("chenghoonteng", "melaka", "青云亭", "Cheng Hoon Teng", "Cheng Hoon Teng", 2.1968, 102.2468, "culture", 3, 0.5,
  "约 7:00–19:00", free(), "马来西亚最古老的华人庙宇，建于 17 世纪。")

P("pdbeach", "pd", "波德申海滩", "Port Dickson", "Port Dickson", 2.4640, 101.8530, "beach", 2, 3,
  "全天", free(), "离吉隆坡最近的海滩，周末一日游的好去处。", slot="evening")

P("legoland", "jb", "马来西亚乐高乐园", "LEGOLAND Malaysia", "Legoland Malaysia Resort", 1.4260, 103.6290, "theme", 4, 6,
  "约 10:00–18:00（以官网为准）",
  myr(loc=(205, 179), intl=(205, 179), src="https://funacrosstheworld.com/2026/05/09/legoland-malaysia-ticket-prices-2026-full-guide-for-families-and-best-deals/", asof="2026-05",
      note="主题乐园一日票网上起价；加水上乐园或海洋馆的套票约 RM285 起；3 岁以下免费"),
  "亚洲第一座乐高乐园，适合 2–12 岁小孩，还有水上乐园和 SEA LIFE 海洋馆。")
P("abubakar", "jb", "苏丹阿布峇卡清真寺", "Sultan Abu Bakar Mosque", "Sultan Abu Bakar State Mosque", 1.4590, 103.7470, "culture", 2, 0.5,
  "礼拜时间以外开放", free(), "融合维多利亚与摩尔风格的白色清真寺，可远眺新加坡。")
P("desaru", "desaru", "迪沙鲁海岸冒险水上乐园", "Adventure Waterpark Desaru Coast", "Desaru Coast", 1.5550, 104.2600, "theme", 3, 5,
  "周三至周一 10:00–18:00，周二休息（假期除外）",
  myr(loc=(76, None), intl=(121, None), approx=True, src="https://www.pelago.com/en/activity/p0sg5-adventure-waterpark-desaru-coast-johor-johor-bahru/",
      note="网上起价按美元折算（马来西亚人 US$18.65 / 外国人 US$29.85）"),
  "大马最大的水上乐园之一，有冲浪池和超长滑道，旁边就是迪沙鲁海滩。", closed=[2])

P("telukcempedak", "kuantan", "直落斯巴达海滩", "Teluk Cempedak", "Teluk Cempedak", 3.8130, 103.3730, "beach", 3, 2,
  "全天", free(), "关丹市区旁的海滩，有木栈道沿着海边岩石延伸。", slot="morning")
P("cherating", "kuantan", "珍拉丁海滩", "Cherating", "Cherating", 4.1250, 103.3900, "beach", 3, 3,
  "全天", free(), "东海岸冲浪小镇，晚上可以参加萤火虫游船。")
P("tamannegara", "tamannegara", "大汉山国家公园（树冠吊桥）", "Taman Negara", "Taman Negara", 4.3820, 102.4010, "nature", 4, 4,
  "树冠吊桥 10:00–15:00，周五休息",
  myr(loc=(5, 3), intl=(5, 3), src="https://malaysiauncovered.com/taman-negara-canopy-walk/", asof="2024-11",
      note="树冠吊桥价格；另需入园许可 RM1"),
  "1.3 亿年历史的热带雨林，走上离地 40 米的树冠吊桥，可以夜间丛林徒步。", "建议住一晚，参加夜间丛林团。", closed=[5])
P("tioman", "tioman", "刁曼岛", "Tioman Island", "Tioman Island", 2.8167, 104.1667, "beach", 4, 6,
  "全天（东北季风期 11–2 月船班少）",
  myr(loc=(5, 2), intl=(30, 15), src="https://www.dof.gov.my/en/services/marine-park-resource-management/conservation-charge-fee-rates",
      note="海洋公园保护费；渡轮另计"),
  "曾被评为世界最美岛屿之一，浮潜、潜水和丛林瀑布。", "11–2 月季风期风浪大。")
P("redang", "redang", "热浪岛", "Redang Island", "Redang Island", 5.7790, 103.0170, "beach", 5, 6,
  "3–10 月（11–2 月季风期多数度假村关闭）",
  myr(loc=(5, 2), intl=(30, 15), src="https://www.dof.gov.my/en/services/marine-park-resource-management/conservation-charge-fee-rates",
      note="海洋公园保护费；多数游客订含船票和浮潜的度假村配套"),
  "东海岸最美的海岛之一，海水清澈见底，浮潜能看到海龟和小鲨鱼。", "11–2 月季风期不开放。")
P("perhentian", "perhentian", "停泊岛", "Perhentian Islands", "Perhentian Islands", 5.9100, 102.7400, "beach", 5, 6,
  "3–10 月（11–2 月季风期多数度假村关闭）",
  myr(loc=(5, 2), intl=(30, 15), src="https://www.dof.gov.my/en/services/marine-park-resource-management/conservation-charge-fee-rates",
      note="海洋公园保护费；渡轮另计"),
  "大小停泊岛以背包客风格和平价潜水出名，水下能见度极高。", "11–2 月季风期不开放。")
P("crystalmosque", "kt", "水晶清真寺 · 伊斯兰文明公园", "Crystal Mosque", "Crystal Mosque", 5.3220, 103.1220, "culture", 4, 2,
  "公园：周一、三、四 10:00–18:00；周五至周日 9:00–19:00；周二休息",
  myr(loc=(20, 16), intl=(20, 16), src="https://www.malaysia-traveller.com/islamic-civilization-park.html", asof="2024",
      note="伊斯兰文明公园门票；水晶清真寺在公园外，免费"),
  "钢铁和玻璃建成的清真寺，旁边的公园有世界著名清真寺的缩小模型。", "日落时清真寺亮灯最美。", closed=[2], slot="evening")
P("ktchinatown", "kt", "瓜登嘉楼唐人街", "Kuala Terengganu Chinatown", "Kampung Cina, Kuala Terengganu", 5.3360, 103.1430, "food", 2, 1.5,
  "全天", free(), "彩色老街和壁画巷，有不少老字号咖啡店。")
P("sitikhadijah", "kb", "西蒂卡迪佳巴刹", "Siti Khadijah Market", "Siti Khadijah Market", 6.1300, 102.2390, "food", 3, 1,
  "约 7:00–18:00", free(), "由女摊贩经营的传统市场，从二楼俯瞰五彩缤纷的摊位最壮观。", slot="morning")
P("watmachim", "kb", "吉兰丹坐佛寺", "Wat Machimmaram", "Wat Machimmaram", 6.1500, 102.1300, "culture", 2, 1,
  "白天", free(), "东南亚最大的坐佛之一，展现吉兰丹的暹罗佛教文化。")

# ===================== SINGAPORE =====================
S_SGSPIRIT = "https://www.singapore-spirit.com/guides/top-attractions-singapore/"
S_MISSLOBANG = "https://www.misslobang.com/article/best-attractions-deals-singapore-2026"
P("gardensbay", "sg", "滨海湾花园", "Gardens by the Bay", "Gardens by the Bay", 1.2816, 103.8636, "nature", 5, 3,
  "约 9:00–21:00；超级树灯光秀每晚 19:45、20:45",
  fx("SGD", 46, None, src=S_SGSPIRIT, asof="2026",
     note="花穹 + 云雾林两馆；含空中走道的三景点票 S$49 / 儿童 S$37；户外花园和灯光秀免费"),
  "巨型玻璃温室里的花海和室内瀑布，晚上超级树林有免费灯光音乐秀。", "下午进温室，晚上看灯光秀。", slot="evening")
P("mbsskypark", "sg", "金沙空中花园观景台", "Marina Bay Sands SkyPark", "Marina Bay Sands SkyPark", 1.2840, 103.8610, "city", 4, 1,
  "约 10:00–22:00",
  fx("SGD", 35, None, src=S_SGSPIRIT, asof="2026", note="非高峰 S$35，高峰 S$39；无边泳池只限住客"),
  "57 楼观景台，滨海湾、鱼尾狮和花园尽收眼底。", "日落前上去。", slot="evening")
P("merlion", "sg", "鱼尾狮公园", "Merlion Park", "Merlion Park", 1.2868, 103.8545, "city", 5, 0.5,
  "全天", free(), "新加坡的象征，对岸就是金沙酒店；晚上 8 点和 9 点有免费灯光水舞秀。", slot="night")
P("uss", "sg", "新加坡环球影城", "Universal Studios Singapore", "Universal Studios Singapore", 1.2540, 103.8238, "theme", 5, 8,
  "约 10:00–19:00（以官网为准）",
  fx("SGD", 76, 69, src=S_MISSLOBANG, asof="2026-10",
     note="11 月非高峰一日票；12 月约 S$89；快速通行证另加 S$60–90"),
  "变形金刚、侏罗纪公园和小黄人，一整天都玩不完。", "开门前到，先玩热门项目。")
P("oceanarium", "sg", "新加坡海洋馆", "Singapore Oceanarium", "S.E.A. Aquarium", 1.2580, 103.8200, "theme", 3, 2,
  "约 10:00–19:00",
  fx("SGD", 55, 43, src=S_MISSLOBANG, asof="2026-10", note="原 S.E.A. 海洋馆；2026 年起所有游客同价"),
  "巨型海洋展窗能看到鬼蝠魟和上万条鱼，在圣淘沙。")
P("sgzoo", "sg", "新加坡动物园", "Singapore Zoo", "Singapore Zoo", 1.4043, 103.7930, "nature", 5, 3.5,
  "约 8:30–18:00",
  fx("SGD", 49, None, src="https://www.trvlogue.com/en/singapore-zoo-night-safari-tickets", asof="2026-07",
     note="成人价；可买动物园 + 河川生态园等多园套票"),
  "开放式的雨林动物园，可以和红毛猩猩一起吃早餐。", "和河川生态园、夜间动物园安排在同一天。", slot="morning")
P("nightsafari", "sg", "新加坡夜间野生动物园", "Night Safari", "Night Safari, Singapore", 1.4022, 103.7882, "nature", 4, 3,
  "19:00–24:00",
  fx("SGD", 58, None, src=S_SGSPIRIT, asof="2026", note="含游园电车"),
  "世界首个夜间动物园，坐电车看夜行动物。", slot="night")
P("riverwonders", "sg", "河川生态园", "River Wonders", "River Wonders", 1.4040, 103.7898, "nature", 3, 2,
  "约 10:00–19:00",
  fx("SGD", 45, None, src="https://www.trvlogue.com/en/singapore-zoo-night-safari-tickets", asof="2026-07"),
  "以河流为主题的动物园，大熊猫是明星。")
P("birdparadise", "sg", "新加坡飞禽公园", "Bird Paradise", "Bird Paradise", 1.4040, 103.7860, "nature", 3, 2.5,
  "约 9:00–18:00",
  fx("SGD", 49, 34, src=S_MISSLOBANG, asof="2026-10"),
  "亚洲最大的鸟类公园，八个大型鸟舍模拟世界各地的生态。")
P("chinatownsg", "sg", "牛车水 · 佛牙寺", "Chinatown & Buddha Tooth Relic Temple", "Buddha Tooth Relic Temple and Museum", 1.2815, 103.8443, "culture", 4, 2,
  "佛牙寺约 7:00–17:00", free(), "唐风佛寺供奉佛牙舍利，周边是牛车水小吃和熟食中心。")
P("littleindia", "sg", "小印度", "Little India", "Little India, Singapore", 1.3066, 103.8518, "culture", 3, 1.5,
  "全天", free(), "色彩鲜艳的街区、印度庙和 24 小时营业的慕斯达法。")
P("flyer", "sg", "新加坡摩天观景轮", "Singapore Flyer", "Singapore Flyer", 1.2893, 103.8631, "city", 3, 1,
  "约 10:00–22:00",
  fx("SGD", 46, 28, src=S_MISSLOBANG, asof="2026-10"),
  "165 米高的摩天轮，绕一圈约 30 分钟。", slot="evening")
P("jewel", "sg", "星耀樟宜", "Jewel Changi Airport", "Jewel Changi Airport", 1.3602, 103.9897, "city", 4, 2,
  "24 小时；雨漩涡灯光秀晚上", free("雨漩涡和森林谷免费；顶层 Canopy Park 活动 S$12–38"),
  "机场旁 40 米高的室内瀑布被雨林环绕，出发或抵达新加坡时顺路看。")
P("botanic", "sg", "新加坡植物园", "Singapore Botanic Gardens", "Singapore Botanic Gardens", 1.3138, 103.8159, "nature", 3, 2,
  "5:00–24:00", free("植物园免费；国家胡姬花园约 S$15"),
  "新加坡唯一的世界遗产，胡姬花园有上千种兰花。", "早上 9 点前去比较凉快。", slot="morning")
P("sentosa", "sg", "圣淘沙 · 空中缆车", "Sentosa & Cable Car", "Singapore Cable Car", 1.2550, 103.8190, "beach", 4, 3,
  "缆车约 8:45–22:00",
  fx("SGD", 35, None, approx=True, src=S_SGSPIRIT, asof="2026", note="空中缆车 Sky Pass 来回 S$35–40；海滩免费"),
  "坐缆车从花柏山跨海到圣淘沙，西乐索、巴拉湾海滩适合放空。", slot="evening")
P("clarkequay", "sg", "克拉码头与新加坡河游船", "Clarke Quay", "Clarke Quay", 1.2906, 103.8465, "food", 3, 2,
  "傍晚至深夜", free("河道游船约 S$28"), "河边的餐厅酒吧区，坐游船看滨海湾夜景。", slot="night")
P("hawpar", "sg", "虎豹别墅", "Haw Par Villa", "Haw Par Villa", 1.2830, 103.7820, "culture", 2, 1.5,
  "约 9:00–20:00", free(), "上千座中国神话雕塑，十殿阎罗很有名，小孩可能会怕。")
P("laupasat", "sg", "老巴刹美食中心", "Lau Pa Sat", "Lau Pa Sat", 1.2806, 103.8504, "food", 3, 1.5,
  "24 小时；沙爹街傍晚开", free(), "百年铁铸建筑里的熟食中心，晚上旁边整条街烤沙爹。", slot="night",
  eat=[("沙爹", "沙爹街傍晚封路摆摊", ""), ("福建虾面 · 炒粿条", "", ""), ("辣椒螃蟹（海鲜摊）", "按重量，先问价", "")])

# ===================== THAILAND =====================
P("hatyaipark", "hatyai", "合艾市政公园", "Hat Yai Municipal Park", "Hat Yai Municipal Park", 7.0390, 100.4880, "culture", 3, 2,
  "约 6:00–20:00", free("公园免费；登山缆车另收费"), "山上有四面佛、观音像和金色佛塔，坐缆车可俯瞰合艾市。")
P("kimyong", "hatyai", "金荣市场", "Kim Yong Market", "Kim Yong Market", 7.0080, 100.4740, "food", 3, 1.5,
  "约 9:00–18:00", free(), "合艾最有名的市场，干货、零食和泰国小吃，马来西亚游客必逛。",
  eat=[("合艾炸鸡", "配炸葱头和糯米饭", ""), ("泰式点心 · 烤肉串", "", ""), ("干货零食（腰果、鱿鱼丝）", "带回家当手信", "")])
P("samila", "hatyai", "宋卡沙密拉海滩", "Samila Beach, Songkhla", "Samila Beach", 7.2140, 100.5960, "beach", 3, 1.5,
  "全天", free(), "海边的美人鱼铜像是宋卡的象征，傍晚散步最舒服。", slot="evening")
P("hatyaifloating", "hatyai", "合艾水上市场", "Khlong Hae Floating Market", "Khlong Hae Floating Market", 7.0320, 100.4570, "food", 3, 1.5,
  "周五至周日傍晚（以现场为准）", free(), "小船上卖泰式小吃和甜点，晚上灯火通明。", slot="night", closed=only(5, 6, 0),
  eat=[("船上烤海鲜", "", ""), ("泰式甜品", "", ""), ("泰式奶茶", "", "")])
P("watHatYaiNai", "hatyai", "合艾大卧佛寺", "Wat Hat Yai Nai", "Wat Hat Yai Nai", 7.0210, 100.4520, "culture", 2, 0.5,
  "白天", free(), "35 米长的卧佛，身体里面可以进去参观。")

P("grandpalace", "bangkok", "大皇宫 · 玉佛寺", "Grand Palace", "Grand Palace", 13.7500, 100.4913, "culture", 5, 2.5,
  "约 8:30–15:30（皇室活动时会临时关闭）",
  fx("THB", 500, None, src="https://www.khaosanroad.com/articles/bangkok-temple-run-ticket-strategy-fees-opening-hours-wat-pho-grand-palace-golden-mount", asof="2026-09",
     note="含大皇宫和玉佛寺"),
  "曼谷最辉煌的建筑群，玉佛寺供奉泰国最神圣的翡翠玉佛。", "须穿长裤或长裙、遮住肩膀；早上开门就进去。", slot="morning")
P("watpho", "bangkok", "卧佛寺", "Wat Pho", "Wat Pho", 13.7465, 100.4927, "culture", 5, 1.5,
  "约 8:00–18:30",
  fx("THB", 300, None, src="https://wat-pho.org/tickets/", asof="2026-10"),
  "46 米长的金色卧佛，也是泰式按摩的发源地。", "离大皇宫步行 5 分钟。")
P("watarun", "bangkok", "郑王庙", "Wat Arun", "Wat Arun", 13.7437, 100.4888, "culture", 5, 1,
  "每日 8:00–18:00",
  fx("THB", 200, None, src="https://watarun.com/wat-arun-tickets/", asof="2026"),
  "湄南河畔用彩瓷镶嵌的高塔，从对岸看夕阳下的剪影最美。", "从卧佛寺旁的码头坐渡轮过河。", slot="evening")
P("chatuchak", "bangkok", "乍都乍周末市场", "Chatuchak Weekend Market", "Chatuchak Weekend Market", 13.7999, 100.5510, "food", 4, 3,
  "周六、周日 9:00–18:00（周五晚上部分开）", free(), "上万个摊位的超大市场，衣服、手工艺品和椰子冰淇淋。", "只在周末开，坐 MRT 到 Chatuchak Park。",
  closed=only(6, 0), eat=[("椰子冰淇淋", "", ""), ("西班牙海鲜饭（市场名摊）", "", ""), ("泰式烤肉串", "", "")])
P("skywalk", "bangkok", "王权大京都 SkyWalk", "Mahanakhon SkyWalk", "King Power Mahanakhon", 13.7234, 100.5283, "city", 3, 1.5,
  "10:00–19:00",
  fx("THB", 900, None, approx=True, src="https://itsbetterinthailand.com/our-100-best-things-to-do-in-thailand-in-2026/", asof="2026",
     note="约价；日落时段较贵"),
  "314 米高的玻璃地板观景台，曼谷最高的户外观景点。", slot="evening")
P("asiatique", "bangkok", "Asiatique 河滨夜市", "Asiatique The Riverfront", "Asiatique The Riverfront", 13.7040, 100.5030, "food", 3, 2,
  "约 16:00–24:00", free(), "旧码头仓库改建的夜市，有摩天轮和河景餐厅。", slot="night")
P("goldenmount", "bangkok", "金山寺", "Golden Mount (Wat Saket)", "Wat Saket", 13.7538, 100.5066, "culture", 3, 1,
  "约 7:30–19:00",
  fx("THB", 100, None, src="https://www.khaosanroad.com/articles/bangkok-temple-run-ticket-strategy-fees-opening-hours-wat-pho-grand-palace-golden-mount", asof="2026-09"),
  "人工山丘上的金色佛塔，爬 300 多级阶梯可看老城全景。", slot="evening")
P("damnoen", "bangkok", "丹嫩沙多水上市场", "Damnoen Saduak Floating Market", "Damnoen Saduak Floating Market", 13.5190, 99.9590, "food", 3, 3,
  "约 7:00–14:00", free("免费入场；坐船游市场另付费"), "最有名的水上市场，小船载满水果和船面。", "离曼谷约 100 公里，越早到越好。", slot="morning")
P("safariworld", "bangkok", "曼谷野生动物园", "Safari World", "Safari World", 13.8600, 100.7050, "theme", 3, 5,
  "约 9:00–17:00",
  fx("THB", 1500, 1400, note="野生动物园 + 海洋公园联票柜台价；网上预订约 ฿1,020。身高 100 厘米以下儿童免费",
     src="https://thaiest.com/en/blog/safari-world-bangkok-schedule", asof="2026-06", approx=True),
  "开车穿越的野生动物园加海洋世界表演，适合亲子。")
P("ayutthaya", "ayutthaya", "大城历史公园", "Ayutthaya Historical Park", "Ayutthaya Historical Park", 14.3560, 100.5580, "culture", 5, 4,
  "约 8:30–17:00",
  fx("THB", 50, None, src="https://ayutthayatemple-guide.com/tickets/ayutthaya-temple-entrance-fees/", asof="2026",
     note="每座寺庙 ฿50；六寺联票 ฿220（30 天有效）"),
  "暹罗古都遗址，树根缠绕的佛头和高耸的佛塔，世界文化遗产。", "租脚踏车或嘟嘟车逛最方便；离曼谷约 1.5 小时。")
P("sanctuarytruth", "pattaya", "真理寺", "Sanctuary of Truth", "Sanctuary of Truth", 12.9727, 100.8890, "culture", 4, 2,
  "约 8:00–18:00",
  fx("THB", 500, None, src="https://itsbetterinthailand.com/our-100-best-things-to-do-in-thailand-in-2026/", asof="2026"),
  "全部用木头雕成、高 105 米的海边神殿，至今仍在施工。")
P("nongnooch", "pattaya", "东芭乐园", "Nong Nooch Tropical Garden", "Nong Nooch Tropical Botanical Garden", 12.7650, 100.9340, "nature", 4, 4,
  "每日 8:00–18:00",
  fx("THB", 480, None, approx=True, src="https://www.click2gothailand.com/tours/00448",
     note="外国人网上优惠价（含文化表演）；身高 90 cm 以下免费"),
  "占地广阔的热带植物园，有泰国文化表演和大象秀。")
P("kohlarn", "pattaya", "格兰岛", "Koh Larn", "Ko Lan", 12.9200, 100.7800, "beach", 3, 5,
  "全天", None, "从芭提雅坐渡轮约 45 分钟，海水比芭提雅海滩清澈得多。", "在巴厘海码头坐公共渡轮最便宜。")
P("walkingstreet", "pattaya", "芭提雅步行街", "Walking Street", "Walking Street, Pattaya", 12.9270, 100.8720, "food", 3, 2,
  "傍晚至凌晨", free(), "霓虹灯闪烁的夜生活大街。", slot="night")
P("doisuthep", "chiangmai", "双龙寺", "Wat Phra That Doi Suthep", "Wat Phra That Doi Suthep", 18.8050, 98.9216, "culture", 5, 2,
  "约 6:00–20:00",
  fx("THB", 30, None, approx=True, src="https://beliholiday.com/blogs/181/wat-phra-that-doi-suthep-opening-hours-entrance-fee-visitor-guide",
     note="各来源 ฿30–50 不等；登山缆车另付约 ฿20–50"),
  "山顶金色佛塔俯瞰清迈城，爬 306 级龙形阶梯上去。", "傍晚去可以看日落和城市灯光。")
P("chediluang", "chiangmai", "契迪龙寺", "Wat Chedi Luang", "Wat Chedi Luang", 18.7869, 98.9866, "culture", 4, 1,
  "约 8:00–17:00",
  fx("THB", 40, None, note="外国游客门票 ฿40，泰国人免费", src="https://www.phuket101.net/wat-chedi-luang/", asof="2026-06", approx=True),
  "古城中心 14 世纪的巨大残塔，可以和僧侣聊天。")
P("nightbazaar", "chiangmai", "清迈夜市", "Chiang Mai Night Bazaar", "Chiang Mai Night Bazaar", 18.7870, 99.0000, "food", 3, 2,
  "每晚", free(), "长康路一带的夜市，手工艺品、泰北小吃和现场音乐，每天都开。", "周六、周日晚上改去步行街更有特色。", slot="night",
  eat=[("泰北香肠", "", ""), ("芒果糯米饭", "", ""), ("烤肉串", "", "")])
P("elephantpark", "chiangmai", "大象自然公园", "Elephant Nature Park", "Elephant Nature Park", 19.2140, 98.8590, "nature", 4, 6,
  "需提前预约（含接送）",
  fx("THB", 2500, None, approx=True, src="https://itsbetterinthailand.com/our-100-best-things-to-do-in-thailand-in-2026/", asof="2026",
     note="半日行程约 ฿2,500–3,500，含接送"),
  "不骑大象的救援保护园区，近距离观察和喂食大象。", "选择不提供骑象和表演的保护营。")
P("whitetemple", "chiangrai", "白庙", "Wat Rong Khun (White Temple)", "Wat Rong Khun", 19.8242, 99.7631, "culture", 4, 1.5,
  "约 8:00–17:00",
  fx("THB", 200, None, src="https://www.khaosodenglish.com/tourism/2025/11/10/chiang-rais-white-temple-doubles-admission-fee-for-foreign-tourists/", asof="2026-01",
     note="含艺术洞穴；身高 120 cm 以下和 70 岁以上免费"),
  "通体洁白、镶满镜片的当代佛寺，由泰国艺术家设计。", "从清迈出发单程约 3 小时，适合一日游。")
P("bigbuddha", "phuket", "普吉大佛", "Big Buddha Phuket", "Big Buddha Phuket", 7.8275, 98.3127, "culture", 4, 1,
  "约 8:00–19:30", free("免费，欢迎随喜捐款"), "45 米高的白色大理石佛像，山顶可看查龙湾全景。")
P("phukettown", "phuket", "普吉老镇", "Phuket Old Town", "Thalang Road", 7.8840, 98.3880, "culture", 4, 2,
  "白天；周日晚有步行街", free(), "中葡式彩色骑楼老街，咖啡馆和壁画很多。")
P("phiphi", "phuket", "皮皮岛一日游", "Phi Phi Islands", "Phi Phi Islands", 7.7407, 98.7784, "beach", 5, 9,
  "跟团一日游（8 月、9 月玛雅湾关闭）",
  fx("THB", 400, 200, src="https://simbaseatrips.com/blog/phuket/national-park-fees-in-phuket-what-you-need-to-know/", asof="2026",
     note="国家公园费；快艇团费另计（各家价格不同）"),
  "电影《海滩》取景的玛雅湾和碧绿的皮莱湾，浮潜看珊瑚。", "选早班快艇避开人潮；8–9 月玛雅湾关闭。")
P("phangnga", "phuket", "攀牙湾（007 岛）", "Phang Nga Bay", "Phang Nga Bay", 8.2750, 98.5010, "nature", 4, 7,
  "跟团一日游",
  fx("THB", 300, 150, src="https://simbaseatrips.com/blog/phuket/national-park-fees-in-phuket-what-you-need-to-know/", asof="2026",
     note="国家公园费；团费另计"),
  "上百座石灰岩小岛矗立海上，划独木舟穿过海洞。")
P("patong", "phuket", "芭东海滩", "Patong Beach", "Patong", 7.8960, 98.2960, "beach", 3, 3,
  "全天；晚上邦古拉街最热闹", free(), "普吉最热闹的海滩，晚上的邦古拉街是夜生活中心。", slot="evening")
P("promthep", "phuket", "神仙半岛", "Promthep Cape", "Promthep Cape", 7.7620, 98.3050, "nature", 3, 1,
  "全天", free(), "普吉最南端的岬角，公认看日落最美的地方。", slot="evening")
P("railay", "krabi", "莱利海滩", "Railay Beach", "Railay Beach", 8.0110, 98.8380, "beach", 5, 5,
  "全天（只能坐船抵达）", free("海滩免费；从奥南坐长尾船往返另付"), "被石灰岩峭壁包围的海滩，攀岩和看日落的胜地。")
P("tigercave", "krabi", "虎窟寺", "Tiger Cave Temple", "Wat Tham Suea (Krabi)", 8.1250, 98.9230, "culture", 3, 2.5,
  "约 7:00–17:00", free(), "爬 1,260 级阶梯到山顶佛像，可以看甲米全景。", "清早爬，带足水。", slot="morning")
P("fourislands", "krabi", "甲米四岛游", "Krabi 4 Islands", "Ko Poda", 7.9690, 98.8100, "beach", 4, 6,
  "跟团一日游",
  fx("THB", 400, 200, src="https://simbaseatrips.com/blog/phuket/national-park-fees-in-phuket-what-you-need-to-know/", asof="2026",
     note="属于 Hat Noppharat Thara–Mu Ko Phi Phi 国家公园；团费另计"),
  "鸡岛、波达岛、塔岛和帕南海滩，退潮时能走在连接小岛的沙洲上。")

# ===================== INDONESIA =====================
S_BALI = "https://baliindotour.com/bali_entrance_fee.php"
P("barelang", "batam", "巴列朗大桥", "Barelang Bridge", "Barelang Bridge", 0.9930, 104.0680, "city", 3, 1.5,
  "全天", free(), "连接巴淡周边六个岛的六座大桥，傍晚景色最好。", slot="evening")
P("nagoya", "batam", "名古屋购物区与海鲜", "Nagoya, Batam", "Lubuk Baja", 1.1450, 104.0130, "food", 3, 2,
  "白天至晚上", free(), "巴淡最热闹的商业区，购物、按摩和平价海鲜。")
P("lagoi", "bintan", "民丹岛拉高度假区", "Lagoi Bay, Bintan", "Bintan Island", 1.1830, 104.3300, "beach", 4, 5,
  "全天", free(), "白沙滩度假村集中地，从新加坡坐船约 1 小时 15 分钟。")
P("monas", "jakarta", "雅加达独立纪念碑", "National Monument (Monas)", "National Monument (Indonesia)", -6.1754, 106.8272, "city", 4, 1.5,
  "约 8:00–22:00（周一顶部观景台关闭）",
  fx("IDR", 24000, 6000, src="https://news.detik.com/berita/d-7851172/harga-tiket-masuk-tmii-ragunan-hingga-ancol-di-libur-lebaran-2025/amp", asof="2025-04",
     note="广场免费；登顶 + 博物馆票价；需用 JakCard 交通卡付款"),
  "132 米高、顶端镀金火焰的纪念塔，可以登顶看雅加达市景。")
P("kotatua", "jakarta", "雅加达老城区", "Kota Tua", "Kota Tua Jakarta", -6.1352, 106.8133, "culture", 4, 2,
  "全天", free("广场免费；周边博物馆小额收费"), "荷兰殖民时期的巴达维亚旧城，法塔希拉广场和咖啡馆 Café Batavia。")
P("istiqlal", "jakarta", "伊斯蒂克拉尔清真寺", "Istiqlal Mosque", "Istiqlal Mosque, Jakarta", -6.1702, 106.8310, "culture", 3, 1,
  "礼拜时间以外开放", free(), "东南亚最大的清真寺，对面就是雅加达主教座堂。")
P("tmii", "jakarta", "印尼缩影公园", "Taman Mini Indonesia Indah", "Taman Mini Indonesia Indah", -6.3025, 106.8952, "culture", 3, 3,
  "约 7:00–22:00",
  fx("IDR", 25000, 25000, src="https://news.detik.com/berita/d-7851172/harga-tiket-masuk-tmii-ragunan-hingga-ancol-di-libur-lebaran-2025/amp", asof="2025-04",
     note="入园费，车辆另计；园内场馆另收费"),
  "一次看遍印尼各省的传统建筑和文化。")
P("ancol", "jakarta", "安佐尔梦幻公园", "Ancol Dreamland", "Ancol Dreamland", -6.1220, 106.8330, "theme", 3, 5,
  "全天（各园区时间不同）",
  fx("IDR", 35000, 35000, src="https://news.detik.com/berita/d-7851172/harga-tiket-masuk-tmii-ragunan-hingga-ancol-di-libur-lebaran-2025/amp", asof="2025-04",
     note="海滨区入场费；Dufan 游乐园、海洋世界另购票"),
  "雅加达海边的大型娱乐区，有 Dufan 游乐园和海滩。")
P("borobudur", "yogya", "婆罗浮屠", "Borobudur", "Borobudur", -7.6079, 110.2038, "culture", 5, 3,
  "园区 6:30–17:30；登塔 8:30–15:30（周一不开放登塔）",
  fx("IDR", 455000, None, src="https://borobudurtemple.org/hours-and-fees/", asof="2026",
     note="外国游客登塔票（含 1.5 小时导览，需网上预约，每日限 1,200 人）；只进园区 Rp412,500"),
  "世界最大的佛教遗迹，九层石坛上排列着 72 座钟形佛塔。", "提前在官网 borobudurpark.com 预约登塔时段。", closed=[1], slot="morning")
P("prambanan", "yogya", "普兰巴南神庙", "Prambanan", "Prambanan", -7.7520, 110.4915, "culture", 5, 2,
  "每日 6:30–17:00",
  fx("USD", 22.8, 15.26, src="https://jotuu.com/blogs/updated-prambanan-temple-ticket-price", asof="2025-09",
     note="外国游客价，约 Rp375,000；晚上有罗摩衍那芭蕾舞剧"),
  "印尼最大的兴都教神庙群，尖塔高 47 米，日落时特别壮观。", slot="evening")
P("kraton", "yogya", "日惹苏丹王宫", "Kraton Yogyakarta", "Kraton Ngayogyakarta Hadiningrat", -7.8053, 110.3642, "culture", 4, 1.5,
  "周二至周日 9:00–14:00",
  fx("IDR", 25000, 20000, src="https://www.indonesia.travel/my-zh-cn/destination/java/yogyakarta/karaton-ngayogyakarta"),
  "苏丹至今仍居住的王宫，上午常有爪哇传统音乐和舞蹈表演。", closed=[1], slot="morning")
P("tamansari", "yogya", "水宫", "Taman Sari", "Taman Sari (Yogyakarta)", -7.8100, 110.3594, "culture", 4, 1,
  "约 9:00–15:00",
  fx("IDR", 25000, 20000, note="外国游客价（2–12 岁算儿童）；印尼人成人 Rp15,000", approx=True,
     src="https://www.detik.com/jogja/plesir/d-7091026/harga-tiket-masuk-taman-sari-yogyakarta-dan-daya-tariknya", asof="2023-12"),
  "苏丹昔日的皇家浴池和地下清真寺，就在王宫旁边。")
P("malioboro", "yogya", "马里奥波罗街", "Malioboro Street", "Malioboro", -7.7926, 110.3658, "food", 4, 2,
  "全天，晚上最热闹", free(), "日惹的主街，蜡染布、街头小吃和街头艺人。", slot="night",
  eat=[("Angkringan 路边摊", "小包饭、炸物串和炭火咖啡（kopi joss）", ""), ("鸡肉沙爹", "", ""), ("Gudeg 菠萝蜜饭", "日惹名菜，附近 Wijilan 街最集中", "")])
P("merapi", "yogya", "默拉皮火山吉普车", "Merapi Lava Tour", "Mount Merapi", -7.5407, 110.4457, "nature", 3, 3,
  "清晨或下午", None, "坐吉普车在火山熔岩地带越野，看 2010 年火山爆发遗迹。", slot="sunrise")
P("tanahlot", "bali", "海神庙", "Tanah Lot", "Tanah Lot", -8.6212, 115.0868, "culture", 5, 2,
  "约 7:00–19:00",
  fx("IDR", 75000, None, approx=True, src=S_BALI, asof="2026-11"),
  "建在海中岩石上的神庙，日落剪影是巴厘岛最经典的画面。", "涨潮时不能走到岩石下。", slot="evening")
P("uluwatu", "bali", "情人崖 · 乌鲁瓦图寺", "Uluwatu Temple", "Uluwatu Temple", -8.8291, 115.0849, "culture", 5, 2.5,
  "约 7:00–19:00；克差舞 18:00 左右",
  fx("IDR", 50000, None, approx=True, src=S_BALI, asof="2026-11",
     note="门票各来源 Rp30,000–50,000；日落克差火舞 Rp150,000（儿童 Rp100,000）"),
  "70 米高的悬崖上的神庙，日落时看克差火舞。", "看好眼镜和帽子，猴子会抢东西。", slot="evening")
P("tegallalang", "bali", "德格拉朗梯田", "Tegallalang Rice Terrace", "Tegallalang Rice Terrace", -8.4325, 115.2792, "nature", 4, 1.5,
  "约 8:00–18:00",
  fx("IDR", 15000, None, approx=True, src=S_BALI, asof="2026-11", note="入场费；秋千等拍照项目另付"),
  "乌布北边层层叠叠的梯田，椰子树点缀其间。", "早上 8 点前光线最好、人最少。", slot="morning")
P("monkeyforest", "bali", "乌布圣猴森林", "Ubud Monkey Forest", "Ubud Monkey Forest", -8.5188, 115.2585, "nature", 4, 1.5,
  "约 9:00–18:00",
  fx("IDR", 80000, None, approx=True, src=S_BALI, asof="2026-11"),
  "古树和神庙之间住着上千只长尾猕猴。", "不要带食物、塑料袋进去。")
P("tirtaempul", "bali", "圣泉寺", "Tirta Empul", "Tirta Empul", -8.4152, 115.3154, "culture", 4, 1.5,
  "约 8:00–17:00",
  fx("IDR", 50000, None, approx=True, src=S_BALI, asof="2026-11"),
  "巴厘岛人沐浴净身的圣泉，游客也可以下池体验。")
P("ulundanu", "bali", "水神庙", "Ulun Danu Beratan", "Pura Ulun Danu Bratan", -8.2752, 115.1668, "culture", 4, 1.5,
  "约 7:00–19:00",
  fx("IDR", 75000, None, approx=True, src=S_BALI, asof="2026-11"),
  "布拉坦湖上的神庙，背后是云雾缭绕的群山，印在 5 万盾钞票上。")
P("besakih", "bali", "百沙基母庙", "Besakih Temple", "Besakih", -8.3741, 115.4510, "culture", 3, 2,
  "约 8:00–18:00",
  fx("IDR", 60000, None, approx=True, src=S_BALI, asof="2026-11"),
  "阿贡火山山腰上的巴厘岛最大、最神圣的神庙群。")
P("lempuyang", "bali", "天堂之门（Lempuyang 寺）", "Lempuyang Temple", "Pura Lempuyang Luhur", -8.3910, 115.6300, "culture", 3, 3,
  "约 6:00–18:00",
  fx("IDR", 55000, None, approx=True, src=S_BALI, asof="2026-11", note="另付接驳车 Rp45,000"),
  "以阿贡火山为背景的「天堂之门」，排队拍照可能要等很久。", "清晨去排队较短。", slot="sunrise")
P("batur", "bali", "巴杜尔火山日出徒步", "Mount Batur sunrise trek", "Mount Batur", -8.2420, 115.3750, "nature", 4, 6,
  "凌晨 3–4 点出发", None, "摸黑登上活火山，在火山口看日出和云海。", "参加有向导的团，带头灯和外套。", slot="sunrise")
P("nusapenida", "bali", "佩尼达岛一日游", "Nusa Penida day trip", "Nusa Penida", -8.7270, 115.5440, "beach", 4, 9,
  "跟团一日游（从沙努尔坐快船约 45 分钟）", None, "精灵坠崖、破碎海滩和天使浴池，悬崖海景震撼。", "岛上路况差，参加包车团最省事。")
P("seminyak", "bali", "水明漾 · 库塔海滩", "Seminyak & Kuta Beach", "Seminyak", -8.6900, 115.1580, "beach", 3, 3,
  "全天", free(), "冲浪、海滩俱乐部和看日落的地方。", slot="evening")
P("jatiluwih", "bali", "佳蒂露维梯田", "Jatiluwih Rice Terraces", "Jatiluwih", -8.3700, 115.1310, "nature", 3, 2,
  "约 8:00–18:00",
  fx("IDR", 40000, None, approx=True, src=S_BALI, asof="2026-11"),
  "列入世界遗产的巴厘岛灌溉梯田，规模比德格拉朗更大、人更少。")
P("samosir", "toba", "沙摩西岛", "Samosir Island", "Samosir", 2.5833, 98.8167, "nature", 4, 6,
  "全天", free("从巴拉巴 Parapat 坐渡轮约 1 小时"), "世界最大火山湖中的岛，巴塔克传统船形屋和石椅遗址。")
P("sipisopiso", "toba", "西皮索皮索瀑布", "Sipiso-piso Waterfall", "Sipiso-piso", 2.9170, 98.5190, "nature", 3, 1.5,
  "白天", None, "120 米高的瀑布从峭壁直落峡谷，可远眺多巴湖。")

# ---------- curated routes: id, name, days, start region, note, [[place ids per day]]
# ===================== FOOD & NIGHT MARKETS (more) =====================
KLNM = "https://the-malaysia-move.pages.dev/kl-night-markets/"
PGF = "https://www.penang-traveltips.com/malaysia/penang/must-try-penang-food.htm"
IPF = "https://www.travelswithsun.com/best-ipoh-food/"
P("connaught", "kl", "康乐花园夜市（周三）", "Taman Connaught Night Market", "Taman Connaught", 3.0827, 101.7375, "food", 3, 2,
  "只在周三，约 17:00–24:00（19:00–21:00 最热闹）", free(), "吉隆坡最长的夜市之一，两公里的摊位从台湾小吃到烧烤、水果应有尽有。",
  "人很多，坐 MRT 到 Taman Connaught 站，或者叫 Grab。", closed=only(3), slot="night", geo="Taman Connaught, Cheras",
  eat=[("臭豆腐 · 台湾小吃", "", ""), ("烧烤串", "", ""), ("珍珠奶茶 · 甘蔗水", "", ""), ("煎蕊 · 新鲜水果", "", ""),
       ("小吃价钱", "大多 RM2–10，一晚吃饱约 RM30/人", "RM2–10")], src=KLNM, asof="2026-05")
P("newlane", "penang", "新巷小贩中心（Lorong Baru）", "New Lane Hawker Stalls", "", 5.4148, 100.3265, "food", 4, 1.5,
  "约 16:00–22:00，周三休息", free(), "槟城人晚上吃宵夜的老地方，街边一整排小贩，鸭蛋炒粿条最出名。", "周三大部分摊位休息。",
  closed=[3], slot="night", geo="Lorong Baru, George Town, Penang",
  eat=[("鸭蛋炒粿条", "", "约 RM12"), ("炒粿角", "", ""), ("卤肉 · 沙爹", "", ""), ("云吞面 · 粿汁", "", ""),
       ("一般小吃", "", "RM5–10")], src="https://thebackpackingfamily.com/?p=5641", asof="2025-12")
P("airitamlaksa", "penang", "亚依淡叻沙", "Air Itam Laksa", "", 5.4005, 100.2782, "food", 3, 1,
  "白天（以现场为准）", free(), "极乐寺山脚的老字号亚参叻沙，酸辣鱼汤配粗米粉、黄梨和薄荷叶。", "和极乐寺一起安排最顺路。",
  eat=[("亚参叻沙", "罗望子鱼汤，酸辣开胃", "")], src=PGF, asof="2025-08")
P("cendol", "penang", "槟城路驰名潮州煎蕊", "Penang Road Famous Teochew Chendul", "", 5.4172, 100.3305, "food", 3, 0.5,
  "白天（以现场为准）", free(), "1932 年开到现在的街边煎蕊摊，椰浆、椰糖、红豆和斑兰绿条，排队也值得。",
  geo="Lebuh Keng Kwee, George Town", eat=[("煎蕊", "椰糖浆 + 椰浆 + 红豆", ""), ("亚参叻沙", "对面小档", "")], src=PGF, asof="2025-08")
P("lineclear", "penang", "Line Clear 嘛嘛饭", "Line Clear Nasi Kandar", "", 5.4191, 100.3330, "food", 3, 1,
  "从早到深夜（以现场为准）", free(), "小巷里的老牌印度穆斯林饭档，白饭淋上好几种咖喱，配炸鸡和蛋。",
  geo="Line Clear Nasi Kandar, Penang", eat=[("嘛嘛饭（Nasi Kandar）", "饭 + 鸡 + 蛋 + 菜 + 多种咖喱", "约 RM6.50 起")], src=PGF, asof="2025-08")
P("ipohcoffee", "ipoh", "旧街场白咖啡（新源隆 · 南香）", "Ipoh White Coffee (Sin Yoon Loong & Nam Heong)", "", 4.5972, 101.0787, "food", 4, 1,
  "新源隆 6:30–14:30；南香 6:30–16:30", free(), "怡保白咖啡的发源地，两家老店就在同一条街，配咖椰吐司和蛋挞。", "早上去，中午前人最多。",
  slot="morning", geo="Jalan Bandar Timah, Ipoh",
  eat=[("白咖啡", "", ""), ("蛋挞", "南香最出名", ""), ("咖椰吐司 · 半生熟蛋", "", "")], src=IPF, asof="2026-08")
P("ipohtauge", "ipoh", "怡保芽菜鸡", "Ipoh Bean Sprout Chicken", "", 4.5905, 101.0880, "food", 3, 1,
  "Cowan Street 店：周四至周日 18:00–24:00（时间不太固定）", free(), "又短又胖的怡保芽菜配白斩鸡和河粉，是怡保晚餐的标配。",
  "另外两家老字号老黄、安记在 Jalan Yau Tet Shin，白天到晚上都开。", closed=only(4, 5, 6, 0), slot="evening", geo="Jalan Raja Ekram, Ipoh",
  eat=[("芽菜鸡", "", ""), ("鸡丝河粉（Sar Hor Fun）", "", "")], src=IPF, asof="2026-08")
P("ricebal", "melaka", "中华茶室鸡饭粒", "Chung Wah Chicken Rice Balls", "", 2.1955, 102.2478, "food", 4, 1,
  "约 9:00–15:00，常常中午就卖完", free(), "马六甲招牌鸡饭粒，就在鸡场街口，半只鸡配饭粒是标准吃法。", "越早去越好；附近还有和记（周四休）、Famosa。",
  geo="Jalan Hang Jebat, Melaka",
  eat=[("鸡饭粒", "", "每粒约 RM0.40–0.60"), ("半只鸡 + 约 10 粒饭", "", "约 RM30"), ("两人一餐", "", "约 RM25–40")],
  src="https://the-malaysia-move.pages.dev/malacca-chicken-rice-balls/", asof="2026-05")
P("pasirpenambang", "kselangor", "巴斯班南海鲜村", "Pasir Penambang Seafood", "", 3.3445, 101.2560, "food", 3, 1.5,
  "午餐至晚餐（各家不同）", free(), "瓜拉雪兰莪河口的渔村，一排海鲜楼，看萤火虫前在这里吃晚餐最顺路。", "海鲜按重量算，点之前先问价。",
  slot="evening", geo="Pasir Penambang, Kuala Selangor",
  eat=[("清蒸鱼 · 奶油虾", "", "按重量"), ("辣椒螃蟹", "", "按重量"), ("炒啦啦（花蛤）", "", "")])
P("langkawinight", "langkawi", "兰卡威流动夜市", "Langkawi Night Markets", "", 6.3270, 99.8460, "food", 3, 1.5,
  "每天换地方，约 17:00–22:00", free(), "当地人的流动夜市（Pasar Malam），每天在岛上不同村镇摆摊，沙爹、椰浆饭和各种马来小吃。",
  "下面的列表按星期几告诉你今天在哪里。", slot="night",
  spots=[(1, "Ulu Melaka 夜市", "Pasar Malam Ulu Melaka Langkawi"), (2, "Kedawang 夜市", "Pasar Malam Kedawang Langkawi"),
         (3, "瓜镇 Kuah 夜市", "Pasar Malam Kuah Langkawi"), (4, "Temonyong 夜市", "Pasar Malam Temonyong Langkawi"),
         (5, "Ayer Hangat 夜市", "Pasar Malam Ayer Hangat Langkawi"), (6, "瓜镇 Kuah 夜市", "Pasar Malam Kuah Langkawi"),
         (0, "Padang Matsirat 夜市", "Pasar Malam Padang Matsirat Langkawi")],
  eat=[("沙爹", "", ""), ("煎饼（Apam Balik）", "", ""), ("椰浆饭（Nasi Lemak）", "", ""), ("烤鸡 · 海鲜", "", "")],
  src="https://www.travelswithsun.com/langkawi-night-market/")
P("maxwell", "sg", "麦士威熟食中心", "Maxwell Food Centre", "Maxwell Food Centre", 1.2803, 103.8448, "food", 4, 1,
  "各摊位时间不同（大多早上至晚上）", free(), "牛车水旁边的熟食中心，天天海南鸡饭上过米其林推荐，中午排队最长。", "天天鸡饭周一休息；避开 12:00–13:30 的上班族午餐时间。",
  geo="Maxwell Food Centre, Singapore",
  eat=[("天天海南鸡饭", "周二至周日 10:00–20:00", "S$5 起"), ("亚太海南鸡饭", "周二休息", "S$5 起"),
       ("真真粥品", "周三、周五至周日 5:30–14:00", "S$4–5")], src="https://eatbook.sg/maxwell-food-centre-guide/", asof="2026-09")
P("oldairport", "sg", "旧机场路熟食中心", "Old Airport Road Food Centre", "Old Airport Road Food Centre", 1.3082, 103.8855, "food", 3, 1,
  "各摊位时间不同（大多早上至晚上）", free(), "本地人最爱的大型熟食中心，几十家老字号虾面、炒粿条、沙爹和豆花。",
  geo="Old Airport Road Food Centre, Singapore",
  eat=[("Albert Street 虾面", "", "S$4–5"), ("南星福建虾面", "", "S$3–5"), ("Lucky 炒粿条", "", "S$3–5"),
       ("老伴豆花", "周一休息", "S$2.90 / 杏仁 S$3.60"), ("全记沙爹", "晚上开，周一、周四休息", "每串 S$0.40–0.50")],
  src="https://www.thebestsingapore.com/?p=3393", asof="2026-05")
P("yaowarat", "bangkok", "耀华力路唐人街夜食", "Yaowarat Street Food", "Yaowarat Road", 13.7402, 100.5096, "food", 5, 2.5,
  "每晚约 17:00 开始，18:30–22:30 最热闹（部分摊位周一休息）", free(), "曼谷唐人街一到晚上就变成大排档，粿汁、蚝煎、鱼鳔汤和海鲜一路吃过去。",
  "坐 MRT 到 Wat Mangkon 站；热门摊要排 20–40 分钟。", slot="night", geo="Yaowarat Road, Bangkok",
  eat=[("粿汁（Guay Jub）", "胡椒汤卷粉", "฿60–100"), ("蚝煎", "", "฿120–200"), ("蟹肉炒饭", "", "฿150–280"),
       ("鱼鳔汤", "", "฿60–100"), ("沙爹猪肉（10 串）", "", "฿70–90"), ("芒果糯米饭", "", "฿80–150"), ("海鲜拼盘", "", "฿400–1,200")],
  src="https://www.khaosanroad.com/articles/yaowarat-street-food-guide", asof="2026-06")
P("joddfairs", "bangkok", "Jodd Fairs 拉差达夜市", "Jodd Fairs Ratchada", "", 13.7656, 100.5699, "food", 4, 2,
  "每天约 17:00–01:00", free(), "曼谷最红的夜市，招牌是堆成小山的火山排骨，还有烤河虾和海鲜桶。",
  "旧的拉玛九 Rama 9 场地已经关了，现在在 Big C Place Ratchadaphisek 旁边；MRT 泰国文化中心站 4 号出口走 3 分钟。",
  slot="night", geo="Big C Place Ratchadaphisek, Bangkok",
  eat=[("火山排骨（Leng Saap）", "两个人点中份就够", "中 ฿180 / 大 ฿250 / 特大 ฿350"), ("烤河虾", "", "฿200–450"),
       ("盐烤鱼", "", "฿250–400"), ("海鲜桶", "适合 3–4 人分", "฿500–900"), ("烤肉串", "", "每串 ฿15–40"),
       ("两人晚餐连饮料", "", "约 ฿600–1,200")],
  src="https://travjoy.com/blog/jodd-fairs-night-market-bangkok-a-complete-2026-guide-to-the-ratchada-site", asof="2026-07")
P("greenway", "hatyai", "合艾绿道夜市 Greenway", "Greenway Night Market", "", 7.0140, 100.4900, "food", 3, 1.5,
  "周二至周日 17:00–22:00，周一休息", free(), "合艾最大的美食夜市，炸鸡、烤鱿鱼、海鲜桶，清真食品也多。", "离 ASEAN Night Bazaar 只有 400 米。",
  closed=[1], slot="night", geo="Greenway Night Market, Hat Yai",
  eat=[("合艾炸鸡", "", ""), ("烤鱿鱼", "", ""), ("海鲜桶", "", ""), ("清真烧烤 · 甜品", "", "")],
  src="https://www.lovethailand.org/travel/en/73-Songkhla/8192-Greenway-Market.html")
P("sundaywalk", "chiangmai", "清迈周日步行街", "Chiang Mai Sunday Walking Street", "", 18.7883, 98.9905, "food", 5, 2.5,
  "只在周日 16:30–23:00", free(), "从塔佩门到帕辛寺的整条拉差当嫩路封街，手工艺品加上寺庙里的美食区。",
  "人非常多，傍晚 5 点前到比较好走。", closed=only(0), slot="night", geo="Ratchadamnoen Road, Chiang Mai",
  eat=[("泰北香肠（Sai Ua）", "", ""), ("泰北咖喱面（Khao Soi）", "", ""), ("寺庙里的小吃摊", "", "")],
  src="https://www.expique.com/the-best-markets-in-chiang-mai/", asof="2023-03")
P("saturdaywalk", "chiangmai", "清迈周六步行街（Wua Lai）", "Chiang Mai Saturday Walking Street", "", 18.7808, 98.9845, "food", 4, 2,
  "只在周六 16:30–23:00", free(), "银器街 Wua Lai 路的周六夜市，比周日步行街小一点、好逛一点。", closed=only(6), slot="night",
  geo="Wua Lai Road, Chiang Mai",
  eat=[("泰北香肠（Sai Ua）", "", ""), ("泰北咖喱面（Khao Soi）", "", ""), ("南咪粉（Khanom Jeen Nam Ngiao）", "", "")],
  src="https://www.expique.com/the-best-markets-in-chiang-mai/", asof="2023-03")
P("warorot", "chiangmai", "瓦洛洛市场", "Warorot Market (Kad Luang)", "Warorot Market", 18.7905, 99.0003, "food", 3, 1.5,
  "每天约 4:00–18:00", free(), "清迈本地人的大市场，泰北熟食、零食和手信都便宜。", geo="Warorot Market, Chiang Mai",
  eat=[("汉莱咖喱（Gaeng Hunglay）", "缅甸风味猪肉咖喱", ""), ("炸猪皮（Kap Moo）", "", ""), ("泰北香肠", "", "")],
  src="https://www.expique.com/the-best-markets-in-chiang-mai/", asof="2023-03")
P("phuketsunday", "phuket", "普吉老镇周日夜市（Lard Yai）", "Phuket Town Sunday Walking Street", "", 7.8853, 98.3888, "food", 4, 2,
  "只在周日 16:00–22:00", free(), "老镇他朗路封街的周日夜市，普吉本地小吃最集中。", closed=only(0), slot="night", geo="Thalang Road, Phuket",
  eat=[("蚝仔煎（O-tao）", "加芋头的普吉版蚝煎", ""), ("椰子小煎饼（A-pong）", "", ""), ("刨冰（O-eaw）", "", ""),
       ("福建面", "", ""), ("卤味串（Loba）", "", "")], src="https://21sensations.com/phuket-town-night-market/", asof="2026-04")
P("krabinight", "krabi", "甲米镇周末夜市", "Krabi Town Weekend Night Market", "", 8.0645, 98.9161, "food", 3, 1.5,
  "周五、周六 17:00 后（19:30–22:00 最热闹）", free(), "甲米镇中心的步行街夜市，以小吃为主，座位不多，大家都边走边吃。",
  closed=only(5, 6), slot="night", eat=[("烤肉串 · 炸物", "", ""), ("泰式甜品", "", ""), ("果汁 · 奶茶", "", "")],
  src="https://krabinature.com/krabi-night-markets-in-town-and-ao-nang-beach/", asof="2026-09")
P("aonangnight", "krabi", "奥南海滩夜市", "Ao Nang Landmark Night Market", "", 8.0432, 98.8114, "food", 3, 1.5,
  "傍晚开始（以现场为准）", free(), "奥南海边最大的夜市，烤鸡、烤鱼、烤猪肉和水果甜品。", slot="night",
  eat=[("烤鸡 · 烤鱼", "", ""), ("烤猪肉", "", ""), ("水果 · 甜品", "", "")],
  src="https://krabinature.com/krabi-night-markets-in-town-and-ao-nang-beach/", asof="2026-09")
P("thepprasit", "pattaya", "Thepprasit 夜市", "Thepprasit Night Market", "", 12.9050, 100.8840, "food", 3, 1.5,
  "每晚约 17:00–23:00，周五至周日最热闹", free(), "芭提雅南边最大的夜市，盐烤鱼、烤猪肉串配糯米饭和各种泰式小吃。", "带零钱，大部分摊位不标价。",
  slot="night", geo="Thepprasit Night Market, Pattaya",
  eat=[("盐烤鱼（Pla Pao）", "", ""), ("烤猪肉串配糯米饭", "", ""), ("泰式炒河粉 · 青木瓜沙拉", "", ""), ("芒果糯米饭 · 椰奶小煎饼", "", "")],
  src="https://pattayapointer.pages.dev/guides/pattaya-night-markets-guide", asof="2026-10")
P("gianyar", "bali", "吉安雅夜市", "Gianyar Night Market", "", -8.5420, 115.3260, "food", 4, 1.5,
  "每天 16:00–23:00", free(), "巴厘岛最大的本地夜市，离乌布约 10 公里，烤乳猪饭便宜又地道。", slot="night", geo="Pasar Gianyar, Bali",
  eat=[("烤乳猪饭（Babi Guling）", "", "约 Rp15,000"), ("巴厘沙爹（Sate Lilit）", "", "")],
  src="https://forevervacation.com/the-vacationer/best-night-markets-in-bali", asof="2026")
P("sindhu", "bali", "沙努尔 Sindhu 夜市", "Sindhu Night Market, Sanur", "", -8.6835, 115.2620, "food", 3, 1,
  "每天 17:00–22:00", free(), "沙努尔海边小镇的小夜市，适合住在沙努尔的人散步过去吃晚餐。", slot="night",
  geo="Sindhu Night Market, Sanur",
  eat=[("巴厘沙爹（Sate Lilit）", "", "Rp15,000–25,000"), ("炒饭 · 炒面", "", "")],
  src="https://forevervacation.com/the-vacationer/best-night-markets-in-bali", asof="2026")
P("jimbaran", "bali", "金巴兰海滩海鲜晚餐", "Jimbaran Seafood Dinner", "Jimbaran", -8.7780, 115.1650, "food", 4, 2,
  "海滩餐厅傍晚开始；鱼市约 7:00–19:00", free(), "在沙滩上一边看日落一边吃炭烤海鲜，是巴厘岛的经典晚餐。",
  "海滩餐厅比自己去 Kedonganan 鱼市买了再请人烤贵很多；点之前一定问清每公斤价钱。", slot="evening",
  eat=[("红鲷鱼（鱼市价）", "", "每公斤 Rp90,000–130,000"), ("虾（鱼市价）", "", "每公斤 Rp100,000–200,000"),
       ("螃蟹（鱼市价）", "", "每公斤 Rp150,000–180,000"), ("龙虾（鱼市价）", "", "每公斤 Rp400,000–500,000"),
       ("代烤费", "鱼市旁的烧烤档", "每公斤 Rp15,000")], src="https://backpackmoments.com/jimbaran-fish-market/", asof="2025-01")
P("gudeg", "yogya", "Wijilan 菠萝蜜饭街", "Gudeg Wijilan", "", -7.8058, 110.3672, "food", 3, 1,
  "各家时间不同（大多早上至晚上）", free(), "王宫北广场东边的一条街，全是卖日惹名菜 Gudeg（甜味菠萝蜜炖菜饭）的老店。",
  "Gudeg 偏甜，配辣的 krecek（牛皮辣酱）一起吃。", geo="Jalan Wijilan, Yogyakarta",
  eat=[("Gudeg 菠萝蜜饭", "配鸡肉、鸡蛋和牛皮辣酱", "")], src="https://simplyindonesia.pages.dev/yogyakarta/best-gudeg-in-yogyakarta/", asof="2026-05")

ROUTES = [
    ("klclassic", "吉隆坡经典 3 天", "kl", "第一次来吉隆坡：地标、黑风洞、布城和萤火虫。",
     [["petronas", "aquaria", "iamm", "merdeka", "petaling"], ["batucaves", "birdpark", "kltower", "theanhou", "bukitbintang"], ["putramosque", "putracruise", "melawati", "fireflies"]]),
    ("melaka2", "马六甲古城 2 天", "melaka", "慢游世界遗产古城，周五或周六出发能逛鸡场街夜市。",
     [["afamosa", "dutchsquare", "chenghoonteng", "babanyonya", "jonker"], ["tamingsari", "straitsmosque", "rivercruise"]]),
    ("highlands", "云顶 + 金马伦 3 天", "genting", "高原避暑：主题乐园、茶园和草莓园。",
     [["awana", "skyworlds", "chinswee"], ["bohtea", "strawberry", "cameronmarket"], ["mossy"]]),
    ("penangipoh", "怡保 + 槟城 4 天", "ipoh", "北马美食与老城之旅。",
     [["ipoholdtown", "sampohtong", "keklotong"], ["kellies", "georgetown", "khookongsi", "gurney"], ["keklosi", "penanghill", "habitat"], ["entopia", "peranakan", "batuferringhi"]]),
    ("langkawi3", "兰卡威海岛 3 天", "langkawi", "免税岛：缆车、红树林和日落海滩。",
     [["skycab", "cenang"], ["kilim", "eaglesq"], ["islandhop", "underwater"]]),
    ("eastcoast", "东海岸海岛 4 天", "kt", "3–10 月出发：水晶清真寺和热浪岛。",
     [["crystalmosque", "ktchinatown"], ["redang"], ["redang"], ["sitikhadijah"]]),
    ("sg3", "新加坡 3 天", "sg", "滨海湾、环球影城和动物园。",
     [["chinatownsg", "littleindia", "gardensbay", "merlion", "laupasat"], ["uss", "sentosa"], ["sgzoo", "riverwonders", "nightsafari"]]),
    ("hatyai2", "合艾自驾 2 天", "hatyai", "从北马开车过境，吃喝逛买。",
     [["kimyong", "hatyaipark", "hatyaifloating"], ["samila", "watHatYaiNai"]]),
    ("bangkok4", "曼谷 + 大城 4 天", "bangkok", "大皇宫、郑王庙、水上市场和古都。",
     [["grandpalace", "watpho", "watarun", "asiatique"], ["chatuchak", "goldenmount", "skywalk"], ["ayutthaya"], ["damnoen"]]),
    ("chiangmai3", "清迈 + 清莱 3 天", "chiangmai", "泰北寺庙与大象保护营。",
     [["doisuthep", "chediluang", "nightbazaar"], ["elephantpark"], ["whitetemple"]]),
    ("phuketkrabi", "普吉 + 甲米 5 天", "phuket", "安达曼海跳岛之旅。",
     [["bigbuddha", "phukettown", "promthep"], ["phiphi"], ["phangnga", "patong"], ["railay"], ["fourislands", "tigercave"]]),
    ("bali5", "巴厘岛 5 天", "bali", "神庙、梯田、火山和海岛。",
     [["tanahlot", "seminyak"], ["tegallalang", "monkeyforest", "tirtaempul"], ["batur"], ["ulundanu", "jatiluwih"], ["uluwatu"]]),
    ("yogya3", "日惹 3 天", "yogya", "婆罗浮屠、普兰巴南和苏丹王宫。",
     [["borobudur", "merapi"], ["kraton", "tamansari", "malioboro"], ["prambanan"]]),
]

# ---------- start towns: name, lat, lng (also matched by English name)
TOWNS = [
    ("吉隆坡 Kuala Lumpur", 3.1478, 101.6953), ("八打灵再也 Petaling Jaya", 3.1073, 101.6067), ("莎阿南 Shah Alam", 3.0733, 101.5185),
    ("巴生 Klang", 3.0449, 101.4456), ("梳邦再也 Subang Jaya", 3.0565, 101.5851), ("蒲种 Puchong", 3.0256, 101.6187),
    ("蕉赖 Cheras", 3.0915, 101.7456), ("加影 Kajang", 2.9935, 101.7871), ("万宜 / 雪邦 Sepang (KLIA)", 2.7456, 101.7072),
    ("万挠 Rawang", 3.3213, 101.5767), ("瓜拉雪兰莪 Kuala Selangor", 3.3400, 101.2550), ("芙蓉 Seremban", 2.7297, 101.9381),
    ("汝来 Nilai", 2.8166, 101.7979), ("波德申 Port Dickson", 2.5220, 101.7960), ("马六甲 Melaka", 2.1940, 102.2490),
    ("麻坡 Muar", 2.0442, 102.5689), ("峇株巴辖 Batu Pahat", 1.8548, 102.9325), ("居銮 Kluang", 2.0251, 103.3328),
    ("新山 Johor Bahru", 1.4927, 103.7414), ("古来 Kulai", 1.6561, 103.6032), ("丰盛港 Mersing", 2.4339, 103.8393),
    ("昔加末 Segamat", 2.5148, 102.8158), ("关丹 Kuantan", 3.8077, 103.3260), ("淡马鲁 Temerloh", 3.4500, 102.4176),
    ("文冬 Bentong", 3.5226, 101.9083), ("劳勿 Raub", 3.7930, 101.8572), ("云顶 Genting Highlands", 3.4236, 101.7932),
    ("丹那拉打 Tanah Rata", 4.4720, 101.3800), ("怡保 Ipoh", 4.5975, 101.0901), ("金宝 Kampar", 4.3083, 101.1534),
    ("安顺 Teluk Intan", 4.0259, 101.0213), ("实兆远 Sitiawan", 4.2167, 100.7000), ("红土坎 Lumut", 4.2318, 100.6290),
    ("太平 Taiping", 4.8500, 100.7400), ("北海 Butterworth", 5.3991, 100.3638), ("大山脚 Bukit Mertajam", 5.3631, 100.4667),
    ("乔治市 George Town", 5.4141, 100.3288), ("双溪大年 Sungai Petani", 5.6470, 100.4877), ("居林 Kulim", 5.3650, 100.5617),
    ("亚罗士打 Alor Setar", 6.1210, 100.3670), ("加央 Kangar", 6.4414, 100.1986), ("瓜拉丁加奴 Kuala Terengganu", 5.3302, 103.1408),
    ("甘马挽 Kemaman", 4.2325, 103.4219), ("哥打巴鲁 Kota Bharu", 6.1254, 102.2381), ("兰卡威 Langkawi", 6.3300, 99.8000),
    ("新加坡 Singapore", 1.2903, 103.8520), ("合艾 Hat Yai", 7.0084, 100.4747), ("曼谷 Bangkok", 13.7563, 100.5018),
    ("普吉 Phuket", 7.8804, 98.3923), ("清迈 Chiang Mai", 18.7883, 98.9853), ("芭提雅 Pattaya", 12.9236, 100.8825),
    ("甲米 Krabi", 8.0300, 98.8250), ("雅加达 Jakarta", -6.2000, 106.8166), ("巴厘岛 Bali (Denpasar)", -8.6705, 115.2126),
    ("日惹 Yogyakarta", -7.7956, 110.3695), ("巴淡 Batam", 1.1300, 104.0500), ("棉兰 Medan", 3.5952, 98.6722),
]

# ---------- money: fallback rates (ringgit per unit), Bank Negara Malaysia closing rates on 9 Sep 2026
FX = {"MYR": 1.0, "SGD": 3.2158, "THB": 0.123699, "IDR": 0.000232, "USD": 4.066,
      "asof": "2026-09-09", "src": "https://www.businesstoday.com.my/2026/09/09/foreign-exchange-rates-sept-9-2026/"}

# ---------- travel notes shown on cross-border and ferry legs
NOTES = {
    "MY-SG": "过新山关卡需护照；自驾进新加坡要先申请 VEP 和 Autopass，高峰期关卡可能塞 1–2 小时，可改坐巴士或 RTS 轻轨。",
    "MY-TH": "从黑木山 Bukit Kayu Hitam 或巴东勿刹 Padang Besar 过境需护照；自驾要准备泰国车险和 TM2/TM3 车辆入境表格。",
    "ferry": "渡轮班次受天气影响，季风期（11–2 月）东海岸船班可能停航；请提前在码头或网上确认船票。",
    "flight": "此段建议坐飞机：机票价格浮动，请尽早在航空公司官网或订票平台购买；预计时间含往返机场和安检。",
}

# ---------- photo choice: a Commons search used when the article's lead photo is missing or unsuitable
# (e.g. a map or satellite picture). The build logs which photo each place ends up with.
PHOTO = {
    "connaught": "Stinky tofu|Taiwanese street food",
    "newlane": "Char kway teow Penang|Penang hawker food|Char kway teow",
    "airitamlaksa": "Penang asam laksa",
    "cendol": "Cendol Penang|Cendol",
    "lineclear": "Nasi kandar Penang",
    "ipohcoffee": "Ipoh white coffee",
    "ipohtauge": "",
    "ricebal": "Chicken rice balls Malacca",
    "pasirpenambang": "Kuala Selangor seafood restaurant",
    "langkawinight": "Apam balik|Satay",
    "joddfairs": "Jodd Fairs",
    "greenway": "Hat Yai night market",
    "sundaywalk": "Sunday Walking Street Chiang Mai",
    "saturdaywalk": "Wualai Saturday walking street",
    "phuketsunday": "Lard Yai|Phuket Sunday market|Phuket Old Town night",
    "krabinight": "Krabi night market",
    "aonangnight": "Gai yang|Thai grilled chicken",
    "thepprasit": "Mango sticky rice",
    "gianyar": "Pasar Gianyar|Gianyar market|Babi guling",
    "sindhu": "Sanur night market|Sate lilit|Sanur",
    "jimbaran": "Jimbaran seafood",
    "gudeg": "Gudeg Yogyakarta",
    "watmachim": "",  # no reliable photo found: show the icon instead of a wrong picture
    "elephantpark": "Elephant Nature Park Chiang Mai",
    "pdbeach": "Port Dickson beach",
    "perhentian": "Perhentian Kecil beach",
    "redang": "Redang island beach",
    "samosir": "Samosir Lake Toba Tuktuk",
    "jatiluwih": "Jatiluwih rice terraces",
    "pangkor": "Pasir Bogak Pangkor",
    "petronas": "Petronas Twin Towers",
    "melawati": "Bukit Melawati lighthouse",
    "bohtea": "BOH tea plantation Sungei Palas",
    "ipoholdtown": "Ipoh old town",
    "keklotong": "Kek Lok Tong Ipoh",
    "tambun": "Lost World of Tambun",
    "zootaiping": "Taiping Zoo",
    "habitat": "The Habitat Penang Hill",
    "clanjetty": "Chew Jetty George Town",
    "entopia": "Entopia Penang butterfly",
    "kilim": "Kilim Karst Geoforest Park",
    "underwater": "Underwater World Langkawi",
    "cenang": "Pantai Cenang Langkawi",
    "eaglesq": "Dataran Lang Langkawi eagle",
    "islandhop": "Tasik Dayang Bunting",
    "jonker": "Jonker Street Melaka",
    "tamingsari": "Menara Taming Sari Melaka",
    "babanyonya": "Baba Nyonya Heritage Museum Melaka",
    "desaru": "Desaru beach",
    "ktchinatown": "Kampung Cina Kuala Terengganu",
    "mbsskypark": "Marina Bay Sands SkyPark",
    "hatyaipark": "Hat Yai Municipal Park",
    "samila": "Samila Beach Songkhla mermaid",
    "watHatYaiNai": "Wat Hatyai Nai",
    "skywalk": "King Power Mahanakhon",
    "ayutthaya": "Wat Phra Si Sanphet Ayutthaya",
    "bigbuddha": "Big Buddha Phuket",
    "phukettown": "Thalang Road Phuket",
    "promthep": "Promthep Cape",
    "tigercave": "Wat Tham Suea Krabi",
    "monas": "Tugu Monas",
    "ancol": "Ancol Jakarta beach",
    "tegallalang": "Tegallalang rice terraces",
    "lempuyang": "Pura Lempuyang gate",
    "sipisopiso": "Sipiso-piso waterfall",
}

# ---------- ferries to the islands: piers, sailings, fares (shown per day in the app; also used by the planner)
# times: [("HH:MM", days or None)] with days = weekdays it runs (0 = Sunday); None = every day.
# Leave times empty when the operator changes them often; then "note" explains and "link" points to the live timetable.
def T(spec, days=None):
    return [(x.strip(), days) for x in spec.split(",") if x.strip()]

FERRIES = [
    dict(id="lgk-kp", island="langkawi", name="玻璃市 Kuala Perlis ⇄ 兰卡威瓜镇 Kuah",
         a=("玻璃市 Kuala Perlis 码头", 6.3996, 100.1288, "Jeti Kuala Perlis"), b=("兰卡威 Kuah 码头（Jetty Point）", 6.3105, 99.8508, "Jetty Point Complex Kuah"),
         ops="Langkawi Ferry Line", mins=75, out=[], back=[],
         note="2026 年 3 月 25 日起因柴油涨价，每天减为约 3 班（早上、下午、傍晚各一班），具体时间每 3 个月公布、旺季会加班。出发前一天在官网查当天船班。",
         fares=dict(cur="MYR", rows=[("马来西亚人 MyKad", 21, 16), ("外国人", 45, 35)], unit="单程"),
         extra="网上订票每张加 RM2；海关检查要时间，提早 1 小时到。", link=("官网查船班 / 订票", "https://langkawiferryline.com"),
         src=["https://www.malaymail.com/amp/news/malaysia/2026/04/10/langkawi-ferry-operator-raises-fares-for-non-mykad-holders-by-rm10-amid-rising-costs/215839",
              "https://www.stampednomad.com/digital-nomad-news/malaysia-ferry-routes-to-langkawi-stay-capped-at-3-daily-sailings"], asof="2026-07"),
    dict(id="lgk-kk", island="langkawi", name="吉打 Kuala Kedah ⇄ 兰卡威瓜镇 Kuah",
         a=("瓜拉吉打 Kuala Kedah 码头", 6.1036, 100.2905, "Jeti Kuala Kedah"), b=("兰卡威 Kuah 码头（Jetty Point）", 6.3105, 99.8508, "Jetty Point Complex Kuah"),
         ops="Langkawi Ferry Line", mins=105, out=[], back=[],
         note="2026 年 3 月 25 日起每天约 3 班（早上、下午、傍晚），时间每 3 个月更新；风浪大时会取消。",
         fares=dict(cur="MYR", rows=[("马来西亚人 MyKad", 26.5, 20.5), ("外国人", 55, 42)], unit="单程"),
         extra="从亚罗士打、槟城方向过来比较近。", link=("官网查船班 / 订票", "https://langkawiferryline.com"),
         src=["https://www.malaymail.com/amp/news/malaysia/2026/04/10/langkawi-ferry-operator-raises-fares-for-non-mykad-holders-by-rm10-amid-rising-costs/215839"], asof="2026-04"),
    dict(id="lgk-roro", island="langkawi", name="玻璃市 ⇄ 兰卡威 车渡（RORO，可载车）",
         a=("玻璃市 Kuala Perlis 车渡码头", 6.3990, 100.1300, "Kuala Perlis RORO"), b=("兰卡威 Tanjung Lembong 码头", 6.3270, 99.8670, "Tanjung Lembong Langkawi"),
         ops="Wantas RORO", mins=90, approx=True,
         out=T("08:00,09:00,12:00,13:00,14:00,16:00"), back=T("09:30,10:00,12:00,13:00,15:00,17:00"),
         note="车渡每天的开船时间不一样，上面是 2026 年 11–12 月时间表里常见的开船时间（玻璃市最常见 13:00、兰卡威最常见 9:30），以官网当天为准。",
         fares=dict(cur="MYR", rows=[("马来西亚人", 26, None), ("外国人", 45, None)], unit="单程（乘客）"),
         extra="可以把车开上岛；车辆另外收费。", link=("车渡时间表", "https://production.wantasroro.com/info/schedules"),
         src=["https://production.wantasroro.com/info/schedules", "https://www.busonlineticket.com/booking/kuala-perlis-to-langkawi-island-ferry-tickets"], asof="2026-11"),
    dict(id="pangkor", island="pangkor", name="红土坎 Lumut ⇄ 邦咯岛 Pangkor",
         a=("红土坎 Lumut 码头", 4.2318, 100.6290, "Lumut Jetty"), b=("邦咯岛 Pangkor 码头", 4.2185, 100.5730, "Pangkor Jetty"),
         ops="Pangkor 渡轮", mins=40, approx=True,
         out=T("09:30,10:30,11:30,12:45,13:30,14:00,15:30,16:00,17:00,18:00"),
         back=T("06:30,08:30,10:30,11:30,12:30,12:45,14:00,14:30,15:30,16:30,17:00"),
         note="大约每小时一班，中途停 Sungai Pinang Kecil 渔村；周末和假期会加班。",
         fares=dict(cur="MYR", rows=[("成人", 13, 7)], unit="单程（来回成人 RM25、儿童 RM14）"),
         extra="来回票在 Lumut 海滨售票处买。", link=None,
         src=["https://www.malaysia-traveller.com/pangkor-island-ferry.html"], asof="2024-01"),
    dict(id="tioman", island="tioman", name="丰盛港 Mersing ⇄ 刁曼岛 Tioman",
         a=("丰盛港 Mersing 码头", 2.4339, 103.8393, "Mersing Jetty"), b=("刁曼岛 Tekek 码头", 2.8170, 104.1590, "Tekek Jetty Tioman"),
         ops="Bluewater Express", mins=105, out=[], back=[], monsoon=[11, 12, 1, 2],
         note="开船时间看潮水，每天都不一样，每月中公布下个月的时间表。平时一天至少早上、下午各一班；季风期（约 11–2 月）通常一天只有一班，风浪大会取消或延误。",
         fares=dict(cur="MYR", rows=[("成人", 60, 55)], unit="单程起（越接近满座越贵）"),
         extra="另付海洋公园保护费：外国人成人 RM30、儿童 RM15（马来西亚人 RM5 / RM2），在 Mersing 码头付。",
         link=("查当天船班 / 订票", "https://www.busonlineticket.com/booking/mersing-to-tioman-island-ferry-tickets"),
         src=["https://malaysiauncovered.com/mersing-to-tioman/", "https://www.busonlineticket.com/?p=23796"], asof="2026-05"),
    dict(id="redang", island="redang", name="瓜登嘉楼 Shahbandar ⇄ 热浪岛 Redang",
         a=("瓜登嘉楼 Shahbandar 码头", 5.3366, 103.1435, "Jeti Shahbandar"), b=("热浪岛码头", 5.7700, 103.0350, "Redang Island jetty"),
         ops="公共渡轮（另有度假村接驳船从 Merang 出发）", mins=120, out=[], back=[], closed=[11, 12, 1, 2],
         note="每天早上一班。只在 3–10 月开；11–2 月东北季风期基本停航，大部分度假村也关门。",
         fares=dict(cur="MYR", rows=[("成人", 58, 25)], unit="单程"),
         extra="海洋公园保护费：外国人成人 RM30、儿童 RM15；马来西亚人 RM5 / RM2。提早 30 分钟到码头。",
         link=None, src=["https://malaysiauncovered.com/kuala-terengganu-to-redang-island/"], asof="2026-05"),
    dict(id="perhentian", island="perhentian", name="瓜拉勿述 Kuala Besut ⇄ 停泊岛 Perhentian",
         a=("瓜拉勿述 Kuala Besut 码头", 5.8297, 102.5600, "Kuala Besut Jetty"), b=("停泊岛（船会送到各个海滩）", 5.9100, 102.7300, "Perhentian Kecil"),
         ops="快艇（多家）", mins=40, closed=[11, 12, 1, 2], approx=True,
         out=T("07:00,08:00,09:00,10:00,11:00,12:00,13:00,14:00,15:00,16:00,17:00"), back=T("08:00,10:00,12:00,16:00"),
         note="去程大约每小时一班；回程固定 8:00、10:00、12:00、16:00，最后一班 16:00。只在白天开、看天气。约 10 月中到 2 月底季风期停航，95% 度假村关门。",
         fares=dict(cur="MYR", rows=[("成人", 40, 25)], unit="单程（来回约 RM70–80）"),
         extra="海洋公园保护费：外国人成人 RM30、儿童 RM15，在码头柜台付。包船约 RM450（最多 10 人）。码头停车每天 RM7–10。",
         link=None, src=["https://laoujin.github.io/Atlas/research/2026-06-11-a-first-visit-guide-to-the-perhentian-islands/around-day-trips-perhentian-islands/"], asof="2026-06"),
    dict(id="batam-hf", island="batam", name="新加坡 HarbourFront ⇄ 巴淡 Batam Centre",
         a=("新加坡 HarbourFront 码头", 1.2647, 103.8203, "HarbourFront Ferry Terminal"), b=("巴淡 Batam Centre 码头", 1.1290, 104.0530, "Batam Centre Ferry Terminal"),
         ops="Majestic Fast Ferry（另有 BatamFast、Sindo、Horizon）", mins=60,
         out=T("08:45,09:45,12:45,17:40,20:40"), back=T("06:00,09:00,13:10,16:20,18:00"),
         note="上面是 Majestic 2026 年 4 月 7 日起的时间（回程是印尼时间，比新加坡慢 1 小时）。四家公司加起来，新加坡出发大约 7:40–21:40、巴淡出发大约 6:00–20:30 都有船。",
         fares=dict(cur="SGD", rows=[("成人（含码头费）", 43, None)], unit="单程约（来回约 S$76）"),
         extra="要带护照；从新山 Stulang Laut 码头也有船到巴淡（约 RM100 起，船程约 1.5 小时）。",
         link=("Majestic 船期表", "https://www.majesticfastferry.com.sg/Assets/Schedule/webschedule03042026093738.pdf"),
         src=["https://www.majesticfastferry.com.sg/Assets/Schedule/webschedule03042026093738.pdf", "https://www.instarem.com/blog/singapore-to-batam-ferry-guide-ticket-price-schedule/"], asof="2026-04"),
    dict(id="batam-jb", island="batam", name="新山 Stulang Laut ⇄ 巴淡",
         a=("新山 Stulang Laut 码头", 1.4720, 103.7810, "Stulang Laut Ferry Terminal"), b=("巴淡 Batam Centre / Harbour Bay", 1.1290, 104.0530, "Batam Centre Ferry Terminal"),
         ops="Dolphin、Bistari Gemilang、Pintas Samudra", mins=90, out=[], back=[],
         note="三家公司每家每天约 4 班：Pintas Samudra 7:30–16:00、Dolphin 8:30–17:30（到 Batam Centre）、Bistari Gemilang 9:00–17:30（到 Harbour Bay）。连出入境约 2–2.5 小时。",
         fares=dict(cur="MYR", rows=[("成人", 100, None)], unit="单程起（假期较贵）"),
         extra="提早 1–1.5 小时到码头办出境。", link=("查船班 / 订票", "https://www.busonlineticket.com/booking/johor-bahru-to-batam-ferry-tickets"),
         src=["https://www.busonlineticket.com/booking/johor-bahru-to-batam-ferry-tickets"], asof="2026-11"),
    dict(id="bintan", island="bintan", name="新加坡 Tanah Merah ⇄ 民丹 Bandar Bentan Telani",
         a=("新加坡 Tanah Merah 码头", 1.3122, 103.9874, "Tanah Merah Ferry Terminal"), b=("民丹 Bandar Bentan Telani 码头", 1.1660, 104.4000, "Bandar Bentan Telani Ferry Terminal"),
         ops="Bintan Resort Ferries", mins=70, approx=True,
         out=[("08:10", None), ("09:10", [6, 0]), ("11:10", [1, 2, 3, 4, 5, 0]), ("14:00", None), ("17:00", None)], back=[],
         note="新加坡出发的时间来自 2026 年 2 月的时间表，旺季会加班。回程时间没有找到可靠资料，请在官网或码头查。",
         fares=dict(cur="SGD", rows=[("成人（平日 / 旺季）", 57, 52)], unit="经济舱单程平日价（旺季成人 S$90、儿童 S$78；来回成人 S$100）"),
         extra="民丹是印尼时间，比新加坡慢 1 小时。", link=None,
         src=["https://www.directferries.ie/tanah_merah_bandar_bentan_telani_ferry.htm", "https://ferrygogo.com/?p=39583"], asof="2026-02"),
    dict(id="phiphi", island="phuket", places=["phiphi"], name="普吉 Rassada ⇄ 皮皮岛 Tonsai",
         a=("普吉 Rassada 码头", 7.8590, 98.4010, "Rassada Pier"), b=("皮皮岛 Tonsai 码头", 7.7380, 98.7720, "Tonsai Pier"),
         ops="大船：Andaman Wave Master、Phi Phi Cruiser、Chaokoh 等；快艇：Bundhaya 等", mins=120,
         out=T("08:30,08:45,11:00,13:30,13:45,15:00"), back=T("09:00,11:00,14:30,15:30"),
         note="大船约 2 小时；快艇约 1 小时，08:30–15:30 大约每小时一班（฿750–850）。11–4 月旺季班次较多。",
         fares=dict(cur="THB", rows=[("大船标准座", 500, None)], unit="单程约 ฿500–700（头等约 ฿1,000）"),
         extra="上岸付岛费 ฿20；去玛雅湾等国家公园另付 ฿400（儿童 ฿200）。玛雅湾每年 8–9 月关闭。",
         link=None, src=["https://www.visitthailandtoday.com/guides/phuket-to-phi-phi"], asof="2026-10"),
    dict(id="kohlarn-naban", island="pattaya", places=["kohlarn"], name="芭提雅 Bali Hai ⇄ 格兰岛 Na Ban 码头",
         a=("芭提雅 Bali Hai 码头（A 柜台）", 12.9200, 100.8690, "Bali Hai Pier"), b=("格兰岛 Na Ban 码头", 12.9180, 100.7840, "Na Ban Pier Koh Larn"),
         ops="公共渡轮", mins=45, out=T("07:00,10:00,12:00,14:00,15:30,17:00,18:30"), back=T("06:30,07:30,09:30,12:00,14:00,15:30,17:00,18:00"),
         note="最后一班回程 18:00。快艇约 15–20 分钟，每人 ฿150–300。",
         fares=dict(cur="THB", rows=[("每人", 40, None)], unit="单程（现金，婴儿免费）"),
         extra="船票在 Bali Hai 码头 A 柜台买，开船前 20 分钟停售。", link=None,
         src=["https://pattayapointer.pages.dev/guides/koh-larn-island-complete-guide"], asof="2026-10"),
    dict(id="kohlarn-tawaen", island="pattaya", places=["kohlarn"], name="芭提雅 Bali Hai ⇄ 格兰岛 Tawaen 海滩",
         a=("芭提雅 Bali Hai 码头（A 柜台）", 12.9200, 100.8690, "Bali Hai Pier"), b=("格兰岛 Tawaen 海滩", 12.9350, 100.7700, "Tawaen Beach"),
         ops="公共渡轮", mins=45, out=T("08:00,09:00,11:00,13:00"), back=T("13:00,14:00,15:00,16:00,17:00"),
         note="直达最受欢迎的 Tawaen 海滩，最后一班回程 17:00。",
         fares=dict(cur="THB", rows=[("每人", 40, None)], unit="单程（现金）"),
         extra="", link=None, src=["https://pattayapointer.pages.dev/guides/koh-larn-island-complete-guide"], asof="2026-10"),
    dict(id="penida", island="bali", places=["nusapenida"], name="巴厘岛 Sanur ⇄ 佩尼达岛 Nusa Penida",
         a=("Sanur 码头（Matahari Terbit）", -8.6745, 115.2640, "Pelabuhan Sanur"), b=("佩尼达岛 Banjar Nyuh / Sampalan", -8.6798, 115.4975, "Banjar Nyuh Nusa Penida"),
         ops="多家快艇（Idola、Maruti、El Rey 等）", mins=45,
         out=T("06:30,07:00,07:30,08:00,08:30,09:00,09:30,10:00,10:30,11:00,11:30,12:00,12:25,13:00,15:00,15:30,17:00"), back=[],
         note="回程时间各家不同，最后一班回 Sanur 一般约 17:00。上船要涉水，穿拖鞋。",
         fares=dict(cur="IDR", rows=[("现场买", 200000, None), ("网上提前订", 120000, None)], unit="单程（来回约 Rp250,000–350,000）"),
         extra="现场价 Rp200,000–250,000；网上 Rp120,000–175,000。", link=None,
         src=["https://inivie.com/discover-bali/nusa-penida-ferry-schedule-prices-and-how-to-book"], asof="2026-08"),
    dict(id="toba-ajibata", island="toba", places=["samosir"], name="Ajibata ⇄ 沙摩西岛 Ambarita（汽车渡轮）",
         a=("Ajibata 码头（巴拉巴附近）", 2.6619, 98.9339, "Pelabuhan Ajibata"), b=("沙摩西岛 Ambarita 码头", 2.6818, 98.8327, "Pelabuhan Ambarita"),
         ops="ASDP（KMP Ihan Batak、KMP Pora-pora）", mins=45,
         out=T("07:30,09:00,10:30,12:00,13:30,15:00,16:30,18:00", [1, 4]) + T("07:30,10:00,12:30,15:00,17:30", [2, 3])
             + T("07:30,08:45,10:00,11:15,12:30,13:45,15:00,16:15,17:30,18:45,20:00,21:15", [5, 6, 0]),
         back=T("09:00,10:30,12:00,13:30,15:00,16:30,18:00,19:30", [1, 4]) + T("08:45,11:15,13:45,16:15,18:45", [2, 3])
             + T("07:30,08:45,10:00,11:15,12:30,13:45,15:00,16:15,17:30,18:45,20:00,21:15", [5, 6, 0]) + T("22:15", [0]),
         note="2026 年 1 月的时间表，周一、四，周二、三，周五到周日各不相同；节假日会加班。巴拉巴（Parapat）Tiga Raja 码头另有小船到 Tuktuk。",
         fares=dict(cur="IDR", rows=[], unit=""), extra="票价没有找到可靠资料，在码头现场买。",
         link=None, src=["https://www.detik.com/sumut/berita/d-8298820/jadwal-penyeberangan-kapal-ferry-di-danau-toba-pada-januari-2026"], asof="2026-01"),
    dict(id="toba-tigaras", island="toba", places=["samosir"], name="Tigaras ⇄ 沙摩西岛 Simanindo（汽车渡轮）",
         a=("Tigaras 码头", 2.7870, 98.8290, "Tigaras"), b=("沙摩西岛 Simanindo 码头", 2.7740, 98.8070, "Pelabuhan Simanindo"),
         ops="KMP Sumut I、Sumut 2", mins=45,
         out=T("07:00,08:00,09:00,10:00,11:00,12:00,13:00,14:00,15:00,16:00,17:00,18:00,19:00,20:30"),
         back=T("07:00,08:00,09:00,10:00,11:00,12:00,13:00,14:00,15:00,16:00,17:00,18:00,19:00,20:30"),
         note="每天 7:00–19:00 每小时一班，最后一班 20:30。从棉兰过来走这边比较近。",
         fares=dict(cur="IDR", rows=[], unit=""), extra="票价没有找到可靠资料，在码头现场买。",
         link=None, src=["https://www.detik.com/sumut/berita/d-8298820/jadwal-penyeberangan-kapal-ferry-di-danau-toba-pada-januari-2026"], asof="2026-01"),
]
