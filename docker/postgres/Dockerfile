# PostgreSQL 17 + PostGIS, pgvector and pg_partman (all supported on Aurora)
FROM postgres:17-trixie
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      postgresql-17-postgis-3 postgresql-17-postgis-3-scripts \
      postgresql-17-pgvector postgresql-17-partman \
 && rm -rf /var/lib/apt/lists/*
