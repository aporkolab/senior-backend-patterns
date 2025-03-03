#!/usr/bin/env bash












set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"


RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[OK]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }


docker_compose() {
    if docker compose version &> /dev/null; then
        docker compose "$@"
    else
        docker-compose "$@"
    fi
}


CONTAINER_PREFIX="patterns-"


DEFAULT_POSTGRES_PORT=15432
DEFAULT_ZOOKEEPER_PORT=12181
DEFAULT_KAFKA_PORT=19092
DEFAULT_KAFKA_INTERNAL_PORT=19093
DEFAULT_PROMETHEUS_PORT=19090
DEFAULT_GRAFANA_PORT=13000
DEFAULT_KAFKA_UI_PORT=18090
DEFAULT_ORDER_SERVICE_PORT=18081
DEFAULT_PAYMENT_SERVICE_PORT=18082
DEFAULT_NOTIFICATION_SERVICE_PORT=18083


is_port_available() {
    ! lsof -i ":$1" >/dev/null 2>&1
}


find_available_port() {
    local port=$1
    local max=100
    local i=0
    while [ $i -lt $max ]; do
        if is_port_available $port; then
            echo $port
            return 0
        fi
        port=$((port + 1))
        i=$((i + 1))
    done
    echo "0"
    return 1
}


full_cleanup() {
    log_info "Cleaning up..."

    local containers=$(docker ps -aq --filter "name=${CONTAINER_PREFIX}" 2>/dev/null || true)
    if [ -n "$containers" ]; then
        docker stop $containers 2>/dev/null || true
        docker rm -f $containers 2>/dev/null || true
    fi

    docker_compose down --remove-orphans --volumes 2>/dev/null || true
    docker network rm patterns-network 2>/dev/null || true

    log_success "Cleanup complete"
}


load_env() {
    if [ -f .env ]; then
        log_info "Loading .env"
        set -a
        source .env
        set +a
    fi
}


resolve_ports() {
    log_info "Checking ports..."
    echo ""

    
    local pg_port=${POSTGRES_PORT:-$DEFAULT_POSTGRES_PORT}
    local zk_port=${ZOOKEEPER_PORT:-$DEFAULT_ZOOKEEPER_PORT}
    local kafka_port=${KAFKA_PORT:-$DEFAULT_KAFKA_PORT}
    local kafka_int_port=${KAFKA_INTERNAL_PORT:-$DEFAULT_KAFKA_INTERNAL_PORT}
    local prom_port=${PROMETHEUS_PORT:-$DEFAULT_PROMETHEUS_PORT}
    local graf_port=${GRAFANA_PORT:-$DEFAULT_GRAFANA_PORT}
    local kafkaui_port=${KAFKA_UI_PORT:-$DEFAULT_KAFKA_UI_PORT}
    local order_port=${ORDER_SERVICE_PORT:-$DEFAULT_ORDER_SERVICE_PORT}
    local payment_port=${PAYMENT_SERVICE_PORT:-$DEFAULT_PAYMENT_SERVICE_PORT}
    local notif_port=${NOTIFICATION_SERVICE_PORT:-$DEFAULT_NOTIFICATION_SERVICE_PORT}

    
    if is_port_available $pg_port; then
        POSTGRES_PORT=$pg_port
        echo -e "  ${GREEN}✓${NC} PostgreSQL          : $pg_port"
    else
        POSTGRES_PORT=$(find_available_port $pg_port)
        echo -e "  ${YELLOW}⚡${NC} PostgreSQL          : $pg_port → $POSTGRES_PORT"
    fi

    if is_port_available $zk_port; then
        ZOOKEEPER_PORT=$zk_port
        echo -e "  ${GREEN}✓${NC} Zookeeper           : $zk_port"
    else
        ZOOKEEPER_PORT=$(find_available_port $zk_port)
        echo -e "  ${YELLOW}⚡${NC} Zookeeper           : $zk_port → $ZOOKEEPER_PORT"
    fi

    if is_port_available $kafka_port; then
        KAFKA_PORT=$kafka_port
        echo -e "  ${GREEN}✓${NC} Kafka               : $kafka_port"
    else
        KAFKA_PORT=$(find_available_port $kafka_port)
        echo -e "  ${YELLOW}⚡${NC} Kafka               : $kafka_port → $KAFKA_PORT"
    fi

    if is_port_available $kafka_int_port; then
        KAFKA_INTERNAL_PORT=$kafka_int_port
        echo -e "  ${GREEN}✓${NC} Kafka Internal      : $kafka_int_port"
    else
        KAFKA_INTERNAL_PORT=$(find_available_port $kafka_int_port)
        echo -e "  ${YELLOW}⚡${NC} Kafka Internal      : $kafka_int_port → $KAFKA_INTERNAL_PORT"
    fi

    if is_port_available $prom_port; then
        PROMETHEUS_PORT=$prom_port
        echo -e "  ${GREEN}✓${NC} Prometheus          : $prom_port"
    else
        PROMETHEUS_PORT=$(find_available_port $prom_port)
        echo -e "  ${YELLOW}⚡${NC} Prometheus          : $prom_port → $PROMETHEUS_PORT"
    fi

    if is_port_available $graf_port; then
        GRAFANA_PORT=$graf_port
        echo -e "  ${GREEN}✓${NC} Grafana             : $graf_port"
    else
        GRAFANA_PORT=$(find_available_port $graf_port)
        echo -e "  ${YELLOW}⚡${NC} Grafana             : $graf_port → $GRAFANA_PORT"
    fi

    if is_port_available $kafkaui_port; then
        KAFKA_UI_PORT=$kafkaui_port
        echo -e "  ${GREEN}✓${NC} Kafka UI            : $kafkaui_port"
    else
        KAFKA_UI_PORT=$(find_available_port $kafkaui_port)
        echo -e "  ${YELLOW}⚡${NC} Kafka UI            : $kafkaui_port → $KAFKA_UI_PORT"
    fi

    if is_port_available $order_port; then
        ORDER_SERVICE_PORT=$order_port
        echo -e "  ${GREEN}✓${NC} Order Service       : $order_port"
    else
        ORDER_SERVICE_PORT=$(find_available_port $order_port)
        echo -e "  ${YELLOW}⚡${NC} Order Service       : $order_port → $ORDER_SERVICE_PORT"
    fi

    if is_port_available $payment_port; then
        PAYMENT_SERVICE_PORT=$payment_port
        echo -e "  ${GREEN}✓${NC} Payment Service     : $payment_port"
    else
        PAYMENT_SERVICE_PORT=$(find_available_port $payment_port)
        echo -e "  ${YELLOW}⚡${NC} Payment Service     : $payment_port → $PAYMENT_SERVICE_PORT"
    fi

    if is_port_available $notif_port; then
        NOTIFICATION_SERVICE_PORT=$notif_port
        echo -e "  ${GREEN}✓${NC} Notification Service: $notif_port"
    else
        NOTIFICATION_SERVICE_PORT=$(find_available_port $notif_port)
        echo -e "  ${YELLOW}⚡${NC} Notification Service: $notif_port → $NOTIFICATION_SERVICE_PORT"
    fi

    
    export POSTGRES_PORT ZOOKEEPER_PORT KAFKA_PORT KAFKA_INTERNAL_PORT
    export PROMETHEUS_PORT GRAFANA_PORT KAFKA_UI_PORT
    export ORDER_SERVICE_PORT PAYMENT_SERVICE_PORT NOTIFICATION_SERVICE_PORT

    echo ""
}


