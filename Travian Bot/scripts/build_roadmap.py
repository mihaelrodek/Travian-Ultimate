"""
Build ROADMAP.xlsx — feature backlog with status tracking.

Outputs three sheets:
  1. Backlog  — full feature list with status, priority, effort, etc.
  2. Summary  — pivot counts via COUNTIF
  3. Legend   — column / value definitions
"""

from openpyxl import Workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
from openpyxl.formatting.rule import CellIsRule
from openpyxl.worksheet.table import Table, TableStyleInfo


HEADER_FONT = Font(name="Arial", size=11, bold=True, color="FFFFFF")
HEADER_FILL = PatternFill("solid", start_color="1F4E78")
BODY_FONT = Font(name="Arial", size=10)
BORDER = Border(
    left=Side(style="thin", color="CCCCCC"),
    right=Side(style="thin", color="CCCCCC"),
    top=Side(style="thin", color="CCCCCC"),
    bottom=Side(style="thin", color="CCCCCC"),
)
CENTER = Alignment(horizontal="center", vertical="center", wrap_text=True)
LEFT = Alignment(horizontal="left", vertical="center", wrap_text=True)

# Status color fills
STATUS_FILLS = {
    "Done":        PatternFill("solid", start_color="C6EFCE"),
    "In Progress": PatternFill("solid", start_color="FFEB9C"),
    "Deferred":    PatternFill("solid", start_color="D9D9D9"),
    "Not Started": PatternFill("solid", start_color="FFFFFF"),
}
PRIORITY_FILLS = {
    "Critical": PatternFill("solid", start_color="F4B084"),
    "High":     PatternFill("solid", start_color="FFD966"),
    "Medium":   PatternFill("solid", start_color="FFF2CC"),
    "Low":      PatternFill("solid", start_color="E7E6E6"),
}

HEADERS = [
    "ID", "Phase / Tier", "Category", "Feature",
    "Description", "Status", "Priority", "Effort",
    "Inspired By", "Dependencies", "Notes",
]


