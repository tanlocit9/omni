# Omni Roadmap

Consolidated plan for (1) building analysis/ranking/news capabilities based on specifications from the legacy FastAPI backend into Omni, and (2) refactoring `analyzer` and `ingestor` around a MinIO-first data architecture with a shared Python package.

**⚠️ IMPORTANT NOTE**: Legacy services (`indicator_service`, `pattern_service`, `analysis_service`, etc.) do **not exist** in the current Omni codebase. They must be **built from scratch**, not ported. Similarly, analysis and ranking endpoints are net-new and do not currently exist.

---

## 0. Architecture Decision (source of truth for all phases below)

**Current state** (TODAY):

- `analyzer` reads `stock_prices` directly from PostgreSQL
- `POST /v1/stocks/sync` writes price data to PostgreSQL
- No shared package exists; each service manages its own connections

**Target state** (AFTER Phase 0):

- `analyzer` reads raw price history from MinIO (`EOD/{symbol}.parquet`)
- `analyzer` writes computed results to PostgreSQL only
- Shared Python package (`libs/omni-common`) centralizes Kafka, MinIO, Parquet I/O logic

\*\*New data flow (TARGET):

- **Raw / time-series data** (OHLCV history, indicator inputs, news content, anything bulk or symbol-keyed) → **MinIO** (Parquet), not Postgres.
- **Computed results** (analysis scores, ranking outputs, ratings, alerts, job/log metadata) → **Postgres**, via Flyway-managed schema.
- **`analyzer`** stops reading `stock_prices` from Postgres directly. It reads raw price/history data from MinIO (same `EOD/{symbol}.parquet` layout the ingestor already writes), computes on it, and persists only the _output_ rows to Postgres.
- **`ingestor`** keeps owning the MinIO write path for raw sync data; no change to its core responsibility, but it now shares its MinIO client/connection code with `analyzer` instead of each service rolling its own.
- **Shared Python package** (new, e.g. `libs/omni-common` or `packages/omni-shared` inside the Nx workspace) holds:
- MinIO connection/client setup (wrapping the `minio` library config: endpoint, bucket, credentials, retry)
- Kafka consumer/producer scaffolding (`aiokafka` setup, the event-router/dispatch pattern currently duplicated conceptually between `ingestor` and any future consumer)
- Common Parquet read/write helpers (schema, merge/dedupe-by-date logic, path conventions like `EOD/{symbol}.parquet`)
- Shared Pydantic/dataclass models for price rows, sync job payloads, status payloads
- Both `analyzer` and `ingestor` depend on this package instead of duplicating connection/config code.

This decision gates every phase below — nothing in Phase 1+ should write raw data to Postgres, and nothing should read raw data from Postgres.

---

## Phase 0 — Foundation

**CRITICAL PREREQUISITE — Must complete before Phase 1+**

### 0.1 Shared Package

- [ ] **Design and scaffold the shared package** (`libs/omni-common` or similar) as a proper Nx Python project (`uv`-managed, buildable/publishable within the monorepo).
- [ ] `minio_client.py` — connection factory reading `S3_ENDPOINT_URL`, bucket, credentials from env (matches existing 12-factor convention).
- [ ] `kafka.py` — shared consumer/producer setup, reusable dispatch/router pattern (extract from `ingestor/main.py`).
- [ ] `parquet_io.py` — read/write/merge helpers for `EOD/{symbol}.parquet`, dedupe-by-`date` logic.
- [ ] `models.py` — shared Pydantic models for price rows, `topic-sync-stock-prices`, `stock-sync-status` payloads.

### 0.2 Refactor Services to Use Shared Package

