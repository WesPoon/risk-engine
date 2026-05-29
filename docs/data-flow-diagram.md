# Data Flow Diagram – Risk Engine

## End-to-End Event Flow

```mermaid
flowchart TD
    subgraph Inbound["Inbound Events"]
        PU["PositionUpdate\n(tradeId, underlier, portfolio,\nquantity, side, strike, maturity)"]
        SU["SpotUpdate\n(underlier, spotPrice, riskFreeRate)"]
    end

    subgraph Engine["RiskEngine (Orchestrator)"]
        OBS["onUpdate(RiskEvent)"]
        HP["handlePosition()\n• Look up or create Trade\n• Derive GICS sector + country\n• Set initial spot = strikePrice"]
        HS["handleSpot()\n• Find all Trades for underlier\n• Update spot + riskFreeRate"]
        RECALC["recalculate()"]
    end

    subgraph Cache["TradeCache\n(InMemoryTradeCache / Redis)"]
        TC_PUT["putTrade()"]
        TC_GET["getAllTrades()"]
    end

    subgraph Pricing["Pricing – BlackScholesPricer"]
        BSP["price(Trade)\n① Implied vol (Newton-Raphson)\n② d1, d2\n③ delta = N(d1)\n④ gamma = N'(d1)/(S·σ·√T)\n⑤ vega = S·N'(d1)·√T / 100\n⑥ theta = −S·N'(d1)·σ/(2√T·365)"]
        RA["RiskAttributes\n{delta, gamma, vega, theta, impliedVol}"]
    end

    subgraph Aggregation["RiskAggregator"]
        AGG["aggregate(trades, attributes)\nGroup by AggKey:\n  SECTOR  | COUNTRY | PORTFOLIO\nSum netDelta, netGamma,\n    netVega, netTheta per bucket"]
        AR["AggregatedRisk[ ]\n{dimension, bucketValue,\n netDelta, netGamma,\n netVega,  netTheta}"]
    end

    subgraph LimitCheck["Limit Check (per bucket)"]
        FIND_LIM["H2RiskRepository\nfindLimit(dimension, bucket)"]
        RL["RiskLimit\n{maxAbsNetDelta, maxAbsNetVega}"]
        BREACH{"Breached?\n|netDelta| > maxAbsNetDelta\nOR\n|netVega| > maxAbsNetVega"}
    end

    subgraph Alerting["Alert Dispatch"]
        BUILD_ALERT["Build Alert\n{dimension, bucket,\n netDelta, deltaLimit,\n netVega,  vegaLimit,\n timestamp}"]
        EMAIL["EmailSender\n→ risk-desk@example.com"]
        SYMPHONY["SymphonySender\n→ risk-alerts-room-42"]
    end

    subgraph Persistence["Persistence – H2RiskRepository"]
        SAVE["saveResult(RiskResult)\n{dimension, bucket,\n netDelta, netGamma,\n netVega, netTheta,\n limitBreached, timestamp}"]
    end

    %% ── Flow ──────────────────────────────────────────────────────────
    PU  --> OBS
    SU  --> OBS
    OBS --> HP
    OBS --> HS

    HP  --> TC_PUT
    HS  --> TC_PUT
    HP  --> RECALC
    HS  --> RECALC

    RECALC --> TC_GET
    TC_GET --> BSP
    BSP    --> RA
    RA     --> AGG
    AGG    --> AR

    AR --> FIND_LIM
    FIND_LIM --> RL
    RL --> BREACH

    BREACH -- Yes --> BUILD_ALERT
    BUILD_ALERT --> EMAIL
    BUILD_ALERT --> SYMPHONY

    BREACH -- Yes --> SAVE
    BREACH -- No  --> SAVE
```

---

## Sequence Diagram – Single PositionUpdate + SpotUpdate Cycle

```mermaid
sequenceDiagram
    autonumber
    participant Main   as RiskEngineMain
    participant Engine as RiskEngine
    participant Cache  as TradeCache
    participant Pricer as BlackScholesPricer
    participant Agg    as RiskAggregator
    participant Repo   as H2RiskRepository
    participant Alert  as AlertSender(s)

    Main->>Engine: onUpdate(RiskEvent.ofPosition(pu))
    Engine->>Cache: getTrade(tradeId)
    Cache-->>Engine: Optional.empty() [new trade]
    Engine->>Cache: putTrade(enrichedTrade)
    Engine->>Engine: recalculate()

    loop For each Trade in cache
        Engine->>Pricer: price(trade)
        Pricer-->>Engine: RiskAttributes {delta, gamma, vega, theta}
    end

    Engine->>Agg: aggregate(trades, attributesMap)
    Agg-->>Engine: List<AggregatedRisk>

    loop For each AggregatedRisk bucket
        Engine->>Repo: findLimit(dimension, bucket)
        Repo-->>Engine: Optional<RiskLimit>
        alt limit breached
            Engine->>Alert: sendAlert(alert)
        end
        Engine->>Repo: saveResult(riskResult)
    end

    Main->>Engine: onUpdate(RiskEvent.ofSpot(su))
    Engine->>Cache: getTradesByUnderlier(underlier)
    Cache-->>Engine: List<Trade>
    Engine->>Cache: putTrade(updatedTrade) [per trade]
    Engine->>Engine: recalculate()
    Note over Engine,Repo: Same price → aggregate → limit-check → persist loop
```

---

## Component Interaction Overview

```mermaid
flowchart LR
    subgraph Entrypoint
        MAIN["RiskEngineMain"]
    end

    subgraph Core
        ENGINE["RiskEngine\n(RiskObserver)"]
        AGGR["RiskAggregator"]
    end

    subgraph Infra
        CACHE["InMemoryTradeCache\nimplements TradeCache"]
        BS["BlackScholesPricer\nimplements Pricer"]
        H2["H2RiskRepository\n(H2 in-memory SQL)"]
        EM["EmailSender\nimplements AlertSender"]
        SY["SymphonySender\nimplements AlertSender"]
    end

    subgraph Model
        M1["Trade"]
        M2["RiskEvent\nPositionUpdate | SpotUpdate"]
        M3["RiskAttributes"]
        M4["AggregatedRisk"]
        M5["RiskLimit"]
        M6["RiskResult"]
        M7["Alert"]
    end

    MAIN -->|wires & drives| ENGINE
    ENGINE -->|reads/writes| CACHE
    ENGINE -->|price()| BS
    ENGINE -->|aggregate()| AGGR
    ENGINE -->|findLimit / saveResult| H2
    ENGINE -->|sendAlert| EM
    ENGINE -->|sendAlert| SY

    ENGINE -.->|consumes / produces| M1
    ENGINE -.->|consumes| M2
    ENGINE -.->|uses| M3
    ENGINE -.->|uses| M4
    ENGINE -.->|uses| M5
    ENGINE -.->|produces| M6
    ENGINE -.->|produces| M7
```