# ---------- Feature catalogue ----------
# (ID, Phase, Category, Feature, Description, Status, Priority, Effort, InspiredBy, Deps, Notes)
ROWS = [
    # Phase 0 — Infrastructure
    ("P0.1", "Phase 0",  "Infra",       "Fix docker-compose.yml",
     "Broken volume mount + blank port entry — make Postgres persist cleanly.",
     "Done", "Critical", "S", "Custom", "", ""),
    ("P0.2", "Phase 0",  "Infra",       "Flyway dep + config",
     "Add flyway-core / flyway-database-postgresql, baseline-on-migrate=true.",
     "Done", "High", "S", "Custom", "", "V1__init.sql still TBD"),
    ("P0.3", "Phase 0",  "Infra",       "ddl-auto → validate",
     "Switch Hibernate to validate once V1 baseline captured.",
     "Deferred", "High", "S", "Custom", "P0.2,TEST4", "Blocked on V1 dump"),
    ("P0.4", "Phase 0",  "Testing",     "Testcontainers + sanity ITs",
     "AbstractIntegrationTest + HealthControllerIT + AccountRepositoryIT.",
     "Done", "High", "M", "Custom", "", ""),
    ("P0.5", "Phase 0",  "Infra",       "GitHub Actions CI",
     "mvn verify + npm build on push / PR.",
     "Done", "High", "S", "Custom", "P0.4", ""),
    ("P0.6", "Phase 0",  "Docs",        "Sync README with 13 modules",
     "README listed only auth/health/bot — refresh to reality.",
     "Done", "Medium", "S", "Custom", "", ""),
    ("P0.7", "Phase 0",  "Infra",       "Port consolidation 5185/8185/5485",
     "All app ports end in -185 to avoid collision with other local apps.",
     "Done", "Low", "S", "Custom", "", ""),

    # Phase 1 — Bot Core
    ("P1.1",  "Phase 1", "Bot Core",  "BrowserTravianClient stable login",
     "Tolerant selectors + humanType, retry, captcha-aware fallback.",
     "Done", "Critical", "L", "TravBotSharp", "", ""),
    ("P1.2",  "Phase 1", "Bot Core",  "Village scraping scheduler",
     "VillageScrapeScheduler refreshes resources/buildings on cadence.",
     "Done", "Critical", "M", "TravBotSharp", "P1.1", ""),
    ("P1.3",  "Phase 1", "Bot Core",  "Build queue executor",
     "BuildQueueScheduler picks next pending item, clicks upgrade.",
     "Done", "Critical", "L", "TravBotSharp", "P1.2", ""),
    ("P1.4",  "Phase 1", "Bot Core",  "NPC trade automation",
     "Marketplace gid=17 NPC redistribute, gated on Plus/gold.",
     "Done", "High", "M", "TravBotSharp", "P1.1", ""),
    ("P1.5",  "Phase 1", "Bot Core",  "Farmlist runner",
     "FarmlistSendScheduler + sendFarmlist clicks startFarmListRaid.",
     "Done", "Critical", "M", "TravBotSharp", "P1.1", ""),
    ("P1.6",  "Phase 1", "Bot Core",  "Hero attribute spend",
     "Auto-allocate unspent points into configured attribute.",
     "Done", "High", "M", "TravBotSharp", "P1.1", ""),
    ("P1.7",  "Phase 1", "Bot Core",  "Auto re-login on 302",
     "Wrap NotLoggedInException, trigger login(), retry once.",
     "Not Started", "High", "S", "TravBotSharp", "P1.1", ""),
    ("P1.8",  "Phase 1", "Bot Core",  "Hero adventure auto-runner",
     "Pick safest adventure, send hero, track HP.",
     "Not Started", "Medium", "M", "TravBotSharp", "P1.6", ""),
    ("P1.9",  "Phase 1", "Bot Core",  "Resource overflow alert",
     "Warehouse > 95% → BotEvent + (later) notification.",
     "Not Started", "Medium", "S", "Custom", "P1.2,NOT1", ""),
    ("P1.10", "Phase 1", "Bot Core",  "Master-builder (gold) protection",
     "Refuse to click gold-only upgrade button when queue full.",
     "Done", "High", "S", "Custom", "P1.3", ""),
    ("P1.11", "Phase 1", "Bot Core",  "Server time / Plus / UTC offset scrape",
     "Persist offset + Plus status on every dorf1 scrape.",
     "Done", "Medium", "S", "Custom", "P1.2", ""),
    ("P1.12", "Phase 1", "Bot Core",  "Sidebar village auto-discovery",
     "Parse sidebar list to upsert villages without manual config.",
     "Done", "Medium", "S", "Custom", "P1.2", ""),
    ("P1.13", "Phase 1", "Bot Core",  "In-game build queue parsing",
     "Read finish-at timestamps from #buildingList sidebar.",
     "Done", "Medium", "S", "Custom", "P1.2", ""),
    ("P1.14", "Phase 1", "Bot Core",  "Queue reconciliation RUNNING/DONE",
     "Match scraped page state to local queue rows.",
     "Done", "High", "S", "Custom", "P1.13", ""),

    # Phase 2 — Safety / Anti-detect
    ("P2.1",  "Phase 2", "Safety",    "HumanDelay range",
     "Pre-action random delay from configurable min/max.",
     "Done", "Critical", "S", "Custom", "", ""),
    ("P2.2",  "Phase 2", "Safety",    "humanType per-key with stalls",
     "Per-char keypress with 60–180 ms cadence + 6% thinking stalls.",
     "Done", "Critical", "S", "Custom", "", ""),
    ("P2.3",  "Phase 2", "Safety",    "Click-or-navigate human nav",
     "Prefer clicking <a> instead of editing address bar.",
     "Done", "Medium", "S", "Custom", "", ""),
    ("P2.4",  "Phase 2", "Safety",    "Multi-account parallel sessions",
     "Per-account browser via BrowserSessionManager + ConcurrentHashMap.",
     "Done", "High", "S", "TravBotSharp", "", ""),
    ("P2.6",  "Phase 2", "Safety",    "Playwright proxy injection",
     "Apply AccountProxy when launching Chromium; rotate per account.",
     "Not Started", "Critical", "M", "Custom", "P2.4", "AccountProxy entity exists but unused"),
    ("P2.7",  "Phase 2", "Safety",    "Rate limiter per account",
     "Bucket4j or token bucket: cap req/min per accountId.",
     "Not Started", "High", "M", "Custom", "", ""),
    ("P2.8",  "Phase 2", "Safety",    "Captcha detect + pause + notif",
     "Detect challenge DOM markers, pause scheduler, notify user.",
     "Not Started", "Critical", "M", "Custom", "NOT1", ""),
    ("P2.9",  "Phase 2", "Safety",    "User-agent / viewport rotation",
     "Per-session UA + viewport randomization at launch.",
     "Not Started", "Medium", "S", "Custom", "P2.4", ""),
    ("P2.10", "Phase 2", "Safety",    "BotLog UI filters",
     "Frontend filter by account/severity/date/search.",
     "Not Started", "Medium", "M", "Custom", "", "Backend log exists"),
    ("P2.11", "Phase 2", "Safety",    "Headless mode toggle per-account",
     "Per-account boolean: run Chromium without UI.",
     "Not Started", "Low", "S", "Custom", "P2.4", ""),
    ("P2.12", "Phase 2", "Safety",    "Mouse path randomization",
     "Curve path before click instead of teleporting.",
     "Not Started", "Low", "M", "Custom", "", ""),

    # Tier A — AI Analytics
    ("A1", "Tier A", "AI Analytics", "Incoming attack scraper + TS analyzer",
     "Parse rally point, compute attacker speed/troop class.",
     "Done", "Critical", "L", "Travcotools", "P1.2", ""),
    ("A2", "Tier A", "AI Analytics", "Return-catch optimizer",
     "Suggest launch-time so we hit attacker home with no defense.",
     "Done", "Critical", "M", "Custom", "A1", ""),
    ("A3", "Tier A", "AI Analytics", "Inactive search (map.sql)",
     "Parse daily map.sql dump, flag inactive players with filters.",
     "Done", "High", "L", "Travcotools", "", ""),

    # Tier B — Power features
    ("B1", "Tier B", "Analytics",  "Farmlist analyzer + traffic-light",
     "Parse berichte.php raid reports, attribute, score per target.",
     "Done", "High", "L", "Travcotools", "P1.5", ""),
    ("B2", "Tier B", "AI Analytics", "Enemy intel + hero tracker",
     "Snapshot enemy profile, narrate hero/pop changes.",
     "Done", "High", "L", "Custom", "A1,A3", ""),
    ("B3", "Tier B", "AI Analytics", "Cropper finder 15c/9c",
     "Heuristic classification from population + capital flag.",
     "Done", "Medium", "M", "Travmap", "A3", ""),

    # Tier C — Alliance / Coordination
    ("C1", "Tier C", "Notifications", "Discord webhook notifier",
     "Push BotEvents to configured webhook URL.",
     "Not Started", "High", "S", "Travcotools", "", ""),
    ("C2", "Tier C", "Notifications", "Telegram notifier",
     "Bot token + chat id, push critical events.",
     "Not Started", "Medium", "S", "Custom", "", ""),
    ("C3", "Tier C", "Notifications", "Desktop push notifications",
     "Browser Notification API + service worker.",
     "Not Started", "High", "S", "Custom", "", ""),
    ("C4", "Tier C", "Coord",         "Defense pull (wave-timing)",
     "Coordinate sends from N villages to arrive simultaneously.",
     "Not Started", "Medium", "L", "Travcotools", "P1.1", ""),
    ("C5", "Tier C", "Coord",         "Resource subsidies automation",
     "Inter-account / inter-village marketplace push routing.",
     "Not Started", "Medium", "M", "Travcotools", "P1.4", ""),
    ("C6", "Tier C", "Coord",         "Sitter coop support",
     "Multi-user access with role permissions.",
     "Not Started", "Low", "L", "Travian", "", ""),

    # Tier D — Strategy / Late game
    ("D1", "Tier D", "Strategy",   "Artifact spawn coordinator",
     "Hardcoded spawn dates + grab planning per village.",
     "Not Started", "Low", "XL", "Travcotools", "", ""),
    ("D2", "Tier D", "Analytics",  "Top10 / leaderboard delta tracking",
     "Daily snapshot, render deltas, suspect-hammer detection.",
     "In Progress", "Medium", "M", "Travcotools", "", "Entity + scheduler exist; UI partial"),
    ("D3", "Tier D", "Strategy",   "WW countdown tracker",
     "Wonder-of-the-World construction watcher (very late server).",
     "Not Started", "Low", "M", "Travian", "", ""),
    ("D4", "Tier D", "Analytics",  "Cropper ROI ranking",
     "Score croppers by distance/oasis bonus/settle time.",
     "Not Started", "Low", "M", "Custom", "B3", ""),
    ("D5", "Tier D", "Strategy",   "Goal mode (WW/off/deff templates)",
     "Pre-set build queues + troop mix based on user goal.",
     "Not Started", "Low", "XL", "Custom", "P1.3", ""),
    ("D6", "Tier D", "Strategy",   "CP / settler planner",
     "Compute when CP + 3 settlers reach quota for next village.",
     "Not Started", "Medium", "M", "Gettertools", "P1.2", ""),
    ("D7", "Tier D", "Strategy",   "Capital choice advisor",
     "Detect 15c in radius, recommend capital relocation.",
     "Not Started", "Low", "M", "Travmap", "B3", ""),

    # Tier MAP — Travmap style
    ("MAP1",  "Tier MAP", "Map", "Interactive world map view",
     "Render MapPlayer/MapVillage on Canvas/SVG, pan + zoom.",
     "Not Started", "High", "XL", "Travmap", "A3", ""),
    ("MAP2",  "Tier MAP", "Map", "Layer toggles",
     "Toggle: own villages, alliance, tribe, inactive, croppers.",
     "Not Started", "High", "M", "Travmap", "MAP1", ""),
    ("MAP3",  "Tier MAP", "Map", "Distance measurement tool",
     "Click 2 tiles → field distance + travel time per tribe.",
     "Not Started", "Medium", "S", "Travmap,Gettertools", "MAP1", ""),
    ("MAP4",  "Tier MAP", "Map", "Tile action menu",
     "Right-click tile → add to farmlist / mark cropper / view player.",
     "Not Started", "Medium", "M", "Custom", "MAP1,B1,B3", ""),
    ("MAP5",  "Tier MAP", "Map", "Historical playback (time slider)",
     "Step through map.sql snapshots over time.",
     "Not Started", "Low", "L", "Travmap", "MAP1", "Needs MapPlayerHistory entity"),
    ("MAP6",  "Tier MAP", "Map", "Bookmarks / notes on tiles",
     "Pin tiles with user notes (cropper target, enemy spawn, etc).",
     "Not Started", "Low", "S", "Travmap", "MAP1", ""),
    ("MAP7",  "Tier MAP", "Map", "Search by player / alliance",
     "Center map on player or zoom-fit alliance territory.",
     "Not Started", "Medium", "S", "Travmap", "MAP1", ""),
    ("MAP8",  "Tier MAP", "Map", "Map.sql diff visualization",
     "Show newly-settled / abandoned villages between syncs.",
     "Not Started", "Low", "M", "Travmap", "MAP1", ""),
    ("MAP9",  "Tier MAP", "Map", "Heatmap overlays",
     "Population density / alliance influence heatmap.",
     "Not Started", "Medium", "M", "Travmap", "MAP1", ""),
    ("MAP10", "Tier MAP", "Map", "Incoming-attack arrows overlay",
     "Render IncomingAttack source→target arrows live.",
     "Not Started", "High", "M", "Custom", "MAP1,A1", ""),

    # Tier CALC — Gettertools style
    ("CALC1", "Tier CALC", "Calculator", "Troop battle simulator",
     "Simulate raid: attacker units vs defender + wall + hero.",
     "Not Started", "Medium", "M", "Gettertools", "", ""),
    ("CALC2", "Tier CALC", "Calculator", "Travel time calc",
     "Coord-to-coord time per tribe / unit class.",
     "Not Started", "High", "S", "Gettertools", "", ""),
    ("CALC3", "Tier CALC", "Calculator", "Settler ETA calc",
     "Resources + CP + production → ETA new village.",
     "Not Started", "Low", "S", "Gettertools", "P1.2", ""),
    ("CALC4", "Tier CALC", "Calculator", "Hero regen calc",
     "HP regen rate from helmet / production bonus.",
     "Not Started", "Low", "S", "Gettertools", "P1.6", ""),
    ("CALC5", "Tier CALC", "Calculator", "Building cost lookup",
     "Lookup wood/clay/iron/crop cost for any building level.",
     "Not Started", "Medium", "S", "Gettertools", "", ""),
    ("CALC6", "Tier CALC", "Calculator", "Combat simulator (advanced)",
     "Includes hero stats, walls, alliance bonuses, morale.",
     "Not Started", "Low", "L", "Gettertools", "CALC1", ""),

    # Notifications & UX
    ("NOT3", "Notifications", "Notifications", "Sound alerts on critical events",
     "Web Audio API on incoming attack, captcha, hero died.",
     "Not Started", "Medium", "S", "Custom", "C3", ""),
    ("NOT4", "Notifications", "UX",           "WebSocket / SSE live updates",
     "Push backend events to UI instead of polling REST.",
     "Not Started", "High", "M", "Custom", "", ""),
    ("NOT5", "Notifications", "UX",           "Mobile-friendly responsive layout",
     "Sidebar collapses, tables horizontal-scroll, touch targets.",
     "Not Started", "Low", "M", "Custom", "", ""),
    ("NOT6", "Notifications", "UX",           "PWA install",
     "Service worker + manifest, installable on mobile/desktop.",
     "Not Started", "Low", "S", "Custom", "NOT5", ""),
    ("NOT7", "Notifications", "Notifications", "Email daily digest",
     "SMTP / Resend summary of activity & alerts.",
     "Not Started", "Low", "M", "Custom", "", ""),

    # Security
    ("SEC1", "Security", "Security", "Password encryption AES-GCM",
     "Encrypt Account.password at rest keyed from master password.",
     "Not Started", "Critical", "M", "Custom", "", "Currently plaintext"),
    ("SEC2", "Security", "Security", "Session secret rotation",
     "Refresh Travian session cookies periodically.",
     "Not Started", "Low", "S", "Custom", "P1.1", ""),
    ("SEC3", "Security", "Security", "Audit log of account access",
     "Track who/when accessed Account credentials.",
     "Not Started", "Low", "S", "Custom", "", ""),
    ("SEC4", "Security", "Security", "IP allowlist for backend API",
     "Restrict /api/* to allowed IPs (when deployed).",
     "Not Started", "Low", "S", "Custom", "", ""),

    # Reliability
    ("REL1", "Reliability", "Reliability", "Durable task queue",
     "Persist scheduler state in DB so restart resumes mid-batch.",
     "Not Started", "High", "L", "Custom", "", "task/ module exists, not wired"),
    ("REL2", "Reliability", "Reliability", "Crash-recovery state",
     "On boot, finish or rollback in-flight Playwright operations.",
     "Not Started", "High", "M", "Custom", "REL1", ""),
    ("REL3", "Reliability", "Reliability", "PG dump scheduler",
     "Daily backup, retain N copies.",
     "Not Started", "Medium", "S", "Custom", "", ""),
    ("REL4", "Reliability", "Reliability", "Health-check + auto-restart",
     "/api/health watchdog + supervisor restart on failure.",
     "Not Started", "Low", "S", "Custom", "", ""),
    ("REL5", "Reliability", "Reliability", "Observability (Prometheus + Grafana)",
     "Metrics export + dashboards.",
     "Not Started", "Low", "L", "Custom", "", ""),

    # Performance
    ("PERF1", "Performance", "Performance", "HTTP fallback client",
     "Jsoup + HttpClient + cookies for routine scraping (no browser).",
     "Not Started", "High", "XL", "Custom", "P1.1", "10x faster, 5x less RAM"),
    ("PERF2", "Performance", "Performance", "Page result caching",
     "Cache scraped HTML for N seconds for repeated reads.",
     "Not Started", "Low", "M", "Custom", "", ""),
    ("PERF3", "Performance", "Performance", "Browser pool optimization",
     "Reuse Chromium contexts across accounts where safe.",
     "Not Started", "Medium", "M", "Custom", "P2.4", ""),

    # Testing
    ("TEST1", "Testing", "Testing", "Scraper golden HTML fixtures",
     "Convert build/debug/*.html into parser regression tests.",
     "Not Started", "High", "M", "Custom", "", ""),
    ("TEST2", "Testing", "Testing", "Frontend E2E tests",
     "Playwright tests for sidebar / pages / forms.",
     "Not Started", "Medium", "L", "Custom", "", ""),
    ("TEST3", "Testing", "Testing", "Load testing",
     "Simulate N accounts × M villages traffic.",
     "Not Started", "Low", "M", "Custom", "", ""),
    ("TEST4", "Testing", "Testing", "V1 baseline migration generation",
     "Run Hibernate schema export → V1__init.sql.",
     "Not Started", "High", "S", "Custom", "P0.2", "Blocks P0.3"),

    # i18n
    ("I18N1", "i18n", "i18n", "English translation",
     "All UI strings keyed.",
     "Done", "Critical", "S", "Custom", "", ""),
    ("I18N2", "i18n", "i18n", "Croatian translation",
     "All UI strings keyed.",
     "Done", "Critical", "S", "Custom", "", ""),
    ("I18N3", "i18n", "i18n", "German translation",
     "Important market for Travian.",
     "Not Started", "Low", "M", "Custom", "", ""),
    ("I18N4", "i18n", "i18n", "Russian translation",
     "Important market for Travian.",
     "Not Started", "Low", "M", "Custom", "", ""),
]


