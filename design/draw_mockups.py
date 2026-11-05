# -*- coding: utf-8 -*-
"""
智维工单 · 前端效果图（静态示意，不是可运行前端）
用 Pillow 画 6 张 1440x900 的界面草图 + 1 张总览。
"""
import os
from functools import lru_cache
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = r"D:\Users\lzh36\Documents\deepseek-harness\default-workspace\servicedesk-ai\design"
os.makedirs(OUT, exist_ok=True)

W, H = 1440, 900
FONT = r"C:\Windows\Fonts\msyh.ttc"
FONT_B = r"C:\Windows\Fonts\msyhbd.ttc"
MONO = r"C:\Windows\Fonts\consola.ttf"


def rgb(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


BG = rgb("#F4F6FA")
SIDEBAR = rgb("#0E2233")
SIDEBAR_2 = rgb("#173B57")
CARD = rgb("#FFFFFF")
TEXT = rgb("#16202A")
SUB = rgb("#64748B")
MUTED = rgb("#94A3B8")
BORDER = rgb("#E6EAF2")
PRIMARY = rgb("#2F6BFF")
PRIMARY_L = rgb("#EAF1FF")
TEAL = rgb("#14B8A6")
TEAL_L = rgb("#E6FFFB")
RED = rgb("#DC2626")
RED_L = rgb("#FEE2E2")
AMBER = rgb("#D97706")
AMBER_L = rgb("#FEF3C7")
GREEN = rgb("#059669")
GREEN_L = rgb("#D1FAE5")
PURPLE = rgb("#7C3AED")
PURPLE_L = rgb("#EDE9FE")


@lru_cache(maxsize=256)
def font(size, bold=False, mono=False):
    if mono:
        return ImageFont.truetype(MONO, size)
    return ImageFont.truetype(FONT_B if bold else FONT, size)


def T(d, xy, s, size=13, color=TEXT, bold=False, anchor="la", mono=False):
    d.text(xy, s, font=font(size, bold, mono), fill=color, anchor=anchor)


def card(base, box, r=14, fill=CARD, shadow=True):
    if shadow:
        sh = Image.new("RGBA", base.size, (0, 0, 0, 0))
        sd = ImageDraw.Draw(sh)
        sd.rounded_rectangle((box[0], box[1] + 6, box[2], box[3] + 10), r, fill=(15, 35, 60, 24))
        sh = sh.filter(ImageFilter.GaussianBlur(10))
        base.alpha_composite(sh)
    d = ImageDraw.Draw(base)
    d.rounded_rectangle(box, r, fill=fill)
    return d


def tag(d, x, y, s, fg, bg, size=12, bold=True, r=6, pad=9):
    f = font(size, bold)
    tw = d.textlength(s, font=f)
    h = size + 11
    d.rounded_rectangle((x, y, x + tw + pad * 2, y + h), r, fill=bg)
    d.text((x + pad, y + h / 2), s, font=f, fill=fg, anchor="lm")
    return tw + pad * 2


def button(d, x, y, w, h, s, primary=True, size=13):
    if primary:
        d.rounded_rectangle((x, y, x + w, y + h), 8, fill=PRIMARY)
        T(d, (x + w / 2, y + h / 2), s, size, rgb("#FFFFFF"), True, "mm")
    else:
        d.rounded_rectangle((x, y, x + w, y + h), 8, fill=rgb("#FFFFFF"), outline=BORDER, width=1)
        T(d, (x + w / 2, y + h / 2), s, size, TEXT, False, "mm")


def avatar(d, x, y, r, s, color=PRIMARY):
    d.ellipse((x, y, x + r * 2, y + r * 2), fill=color)
    T(d, (x + r, y + r), s, int(r * 0.9), rgb("#FFFFFF"), True, "mm")


def search(d, x, y, w, h, placeholder="搜索工单号 / 标题 / 客户"):
    d.rounded_rectangle((x, y, x + w, y + h), 8, fill=rgb("#F8FAFC"), outline=BORDER, width=1)
    d.ellipse((x + 12, y + h / 2 - 5, x + 22, y + h / 2 + 5), outline=MUTED, width=2)
    d.line((x + 21, y + h / 2 + 4, x + 26, y + h / 2 + 9), fill=MUTED, width=2)
    T(d, (x + 34, y + h / 2), placeholder, 12, MUTED, anchor="lm")


def select(d, x, y, w, h, label):
    d.rounded_rectangle((x, y, x + w, y + h), 8, fill=rgb("#FFFFFF"), outline=BORDER, width=1)
    T(d, (x + 12, y + h / 2), label, 12, SUB, anchor="lm")
    d.polygon([(x + w - 18, y + h / 2 - 3), (x + w - 10, y + h / 2 - 3), (x + w - 14, y + h / 2 + 3)], fill=MUTED)


def nav_item(d, y, label, active=False):
    if active:
        d.rounded_rectangle((14, y, 222, y + 44), 10, fill=SIDEBAR_2)
        d.rounded_rectangle((14, y + 10, 18, y + 34), 2, fill=TEAL)
    color = rgb("#FFFFFF") if active else rgb("#9FB3C8")
    d.ellipse((34, y + 18, 42, y + 26), fill=TEAL if active else rgb("#486581"))
    T(d, (54, y + 22), label, 14, color, active, "lm")


def sidebar(base, active):
    d = ImageDraw.Draw(base)
    d.rectangle((0, 0, 236, H), fill=SIDEBAR)
    d.rounded_rectangle((24, 24, 56, 56), 10, fill=TEAL)
    T(d, (40, 40), "智", 17, rgb("#FFFFFF"), True, "mm")
    T(d, (68, 28), "智维工单", 17, rgb("#FFFFFF"), True)
    T(d, (68, 50), "ServiceDesk AI", 9, rgb("#7E97AD"))
    d.line((24, 84, 212, 84), fill=rgb("#1B3A52"), width=1)
    navs = ["工作台", "工单管理", "知识库", "运营报表", "客户与设备", "系统管理"]
    for i, n in enumerate(navs):
        nav_item(d, 110 + i * 52, n, n == active)
    d.line((24, 790, 212, 790), fill=rgb("#1B3A52"), width=1)
    avatar(d, 24, 812, 16, "张", TEAL)
    T(d, (64, 806), "张工", 13, rgb("#FFFFFF"), True)
    T(d, (64, 824), "工程师 · 华东一组", 10, rgb("#7E97AD"))


def topbar(base, title, crumb=None, right_text=None, icons=False):
    d = ImageDraw.Draw(base)
    d.rectangle((236, 0, W, 64), fill=rgb("#FFFFFF"))
    d.line((236, 64, W, 64), fill=BORDER, width=1)
    if crumb:
        T(d, (268, 32), crumb, 12, MUTED, anchor="lm")
        T(d, (268 + d.textlength(crumb, font=font(12)) + 8, 32), "/", 12, MUTED, anchor="lm")
        T(d, (268 + d.textlength(crumb, font=font(12)) + 20, 32), title, 18, TEXT, True, "lm")
    else:
        T(d, (268, 32), title, 19, TEXT, True, "lm")
    if right_text:
        T(d, (1228, 32), right_text, 11, MUTED, anchor="rm")
    if icons:
        d.rounded_rectangle((1254, 24, 1270, 36), 7, outline=SUB, width=2)
        d.line((1252, 38, 1272, 38), fill=SUB, width=2)
        d.line((1262, 39, 1262, 43), fill=SUB, width=2)
        d.ellipse((1268, 18, 1280, 30), fill=RED)
        T(d, (1274, 24), "3", 7, rgb("#FFFFFF"), True, "mm")
        avatar(d, 1300, 16, 16, "张", PRIMARY)


def section_title(d, x, y, title, sub=None):
    T(d, (x, y), title, 15, TEXT, True)
    if sub:
        T(d, (x, y + 22), sub, 11, SUB)


def wrap(d, text, f, max_w):
    lines, cur = [], ""
    for ch in text:
        if d.textlength(cur + ch, font=f) <= max_w:
            cur += ch
        else:
            if cur:
                lines.append(cur)
            cur = ch
    if cur:
        lines.append(cur)
    return lines


def paragraph(d, x, y, text, size, color, max_w, line_h=None):
    f = font(size)
    lines = wrap(d, text, f, max_w)
    line_h = line_h or int(size * 1.6)
    for i, ln in enumerate(lines):
        T(d, (x, y + i * line_h), ln, size, color)
    return y + len(lines) * line_h


# ---------------------------------------------------------------- 01 工作台
def screen_dashboard():
    img = Image.new("RGBA", (W, H), BG)
    sidebar(img, "工作台")
    topbar(img, "工作台", right_text="2026-10-27 周一", icons=True)
    d = ImageDraw.Draw(img)

    T(d, (268, 96), "下午好，张工", 26, TEXT, True)
    T(d, (268, 134), "今天有 3 张工单即将超时，优先处理。", 13, SUB)

    stats = [
        ("待处理", "12", "较昨日 +2", PRIMARY, PRIMARY_L),
        ("即将超时", "3", "需要今天处理", RED, RED_L),
        ("今日已解决", "8", "本周累计 41", GREEN, GREEN_L),
        ("SLA 达成率", "94.2%", "较上周 +1.8%", TEAL, TEAL_L),
    ]
    for i, (label, val, delta, color, light) in enumerate(stats):
        x = 268 + i * 289
        card(img, (x, 164, x + 273, 272))
        d = ImageDraw.Draw(img)
        d.ellipse((x + 229, 184, x + 253, 208), fill=light)
        d.ellipse((x + 237, 192, x + 245, 200), fill=color)
        T(d, (x + 20, 184), label, 12, SUB)
        T(d, (x + 20, 208), val, 30, TEXT, True)
        T(d, (x + 20, 248), delta, 11, color if i != 0 else MUTED)

    # SLA 告警
    card(img, (268, 292, 1008, 652))
    d = ImageDraw.Draw(img)
    T(d, (288, 312), "SLA 告警", 15, TEXT, True)
    T(d, (988, 314), "查看全部 →", 11, PRIMARY, anchor="ra")
    alerts = [
        ("TK20261027000002", "注塑机温控异常", "P0", "剩余 12 分钟", RED, RED_L, "昌盛塑业"),
        ("TK20261027000001", "空压机运行异响", "P1", "剩余 45 分钟", AMBER, AMBER_L, "九江精工机械"),
        ("TK20261026000018", "传送带跑偏", "P2", "已超时 18 分钟", RED, RED_L, "华鑫物流"),
    ]
    for i, (no, title, p, remain, color, light, customer) in enumerate(alerts):
        y = 352 + i * 92
        d.rounded_rectangle((288, y, 988, y + 76), 10, fill=rgb("#FBFCFE"), outline=rgb("#EEF2F7"), width=1)
        T(d, (304, y + 18), title, 13, TEXT, True)
        T(d, (304, y + 42), no, 11, MUTED, mono=True)
        T(d, (470, y + 42), customer, 10, MUTED)
        tag(d, 620, y + 24, p, color, light, 10)
        T(d, (960, y + 38), remain, 12, color, True, "ra")
    T(d, (288, 624), "提示：SLA 剩余 30 分钟内会标红，超时会自动升级并记录事件。", 10, MUTED)

    # 工单分布
    card(img, (1024, 292, 1408, 652))
    d = ImageDraw.Draw(img)
    T(d, (1044, 312), "工单分布", 15, TEXT, True)
    T(d, (1388, 314), "本周", 11, MUTED, anchor="ra")
    segs = [("硬件", 46, PRIMARY), ("软件", 27, TEAL), ("耗材", 18, AMBER),
            ("安装", 15, PURPLE), ("投诉", 12, RED), ("其他", 10, MUTED)]
    total = sum(s[1] for s in segs)
    cx, cy, r = 1216, 452, 74
    start = -90
    for _, v, c in segs:
        ext = 360 * v / total
        d.arc((cx - r, cy - r, cx + r, cy + r), start, start + ext, fill=c, width=20)
        start += ext
    T(d, (cx, cy - 6), "128", 24, TEXT, True, "mm")
    T(d, (cx, cy + 18), "本周工单", 10, SUB, False, "mm")
    for i, (name, v, c) in enumerate(segs):
        x = 1052 + (i % 2) * 168
        y = 556 + (i // 2) * 24
        d.ellipse((x, y + 4, x + 8, y + 12), fill=c)
        T(d, (x + 14, y), f"{name} {v}", 10, SUB)

    # 趋势
    card(img, (268, 668, 1408, 876))
    d = ImageDraw.Draw(img)
    T(d, (288, 688), "近 7 天工单趋势", 15, TEXT, True)
    T(d, (1360, 690), "新建", 10, PRIMARY, anchor="ra")
    T(d, (1400, 690), "解决", 10, TEAL, anchor="ra")
    x0, y0, x1, y1 = 320, 730, 1380, 844
    for i in range(4):
        y = y0 + (y1 - y0) * i / 3
        d.line((x0, y, x1, y), fill=rgb("#EEF2F7"), width=1)
    days = ["10-21", "10-22", "10-23", "10-24", "10-25", "10-26", "10-27"]
    new = [18, 22, 16, 25, 21, 28, 24]
    res = [15, 20, 18, 22, 24, 25, 26]
    mn, mx = 12, 30

    def pts(vals):
        out = []
        for i, v in enumerate(vals):
            x = x0 + (x1 - x0) * i / (len(vals) - 1)
            y = y1 - (y1 - y0) * (v - mn) / (mx - mn)
            out.append((x, y))
        return out

    p1, p2 = pts(new), pts(res)
    d.line(p1, fill=PRIMARY, width=3, joint="curve")
    d.line(p2, fill=TEAL, width=3, joint="curve")
    for p in p1:
        d.ellipse((p[0] - 4, p[1] - 4, p[0] + 4, p[1] + 4), fill=rgb("#FFFFFF"), outline=PRIMARY, width=2)
    for p in p2:
        d.ellipse((p[0] - 4, p[1] - 4, p[0] + 4, p[1] + 4), fill=rgb("#FFFFFF"), outline=TEAL, width=2)
    for i, day in enumerate(days):
        x = x0 + (x1 - x0) * i / (len(days) - 1)
        T(d, (x, 856), day, 10, MUTED, anchor="mm")
    return img


# ---------------------------------------------------------------- 02 工单列表
def screen_tickets():
    img = Image.new("RGBA", (W, H), BG)
    sidebar(img, "工单管理")
    topbar(img, "工单管理", crumb="服务台")
    d = ImageDraw.Draw(img)
    button(d, 1252, 16, 156, 36, "＋  新建工单")

    card(img, (268, 88, 1408, 160))
    d = ImageDraw.Draw(img)
    search(d, 288, 106, 300, 36)
    select(d, 604, 106, 130, 36, "全部状态")
    select(d, 748, 106, 120, 36, "紧急度")
    select(d, 882, 106, 130, 36, "全部分类")
    button(d, 1028, 106, 76, 36, "查询", True, 12)
    button(d, 1116, 106, 76, 36, "重置", False, 12)
    T(d, (1388, 124), "共 128 条", 11, MUTED, anchor="rm")

    card(img, (268, 176, 1408, 876))
    d = ImageDraw.Draw(img)
    cols = [("工单号", 150), ("标题", 260), ("客户", 150), ("分类", 90),
            ("紧急度", 70), ("状态", 120), ("工程师", 80), ("SLA 剩余", 180)]
    x = 288
    d.rectangle((268, 176, 1408, 220), fill=rgb("#F8FAFC"))
    for name, w in cols:
        T(d, (x + 14, 198), name, 11, SUB, True, "lm")
        x += w

    rows = [
        ("TK20261027000001", "空压机运行异响", "九江精工机械", "硬件故障", "P1", "IN_PROGRESS", "张工", "45 分钟", AMBER),
        ("TK20261027000002", "注塑机温控异常", "昌盛塑业", "软件故障", "P0", "NEW", "—", "12 分钟", RED),
        ("TK20261026000018", "传送带跑偏", "华鑫物流", "硬件故障", "P2", "WAITING_CUSTOMER", "李工", "2 小时", PURPLE),
        ("TK20261026000017", "变频器报警 E03", "九江精工机械", "硬件故障", "P1", "RESOLVED", "张工", "已完成", GREEN),
        ("TK20261025000009", "设备保养提醒", "昌盛塑业", "耗材更换", "P3", "CLOSED", "王工", "已关闭", MUTED),
        ("TK20261025000008", "操作培训申请", "华鑫物流", "安装调试", "P3", "ASSIGNED", "李工", "8 小时", PRIMARY),
        ("TK20261024000003", "风机噪音过大", "赣北食品", "硬件故障", "P2", "REOPENED", "张工", "已超时", RED),
    ]
    status_map = {
        "NEW": ("待派单", rgb("#475569"), rgb("#F1F5F9")),
        "ASSIGNED": ("已派单", rgb("#1D4ED8"), rgb("#DBEAFE")),
        "IN_PROGRESS": ("处理中", rgb("#B45309"), rgb("#FEF3C7")),
        "WAITING_CUSTOMER": ("等客户", rgb("#6D28D9"), rgb("#EDE9FE")),
        "RESOLVED": ("已解决", rgb("#047857"), rgb("#D1FAE5")),
        "CLOSED": ("已关闭", rgb("#334155"), rgb("#E2E8F0")),
        "REOPENED": ("已重开", rgb("#B91C1C"), rgb("#FEE2E2")),
    }
    urg_map = {"P0": (RED, RED_L), "P1": (AMBER, AMBER_L), "P2": (PRIMARY, PRIMARY_L), "P3": (MUTED, rgb("#F1F5F9"))}
    for i, row in enumerate(rows):
        y = 220 + i * 88
        if i % 2 == 1:
            d.rectangle((268, y, 1408, y + 88), fill=rgb("#FCFDFE"))
        d.line((268, y + 88, 1408, y + 88), fill=rgb("#F1F4F9"), width=1)
        x = 288
        T(d, (x + 14, y + 30), row[0], 11, SUB, mono=True)
        T(d, (x + 14, y + 50), "创建于 10-27 09:12", 9, MUTED)
        x += 150
        T(d, (x + 14, y + 38), row[1], 13, TEXT, True, "lm")
        x += 260
        T(d, (x + 14, y + 38), row[2], 12, SUB, anchor="lm")
        x += 150
        T(d, (x + 14, y + 38), row[3], 12, SUB, anchor="lm")
        x += 90
        tag(d, x + 10, y + 29, row[4], *urg_map[row[4]], 10)
        x += 70
        label, fg, bg = status_map[row[5]]
        tag(d, x + 10, y + 29, label, fg, bg, 10)
        x += 120
        T(d, (x + 14, y + 38), row[6], 12, SUB, anchor="lm")
        x += 80
        T(d, (x + 14, y + 38), row[7], 12, row[8], True, "lm")

    # pagination
    T(d, (288, 852), "显示 1-7 条，共 128 条", 10, MUTED, anchor="lm")
    for i in range(4):
        x = 1180 + i * 44
        fill = PRIMARY if i == 0 else rgb("#FFFFFF")
        fg = rgb("#FFFFFF") if i == 0 else SUB
        d.rounded_rectangle((x, 838, x + 32, 866), 6, fill=fill, outline=None if i == 0 else BORDER, width=1)
        T(d, (x + 16, 852), str(i + 1) if i < 3 else "…", 11, fg, True, "mm")
    return img


# ---------------------------------------------------------------- 03 工单详情
def screen_detail():
    img = Image.new("RGBA", (W, H), BG)
    sidebar(img, "工单管理")
    topbar(img, "TK20261027000001", crumb="工单管理")
    d = ImageDraw.Draw(img)
    button(d, 1188, 16, 96, 36, "派单", False, 12)
    button(d, 1296, 16, 112, 36, "解决工单", True, 12)

    # 标题卡
    card(img, (268, 88, 1028, 256))
    d = ImageDraw.Draw(img)
    T(d, (288, 108), "空压机运行异响", 22, TEXT, True)
    x = 288
    x += tag(d, x, 146, "P1", AMBER, AMBER_L, 11) + 8
    x += tag(d, x, 146, "处理中", rgb("#B45309"), rgb("#FEF3C7"), 11) + 8
    x += tag(d, x, 146, "硬件故障", PRIMARY, PRIMARY_L, 11) + 8
    T(d, (288, 184), "客户：九江精工机械   ·   设备：AC-2000 / SN-2024-001   ·   创建：2026-10-27 09:12   ·   报修人：李报修", 11, SUB)
    T(d, (288, 212), "开机 10 分钟后出现金属摩擦声，压力表波动；已上传现场视频。", 12, SUB)

    # tabs
    d = ImageDraw.Draw(img)
    for i, (name, active) in enumerate([("处理记录", True), ("时间线", False), ("附件 2", False)]):
        x = 288 + i * 112
        T(d, (x, 292), name, 12, TEXT if active else MUTED, active)
    d.line((288, 312, 1008, 312), fill=BORDER, width=1)
    d.rectangle((288, 309, 352, 312), fill=PRIMARY)

    # 时间线
    card(img, (268, 320, 1028, 800))
    d = ImageDraw.Draw(img)
    T(d, (288, 338), "处理记录", 14, TEXT, True)
    T(d, (1008, 340), "按时间正序", 10, MUTED, anchor="ra")
    events = [
        ("09:12", "李报修", "创建工单", "空压机开机 10 分钟后金属摩擦声，压力表波动。", PRIMARY, False),
        ("09:18", "王主管", "派单给 张工", "张工熟悉该型号，优先处理。", MUTED, False),
        ("09:20", "张工", "接单", "已联系客户，约 14:00 上门。", TEAL, False),
        ("10:05", "AI 建议", "推荐方案（3 条引用）", "建议先断电检查轴承间隙，再检查皮带张紧度。", TEAL, True),
        ("10:20", "张工", "处理记录", "按手册 3.2 节更换轴承，试机 30 分钟正常。", TEXT, False),
    ]
    for i, (time, actor, action, content, color, ai) in enumerate(events):
        y = 376 + i * 80
        d.ellipse((316, y + 2, 328, y + 14), fill=color if ai else rgb("#FFFFFF"), outline=color, width=2)
        if i < len(events) - 1:
            d.line((322, y + 16, 322, y + 78), fill=rgb("#E2E8F0"), width=2)
        T(d, (344, y), actor, 12, color if ai else TEXT, True)
        T(d, (344, y + 20), content, 11, SUB)
        T(d, (1008, y), time, 10, MUTED, anchor="ra")
        if ai:
            tag(d, 430, y - 2, "AI", rgb("#FFFFFF"), TEAL, 9, False)

    # 评论框
    card(img, (268, 816, 1028, 876), r=10)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((284, 828, 920, 864), 8, fill=rgb("#F8FAFC"), outline=BORDER, width=1)
    T(d, (300, 846), "写处理记录，或 @ 同事…", 11, MUTED, anchor="lm")
    button(d, 932, 828, 80, 36, "发送", True, 11)

    # 右侧 SLA
    card(img, (1044, 88, 1408, 248))
    d = ImageDraw.Draw(img)
    T(d, (1064, 106), "SLA 倒计时", 14, TEXT, True)
    T(d, (1388, 108), "P1 标准", 10, MUTED, anchor="ra")
    T(d, (1064, 136), "响应", 11, SUB)
    T(d, (1388, 136), "已完成 09:20", 11, GREEN, True, "ra")
    T(d, (1064, 162), "解决", 11, SUB)
    T(d, (1388, 162), "剩余 03:52", 15, AMBER, True, "ra")
    d.rounded_rectangle((1064, 188, 1388, 198), 5, fill=rgb("#F1F5F9"))
    d.rounded_rectangle((1064, 188, 1074, 198), 5, fill=AMBER)
    T(d, (1064, 212), "截止 13:12   ·   已暂停 0 分钟", 10, MUTED)

    # 客户设备
    card(img, (1044, 264, 1408, 404))
    d = ImageDraw.Draw(img)
    T(d, (1064, 282), "客户 / 设备", 14, TEXT, True)
    T(d, (1064, 312), "九江精工机械", 13, TEXT, True)
    T(d, (1064, 334), "刘经理 · 0792-0000000", 10, SUB)
    d.line((1064, 354, 1388, 354), fill=rgb("#F1F4F9"), width=1)
    T(d, (1064, 366), "AC-2000  ·  SN-2024-001  ·  已过保", 10, SUB)

    # AI 建议
    card(img, (1044, 420, 1408, 760))
    d = ImageDraw.Draw(img)
    T(d, (1064, 438), "AI 建议", 14, TEXT, True)
    tag(d, 1236, 438, "3 条引用", TEAL, TEAL_L, 9, False)
    paragraph(d, 1064, 468, "推荐先断电检查轴承间隙，再检查皮带张紧度；若间隙超标，按手册 3.2 节更换轴承。", 10, SUB, 300, 16)
    refs = [
        ("空压机异响排查手册", "3.2 轴承检查", "0.87"),
        ("空压机维护规范", "4.1 润滑周期", "0.81"),
        ("历史工单 #2026-0912", "轴承更换", "0.76"),
    ]
    for i, (title, head, score) in enumerate(refs):
        y = 520 + i * 58
        d.rounded_rectangle((1064, y, 1388, y + 48), 8, fill=rgb("#F8FAFC"), outline=rgb("#EEF2F7"), width=1)
        T(d, (1076, y + 8), title, 10, TEXT, True)
        T(d, (1076, y + 26), head, 9, MUTED)
        T(d, (1376, y + 14), score, 10, TEAL, True, "ra")
    button(d, 1064, 700, 150, 34, "采纳并写入记录", True, 11)
    button(d, 1224, 700, 76, 34, "忽略", False, 11)

    # Agent
    card(img, (1044, 776, 1408, 876))
    d = ImageDraw.Draw(img)
    T(d, (1064, 792), "让 Agent 帮我处理", 12, TEXT, True)
    d.rounded_rectangle((1064, 818, 1268, 856), 8, fill=rgb("#F8FAFC"), outline=BORDER, width=1)
    T(d, (1076, 837), "查设备保修 / 建单 / 派单…", 10, MUTED, anchor="lm")
    button(d, 1280, 818, 108, 38, "打开 Agent", True, 11)
    return img


# ---------------------------------------------------------------- 04 建单
def screen_create():
    img = Image.new("RGBA", (W, H), BG)
    sidebar(img, "工单管理")
    topbar(img, "新建工单", crumb="工单管理")
    d = ImageDraw.Draw(img)

    card(img, (268, 88, 1028, 876))
    d = ImageDraw.Draw(img)
    T(d, (288, 108), "基本信息", 15, TEXT, True)
    T(d, (288, 136), "带 * 为必填；AI 结果可修改。", 10, MUTED)
    fields = [
        ("客户 *", "九江精工机械", 288, 168, 340),
        ("设备", "AC-2000 / SN-2024-001", 644, 168, 364),
        ("标题 *", "空压机运行异响", 288, 240, 720),
    ]
    for label, value, x, y, w in fields:
        T(d, (x, y), label, 11, SUB, True)
        d.rounded_rectangle((x, y + 20, x + w, y + 58), 8, fill=rgb("#FFFFFF"), outline=BORDER, width=1)
        T(d, (x + 12, y + 39), value, 12, TEXT, anchor="lm")

    T(d, (288, 312), "描述 *", 11, SUB, True)
    d.rounded_rectangle((288, 332, 1008, 386), 8, fill=rgb("#FFFFFF"), outline=BORDER, width=1)
    T(d, (300, 344), "开机 10 分钟后出现金属摩擦声，压力表波动。", 12, TEXT)
    T(d, (300, 364), "已上传现场视频，客户希望今天上门。", 11, SUB)

    # AI banner
    d.rounded_rectangle((288, 396, 1008, 470), 10, fill=TEAL_L)
    d.ellipse((304, 416, 320, 432), fill=TEAL)
    T(d, (330, 410), "AI 已识别", 12, TEAL, True)
    T(d, (330, 432), "硬件故障  ·  P1  ·  置信度 82%", 12, TEXT, True)
    T(d, (330, 450), "依据：描述含\"金属摩擦声\"\"压力波动\"，与轴承故障特征一致。", 10, SUB)
    button(d, 900, 416, 92, 32, "重新识别", False, 10)

    # 分类
    T(d, (288, 496), "故障分类", 11, SUB, True)
    cats = [("硬件故障", True), ("软件故障", False), ("耗材更换", False), ("安装调试", False), ("投诉", False), ("其他", False)]
    x = 288
    for name, active in cats:
        tw = d.textlength(name, font=font(11, True)) + 28
        d.rounded_rectangle((x, 518, x + tw, 550), 7, fill=PRIMARY if active else rgb("#FFFFFF"),
                            outline=PRIMARY if active else BORDER, width=1)
        T(d, (x + tw / 2, 534), name, 11, rgb("#FFFFFF") if active else SUB, True, "mm")
        x += tw + 10

    # 紧急度
    T(d, (288, 576), "紧急度", 11, SUB, True)
    urgs = [("P0", False), ("P1", True), ("P2", False), ("P3", False)]
    x = 288
    for name, active in urgs:
        tw = 54
        d.rounded_rectangle((x, 598, x + tw, 630), 7, fill=AMBER if active else rgb("#FFFFFF"),
                            outline=AMBER if active else BORDER, width=1)
        T(d, (x + tw / 2, 614), name, 11, rgb("#FFFFFF") if active else SUB, True, "mm")
        x += tw + 10
    T(d, (550, 614), "P1：30 分钟响应 / 4 小时解决", 10, MUTED, anchor="lm")

    # 图片
    T(d, (288, 656), "现场图片（2/5）", 11, SUB, True)
    for i in range(2):
        x = 288 + i * 116
        d.rounded_rectangle((x, 678, x + 104, 766), 8, fill=rgb("#EEF2F7"), outline=BORDER, width=1)
        d.line((x + 30, 730, x + 52, 704), fill=MUTED, width=2)
        d.line((x + 52, 704, x + 74, 730), fill=MUTED, width=2)
        d.ellipse((x + 66, 692, x + 78, 704), fill=MUTED)
    d.rounded_rectangle((520, 678, 624, 766), 8, fill=rgb("#FFFFFF"), outline=BORDER, width=1)
    T(d, (572, 716), "＋", 22, MUTED, False, "mm")
    T(d, (572, 742), "上传", 10, MUTED, False, "mm")

    # footer
    d.line((288, 804, 1008, 804), fill=rgb("#F1F4F9"), width=1)
    button(d, 804, 820, 92, 36, "取消", False, 12)
    button(d, 908, 820, 100, 36, "提交工单", True, 12)

    # 右侧 AI 摘要
    card(img, (1044, 88, 1408, 388))
    d = ImageDraw.Draw(img)
    T(d, (1064, 106), "AI 摘要", 14, TEXT, True)
    tag(d, 1300, 106, "置信度 82%", TEAL, TEAL_L, 9, False)
    paragraph(d, 1064, 140, "空压机开机后出现金属摩擦异响，压力波动，疑似轴承磨损；建议优先检查轴承间隙与皮带张紧度。", 10, SUB, 320, 18)
    d.line((1064, 220, 1388, 220), fill=rgb("#F1F4F9"), width=1)
    T(d, (1064, 236), "识别字段", 10, MUTED, True)
    for i, (k, v) in enumerate([("分类", "硬件故障"), ("紧急度", "P1"), ("设备型号", "AC-2000")]):
        y = 260 + i * 32
        T(d, (1064, y), k, 10, SUB)
        T(d, (1160, y), v, 10, TEXT, True)

    # SLA 预览
    card(img, (1044, 404, 1408, 584))
    d = ImageDraw.Draw(img)
    T(d, (1064, 422), "SLA 预览", 14, TEXT, True)
    T(d, (1064, 456), "响应时限", 10, SUB)
    T(d, (1388, 456), "30 分钟", 13, TEXT, True, "ra")
    T(d, (1064, 488), "解决时限", 10, SUB)
    T(d, (1388, 488), "4 小时", 13, TEXT, True, "ra")
    T(d, (1064, 520), "适用策略", 10, SUB)
    T(d, (1388, 520), "P1 标准", 10, TEXT, True, "ra")
    T(d, (1064, 552), "截止时间按提交时刻自动计算", 9, MUTED)

    # 提示
    card(img, (1044, 600, 1408, 720))
    d = ImageDraw.Draw(img)
    T(d, (1064, 618), "提示", 12, TEXT, True)
    T(d, (1064, 644), "AI 结果只作为建议，不阻止提交；低置信度会标黄，请人工确认。", 10, SUB)
    T(d, (1064, 666), "图片先上传，提交时自动关联到工单。", 10, SUB)

    # Agent 提示
    card(img, (1044, 736, 1408, 876))
    d = ImageDraw.Draw(img)
    T(d, (1064, 754), "也可以让 Agent 建单", 12, TEXT, True)
    T(d, (1064, 780), "例如：\"查一下 SN-2024-001 过保没，建个 P1 工单\"。", 10, SUB)
    T(d, (1064, 802), "Agent 会先查设备，再让你确认后建单。", 10, SUB)
    button(d, 1064, 830, 100, 32, "打开 Agent", True, 10)
    return img


# ---------------------------------------------------------------- 05 报表
def screen_reports():
    img = Image.new("RGBA", (W, H), BG)
    sidebar(img, "运营报表")
    topbar(img, "运营报表", crumb="数据分析")
    d = ImageDraw.Draw(img)
    select(d, 1128, 16, 150, 34, "近 7 天")
    button(d, 1290, 16, 118, 34, "导出报表", False, 11)

    kpis = [("SLA 达成率", "92.4%", "+1.8%", TEAL), ("工单总量", "128", "+12", PRIMARY),
            ("平均解决", "3.1h", "-0.4h", GREEN), ("一次解决率", "76%", "+3%", PURPLE)]
    for i, (label, val, delta, color) in enumerate(kpis):
        x = 268 + i * 289
        card(img, (x, 88, x + 273, 188))
        d = ImageDraw.Draw(img)
        T(d, (x + 20, 106), label, 11, SUB)
        T(d, (x + 20, 130), val, 26, TEXT, True)
        T(d, (x + 20, 166), delta, 10, color, True)

    # bar chart
    card(img, (268, 204, 1008, 504))
    d = ImageDraw.Draw(img)
    T(d, (288, 222), "各团队 SLA 达成率", 15, TEXT, True)
    T(d, (988, 224), "单位：%", 10, MUTED, anchor="ra")
    data = [("华东一组", 94.2, TEAL), ("华东二组", 89.7, PRIMARY), ("华南组", 91.3, PURPLE), ("华北组", 86.5, AMBER)]
    base_y, top_y = 460, 280
    for i in range(4):
        y = top_y + (base_y - top_y) * i / 3
        d.line((320, y, 968, y), fill=rgb("#EEF2F7"), width=1)
        T(d, (304, y), f"{100 - i * 10}", 9, MUTED, anchor="rm")
    bw = 70
    for i, (name, v, c) in enumerate(data):
        x = 390 + i * 150
        h = (v - 70) / 30 * (base_y - top_y)
        d.rounded_rectangle((x, base_y - h, x + bw, base_y), 6, fill=c)
        T(d, (x + bw / 2, base_y - h - 14), f"{v}%", 10, TEXT, True, "mm")
        T(d, (x + bw / 2, base_y + 14), name, 10, SUB, False, "mm")

    # donut
    card(img, (1024, 204, 1408, 504))
    d = ImageDraw.Draw(img)
    T(d, (1044, 222), "故障分类", 15, TEXT, True)
    segs = [("硬件", 46, PRIMARY), ("软件", 27, TEAL), ("耗材", 18, AMBER),
            ("安装", 15, PURPLE), ("投诉", 12, RED), ("其他", 10, MUTED)]
    total = sum(s[1] for s in segs)
    cx, cy, r = 1216, 350, 70
    start = -90
    for _, v, c in segs:
        ext = 360 * v / total
        d.arc((cx - r, cy - r, cx + r, cy + r), start, start + ext, fill=c, width=18)
        start += ext
    T(d, (cx, cy - 6), "128", 22, TEXT, True, "mm")
    T(d, (cx, cy + 16), "工单", 9, SUB, False, "mm")
    for i, (name, v, c) in enumerate(segs):
        x = 1044 + (i % 2) * 168
        y = 438 + (i // 2) * 22
        d.ellipse((x, y + 4, x + 8, y + 12), fill=c)
        T(d, (x + 14, y), f"{name} {v}", 9, SUB)

    # NL2SQL
    card(img, (268, 520, 1408, 876))
    d = ImageDraw.Draw(img)
    T(d, (288, 538), "用自然语言问数据", 15, TEXT, True)
    tag(d, 470, 538, "NL2SQL", PRIMARY, PRIMARY_L, 9, False)
    d.rounded_rectangle((288, 570, 1150, 612), 8, fill=rgb("#F8FAFC"), outline=BORDER, width=1)
    T(d, (304, 591), "本周各团队的 SLA 达成率", 12, TEXT, anchor="lm")
    button(d, 1164, 570, 92, 42, "生成", True, 12)
    T(d, (1270, 591), "示例：故障最多的 5 个设备型号", 9, MUTED, anchor="lm")

    d.rounded_rectangle((288, 630, 620, 822), 8, fill=rgb("#0F172A"))
    T(d, (304, 646), "SQL", 9, rgb("#7DD3FC"), True)
    sql_lines = ["SELECT t.name AS team,", "  ROUND(SUM(CASE WHEN", "    tk.resolved_at <= tk.resolve_deadline",
                 "    THEN 1 ELSE 0 END)", "  / COUNT(*) * 100, 1) AS rate", "FROM ticket tk JOIN team t",
                 "  ON tk.team_id = t.id", "WHERE tk.created_at >= '2026-10-20'", "GROUP BY t.name", "LIMIT 200;"]
    for i, ln in enumerate(sql_lines):
        T(d, (304, 668 + i * 14), ln, 8, rgb("#E2E8F0"), mono=True)

    # result table
    d.rounded_rectangle((640, 630, 1388, 822), 8, fill=rgb("#FCFDFE"), outline=rgb("#EEF2F7"), width=1)
    T(d, (660, 646), "团队", 10, SUB, True)
    T(d, (1120, 646), "SLA 达成率", 10, SUB, True)
    for i, (name, v) in enumerate([("华东售后一组", "94.2%"), ("华东售后二组", "89.7%"), ("华南售后组", "91.3%")]):
        y = 676 + i * 34
        T(d, (660, y), name, 11, TEXT)
        T(d, (1120, y), v, 11, TEXT, True)
        d.rounded_rectangle((1200, y + 4, 1360, y + 14), 5, fill=rgb("#F1F5F9"))
        d.rounded_rectangle((1200, y + 4, 1200 + 160 * (float(v.strip('%')) - 80) / 20, y + 14), 5, fill=TEAL)
    T(d, (660, 784), "结论：一组最高（94.2%），二组 89.7%，建议关注二组超时单。", 10, SUB)
    return img


# ---------------------------------------------------------------- 06 知识库
def screen_kb():
    img = Image.new("RGBA", (W, H), BG)
    sidebar(img, "知识库")
    topbar(img, "知识库", crumb="知识管理")
    d = ImageDraw.Draw(img)
    button(d, 1252, 16, 156, 36, "＋  上传文档")

    # 左侧分类
    card(img, (268, 88, 508, 876))
    d = ImageDraw.Draw(img)
    T(d, (288, 108), "知识分类", 14, TEXT, True)
    cats = [("全部文章", 42, True), ("设备手册", 18, False), ("故障排查", 12, False),
            ("保修政策", 5, False), ("历史工单", 7, False)]
    for i, (name, count, active) in enumerate(cats):
        y = 144 + i * 46
        if active:
            d.rounded_rectangle((284, y, 492, y + 38), 8, fill=PRIMARY_L)
        T(d, (300, y + 19), name, 12, PRIMARY if active else SUB, active, "lm")
        T(d, (476, y + 19), str(count), 10, MUTED, anchor="rm")
    d.line((288, 392, 488, 392), fill=rgb("#F1F4F9"), width=1)
    T(d, (288, 410), "索引概况", 11, SUB, True)
    for i, (k, v) in enumerate([("文章", "42"), ("分块", "1,286"), ("待处理", "2")]):
        y = 438 + i * 30
        T(d, (288, y), k, 10, SUB)
        T(d, (488, y), v, 10, TEXT, True, "ra")
    T(d, (288, 552), "提示：上传后异步解析，状态到 READY 才可被检索。", 9, MUTED)

    # 右侧表格
    card(img, (524, 88, 1408, 876))
    d = ImageDraw.Draw(img)
    T(d, (544, 108), "全部文章", 15, TEXT, True)
    T(d, (1388, 110), "共 42 篇", 10, MUTED, anchor="ra")
    search(d, 544, 138, 340, 36, "搜索标题 / 分类")
    select(d, 900, 138, 130, 36, "全部状态")
    cols = [("标题", 360), ("分类", 130), ("状态", 120), ("分块", 90), ("更新时间", 150)]
    x = 544
    d.rectangle((524, 190, 1408, 232), fill=rgb("#F8FAFC"))
    for name, w in cols:
        T(d, (x + 12, 211), name, 10, SUB, True, "lm")
        x += w
    rows = [
        ("空压机异响排查手册", "设备手册", "READY", "18", "10-26 18:00"),
        ("注塑机温控故障处理", "故障排查", "READY", "12", "10-25 09:20"),
        ("保修政策（2026 版）", "保修政策", "READY", "6", "10-24 16:10"),
        ("变频器 E03 报警说明", "设备手册", "INDEXING", "—", "10-27 10:10"),
        ("传送带跑偏调整指南", "故障排查", "FAILED", "—", "10-27 09:40"),
        ("历史工单：空压机轴承更换", "历史工单", "READY", "9", "10-23 14:30"),
    ]
    smap = {"READY": ("已就绪", GREEN, GREEN_L), "INDEXING": ("索引中", PRIMARY, PRIMARY_L),
            "FAILED": ("失败", RED, RED_L), "PENDING": ("待解析", MUTED, rgb("#F1F5F9"))}
    for i, (title, cat, status, chunks, upd) in enumerate(rows):
        y = 232 + i * 88
        if i % 2 == 1:
            d.rectangle((524, y, 1408, y + 88), fill=rgb("#FCFDFE"))
        d.line((524, y + 88, 1408, y + 88), fill=rgb("#F1F4F9"), width=1)
        T(d, (556, y + 28), title, 12, TEXT, True)
        T(d, (556, y + 50), "来源：厂商手册 / 内部整理", 9, MUTED)
        T(d, (916, y + 38), cat, 11, SUB, anchor="lm")
        label, fg, bg = smap[status]
        tag(d, 1046, y + 28, label, fg, bg, 9)
        T(d, (1176, y + 38), chunks, 11, SUB, anchor="lm")
        T(d, (1266, y + 38), upd, 10, MUTED, anchor="lm")
        for dx in (-8, 0, 8):
            d.ellipse((1368 + dx, y + 35, 1372 + dx, y + 39), fill=MUTED)

    T(d, (544, 784), "显示 1-6 条，共 42 篇", 10, MUTED)
    for i in range(4):
        x = 1180 + i * 44
        fill = PRIMARY if i == 0 else rgb("#FFFFFF")
        fg = rgb("#FFFFFF") if i == 0 else SUB
        d.rounded_rectangle((x, 770, x + 32, 798), 6, fill=fill, outline=None if i == 0 else BORDER, width=1)
        T(d, (x + 16, 784), str(i + 1) if i < 3 else "…", 11, fg, True, "mm")
    return img


def contact_sheet(images):
    cols, rows = 2, 3
    tw, th = 700, 438
    gap, pad, top = 22, 32, 92
    sheet_w = pad * 2 + cols * tw + gap
    sheet_h = top + rows * th + (rows - 1) * gap + 64
    sheet = Image.new("RGBA", (sheet_w, sheet_h), rgb("#EDF1F7"))
    d = ImageDraw.Draw(sheet)
    T(d, (pad, 28), "智维工单 · 前端效果图", 24, TEXT, True)
    T(d, (pad, 60), "ServiceDesk AI｜静态示意，非可运行前端｜2026-10-27", 11, SUB)
    labels = ["01 工作台", "02 工单列表", "03 工单详情", "04 新建工单（AI 预填）", "05 运营报表（NL2SQL）", "06 知识库"]
    for i, im in enumerate(images):
        r, c = divmod(i, cols)
        x = pad + c * (tw + gap)
        y = top + r * (th + gap)
        thumb = im.convert("RGB").resize((tw, th), Image.LANCZOS)
        sheet.paste(thumb, (x, y))
        d.rectangle((x, y, x + tw, y + th), outline=rgb("#D8E0EA"), width=1)
        T(d, (x, y + th + 8), labels[i], 11, TEXT, True)
    return sheet


def main():
    screens = [
        ("01_dashboard.png", screen_dashboard()),
        ("02_tickets.png", screen_tickets()),
        ("03_detail.png", screen_detail()),
        ("04_create.png", screen_create()),
        ("05_reports.png", screen_reports()),
        ("06_kb.png", screen_kb()),
    ]
    for name, im in screens:
        im.convert("RGB").save(os.path.join(OUT, name), quality=95)
        print("saved", name)
    sheet = contact_sheet([im for _, im in screens])
    sheet.convert("RGB").save(os.path.join(OUT, "00_overview.png"), quality=95)
    print("saved 00_overview.png", sheet.size)


if __name__ == "__main__":
    main()
