# PostgreSQL 17 + PostGIS, plus pgvector and pg_partman (all supported on Aurora)
FROM postgis/postgis:17-3.5
RUN apt-get update \
 && apt-get install -y --no-install-recommends postgresql-17-pgvector postgresql-17-partman \
 && rm -rf /var/lib/apt/lists/*
