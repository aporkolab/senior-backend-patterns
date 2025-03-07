# Self-Hosted Production Deployment

Complete guide to deploy Senior Backend Patterns on your own server with SSL, monitoring, and production hardening.

## Prerequisites

- **Server**: Ubuntu 22.04 LTS (minimum 4GB RAM, 2 vCPU)
- **Domain**: DNS A record pointing to your server IP
- **Ports**: 80, 443 open in firewall
- **Build tools**: Java 21+, Maven 3.9+ (for building JAR files)
- **Docker**: Docker 24+ and Docker Compose v2

## Quick Start (5 minutes)

```bash
# 1. Clone repository
git clone https://github.com/aporkolab/senior-backend-patterns.git
cd senior-backend-patterns

# 2. Build JAR files (requires Java 21 and Maven)
mvn clean package -DskipTests -pl demo-app/order-service,demo-app/payment-service,demo-app/notification-service -am

# 3. Configure deployment
cd deploy/self-hosted
cp .env.example .env
nano .env  # Set DOMAIN, passwords, email

# 4. Deploy
chmod +x deploy.sh
./deploy.sh setup
./deploy.sh build
./deploy.sh start

# 5. Test
./deploy.sh demo
```

## Access Points

After deployment:

| Service | URL |
|---------|-----|
| **Swagger UI** | `https://your-domain.com/order/swagger-ui.html` |
| **Order API** | `https://your-domain.com/order/api/v1/orders` |
| **Payment API** | `https://your-domain.com/payment/api/v1/payments` |
| **Notification API** | `https://your-domain.com/notification/api/v1/notifications` |
| **Grafana** | `https://your-domain.com/grafana/` |
| **Kafka UI** | `https://your-domain.com/kafka-ui/` |
| **Prometheus** | `https://your-domain.com/prometheus/` |

## Architecture

```
                          ┌─────────────────────────────────────────┐
                          │              NGINX                       │
                          │         (SSL Termination)                │
Internet ──── :443 ──────►│    Rate Limiting, Security Headers      │
                          └──────────────┬──────────────────────────┘
                                         │
            ┌────────────────────────────┼────────────────────────────┐
            │                            │                            │
            ▼                            ▼                            ▼
    ┌───────────────┐           ┌───────────────┐           ┌───────────────┐
    │ Order Service │           │Payment Service│           │ Notification  │
    │    :8081      │           │    :8082      │           │   Service     │
    │               │           │               │           │    :8083      │
    │ • Outbox      │           │ • Circuit     │           │ • DLQ         │
    │ • Rate Limit  │           │   Breaker     │           │ • Retry       │
    └───────┬───────┘           └───────┬───────┘           └───────┬───────┘
            │                           │                           │
            └───────────────────────────┼───────────────────────────┘
                                        │
                               ┌────────▼────────┐
                               │     KAFKA       │
                               │  order-events   │
                               │ payment-events  │
                               │ *.dlq topics    │
                               └────────┬────────┘
                                        │
            ┌───────────────────────────┴───────────────────────────┐
            │                                                       │
    ┌───────▼───────┐                                      ┌───────▼───────┐
    │  PostgreSQL   │                                      │     Redis     │
    │   (Orders,    │                                      │ (Rate Limit   │
    │    Outbox)    │                                      │   State)      │
    └───────────────┘                                      └───────────────┘
```

## Configuration

### Required Environment Variables

```bash
# .env file
DOMAIN=patterns.yourdomain.com
DB_PASSWORD=super_secret_password_123
REDIS_PASSWORD=another_secret_456
GRAFANA_PASSWORD=grafana_secret_789
KAFKA_UI_PASSWORD=kafka_secret_012
LETSENCRYPT_EMAIL=your@email.com
```

### Resource Requirements

| Service | Memory | CPU |
|---------|--------|-----|
| Order Service | 512-768 MB | 0.5 |
| Payment Service | 512-768 MB | 0.5 |
| Notification Service | 512-768 MB | 0.5 |
| PostgreSQL | 512 MB | 0.5 |
| Kafka | 1 GB | 1.0 |
| Zookeeper | 512 MB | 0.25 |
| Prometheus | 512 MB | 0.25 |
| Grafana | 256 MB | 0.25 |
| Nginx | 128 MB | 0.1 |
| **Total** | **~5 GB** | **~4 vCPU** |

## Operations

### Daily Operations

```bash
# Check status
./deploy.sh status

# View logs
./deploy.sh logs                    # All services
./deploy.sh logs order-service      # Specific service

# Restart after config change
./deploy.sh restart
```