show_status() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Container Status"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    docker ps --filter "name=${CONTAINER_PREFIX}" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" 2>/dev/null || echo "No containers"
    echo ""
}


show_urls() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo -e "${GREEN}  Ready!${NC}"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""
    echo -e "${CYAN}Services:${NC}"
    echo "  Order:        http://localhost:${ORDER_SERVICE_PORT}/swagger-ui.html"
    echo "  Payment:      http://localhost:${PAYMENT_SERVICE_PORT}/swagger-ui.html"
    echo "  Notification: http://localhost:${NOTIFICATION_SERVICE_PORT}/swagger-ui.html"
    echo ""
    echo -e "${CYAN}Monitoring:${NC}"
    echo "  Grafana:      http://localhost:${GRAFANA_PORT} (admin/admin)"
    echo "  Prometheus:   http://localhost:${PROMETHEUS_PORT}"
    echo "  Kafka UI:     http://localhost:${KAFKA_UI_PORT}"
    echo ""
    echo -e "${CYAN}Database:${NC}"
    echo "  PostgreSQL:   localhost:${POSTGRES_PORT} (postgres/postgres)"
    echo "  Kafka:        localhost:${KAFKA_PORT}"
    echo ""
    echo -e "${CYAN}Commands:${NC}"
    echo "  Logs:         docker compose logs -f"
    echo "  Stop:         ./start.sh --stop"
    echo "  Status:       ./start.sh --status"
    echo ""
}


wait_for_services() {
    log_info "Waiting for services to be healthy..."

    local max_wait=180
    local waited=0

    while [ $waited -lt $max_wait ]; do
        if curl -sf "http://localhost:${ORDER_SERVICE_PORT}/actuator/health" >/dev/null 2>&1; then
            echo ""
            log_success "All services healthy!"
            return 0
        fi

        printf "."
        sleep 3
        waited=$((waited + 3))
    done

    echo ""
    log_warn "Timeout - check: docker-compose logs"
}


build_jars() {
    local need_build=false

    for svc in order-service payment-service notification-service; do
        if ! ls "${svc}/target/${svc}-"*.jar >/dev/null 2>&1; then
            need_build=true
            break
        fi
    done

    if [ "$need_build" = true ]; then
        log_info "Building JAR files..."
        cd "$SCRIPT_DIR/.."
        mvn clean package -DskipTests -pl demo-app/order-service,demo-app/payment-service,demo-app/notification-service -am -q
        cd "$SCRIPT_DIR"
        log_success "JARs built"
    fi
}


check_prerequisites() {
    local missing=false

    if ! command -v docker &> /dev/null; then
        log_error "Docker is not installed"
        missing=true
    elif ! docker info &> /dev/null; then
        log_error "Docker is not running"
        missing=true
    fi

    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        log_error "Docker Compose is not installed"
        missing=true
    fi

    if ! command -v mvn &> /dev/null; then
        log_warn "Maven not found - JAR build may fail if JARs are missing"
    fi

    if [ "$missing" = true ]; then
        log_error "Please install missing prerequisites and try again"
        exit 1
    fi
}


main() {
    echo ""
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo "  Senior Backend Patterns - Demo App"
    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    echo ""

    
    check_prerequisites

    case "${1:-}" in
        --status)
            show_status
            exit 0
            ;;
        --stop)
            full_cleanup
            exit 0
            ;;
        --help|-h)
            echo "Usage: $0 [--status|--stop|--help]"
            echo ""
            echo "  (no args)  Clean start - stops old, builds, starts fresh"
            echo "  --status   Show running containers"
            echo "  --stop     Stop and clean up everything"
            echo "  --help     Show this message"
            echo ""
            exit 0
            ;;
    esac

    
    full_cleanup

    
    load_env

    
    resolve_ports

    
    build_jars

    
    log_info "Starting services..."
    docker_compose up -d --build --remove-orphans 2>&1 | grep -v "obsolete"

    
    wait_for_services

    
    show_urls
}

main "$@"
