.PHONY: build test it verify coverage up down run e2e-install e2e e2e-local clean

build:            ## compile + package (skip tests)
	mvn -B -q -DskipTests package

test:             ## unit tests only (no Docker needed)
	mvn -B test

it:               ## integration tests (Testcontainers: Postgres, Kafka, Keycloak; needs Docker)
	mvn -B verify   # runs unit tests, then failsafe *IT classes

verify:           ## unit + integration + jacoco reports
	mvn -B verify

coverage:         ## unit tests + jacoco report at target/site/jacoco/index.html
	mvn -B verify -DskipITs

up:               ## start Postgres, Kafka, Keycloak
	docker compose up -d --wait

down:
	docker compose down -v

run:              ## run the app against the compose stack
	mvn -B spring-boot:run

e2e-install:
	cd e2e && npm install && npx playwright install --with-deps chromium

e2e:              ## Playwright UI tests against a running stack (make up && make run)
	cd e2e && npx playwright test

e2e-local:        ## everything: compose up, start app, run Playwright, tear down
	./scripts/e2e.sh

clean:
	mvn -B clean
