// 马来西亚游神节庆：日期、时段（白天/晚上）、地点和资料来源。
// 游神按农历举行：已经办过的年份写的是实际日期；下一年的日期按农历换算，主办方通常在活动前一两周才公布时间和封路，
// 所以 est:true 的日期页面上会标「按农历推算」。整理于 2026 年 11 月。
// slot: day = 白天，eve = 傍晚开始，night = 晚上
window.FEST = {
  asof: '2026-11-24',
  list: [
    {
      id: 'jbchingay', name: '柔佛古庙游神', en: 'Johor Bahru Chingay (Johor Ancient Temple)', state: '柔佛', town: '新山', region: 'jb',
      rule: '每年农历正月十八至廿二，正月廿一晚的「众神夜游」是高潮',
      about: '新山最大的年度盛事，海南、广肇、客家、福建、潮州五帮的五尊神明（赵大元帅、华光大帝、感天大帝、洪仙大帝、主神元天上帝）出巡。2012 年列为马来西亚国家文化遗产，夜游约 30 万人参与，新山人说过了正月廿一才算过完年。',
      sessions: [
        {lunar: '正月十八', title: '行宫亮灯', slot: 'night', time: '晚上', where: '行宫（Jalan Ulu Ayer Molek）', p: 'xinggong',
          d: {2026: '2026-03-06', 2027: '2027-02-23'}, est: {2027: 1}, note: '游神的序幕，具体时间看新山中华公会公布'},
        {lunar: '正月十九', title: '洗街清道 · 请神上轿', slot: 'day', time: '11:00 洗街，14:00 请神上轿', where: '从柔佛古庙出发，洗到行宫；请神上轿在古庙内', p: 'temple',
          d: {2026: '2026-03-07', 2027: '2027-02-24'}, est: {2027: 1}, note: '洗街期间 11:00–13:00 封路'},
        {lunar: '正月二十', title: '众神出銮', slot: 'day', time: '11:00 吉时出发', where: '柔佛古庙 → 行宫（几公里）', p: 'temple',
          d: {2026: '2026-03-08', 2027: '2027-02-25'}, est: {2027: 1}, note: '五尊神明七分钟内依序出庙，之后在行宫供人上香'},
        {lunar: '正月廿一', title: '众神夜游（重头戏）', slot: 'night', time: '约 19:00 从行宫起轿，走到凌晨', where: '行宫出发，绕新山市区约 8 公里，恭迎台在黄亚福街 Jalan Wong Ah Fook', p: 'stage', main: 1,
          d: {2026: '2026-03-09', 2027: '2027-02-26'}, est: {2027: 1},
          note: '2026 年为配合斋戒月开斋推迟到 19:20 起轿，最后一尊神约 00:38 到恭迎台；下午 3 点开始分阶段封路，晚上 7 点到凌晨 3 点全封。2027 年正月同样在斋戒月内，起轿时间可能再调整'},
        {lunar: '正月廿二', title: '众神回銮', slot: 'day', time: '11:00 从行宫出发，约 12:45 回到古庙', where: '行宫 → 柔佛古庙（约 4 公里）', p: 'temple',
          d: {2026: '2026-03-10', 2027: '2027-02-27'}, est: {2027: 1}, note: '11:00–13:00 封路'}
      ],
      places: {
        temple: {name: '柔佛古庙', addr: 'Lot 653, Jalan Trus, Bandar Johor Bahru, 80000 Johor Bahru', q: 'Johor Bahru Old Chinese Temple, Jalan Trus, Johor Bahru', lat: 1.460556, lng: 103.763056},
        xinggong: {name: '游神行宫', addr: 'Jalan Ulu Ayer Molek, Johor Bahru（新山中华公会殡仪馆一带，中文报道常称「义兴路行宫」）', q: 'Jalan Ulu Ayer Molek, Johor Bahru'},
        stage: {name: '恭迎台 · 黄亚福街', addr: 'Jalan Wong Ah Fook, Johor Bahru（KOMTAR JBCC 前）', q: 'KOMTAR JBCC, Jalan Wong Ah Fook, Johor Bahru'}
      },
      tips: ['夜游最热闹的看点：下午就到行宫或黄亚福街占位，神轿起轿时信众高喊「兴啊！发啊！」', '市区大范围封路，开车很难进出；坐 Grab 到封路范围外再走进去', '人非常多，看好手机钱包；带水和扇子，天气闷热', '从新加坡过来：古庙离新山关卡 CIQ 步行约 10 分钟'],
      src: [
        ['星洲日报：游神流程（正月十八亮灯至廿二回銮）', 'https://www.sinchew.com.my/news/20260310/johor/7332475'],
        ['The Star：2026 年封路日期和时间', 'https://www.thestar.com.my/news/nation/2026/03/05/road-closures-in-jb-from-march-7-for-chingay-parade'],
        ['东方日报：夜游 19:20 起轿、下午 3 点起封路', 'https://www.orientaldaily.com.my/news/south/2026/03/05/801949'],
        ['中国报：夜游绕城 8 公里、00:38 到恭迎台', 'https://johor.chinapress.com.my/20260310/2026%E6%9F%94%E4%BD%9B%E5%8F%A4%E5%BA%99%E6%B8%B8%E7%A5%9E%EF%BD%9C%E9%80%BE5%E5%B0%8F%E6%97%B6%E5%A4%9C%E6%B8%B8%E5%B7%A1%E5%A2%83-%E4%BA%BA%E4%B8%8E%E7%A5%9E%E5%98%89%E5%B9%B4%E5%8D%8E%E8%90%BD/'],
        ['柔佛州旅游局：古庙地址', 'https://tourism.johor.gov.my/old-chinese-temple/']
      ]
    },
    {
      id: 'kotatinggi', name: '哥打丁宜天后宫妈祖游神', en: 'Kota Tinggi Mazu procession', state: '柔佛', town: '哥打丁宜', region: 'jb',
      rule: '每年妈祖诞（农历三月廿三）前后，庆典约 4 天',
      about: '百年传统的妈祖游神，下午先坐船沿哥打丁宜河迎神，晚上在市区河畔夜游。2026 年被列入「柔佛旅游年」活动年历，有 88 支队伍参加。',
      sessions: [
        {lunar: '三月廿二', title: '坐船迎神 + 妈祖夜游', slot: 'night', time: '约 16:00 河边集合坐船迎神，19:00 吉时夜游', where: '哥打海墘路河畔集合，坐船到妈山天后宫迎神，再在市区河畔夜游约 1 公里', p: 'kt',
          d: {2026: '2026-05-08', 2027: '2027-04-28'}, est: {2027: 1}, note: '2027 年是按 2026 年同一个农历日推算的星期三，正式日期以天后宫公布为准'}
      ],
      places: {
        kt: {name: '哥打丁宜市区河畔', addr: 'Kota Tinggi 市区河畔（哥打海墘路一带）', q: 'Kota Tinggi town riverside, Johor'}
      },
      tips: ['离新山市区约 1 小时车程，晚上回程可以提前约好 Grab', '下午的坐船迎神最有特色，想看要 4 点前到河边'],
      src: [
        ['星洲日报：2026 年哥打天后宫妈祖夜游（5 月 8 日晚）', 'https://johor.sinchew.com.my/news/20260509/johor/7489537'],
        ['星洲日报：列入 2026 柔佛旅游年活动年历', 'https://www.sinchew.com.my/news/20260601/xjs/7549214']
      ]
    },
    {
      id: 'kulai', name: '古来万仙古庙佛母诞游神', en: 'Kulai Wan Xian Gu Miao procession', state: '柔佛', town: '古来', region: 'jb',
      rule: '农历六月十九前后（2026 年是六月十九，正好周六）',
      about: '古来规模最大的游神之一，2026 年有 96 支队伍、超过 3000 人参加，庆典连办 4 天，傍晚开始沿联邦公路游到古来巴士总站。',
      sessions: [
        {lunar: '六月十九', title: '佛母诞游神', slot: 'eve', time: '18:00 出发，走到深夜', where: '万仙古庙出发 → 联邦公路 → 庄园 → 汉联花园 → 亲和园 → Jalan Seri Putri Utama → 可可苑交通圈 → 依斯迈路 → 内环公路 → 古来大街三岔路口 → 古来巴士总站交通灯（约 7 公里）', p: 'kulai',
          d: {2026: '2026-08-01', 2027: '2027-07-22'}, est: {2027: 1}, note: '2026 年 18:00 到午夜封路。2027 年农历六月十九是星期四，庙方可能改到周末，日期以庙方公布为准'}
      ],
      places: {
        kulai: {name: '古来万仙古庙', addr: '古来（Kulai）联邦公路旁', q: '万仙古庙 Kulai'}
      },
      tips: ['想看起轿就 18:00 前到万仙古庙；想轻松一点就在古来巴士总站一带等队伍过来'],
      src: [
        ['星洲日报：路线、96 支队伍（2026-07-14）', 'https://johor.sinchew.com.my/news/20260714/johor/7670932'],
        ['星洲日报：封路时间（2026-07-31）', 'https://johor.sinchew.com.my/news/20260731/johor/7719704']
      ]
    },
    {
      id: 'penangchingay', name: '槟州大旗鼓龙狮游行（乔治市）', en: 'Penang Chingay Parade, George Town', state: '槟城', town: '乔治市', region: 'penang',
      rule: '每年 12 月，日期由槟州政府公布',
      about: '槟城版的「游神大旗」：几十支队伍用额头、肩膀、嘴巴顶起 25–32 尺的大旗，还有舞龙舞狮、独轮车、马来 Boria 和印度舞，正在和柔佛、新加坡一起申请联合国非物质文化遗产。',
      sessions: [
        {lunar: '', title: '大旗鼓龙狮游行', slot: 'eve', time: '约 18:00 起（2025 年：18:00 出发，18:50 过 Gama，19:45 过 Odeon 戏院，20:45 到滨海大草场）', where: 'Padang Brown（Jalan Datuk Keramat）出发 → 市区 → 滨海大草场 Esplanade', p: 'pgstart',
          d: {2025: '2025-12-20', 2026: '2026-12-19'}, note: '2026 年 12 月 19 日（周六）已列入槟州政府活动日历；当天时间沿用往年，以官方公布为准'}
      ],
      places: {
        pgstart: {name: 'Padang Brown（起点）', addr: 'Jalan Datuk Keramat, George Town, Penang', q: 'Padang Brown, Jalan Datuk Keramat, George Town, Penang'},
        pgend: {name: '滨海大草场 Esplanade（终点）', addr: 'Padang Kota Lama, George Town, Penang', q: 'Esplanade Padang Kota Lama, George Town, Penang'}
      },
      tips: ['终点滨海大草场有表演和体验区，可以亲手试拿大旗', '路线上的 Gama 超市、Odeon 戏院一带人比较少，适合带小孩'],
      src: [
        ['槟州政府活动日历：2026 年 12 月 19 日', 'https://www.mypenang.gov.my/events.php'],
        ['Buletin Mutiara：2025 年时间和经过地点', 'https://www.buletinmutiara.com/experience-the-spectacle-of-chingay-parade-in-penang-this-weekend/']
      ]
    },
    {
      id: 'penangmainland', name: '槟州威省大旗鼓舞龙狮大游行（北海）', en: 'Penang Mainland Chingay Parade, Bagan Luar', state: '槟城', town: '北海', region: 'penang',
      rule: '每年 12 月，通常比乔治市那场晚一个星期',
      about: '威省（北海）这一场在 Bagan Luar 一带举行，往年从 Bagan Luar 游到北海斗母宫。',
      sessions: [
        {lunar: '', title: '大旗鼓舞龙狮游行', slot: 'night', time: '晚上（2023 年是 19:00–23:30）', where: 'Bagan Luar 出发（往年从 Dewan Dato Haji Ahmad Badawi 到北海斗母宫 Jalan Raja Uda）', p: 'bagan',
          d: {2026: '2026-12-26'}, note: '2026 年 12 月 26 日（周六）、地点 Bagan Luar 已由槟州政府公布；时间和路线以官方公布为准'}
      ],
      places: {
        bagan: {name: 'Bagan Luar', addr: 'Bagan Luar, Butterworth, Penang', q: 'Bagan Luar, Butterworth, Penang'},
        towboo: {name: '北海斗母宫 Tow Boo Kong', addr: 'Lot 894 & 896, Jalan Raja Uda, 12300 Butterworth, Penang', q: 'Tow Boo Kong Temple Butterworth', lat: 5.4403, lng: 100.38734}
      },
      tips: ['从乔治市过来可以坐渡轮到北海码头，再坐 Grab 几分钟'],
      src: [
        ['槟州政府活动日历：2026 年 12 月 26 日', 'https://www.mypenang.gov.my/events.php'],
        ['Buletin Mutiara：2023 年路线和时间', 'https://www.buletinmutiara.com/penang-to-push-for-unesco-listing-for-chingay-festival/']
      ]
    },
    {
      id: 'ninebutterworth', name: '九皇爷诞 · 北海斗母宫', en: 'Nine Emperor Gods Festival, Tow Boo Kong Butterworth', state: '槟城', town: '北海', region: 'penang',
      rule: '每年农历九月初一至初九，前一晚迎驾，初九晚上送驾出海',
      about: '马来西亚最热闹的九皇爷庙会之一：九天斋期，Jalan Raja Uda 摆满素食摊，有过火、乩童等仪式；最后一晚把九皇爷送到海边。',
      sessions: [
        {lunar: '八月最后一天', title: '迎驾', slot: 'night', time: '晚上', where: '北海斗母宫，到海边迎神', p: 'towboo',
          d: {2026: '2026-10-09', 2027: '2027-09-29'}, est: {2027: 1}, note: '2027 年农历八月只有 29 天，所以是 9 月 29 日晚'},
        {lunar: '九月初一至初九', title: '九天斋期庙会', slot: 'day', time: '全天，晚上最热闹', where: '北海斗母宫和 Jalan Raja Uda 素食夜市', p: 'towboo',
          d: {2026: '2026-10-10', 2027: '2027-09-30'}, to: {2026: '2026-10-18', 2027: '2027-10-08'}, est: {2027: 1}},
        {lunar: '九月初九', title: '送驾出海', slot: 'night', time: '晚上', where: '北海斗母宫游行到 Pantai Bersih 海边送神', p: 'towboo', main: 1,
          d: {2026: '2026-10-18', 2027: '2027-10-08'}, est: {2027: 1}, note: '2026 年附近几间庙也一起在 Pantai Bersih 送驾'}
      ],
      places: {
        towboo: {name: '北海斗母宫 Tow Boo Kong', addr: 'Lot 894 & 896, Jalan Raja Uda, 12300 Butterworth, Penang', q: 'Tow Boo Kong Temple Butterworth', lat: 5.4403, lng: 100.38734}
      },
      tips: ['九天里很多信众吃素、穿白衣，进庙穿浅色衣服比较合适', '送驾那晚人潮很大，车停远一点走过去'],
      src: [
        ['Penang 2026：九皇爷诞 10 月 10 日至 18 日', 'https://georgetowncity.my/nine-emperor-gods-festival/'],
        ['The Star：2026 年在 Pantai Bersih 送驾', 'https://www.thestar.com.my/news/nation/2026/10/20/farewell-to-nine-emperor-gods'],
        ['万年历：2027 年农历九月初一是 9 月 30 日', 'https://wannianli.tianqi.com/2027/9/']
      ]
    },
    {
      id: 'nineampang', name: '九皇爷诞 · 安邦南天宫', en: 'Nine Emperor Gods Festival, Ampang Nan Tian Gong', state: '雪兰莪', town: '安邦（吉隆坡旁）', region: 'kl',
      rule: '每年农历九月初一至初九',
      about: '雪兰莪最老的九皇爷庙（1862 年），九皇爷诞期间是安邦新村一年里最热闹的时候，有晚间游行、过火和大量素食摊。',
      sessions: [
        {lunar: '九月初一至初九', title: '九天斋期庙会', slot: 'day', time: '全天，晚上最热闹', where: '安邦南天宫和安邦新村', p: 'ampang',
          d: {2026: '2026-10-10', 2027: '2027-09-30'}, to: {2026: '2026-10-18', 2027: '2027-10-08'}, est: {2027: 1}},
        {lunar: '九月初九', title: '送驾', slot: 'night', time: '晚上', where: '安邦南天宫出发', p: 'ampang', main: 1,
          d: {2026: '2026-10-18', 2027: '2027-10-08'}, est: {2027: 1}, note: '期间还有晚间游行，2026 年 10 月 11 日晚有一场游到南天宫'}
      ],
      places: {
        ampang: {name: '安邦南天宫', addr: 'Jalan Merdeka, Ampang New Village, 68000 Ampang, Selangor', q: 'Kau Ong Yah Temple Ampang Nan Tian Gong'}
      },
      tips: ['安邦新村停车位很少，建议坐 LRT 到 Ampang 站再坐 Grab'],
      src: [
        ['维基百科：安邦南天宫九皇爷诞', 'https://en.wikipedia.com/wiki/Ampang_Jaya'],
        ['MyCen：庙址 Jalan Merdeka', 'https://mycen.com.my/directions/kau_ong_yah_temple_ampang.html'],
        ['Citizens Journal：2026 年 10 月 11 日晚游行', 'https://cj.my/161161/ons88-dragon-leads-nine-emperor-gods-procession-in-ampang']
      ]
    },
    {
      id: 'guangong', name: '马来西亚国际关公文化节 · 关公巡城', en: 'Malaysia International Guan Gong Cultural Festival', state: '每年换地方', town: '新山 / 槟城等', region: '',
      rule: '每年 10–11 月，主办州每年轮换，日期提前几个月公布',
      about: '由马来西亚关公文化推广中心主办，晚上的「关公万人巡城」最好看：关帝金身、大旗、龙狮和花车游城。新山站 2025 年有 3 万多人参加。',
      sessions: [
        {lunar: '', title: '新山站 关公巡城', slot: 'night', time: '晚上', where: '新山市区（2025 年从大丰花园圣淘沙广场出发，该广场年底拆除）', p: '',
          d: {2025: '2025-10-18'}},
        {lunar: '', title: '槟城站 关公万人巡城', slot: 'night', time: '19:00 出发', where: '大伯公街武帝庙（Lebuh King）→ 椰脚街 → 华盖街 → 海墘路 → 头条路 → 车水路 → 调和路 → 琼花路，约 6 公里', p: 'wudi',
          d: {2026: '2026-11-14'}}
      ],
      tba: '2027 年第 13 届的主办州和日期还没有公布',
      places: {
        wudi: {name: '槟城武帝庙（宁阳会馆）', addr: 'Lebuh King, George Town, Penang', q: 'Kuan Ti Temple Lebuh King George Town Penang'}
      },
      tips: ['新山的义凤联谊会等团体每年都会组队参加，跟着他们的社交账号能最早知道日期'],
      src: [
        ['东方日报：2026 年巡游 11 月 14 日 19:00、路线', 'https://www.orientaldaily.com.my/news/north/2026/11/12/862280'],
        ['中国报：2025 年新山站 3 万人巡游', 'https://johor.chinapress.com.my/20251019/%E5%85%B3%E5%85%AC%E6%96%87%E5%8C%96%E8%8A%82%E5%9C%A3%E9%A9%BE%E5%B7%A1%E6%B8%B8-%E9%80%BE3%E4%B8%87%E5%9B%BD%E5%86%85%E5%A4%96%E4%BA%BA%E5%A3%AB%E5%8F%82%E4%B8%8E/']
      ]
    }
  ]
};