def build_backlog(wb):
    ws = wb.active
    ws.title = "Backlog"

    # Header
    for col, h in enumerate(HEADERS, start=1):
        c = ws.cell(row=1, column=col, value=h)
        c.font = HEADER_FONT
        c.fill = HEADER_FILL
        c.alignment = CENTER
        c.border = BORDER

    # Body
    for r, row in enumerate(ROWS, start=2):
        for col, value in enumerate(row, start=1):
            c = ws.cell(row=r, column=col, value=value)
            c.font = BODY_FONT
            c.alignment = LEFT if col in (4, 5, 11) else CENTER
            c.border = BORDER

        status = row[5]
        priority = row[6]
        if status in STATUS_FILLS:
            ws.cell(row=r, column=6).fill = STATUS_FILLS[status]
        if priority in PRIORITY_FILLS:
            ws.cell(row=r, column=7).fill = PRIORITY_FILLS[priority]

    # Freeze top row + ID column
    ws.freeze_panes = "B2"

    # Column widths
    widths = [10, 14, 16, 36, 60, 14, 11, 8, 18, 18, 28]
    for i, w in enumerate(widths, start=1):
        ws.column_dimensions[get_column_letter(i)].width = w

    # Row height
    for r in range(2, len(ROWS) + 2):
        ws.row_dimensions[r].height = 36

    # AutoFilter
    last_col_letter = get_column_letter(len(HEADERS))
    last_row = len(ROWS) + 1
    ws.auto_filter.ref = f"A1:{last_col_letter}{last_row}"


