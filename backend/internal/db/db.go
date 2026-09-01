// Package db owns the PostgreSQL connection pool and schema migrations.
//
// Migrations are embedded in the binary, so the running image always carries the
// exact schema it was built against and cannot drift from a separately-shipped
// migrations directory.
package db

import (
	"context"
	"embed"
	"errors"
	"fmt"
	"log"
	"time"

	"github.com/golang-migrate/migrate/v4"
	"github.com/golang-migrate/migrate/v4/database/postgres"
	"github.com/golang-migrate/migrate/v4/source/iofs"
	"github.com/jackc/pgx/v5/pgxpool"

	_ "github.com/jackc/pgx/v5/stdlib" // database/sql driver, needed by golang-migrate
)

//go:embed migrations/*.sql
var migrationFS embed.FS

type DB struct {
	Pool *pgxpool.Pool
}

// Connect opens the pool, waiting for PostgreSQL to accept connections. In
// Compose the database and the API start together, so the first few attempts
// failing is normal rather than an error worth crashing on.
func Connect(ctx context.Context, dsn string) (*DB, error) {
	cfg, err := pgxpool.ParseConfig(dsn)
	if err != nil {
		return nil, fmt.Errorf("parse dsn: %w", err)
	}
	cfg.MaxConns = 10
	cfg.MaxConnLifetime = time.Hour

	var pool *pgxpool.Pool
	deadline := time.Now().Add(60 * time.Second)
	for attempt := 1; ; attempt++ {
		pool, err = pgxpool.NewWithConfig(ctx, cfg)
		if err == nil {
			if pingErr := pool.Ping(ctx); pingErr == nil {
				break
			} else {
				pool.Close()
				err = pingErr
			}
		}
		if time.Now().After(deadline) {
			return nil, fmt.Errorf("postgres unreachable after %d attempts: %w", attempt, err)
		}
		log.Printf("postgres not ready (attempt %d), retrying: %v", attempt, err)
		time.Sleep(2 * time.Second)
	}

	log.Printf("connected to postgres")
	return &DB{Pool: pool}, nil
}

// Migrate applies every pending migration. Running with no pending changes is a
// no-op, so it is safe on every boot.
func (d *DB) Migrate(dsn string) error {
	src, err := iofs.New(migrationFS, "migrations")
	if err != nil {
		return fmt.Errorf("load migrations: %w", err)
	}

	m, err := migrate.NewWithSourceInstance("iofs", src, normalizeDSN(dsn))
	if err != nil {
		return fmt.Errorf("init migrate: %w", err)
	}
	defer m.Close()

	if err := m.Up(); err != nil && !errors.Is(err, migrate.ErrNoChange) {
		return fmt.Errorf("apply migrations: %w", err)
	}

	version, dirty, verr := m.Version()
	if verr != nil && !errors.Is(verr, migrate.ErrNilVersion) {
		return fmt.Errorf("read version: %w", verr)
	}
	log.Printf("schema at version %d (dirty=%v)", version, dirty)
	return nil
}

func (d *DB) Close() {
	if d.Pool != nil {
		d.Pool.Close()
	}
}

// normalizeDSN rewrites a postgres:// URL to the pgx5:// scheme golang-migrate
// expects for its stdlib driver.
func normalizeDSN(dsn string) string {
	for _, prefix := range []string{"postgres://", "postgresql://"} {
		if len(dsn) > len(prefix) && dsn[:len(prefix)] == prefix {
			return "pgx5://" + dsn[len(prefix):]
		}
	}
	return dsn
}

var _ = postgres.Postgres{} // keep the postgres driver import linked
