# AWS Production Deployment Architecture: Checkout Engine

This document details the production AWS cloud infrastructure topology for **Checkout Engine**, engineered for high availability, zero inventory overselling, fault tolerance, and defense-in-depth network isolation across Multiple Availability Zones (Multi-AZ).

---

## 1. High-Level Architectural Topology

```mermaid
flowchart TD
    Client["Clients / Web & Mobile"] -->|HTTPS 443| Route53["Amazon Route 53 (DNS / Health Checks)"]
    Route53 -->|HTTPS| ALB["Application Load Balancer (Public Subnets)"]
    
    subgraph VPC["AWS Virtual Private Cloud (10.0.0.0/16)"]
        subgraph PublicSubnets["Public Subnets (AZ-1a, AZ-1b)"]
            ALB
            NAT1["NAT Gateway AZ-a"]
            NAT2["NAT Gateway AZ-b"]
            IGW["Internet Gateway"]
        end

        subgraph AppSubnets["App Private Subnets (AZ-1a, AZ-1b)"]
            ASG["EC2 / Elastic Beanstalk Auto Scaling Group<br/>(checkout-engine Spring Boot Apps)"]
        end

        subgraph DataSubnets["Data Tier Private Subnets (Isolated, AZ-1a, AZ-1b)"]
            subgraph RDS["Amazon RDS MySQL 8.0 Multi-AZ"]
                RDSPrimary["Primary MySQL (Writer - AZ-a)"]
                RDSStandby["Standby MySQL (Sync Replica - AZ-b)"]
            end
            
            subgraph ElastiCache["Amazon ElastiCache for Redis 7.0"]
                RedisPrimary["Redis Primary (AZ-a)"]
                RedisReplica["Redis Read Replica (AZ-b)"]
            end

            subgraph MSK["Amazon MSK (Apache Kafka)"]
                Broker1["Kafka Broker 1 (AZ-a)"]
                Broker2["Kafka Broker 2 (AZ-b)"]
            end
        end
    end

    ALB -->|HTTP 8080| ASG
    ASG -->|Port 3306 (JPA/Flyway)| RDSPrimary
    RDSPrimary -.->|Synchronous Replication| RDSStandby
    ASG -->|Port 6379 (Cache-Aside & Locks)| RedisPrimary
    RedisPrimary -.->|Asynchronous Replication| RedisReplica
    ASG -->|Port 9092/9094 (Outbox Relay & Consumers)| MSK
    ASG -.->|Outbound HTTPS via NAT| PaymentGateway["External Payment Gateway (Razorpay/Stripe)"]
```

---

## 2. VPC & Subnet Partitioning

The VPC is divided across 2 Availability Zones (`us-east-1a` and `us-east-1b`) into three distinct tiers:

| Subnet Tier | CIDR Blocks | Routing & Egress | Purpose |
| :--- | :--- | :--- | :--- |
| **Public Subnets** | `10.0.1.0/24` (AZ-a)<br/>`10.0.2.0/24` (AZ-b) | Routes to Internet Gateway (`0.0.0.0/0 -> igw-xxxx`) | Hosts Public Application Load Balancer (ALB) and Managed NAT Gateways. |
| **App Private Subnets** | `10.0.10.0/24` (AZ-a)<br/>`10.0.20.0/24` (AZ-b) | Routes to NAT Gateways (`0.0.0.0/0 -> nat-xxxx`) | Hosts EC2 instances running Spring Boot containers in an Auto Scaling Group. Direct internet access is blocked. |
| **Data Private Subnets** | `10.0.100.0/24` (AZ-a)<br/>`10.0.200.0/24` (AZ-b) | Isolated (No IGW, No NAT route) | Hosts RDS MySQL Multi-AZ, ElastiCache Redis Cluster, and MSK Brokers. Accessible solely by App Tier Security Group. |

---

## 3. Security Group Ingress / Egress Matrix

A strict defense-in-depth model ensures no internal datastore or microservice is publicly reachable:

```
[Internet]
    | (80/443)
    v
[sg-alb]
    | (8080)
    v
[sg-app-ec2]
    |-----------------------|-----------------------|
    | (3306)                | (6379)                | (9092/9094)
    v                       v                       v
[sg-rds-mysql]       [sg-elasticache-redis]       [sg-msk-kafka]
```

### Firewall Rules:

| Security Group | Ingress Rules | Egress Rules |
| :--- | :--- | :--- |
| **`sg-alb`** (ALB) | • `TCP 80` from `0.0.0.0/0`<br/>• `TCP 443` from `0.0.0.0/0` | • `TCP 8080` to `sg-app-ec2` |
| **`sg-app-ec2`** (App Instances) | • `TCP 8080` from `sg-alb` only | • `TCP 3306` to `sg-rds-mysql`<br/>• `TCP 6379` to `sg-elasticache-redis`<br/>• `TCP 9092/9094` to `sg-msk-kafka`<br/>• `TCP 443` to `0.0.0.0/0` (via NAT for external gateway APIs) |
| **`sg-rds-mysql`** (Database) | • `TCP 3306` from `sg-app-ec2` only *(0.0.0.0/0 completely rejected)* | • Restricted |
| **`sg-elasticache-redis`** (Cache & Locks) | • `TCP 6379` from `sg-app-ec2` only *(0.0.0.0/0 completely rejected)* | • Restricted |
| **`sg-msk-kafka`** (Event Bus) | • `TCP 9092/9094` from `sg-app-ec2` only | • Restricted |

---

## 4. Compute Tier: Elastic Beanstalk / EC2 Auto Scaling