def build_summary(wb, total_rows):
    ws = wb.create_sheet("Summary")

    title = ws.cell(row=1, column=1, value="Travian-Ultimate Roadmap Summary")
    title.font = Font(name="Arial", size=14, bold=True, color="1F4E78")
    ws.merge_cells("A1:D1")

    last_data_row = total_rows + 1  # row 1 is header in Backlog

    # By Status
    ws.cell(row=3, column=1, value="By Status").font = Font(name="Arial", size=12, bold=True)
    ws.cell(row=4, column=1, value="Status")
    ws.cell(row=4, column=2, value="Count")
    ws.cell(row=4, column=3, value="% of Total")
    for c in ws["A4:C4"][0]:
        c.font = HEADER_FONT
        c.fill = HEADER_FILL
        c.alignment = CENTER

    statuses = ["Done", "In Progress", "Deferred", "Not Started"]
    for i, status in enumerate(statuses):
        r = 5 + i
        ws.cell(row=r, column=1, value=status).fill = STATUS_FILLS[status]
        ws.cell(row=r, column=2,
                value=f'=COUNTIF(Backlog!F2:F{last_data_row},"{status}")')
        ws.cell(row=r, column=3,
                value=f'=IFERROR(B{r}/SUM($B$5:$B$8),0)')
        ws.cell(row=r, column=3).number_format = "0.0%"

    # Total row
    total_r = 5 + len(statuses)
    ws.cell(row=total_r, column=1, value="Total").font = Font(name="Arial", bold=True)
    ws.cell(row=total_r, column=2, value=f"=SUM(B5:B{total_r - 1})").font = Font(name="Arial", bold=True)

    # By Priority
    ws.cell(row=3, column=5, value="By Priority").font = Font(name="Arial", size=12, bold=True)
    ws.cell(row=4, column=5, value="Priority")
    ws.cell(row=4, column=6, value="Count")
    ws.cell(row=4, column=7, value="% of Total")
    for c in ws["E4:G4"][0]:
        c.font = HEADER_FONT
        c.fill = HEADER_FILL
        c.alignment = CENTER

    priorities = ["Critical", "High", "Medium", "Low"]
    for i, p in enumerate(priorities):
        r = 5 + i
        ws.cell(row=r, column=5, value=p).fill = PRIORITY_FILLS[p]
        ws.cell(row=r, column=6,
                value=f'=COUNTIF(Backlog!G2:G{last_data_row},"{p}")')
        ws.cell(row=r, column=7,
                value=f'=IFERROR(F{r}/SUM($F$5:$F$8),0)')
        ws.cell(row=r, column=7).number_format = "0.0%"

    # By Inspired By
    ws.cell(row=11, column=1, value="By Inspiration").font = Font(name="Arial", size=12, bold=True)
    ws.cell(row=12, column=1, value="Source")
    ws.cell(row=12, column=2, value="Count")
    for c in ws["A12:B12"][0]:
        c.font = HEADER_FONT
        c.fill = HEADER_FILL
        c.alignment = CENTER

    sources = ["TravBotSharp", "Travcotools", "Travmap", "Gettertools", "Custom"]
    for i, s in enumerate(sources):
        r = 13 + i
        ws.cell(row=r, column=1, value=s)
        ws.cell(row=r, column=2,
                value=f'=COUNTIF(Backlog!I2:I{last_data_row},"*{s}*")')

    # Done by Tier
    ws.cell(row=11, column=5, value="Progress by Phase / Tier").font = Font(name="Arial", size=12, bold=True)
    ws.cell(row=12, column=5, value="Phase / Tier")
    ws.cell(row=12, column=6, value="Done")
    ws.cell(row=12, column=7, value="Total")
    ws.cell(row=12, column=8, value="% Done")
    for c in ws["E12:H12"][0]:
        c.font = HEADER_FONT
        c.fill = HEADER_FILL
        c.alignment = CENTER

    tiers = [
        "Phase 0", "Phase 1", "Phase 2",
        "Tier A", "Tier B", "Tier C", "Tier D",
        "Tier MAP", "Tier CALC",
        "Notifications", "Security", "Reliability",
        "Performance", "Testing", "i18n",
    ]
    for i, tier in enumerate(tiers):
        r = 13 + i
        ws.cell(row=r, column=5, value=tier)
        ws.cell(row=r, column=6,
                value=f'=COUNTIFS(Backlog!B2:B{last_data_row},"{tier}",Backlog!F2:F{last_data_row},"Done")')
        ws.cell(row=r, column=7,
                value=f'=COUNTIF(Backlog!B2:B{last_data_row},"{tier}")')
        ws.cell(row=r, column=8,
                value=f'=IFERROR(F{r}/G{r},0)')
        ws.cell(row=r, column=8).number_format = "0.0%"

    # Column widths
    for col, w in [("A", 18), ("B", 10), ("C", 12), ("D", 4),
                   ("E", 22), ("F", 8), ("G", 8), ("H", 10)]:
        ws.column_dimensions[col].width = w


