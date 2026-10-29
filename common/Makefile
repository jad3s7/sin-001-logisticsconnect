.PHONY: help up down logs ps smoke reset console

help:
	@echo "LogisticsConnect broker targets:"
	@echo "  make up       - start broker (docker compose up -d)"
	@echo "  make ps       - show container + health status"
	@echo "  make logs     - tail broker logs"
	@echo "  make console  - print the web console URL"
	@echo "  make smoke    - run end-to-end MQ smoke test"
	@echo "  make down     - stop broker (keeps volume)"
	@echo "  make reset    - stop broker AND wipe broker state"

up:
	docker compose up -d
	@echo "Waiting for health..."
	@for i in $$(seq 1 30); do \
	  if docker compose ps --format json 2>/dev/null | grep -q '"Health":"healthy"'; then \
	    echo "Broker is healthy."; break; fi; \
	  sleep 1; done
	@docker compose ps

ps:
	docker compose ps

logs:
	docker compose logs -f activemq

console:
	@echo "ActiveMQ web console: http://localhost:8161  (admin / admin)"

smoke:
	./smoke-test.sh

down:
	docker compose down

reset:
	docker compose down -v