- [ ] **Refactor `ingestor`** to import from shared package instead of inline Kafka/MinIO/Parquet code. Verify no behavior change (`nx test ingestor`).
- [ ] **Refactor `analyzer`**: move to MinIO as primary data source.
- [ ] Remove direct Postgres reads of price history.
- [ ] Add MinIO read path via shared package. `GET /v1/stocks/{symbol}` now reads from `EOD/{symbol}.parquet` instead of `stock_prices`.
- [ ] `POST /v1/stocks/sync`: decide whether to keep synchronous VNDirect→MinIO write, or publish `topic-sync-stock-prices` and let ingestor handle it (open decision #1 below).

### 0.3 Database Schema

- [ ] **Audit Flyway migrations V4–V12** (currently in `database/refs/`). Rewrite as _results-only_ tables now that raw price data lives in MinIO:
- Drop any references to `stock_prices` as a raw-data table.
- Create `analysis_results` (technical analysis, patterns, scores).
- Create `ranking_results` (breakout, momentum, volume, pullback, relative-strength scores).
- Keep `symbol` metadata table (V3, already applied).
- Add portfolio/watchlist/alert tables as needed for Phase 5.

### 0.4 Documentation & Config

- [ ] **Standardize `ticker` vs `symbol` naming** across the shared package's models before anything else builds on top of them.
- [ ] **Fix AGENTS.md and README.md** — correct topic names (`topic-sync-stock-prices`, not `stock-sync`) and clarify analyzer's role (MinIO reader, not Postgres reader).
- [ ] **Secrets/config audit** for anything building on legacy services — no hardcoded API keys or credentials land in Omni.

## Phase 1 — Core analysis engine → `apps/analyzer`

**Prerequisite**: Phase 0 must be complete (shared package, analyzer refactored to MinIO reads).

- [ ] **Build** `indicator_service.py` (SMA, EMA, ATR, MACD, Bollinger, RSI, ADX) as a pure computation module — input is a DataFrame from the shared Parquet reader, no DB coupling. Easy to unit test in isolation.
- [ ] **Build** `pattern_service.py` (candlestick pattern detection) the same way — pure function over a price DataFrame.
- [ ] **Build** `analysis_service.py` to orchestrate indicators + patterns:
- Reads raw history from MinIO via shared `parquet_io`.
- Writes the computed score/rating/setup-type result to Postgres (`analysis_results`).
- [ ] **New endpoints**: `GET /v1/analysis/technical/{symbol}`, `GET /v1/analysis/patterns/{symbol}` — initially compute on-demand from MinIO (decide on caching strategy per open decision #3).
- [ ] **Tests**: `nx test analyzer` for all computation modules — this closes the legacy backend's "no automated tests" gap.

## Phase 2 — Market context

**Prerequisite**: Phase 1 complete.

- [ ] **Build** `market_service.py` (market regime, VNINDEX trend, relative strength, sector strength) as a MinIO-reading module — reads index/sector Parquet files same as individual stocks.
- [ ] **Ingestor config**: Ensure ingestor syncs index/sector symbols (VNIndex, sector ETF symbols) via `topic-sync-stock-prices` alongside individual stocks. Add config list of "always-tracked" symbols if needed.
- [ ] **Integration**: Wire into Phase 1's analysis output as an additional context layer, persisted alongside the technical result in `analysis_results`.

## Phase 3 — Ranking & scoring

**Prerequisite**: Phase 2 complete.

- [ ] **Build** `ranking_service.py` (breakout, momentum, volume, pullback, relative-strength). Scans many symbols' Parquet files from MinIO — batch the reads, never synchronously per-request if the symbol universe is large.
- [ ] **Build** `scoring_service.py` to merge technical (Phase 1) + market context (Phase 2) signals into a final action score, written to `ranking_results` in Postgres.
- [ ] **New endpoints**: `GET /v1/ranking/{breakout|momentum|volume|pullback|...}` — serve from Postgres (`ranking_results`), never computed live. Ranking is inherently a batch operation.
- [ ] **Scheduled job pattern**: Run ranking as a Kafka-published job (via shared package's consumer/producer, reusing the existing `sync_job`/`sync_job_log` pattern) rather than a bare loop. This reuses Omni's existing job/log infrastructure instead of introducing unmanaged loops.

## Phase 4 — News & AI layer

**Prerequisite**: Phase 3 complete. Phase 0 secrets audit already completed.

- [ ] **Build** `news_service.py` (CafeF/SSI crawlers). Storage: raw crawled content → MinIO (as text/JSON objects, consistent with "raw data lives in MinIO" rule); sentiment/summary → Postgres.
- [ ] **Build** `nlp_service.py` (LLM sentiment analysis). Read raw news from MinIO, compute sentiment via configured LLM endpoint, persist to Postgres.
- [ ] **Extend scoring**: Merge Phase 4 news/AI signal into Phase 3 action score.
- [ ] **Resilience**: Add retry/backoff for LLM and news source calls (net-new, not in legacy backend).

## Phase 5 — Notifications & product features

**Prerequisite**: Phase 4 complete. Portfolio/watchlist tables must exist in Postgres (from Phase 0 Flyway rewrites).

- [ ] **Build** `telegram_service.py` as an alert dispatcher in `apps/core` (Java), since portfolio/screener alerts are `core`'s domain per the architecture doc. `analyzer` + ranking produce signals; `core` decides when/how to notify.
- [ ] **Integration**: Wire ranking/analysis results (from Postgres) into `core`'s alert workflows for user notifications.

---

## Phase 6 — Financial report intelligence (competitive response, new domain)

Prompted by competitor analysis (Stockbase-class products: AI report summaries, valuation comparison, RAG chatbot over annual reports). This is a **new domain**, not an extension of the price/technical pipeline — it needs its own ingestion path and should not block Phases 1–5.

- [ ] **Scope check first**: confirm this is actually on the roadmap before building — it's a significant new surface (document storage, embeddings, LLM orchestration), not a quick add-on.
- [ ] **Raw document ingestion**: annual reports / financial statements (PDF) → MinIO, following the same "raw data lives in MinIO" convention as price data. New bucket/prefix convention, e.g. `reports/{symbol}/{year}.pdf`.
- [ ] **Financial statement ETL**: structured extraction (revenue, margins, cashflow, debt, ownership) from filings → Postgres, as a computed-result table alongside `analysis_results`/`ranking_results`.
- [ ] **AI summary layer**: LLM-based summarization answering _what changed and why_ (revenue drivers, margin shifts, management commentary, anomaly flags) — not just a plain summary. Depends on the Phase 4 secrets-audit discipline (LLM endpoint config) already established.
- [ ] **RAG chatbot over reports**: embeddings + vector search over ingested PDFs, with page-level citation and a confidence indicator on answers — explicitly better than a bare summary, matching the gap noted in the competitor's chatbot.
- [ ] **Valuation comparison**: sector P/E, P/B, P/S comparison — reuses the `symbol`/sector metadata already in Postgres.
- [ ] **Valuation tools (prefer over ML forecasting)**: DCF, reverse DCF, scenario analysis, sensitivity analysis, Monte Carlo — deliberately chosen over pure ML revenue/profit forecasting, which is flagged as unreliable and trust-eroding if wrong.
- [ ] **Extended visualization set**: revenue waterfall, margin trend, cashflow trend, debt structure, share dilution, insider ownership, DuPont ROE decomposition.
- [ ] **Explicitly deprioritized**: ML-based automatic valuation/forecasting (revenue/profit prediction via Prophet/XGBoost/LSTM-style models). Revisit only after the scenario/DCF tooling above is solid and if there's clear demand — not before.

## Phase 7 — Developer Experience & Web Frontend

Inspired by the Truyen reference architecture — simplify development workflow while maintaining Omni's sophisticated microservices design. This phase can proceed in parallel with Phases 0–6 since it's infrastructure-focused.

**Prerequisite**: None (can start immediately).

### 7.1 Docker Compose Integration

- [ ] **Create Dockerfiles** for each application:
  - `apps/core/Dockerfile` — Multi-stage build: Gradle build → slim JRE runtime
  - `apps/analyzer/Dockerfile` — Python 3.14 + uv, FastAPI with uvicorn
  - `apps/ingestor/Dockerfile` — Python 3.14 + uv, async worker
  - `apps/web/Dockerfile` — Node.js + Nuxt.js (Phase 7.2)
  
- [ ] **Enhance `docker-compose.yaml`** to include all services:
  ```yaml
  services:
    # Existing: postgres, kafka, minio, pgadmin
    
    platform:
      build: ./apps/core
      depends_on: [postgres, kafka, minio]
      environment:
        - SPRING_PROFILES_ACTIVE=dev
        - DATABASE_URL=jdbc:postgresql://postgres:5432/omni
      ports: ["8080:8080"]
      volumes: ["./apps/core:/app"]
    
    analyzer:
      build: ./apps/analyzer
      depends_on: [postgres]
      ports: ["8000:8000"]
      volumes: ["./apps/analyzer:/app"]
    
    ingestor:
      build: ./apps/ingestor
      depends_on: [kafka, minio]
      volumes: ["./apps/ingestor:/app"]
    
    web:
      build: ./apps/web
      depends_on: [platform]
      ports: ["3000:3000"]
      volumes: ["./apps/web:/app"]
  ```

- [ ] **Benefits**:
  - Single command: `docker compose up` starts entire system (like Truyen)
  - Java platform still controls workflow via Kafka (no scheduler service needed)
  - Volume mounts enable hot-reload during development
  - Production-ready: same compose file works on Oracle Always Free VM

### 7.2 Web Frontend (Nuxt.js)

- [ ] **Scaffold `apps/web/`** as a new Nx project:
  ```
  apps/web/                    # Nuxt.js frontend
    ├── pages/
    │   ├── index.vue          # Dashboard/stock screener
    │   ├── stocks/
    │   │   └── [symbol].vue   # Stock detail page (charts, financials)
    │   └── admin/
    │       └── sync.vue       # Trigger sync jobs (calls Java platform API)
    ├── components/
    │   ├── StockChart.vue     # TradingView-style price charts
    │   ├── PriceTable.vue     # Historical prices table
    │   ├── AnalysisCard.vue   # Technical analysis display (Phase 1)
    │   └── SyncStatus.vue     # Real-time job status from Kafka
    ├── composables/
    │   ├── useStockApi.ts     # API client for Java platform (port 8080)
    │   └── useAnalyzerApi.ts  # API client for analyzer (port 8000)
    └── nuxt.config.ts
  ```

- [ ] **Integration points**:
  - Calls Java platform REST API (`http://platform:8080`) to trigger sync jobs, manage watchlists
  - Queries analyzer API (`http://analyzer:8000`) for stock data and analysis results
  - Real-time updates via SSE/WebSocket from platform for job status (optional future enhancement)

- [ ] **Key pages** (incremental rollout):
  1. **Dashboard** — Market overview, top movers, recent syncs
  2. **Stock detail** — Price chart, technical analysis (Phase 1), patterns (Phase 1), fundamentals (Phase 6)
  3. **Screener** — Filter stocks by ranking scores (Phase 3)
  4. **Admin/Sync** — Manual sync trigger, job history, system health

### 7.3 Development Workflow

- [ ] **Add Nx targets** to `project.json` (root):
  ```json
  {
    "docker-dev": {
      "executor": "nx:run-commands",
      "options": {
        "command": "docker compose up --build"
      }
    },
    "docker-down": {
      "executor": "nx:run-commands",
      "options": {
        "command": "docker compose down"
      }
    },
    "docker-logs": {
      "executor": "nx:run-commands",
      "options": {
        "command": "docker compose logs -f {args.service}"
      }
    }
  }
  ```

- [ ] **Update documentation**:
  - `README.md` — Add "Quick Start with Docker" section
  - `AGENTS.md` — Document new Docker workflow and web app structure
  - Keep existing `nx run omni:dev` for local development without Docker

### 7.4 Production Deployment

- [ ] **Oracle Always Free VM setup** (current default target):
  - Same `docker-compose.yaml` runs on ARM VM (4 cores, 24GB RAM)
  - No code changes needed — just env var updates for production endpoints
  
- [ ] **Cloud portability** (future):
  - AWS: ECS for containers, MSK for Kafka, S3 for storage
  - GCP: Cloud Run, Pub/Sub, GCS
  - Cloudflare: R2 for storage (same S3 API)
  - Only env vars change — S3 endpoint, Kafka bootstrap servers, etc.

---

## Competitor Watchlist

Maintain a running list, reviewed whenever a new competing product surfaces. For each entry, capture:

1. What do they do well?
2. What are they missing?
3. Should Omni imitate this, or deliberately differentiate?

**Tracked so far:**

- **Stockbase** — AI report summaries, sector valuation comparison, beta ML-based auto-valuation (self-disclosed as unstable), RAG chatbot over annual reports. Product-focused, not platform-focused; no visible ETL/data-platform layer. See Phase 6 above for Omni's response.
- **Truyen** (reference architecture) — Unified Docker Compose, Nuxt.js frontend, simple polling worker. Good developer experience, but lacks event-driven architecture and data lake design. Omni adopts the Docker/frontend patterns while keeping Kafka + MinIO sophistication. See Phase 7 above.
- FireAnt, Simplize, CafeF, Vietstock, InvestingPro, FinChat — not yet analyzed; add entries as reviewed.

---

## Explicitly not ported

- The legacy `worker.py` loop — fully superseded by the ingestor's Kafka + shared-package pipeline.
- Truyen's polling worker pattern — replaced by Kafka event-driven architecture (Java platform controls flow).
- `backtest_service.py` — rebuild on top of the new indicator/pattern primitives once Phase 1 is solid, rather than porting the old standalone version.

---

## Open decisions to confirm before/during Phase 0

1. **`POST /v1/stocks/sync` behavior**: Stay synchronous (direct MinIO write) or move to publish-and-return (via `topic-sync-stock-prices` to ingestor)?
2. **Shared package location & versioning**: Use `libs/omni-common` or `packages/omni-shared`? Version independently or path-reference in monorepo?
3. **Analysis/ranking endpoint strategy**: Compute on-demand from MinIO (low latency limit, fresh data) or serve cached Postgres result (fast, may go stale)? Decide per-endpoint.
4. **MinIO data retention policy**: If Parquet schemas evolve (e.g., new indicator added later), do old files need backfill or migration tooling, or is a clean slate acceptable?
5. **Phase 6 scope**: Is "Financial report intelligence" (DCF tools, embeddings, chatbot) actually on the roadmap, or is it exploratory? Confirm before committing resources.
6. **Phase 7 frontend framework**: Nuxt.js (SSR, Vue ecosystem) or Next.js (RSC, React ecosystem)? Both support the required features; choose based on team preference.