- **Auto Scaling Policy:**
  - Minimum instances: `2` (spread evenly across `AZ-a` and `AZ-b`).
  - Maximum instances: `10`.
  - Target Tracking Scaling Policy: Target average CPU utilization at `65%` or ALB request count per target (`> 1500 req/min`).
- **Application Health Checks:**
  - ALB Target Group Health Check Path: `/actuator/health`
  - Healthy threshold: `3` successive successes, interval `15s`.
  - Unhealthy threshold: `2` failures, timeout `5s`.
  - Graceful termination: `server.shutdown: graceful` (Spring Boot waits up to 30s to finish ongoing checkout transactions before SIGKILL).

---

## 5. Persistence, Caching & Messaging Tiers

### A. Relational Data Tier: Amazon RDS Multi-AZ MySQL 8.0
- **Topology:** Primary writer in `AZ-a` with synchronous physical block replication to standby instance in `AZ-b`.
- **Automatic Failover:** DNS endpoint flips to standby within 60–120 seconds on primary failure.
- **Connection Pooling:** HikariCP with `maximumPoolSize: 20` per instance; RDS Proxy recommended for cluster scale > 10 app instances.
- **Automated Backups:** Daily automated snapshots with 35-day point-in-time recovery (PITR) enabled.

### B. In-Memory Tier: Amazon ElastiCache for Redis 7.0
- **Cluster Mode:** Enabled with Multi-AZ and automatic failover.
- **Cache-Aside & Distributed Locking:**
  - Read caching for products, carts, and save-for-later.
  - Distributed locks for multi-instance concurrency (`lock:inventory:product:{id}`) using atomic Lua scripts with lease renewal and token verification.

### C. Event Streaming Tier: Amazon MSK (Managed Streaming for Kafka)
- **Topics:** `order-events` (partitions: 6, replication: 3), `catalog-events` (partitions: 3, replication: 3).
- **Dual-Write Prevention:** The app never calls Kafka directly during order transactions. It persists events in `outbox_events` within the same database transaction. `OutboxRelayScheduler` polls pending rows with `SELECT ... FOR UPDATE SKIP LOCKED` and relays them to MSK with partition-ordered aggregate keys.
- **Consumers:**
  - `OrderNotificationConsumer`: Consumes `order-events` for confirmation and expiry notifications.
  - `PriceDropAlertConsumer`: Consumes `catalog-events` and triggers price drop notifications for saved item owners.

---

## 6. Secrets & Configuration Management (AWS Secrets Manager)

- **Managed Secret Rotation:**
  - Database master credentials, Redis authentication tokens, Kafka SASL/SCRAM or mTLS credentials, and the webhook HMAC-SHA256 secret (`CHECKOUT_WEBHOOK_SECRET`) are stored in **AWS Secrets Manager**.
  - Direct database credentials are rotated automatically every 30 days via AWS Lambda rotation handlers without application downtime.
- **IAM Role-Based Access:**
  - App EC2 instances and ECS tasks use an attached **IAM Instance Profile / Task Role** with scoped `secretsmanager:GetSecretValue` permissions restricted to `arn:aws:secretsmanager:${REGION}:${ACCOUNT}:secret:checkout-engine/*`.
  - Zero static credentials reside in Git, environment files, or Docker images. At container launch, Spring Cloud AWS Secrets Manager integration or entrypoint scripts inject secrets into the runtime environment.

---

## 7. Observability, Telemetry & Alerting

- **Application Performance Monitoring (APM):**
  - **OpenTelemetry Collector & AWS X-Ray:** Distributed trace IDs propagated across HTTP requests, database transactions, Redis calls, and Kafka event headers to provide end-to-end request tracing.
  - **Spring Boot Actuator & Prometheus:** `/actuator/prometheus` scraped every 15s by Amazon Managed Prometheus (AMP) or CloudWatch Agent.
- **Key Monitored Metrics & Alarms:**
  - **HikariCP Connection Pool:** Alert on `hikaricp.connections.pending > 5` for > 1 minute (signals DB bottleneck or slow queries).
  - **Outbox Relay Lag:** Alert on `outbox.pending.count > 100` or oldest pending event age `> 30s` (signals Kafka publishing disruption).
  - **Kafka Consumer Lag:** Alert on consumer group `checkout-engine-group` lag `> 500` messages.
  - **Inventory Contention:** Track `checkout.stock.insufficient` and `checkout.deadlocks` (maintained at 0).
  - **JVM Health:** Heap usage, GC pause durations (G1GC), and thread states.
- **Centralized Logging:**
  - Structured JSON logs streamed via CloudWatch Logs agent / FireLens to **Amazon CloudWatch Logs** with a 30-day retention and OpenSearch dashboard indexing for security auditing.

---

## 8. Disaster Recovery & Availability SLA

| Scenario | Impact & Automatic Recovery Mechanism | RTO | RPO |
| :--- | :--- | :--- | :--- |
| **Single EC2/Container Failure** | ALB removes instance from target group; ASG / ECS launches replacement instance in healthy AZ. | < 1 minute | 0 |
| **Availability Zone Outage** | Remaining AZ handles 100% of traffic; RDS and ElastiCache fail over automatically if primary was in impacted AZ. | < 2 minutes | 0 |
| **Kafka Broker Outage** | Checkouts continue normally (`201 Created`); events queue up safely in MySQL `outbox_events` table and flush when MSK recovers. | 0 (No customer impact) | 0 |
| **Database Failure** | RDS Multi-AZ flips to synchronous standby. Active checkouts roll back cleanly and retry safely via idempotency keys. | < 2 minutes | 0 (Synchronous replication) |
