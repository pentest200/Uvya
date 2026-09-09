SHELL := /bin/sh
COMPOSE ?= docker compose

.PHONY: help up down restart logs ps config build test lint verify

help:
	@printf '%s\n' \
		'up       Build and start the local foundation' \
		'down     Stop the local foundation' \
		'restart  Restart the local foundation' \
		'logs     Follow service logs' \
		'ps       Show service status' \
		'config   Validate the Compose configuration' \
		'build    Build all application images' \
		'test     Run service tests in their native toolchains' \
		'lint     Run static analysis in their native toolchains' \
		'verify   Run configuration validation and application checks'

up:
	$(COMPOSE) up --build -d

down:
	$(COMPOSE) down

restart:
	$(COMPOSE) up --build -d --force-recreate

logs:
	$(COMPOSE) logs -f

ps:
	$(COMPOSE) ps

config:
	$(COMPOSE) config --quiet

build:
	$(COMPOSE) build

test:
	cd services/api-gateway && mvn -B test
	cd services/ws-gateway && go test ./...
	cd apps/web && npm run typecheck

lint:
	cd services/api-gateway && mvn -B -DskipTests verify
	cd services/ws-gateway && go vet ./...
	cd apps/web && npm run lint

verify: config test lint
