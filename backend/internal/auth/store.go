package auth

import (
	"errors"
	"sync"

	"golang.org/x/crypto/bcrypt"
)

type Role string

const (
	RoleAdmin    Role = "ADMIN"
	RoleOperator Role = "OPERATOR"
	RoleViewer   Role = "VIEWER"
)

type User struct {
	ID           string
	Email        string
	PasswordHash string
	Role         Role
}

var ErrInvalidCredentials = errors.New("invalid email or password")

// Store is an in-memory user store for Phase 2 (Platform Foundation).
// It will be replaced by the `app_user` table (see docs/phase-1-architecture/03-database.md)
// once persistence lands with the Deployment Engine in Phase 3.
type Store struct {
	mu    sync.RWMutex
	users map[string]*User
}

func NewStore() *Store {
	return &Store{users: make(map[string]*User)}
}

func (s *Store) Seed(email, password string, role Role) error {
	hash, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	if err != nil {
		return err
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.users[email] = &User{ID: email, Email: email, PasswordHash: string(hash), Role: role}
	return nil
}

func (s *Store) Authenticate(email, password string) (*User, error) {
	s.mu.RLock()
	u, ok := s.users[email]
	s.mu.RUnlock()
	if !ok {
		return nil, ErrInvalidCredentials
	}
	if err := bcrypt.CompareHashAndPassword([]byte(u.PasswordHash), []byte(password)); err != nil {
		return nil, ErrInvalidCredentials
	}
	return u, nil
}
