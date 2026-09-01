package auth

import (
	"context"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"golang.org/x/crypto/bcrypt"
)

type Role string

const (
	RoleAdmin    Role = "ADMIN"
	RoleOperator Role = "OPERATOR"
	RoleViewer   Role = "VIEWER"
)

type User struct {
	ID    string
	Email string
	Role  Role
}

var ErrInvalidCredentials = errors.New("invalid email or password")

// Store authenticates users against the app_user table.
type Store struct {
	pool *pgxpool.Pool
}

func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// Authenticate verifies an email/password pair. A missing user and a wrong
// password return the same error, and the bcrypt comparison runs either way, so
// response timing does not reveal which accounts exist.
func (s *Store) Authenticate(ctx context.Context, email, password string) (*User, error) {
	var (
		id, hash string
		role     Role
	)
	err := s.pool.QueryRow(ctx,
		`SELECT id, password_hash, role FROM app_user WHERE lower(email) = lower($1)`,
		email,
	).Scan(&id, &hash, &role)

	if errors.Is(err, pgx.ErrNoRows) {
		// Compare against a dummy hash to keep timing uniform.
		bcrypt.CompareHashAndPassword(
			[]byte("$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"),
			[]byte(password))
		return nil, ErrInvalidCredentials
	}
	if err != nil {
		return nil, err
	}

	if err := bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)); err != nil {
		return nil, ErrInvalidCredentials
	}
	return &User{ID: id, Email: email, Role: role}, nil
}
