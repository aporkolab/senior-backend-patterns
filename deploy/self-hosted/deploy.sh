#!/bin/bash















set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"


RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' 

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }


check_env() {
    if [ ! -f .env ]; then
        log_error ".env file not found!"
        log_info "Copy .env.example to .env and configure it first."
        exit 1
    fi
    source .env
}


setup() {
    log_info "Starting first-time setup..."
    
    
    if [ ! -f .env ]; then
        log_warn ".env not found, copying from .env.example"
        cp .env.example .env
        log_warn "Please edit .env with your configuration, then run setup again."
        exit 0
    fi
    
    source .env
    
    
    if [ -z "$DOMAIN" ] || [ "$DOMAIN" = "patterns.yourdomain.com" ]; then
        log_error "Please set DOMAIN in .env"
        exit 1
    fi
    
    if [ -z "$DB_PASSWORD" ] || [ "$DB_PASSWORD" = "CHANGE_ME_STRONG_PASSWORD_HERE" ]; then
        log_error "Please set a strong DB_PASSWORD in .env"
        exit 1
    fi
    
    
    log_info "Creating directories..."
    mkdir -p certbot/conf certbot/www
    mkdir -p backups
    
    
    log_info "Configuring nginx for domain: $DOMAIN"
    sed -i "s/\${DOMAIN}/$DOMAIN/g" nginx/conf.d/default.conf
    
    
    log_info "Obtaining SSL certificate..."
    
    
    docker-compose -f docker-compose.prod.yml up -d nginx
    sleep 5
    
    
    docker-compose -f docker-compose.prod.yml run --rm certbot certonly \
        --webroot \
        --webroot-path=/var/www/certbot \
        --email ${LETSENCRYPT_EMAIL} \
        --agree-tos \
        --no-eff-email \
        -d ${DOMAIN}
    
    
    docker-compose -f docker-compose.prod.yml restart nginx
    
    log_success "Setup complete!"
    log_info "Next steps:"
    log_info "  1. Run: ./deploy.sh build"
    log_info "  2. Run: ./deploy.sh start"
    log_info "  3. Access: https://${DOMAIN}/order/swagger-ui.html"
}


build() {
    log_info "Building Docker images..."
    check_env
    
    
    docker-compose -f docker-compose.prod.yml build --no-cache
    
    log_success "Build complete!"
}


start() {
    log_info "Starting services..."
    check_env
    
    docker-compose -f docker-compose.prod.yml up -d
    
    log_info "Waiting for services to be healthy..."
    sleep 30
    
    status
    
    log_success "Services started!"
    log_info "Access points:"
    log_info "  - Swagger UI: https://${DOMAIN}/order/swagger-ui.html"
    log_info "  - Grafana:    https://${DOMAIN}/grafana/"
    log_info "  - Kafka UI:   https://${DOMAIN}/kafka-ui/"
}


stop() {
    log_info "Stopping services..."
    docker-compose -f docker-compose.prod.yml down
    log_success "Services stopped!"
}


restart() {
    log_info "Restarting services..."
    stop
    start
}


logs() {
    local service=${1:-}
    if [ -z "$service" ]; then
        docker-compose -f docker-compose.prod.yml logs -f --tail=100
    else
        docker-compose -f docker-compose.prod.yml logs -f --tail=100 "$service"
    fi
}


status() {
    log_info "Service Status:"
    docker-compose -f docker-compose.prod.yml ps
    
    echo ""
    log_info "Health Checks:"
    
    check_env
    
    
    services=("order-service:8081/order" "payment-service:8082/payment" "notification-service:8083/notification")
    for svc in "${services[@]}"; do
        name=$(echo $svc | cut -d: -f1)
        endpoint=$(echo $svc | cut -d: -f2-)
        
        if curl -sf "http://localhost:${endpoint}/actuator/health" > /dev/null 2>&1; then
            echo -e "  ${GREEN}✓${NC} $name"
        else
            echo -e "  ${RED}✗${NC} $name"
        fi
    done
}


backup() {
    log_info "Creating backup..."
    check_env
    
    BACKUP_DIR="backups/$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$BACKUP_DIR"
    
    
    log_info "Backing up PostgreSQL..."
    docker-compose -f docker-compose.prod.yml exec -T postgres \
        pg_dump -U ${DB_USER} ${DB_NAME} > "$BACKUP_DIR/postgres.sql"
    
    
    gzip "$BACKUP_DIR/postgres.sql"
    
    log_success "Backup created: $BACKUP_DIR"
    
    
    log_info "Cleaning old backups..."
    ls -dt backups/*/ | tail -n +8 | xargs -r rm -rf
}


update() {
    log_info "Updating deployment..."
    
    
    backup
    
    
    log_info "Pulling latest code..."
    git pull origin main
    
    
    build
    restart
    
    log_success "Update complete!"
}


demo() {
    log_info "Running demo flow..."
    check_env
    
    BASE_URL="https://${DOMAIN}"
    
    log_info "1. Creating order..."
    ORDER=$(curl -sk -X POST "${BASE_URL}/order/api/v1/orders" \
        -H "Content-Type: application/json" \
        -d '{
            "customerId": "demo-customer-001",
            "productId": "demo-product-001",
            "quantity": 2,
            "amount": 99.99
        }')
    
    echo "$ORDER" | jq .
    
    ORDER_ID=$(echo "$ORDER" | jq -r '.id')
    
    log_info "2. Waiting for payment processing..."
    sleep 3
    
    log_info "3. Checking order status..."
    curl -sk "${BASE_URL}/order/api/v1/orders/${ORDER_ID}" | jq .
    
    log_info "4. Checking notifications..."
    curl -sk "${BASE_URL}/notification/api/v1/notifications" | jq .
    
    log_info "5. Circuit Breaker state..."
    curl -sk "${BASE_URL}/payment/api/v1/payments/circuit-breaker/state" | jq .
    
    log_success "Demo complete!"
}


case "${1:-}" in
    setup)
        setup
        ;;
    build)
        build
        ;;
    start)
        start
        ;;
    stop)
        stop
        ;;
    restart)
        restart
        ;;
    logs)
        logs "$2"
        ;;
    status)
        status
        ;;
    backup)
        backup
        ;;
    update)
        update
        ;;
    demo)
        demo
        ;;
    *)
        echo "Usage: $0 {setup|build|start|stop|restart|logs|status|backup|update|demo}"
        echo ""
        echo "Commands:"
        echo "  setup    - First-time setup (SSL, directories)"
        echo "  build    - Build Docker images"
        echo "  start    - Start all services"
        echo "  stop     - Stop all services"
        echo "  restart  - Restart all services"
        echo "  logs     - View logs (optionally: logs <service>)"
        echo "  status   - Check service status"
        echo "  backup   - Backup databases"
        echo "  update   - Pull latest and redeploy"
        echo "  demo     - Run demo order flow"
        exit 1
        ;;
esac