### Backup & Restore

```bash
# Create backup
./deploy.sh backup

# Backups stored in: ./backups/YYYYMMDD_HHMMSS/
# Automatic cleanup: keeps last 7 backups

# Manual restore (gunzip first, then restore)
gunzip -c backups/20240115_120000/postgres.sql.gz | \
  docker-compose -f docker-compose.prod.yml exec -T postgres \
  psql -U patterns patternsdb
```

### Updates

```bash
# Pull latest and redeploy
./deploy.sh update

# Manual update
git pull origin main
./deploy.sh build
./deploy.sh restart
```

### SSL Certificate Renewal

Certbot runs automatically every 12 hours. Manual renewal:

```bash
docker-compose -f docker-compose.prod.yml run --rm certbot renew
docker-compose -f docker-compose.prod.yml restart nginx
```

## Security Hardening

### Already Implemented

- ✅ HTTPS only (HTTP redirects to HTTPS)
- ✅ TLS 1.2/1.3 only
- ✅ Security headers (HSTS, X-Frame-Options, X-Content-Type-Options)
- ✅ Rate limiting at nginx level
- ✅ Non-root container users
- ✅ Correlation ID tracking
- ✅ Health checks on all services

### Recommended Additional Steps

```bash
# 1. Firewall (UFW)
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw allow 22/tcp
sudo ufw enable

# 2. Fail2ban for SSH
sudo apt install fail2ban
sudo systemctl enable fail2ban

# 3. Automatic security updates
sudo apt install unattended-upgrades
sudo dpkg-reconfigure -plow unattended-upgrades
```

### Secrets Management

For production, consider:
- HashiCorp Vault
- Docker Secrets
- AWS Secrets Manager / Azure Key Vault

## Monitoring

### Grafana Dashboard

Pre-configured dashboard includes:

1. **Circuit Breaker Panel**
   - Current state (CLOSED/OPEN/HALF_OPEN)
   - Call success/failure rates
   - State transition history

2. **Rate Limiter Panel**
   - Remaining permits
   - Rejected requests

3. **Outbox Panel**
   - Pending events count
   - Publishing throughput

4. **DLQ Panel**
   - Queue depth
   - Failure types breakdown

### Alerts (Prometheus)

Add to `prometheus.yml`:

```yaml
alerting:
  alertmanagers:
    - static_configs:
        - targets: ['alertmanager:9093']

rule_files:
  - "alerts.yml"
```

Example alerts (`alerts.yml`):

```yaml
groups:
  - name: patterns
    rules:
      - alert: CircuitBreakerOpen
        expr: circuit_breaker_state == 1
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "Circuit breaker is OPEN"
          
      - alert: HighDLQDepth
        expr: dlq_depth > 100
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "DLQ has {{ $value }} messages"
```

## Troubleshooting

### Service won't start

```bash
# Check logs
./deploy.sh logs order-service

# Common issues:
# - Database not ready: wait for postgres healthcheck
# - Kafka not ready: wait for kafka healthcheck
# - Port conflict: check with `netstat -tlnp`
```

### SSL Certificate Issues

```bash
# Test certificate
openssl s_client -connect your-domain.com:443 -servername your-domain.com

# Force renewal
docker-compose -f docker-compose.prod.yml run --rm certbot certonly \
  --force-renewal \
  --webroot \
  --webroot-path=/var/www/certbot \
  -d your-domain.com
```

### High Memory Usage

```bash
# Check container memory
docker stats

# Reduce JVM heap in .env:
# JAVA_OPTS=-Xms128m -Xmx256m
```

### Kafka Issues

```bash
# Check Kafka logs
./deploy.sh logs kafka

# List topics
docker-compose -f docker-compose.prod.yml exec kafka \
  kafka-topics --list --bootstrap-server localhost:29092

# Check consumer lag
docker-compose -f docker-compose.prod.yml exec kafka \
  kafka-consumer-groups --bootstrap-server localhost:29092 \
  --describe --all-groups
```

## Demo Flow

```bash
# Run automated demo
./deploy.sh demo

# Manual test
curl -X POST https://your-domain.com/order/api/v1/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-001",
    "productId": "prod-001", 
    "quantity": 1,
    "amount": 49.99
  }'
```

## Support

- **Issues**: https://github.com/aporkolab/senior-backend-patterns/issues
- **Documentation**: https://github.com/aporkolab/senior-backend-patterns/wiki

---

**Author**: Ádám Porkoláb  
**License**: MIT
