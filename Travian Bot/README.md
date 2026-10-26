# Travian Bot

Automation bot for the Travian browser game (T4.6 Legends). Java (Spring Boot) backend, React (Vite + TypeScript) frontend, PostgreSQL in Docker. Takes inspiration from [TravianBotSharp](https://github.com/vinaghost/TravianBotSharp) but rewritten with a cleaner architecture and cross-platform (runs natively on macOS Apple Silicon).

## Stack

- **Backend:** Spring Boot 3.3, Java 21, Maven, Flyway
- **Frontend:** React 18, Vite 5, TypeScript 5, react-router 6, i18next (EN/HR)
- **Database:** PostgreSQL 16 (Docker, port 5485 host → 5432 container)
- **Bot engine:** Playwright for Java 1.47 + Jsoup 1.18 (browser + HTML parsing)
- **Testing:** JUnit 5, Spring Boot Test, Testcontainers
- **CI:** GitHub Actions (`.github/workflows/ci.yml`)

## Project layout

```
.
├── .github/workflows/  # CI
└── Travian Bot/
    ├── backend/            # Spring Boot app
    ├── frontend/           # Vite + React app
    ├── build/debug/        # HTML scraper fixtures captured at runtime
    ├── docker-compose.yml  # Postgres only
    └── README.md
```

### Backend (`backend/src/main/java/com/travianbot/`)

Feature-sliced — each module owns its own `api/`, `domain/`, `persistence/`, and `service/` subpackages.

```
com/travianbot/
├── TravianBotApplication.java
├── common/        # cross-cutting: WebConfig (CORS), AsyncConfig, error advice
├── health/        # GET /api/health
│
├── account/       # accounts + proxies + per-account settings + active-window gate
├── village/       # villages, buildings, build queue, resource fields, per-village settings
├── farmlist/      # farmlists + targets
├── hero/          # hero state, adventures, items
├── troops/        # troop train queue
├── template/      # village build templates
├── task/          # scheduled bot tasks
├── statistics/    # stat snapshots, top10, seeders
├── settings/      # global settings
├── note/          # user notes
├── botlog/        # bot event log
├── aianalysis/    # oasis scans + AI analysis hooks
│
└── bot/           # bot engine
    ├── client/        # TravianClient iface + Playwright impl (BrowserTravianClient)
    │   └── browser/   # BrowserSessionManager, HumanDelay, LoginFailedException
    ├── scraper/       # HTML → DTO parsers (Farmlist, Village, Hero, Profile, …)
    ├── command/       # atomic bot ops (planned)
    ├── page/          # typed page objects (planned)
    └── parser/        # pure HTML → DTO helpers (planned)
```

DB schema is currently managed by Hibernate (`ddl-auto=update`). Flyway is wired
but the V1 baseline still needs to be captured — see
`backend/src/main/resources/db/migration/README.md`.

### Frontend (`frontend/src/`)

```
src/
├── main.tsx · App.tsx · index.css
├── api/            # typed HTTP clients per domain (accounts, villages, farmlists, …)
├── types/          # shared TS types per domain
├── hooks/          # custom hooks (useBackendHealth, useCurrentAccount)
├── components/     # primitives (Card, Sidebar, AppLayout, StatusBadge)
├── features/       # feature-scoped UI (accounts, villages, farmlists, hero, settings, …)
├── pages/          # top-level screens (one per route)
├── i18n/           # EN + HR translations
└── utils/          # accountSlug, time helpers
```

## Getting started

### 1. Configure env

```bash
cp .env.example .env    # adjust if you want
```

### 2. Start PostgreSQL

```bash
docker compose up -d
```

Postgres on `localhost:5485` — database `travian_bot`, user/password `travian`.

### 3. Run the backend

```bash
cd backend
mvn spring-boot:run
```

API on `http://localhost:8185`. Health check: `GET /api/health`. Override with `SERVER_PORT=...` env var.

### 4. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Dev server on `http://localhost:5185`. Proxies `/api/*` to the backend on `:8185`.

### Tests

```bash
cd backend
mvn verify     # runs unit + Testcontainers integration tests (needs Docker)
```

## Planned features

See the project roadmap discussion for the full list. Highlights:

- Attack analysis (estimate attacker's TS level from incoming wave speed, time the return-catch)
- Farmlist AI optimizer (prune deadweight, auto-expand by oasis radius)
- Multi-account orchestration with proxy pool rotation + per-account active window
- Hero auto-leveling and adventure runner
- Oasis intel (already partially scaffolded under `aianalysis/`)