def build_legend(wb):
    ws = wb.create_sheet("Legend")

    ws.cell(row=1, column=1, value="ROADMAP.xlsx — Column & Value Legend").font = Font(name="Arial", size=14, bold=True)
    ws.merge_cells("A1:C1")

    sections = [
        ("Status values", [
            ("Done", "Implementation complete; in production."),
            ("In Progress", "Some scaffolding present; not feature-complete."),
            ("Deferred", "Blocked or intentionally postponed; reason in Notes."),
            ("Not Started", "Backlog item, not yet picked up."),
        ]),
        ("Priority values", [
            ("Critical", "Blocks core flow or security; do first."),
            ("High", "Materially improves UX / safety / coverage."),
            ("Medium", "Quality-of-life or strategic edge."),
            ("Low", "Nice-to-have / polish."),
        ]),
        ("Effort values", [
            ("S", "Small  (≤ 1 day)"),
            ("M", "Medium (1–3 days)"),
            ("L", "Large  (3–7 days)"),
            ("XL", "Extra Large (> 1 week)"),
        ]),
        ("Inspired By values", [
            ("TravBotSharp", "Local desktop bot — autoplay parity."),
            ("Travcotools", "Cloud planner — attack/defense/intel parity."),
            ("Gettertools", "Pure calculators — troop/travel/cost lookup."),
            ("Travmap", "Interactive world map viewer."),
            ("Custom", "Original idea not present in inspirations."),
            ("Travian", "Mirrors a stock feature of the game itself."),
        ]),
        ("Workflow", [
            ("Add new item", "Append a row in Backlog; ID convention: <Phase>.<number> or <Tier><number>."),
            ("Update status", "Change the Status cell; Summary recalculates on save (LibreOffice / Excel)."),
            ("Mark blocked", "Set Status=Deferred and explain in Notes."),
            ("Track dependency", "List the blocking IDs comma-separated in Dependencies."),
        ]),
    ]

    row = 3
    for section_title, items in sections:
        cell = ws.cell(row=row, column=1, value=section_title)
        cell.font = Font(name="Arial", size=12, bold=True, color="1F4E78")
        ws.merge_cells(start_row=row, start_column=1, end_row=row, end_column=3)
        row += 1

        ws.cell(row=row, column=1, value="Value").font = HEADER_FONT
        ws.cell(row=row, column=1).fill = HEADER_FILL
        ws.cell(row=row, column=2, value="Meaning").font = HEADER_FONT
        ws.cell(row=row, column=2).fill = HEADER_FILL
        ws.merge_cells(start_row=row, start_column=2, end_row=row, end_column=3)
        row += 1

        for value, meaning in items:
            ws.cell(row=row, column=1, value=value).font = BODY_FONT
            ws.cell(row=row, column=2, value=meaning).font = BODY_FONT
            ws.cell(row=row, column=2).alignment = LEFT
            ws.merge_cells(start_row=row, start_column=2, end_row=row, end_column=3)
            row += 1
        row += 1

    ws.column_dimensions["A"].width = 22
    ws.column_dimensions["B"].width = 50
    ws.column_dimensions["C"].width = 30


def main():
    wb = Workbook()
    build_backlog(wb)
    build_summary(wb, total_rows=len(ROWS))
    build_legend(wb)

    out = r"D:\Mihael\Aplikacije\Travian-Ultimate\Travian Bot\ROADMAP.xlsx"
    wb.save(out)
    print(f"Wrote {out} with {len(ROWS)} backlog rows.")


if __name__ == "__main__":
    main()
