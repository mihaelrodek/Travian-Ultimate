# Travian Bot

Automation bot for the Travian browser game (T4.6 Legends). Java (Spring Boot) backend, React (Vite + TypeScript) frontend, PostgreSQL in Docker. Takes inspiration from [TravianBotSharp](https://github.com/vinaghost/TravianBotSharp) but rewritten with a cleaner architecture and cross-platform (runs natively on macOS Apple Silicon).

## Stack

- **Backend:** Spring Boot 3.3, Java 21, Maven
- **Frontend:** React 18, Vite 5, TypeScript 5
- **Database:** PostgreSQL 16 (Docker)
- **Bot engine (planned):** Playwright for Java (login + JS-heavy flows) + Apache HttpClient 5 + Jsoup (routine scraping)

## Project layout

```
.
├── backend/            # Spring Boot app (see structure below)
├── frontend/           # Vite + React app (see structure below)
├── docker-compose.yml  # Postgres only
└── README.md
```

### Backend (`backend/src/main/java/com/travianbot/`)

Organized by feature module rather than by technical layer. Each module owns its own `api/`, `domain/`, `persistence/`, and `service/` subpackages as it grows.

```
com/travianbot/
├── TravianBotApplication.java
├── common/                   # cross-cutting concerns
│   ├── config/               # WebConfig (CORS), AsyncConfig (thread pool, @EnableScheduling)
│   └── error/                # ApiError record + @RestControllerAdvice
├── health/                   # GET /api/health
├── auth/                     # POST /api/auth/login (placeholder)
│   └── api/
│       └── dto/
└── bot/                      # the bot engine
    ├── client/               # TravianClient interface + TravianSession (per-account state)
    │                         #   browser/ (Playwright impl, planned)
    │                         #   http/    (HttpClient + Jsoup impl, planned)
    ├── page/                 # typed page objects wrapping Jsoup Documents
    ├── parser/               # pure HTML → DTO functions (ported from TravBotSharp Parsers/)
    └── command/              # atomic bot ops (login, navigate, update village, …)
```

Module growth pattern — when a feature gets real code, it gets its own subpackages:

```
account/
├── api/           # AccountController + DTOs
├── domain/        # Account entity, AccountId value object
├── persistence/   # AccountRepository (JPA)
└── service/       # AccountService (transactions, domain orchestration)
```

### Frontend (`frontend/src/`)

Feature-sliced layout. UI primitives under `components/`, feature-scoped UI under `features/`, screens under `pages/`, side effects in `api/` and `hooks/`.

```
src/
├── main.tsx
├── App.tsx              # trivial — just renders <LandingPage />
├── index.css            # design tokens + component styles, light/dark mode
├── api/                 # typed HTTP client
│   ├── client.ts        # fetch wrapper, ApiException unwrapping
│   ├── health.ts        # healthApi
│   └── auth.ts          # authApi
├── types/               # shared TS types per domain
│   ├── api.ts           # ApiError, ApiException
│   ├── auth.ts          # LoginRequest, LoginResponse
│   └── health.ts        # BackendHealth
├── hooks/               # custom hooks
│   └── useBackendHealth.ts
├── components/          # reusable UI primitives
│   ├── Card.tsx
│   └── StatusBadge.tsx
├── features/            # feature-scoped UI (uses api/ + hooks/ + components/)
│   ├── auth/
│   │   └── LoginForm.tsx
│   └── health/
│       └── BackendStatus.tsx
└── pages/               # top-level screens
    └── LandingPage.tsx
```

## Getting started

### 1. Start PostgreSQL

```bash
docker compose up -d
```

Postgres on `localhost:5432` — database `travian_bot`, user/password `travian`.

### 2. Run the backend

```bash
cd backend
mvn spring-boot:run
```

API on `http://localhost:8080`. Health check: `GET /api/health`.

### 3. Run the frontend

```bash
cd frontend
npm install
npm run dev
```

Dev server on `http://localhost:5173`. Proxies `/api/*` to the backend.

## Planned features

- Attack alerts
- Automatic oasis troop calculation
- Hero health tracking and auto-leveling
- Nearby oasis rescanning
- Multi-account orchestration
