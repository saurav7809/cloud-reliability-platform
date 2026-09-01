// Package cache wraps Redis.
//
// The fleet overview aggregates across every target on every request — the most
// expensive read in the API and the one the dashboard hits first. Caching it
// keeps that cost off PostgreSQL.
//
// Redis is treated as strictly optional: if it is unavailable the platform runs
// correctly, just without the cache. A reliability platform that falls over
// because its cache is down would be an awkward thing to ship.
package cache

import (
	"context"
	"encoding/json"
	"log"
	"time"

	"github.com/redis/go-redis/v9"
)

type Cache struct {
	rdb     *redis.Client
	enabled bool
}

// Connect dials Redis. A failure is logged and returns a disabled cache rather
// than an error, so a missing Redis degrades performance instead of breaking the
// service.
func Connect(ctx context.Context, addr string) *Cache {
	if addr == "" {
		log.Printf("redis not configured — caching disabled")
		return &Cache{enabled: false}
	}

	rdb := redis.NewClient(&redis.Options{
		Addr:         addr,
		DialTimeout:  3 * time.Second,
		ReadTimeout:  2 * time.Second,
		WriteTimeout: 2 * time.Second,
	})

	pingCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if err := rdb.Ping(pingCtx).Err(); err != nil {
		log.Printf("redis unreachable at %s, continuing without cache: %v", addr, err)
		return &Cache{enabled: false}
	}

	log.Printf("connected to redis at %s", addr)
	return &Cache{rdb: rdb, enabled: true}
}

func (c *Cache) Enabled() bool { return c.enabled }

// GetJSON unmarshals a cached value into dest. Returns false on a miss, on any
// error, or when the cache is disabled — callers treat all three identically and
// fall through to the database.
func (c *Cache) GetJSON(ctx context.Context, key string, dest any) bool {
	if !c.enabled {
		return false
	}
	raw, err := c.rdb.Get(ctx, key).Bytes()
	if err != nil {
		return false
	}
	if err := json.Unmarshal(raw, dest); err != nil {
		// A value we cannot decode is worse than no value — drop it so the next
		// read repopulates cleanly.
		c.rdb.Del(ctx, key)
		return false
	}
	return true
}

// SetJSON stores a value with a TTL. Failures are ignored: a write that does not
// land costs a cache miss, nothing more.
func (c *Cache) SetJSON(ctx context.Context, key string, value any, ttl time.Duration) {
	if !c.enabled {
		return
	}
	raw, err := json.Marshal(value)
	if err != nil {
		return
	}
	c.rdb.Set(ctx, key, raw, ttl)
}

// Invalidate drops keys whose underlying data just changed — called after
// mutations so the dashboard never shows a stale rollup.
func (c *Cache) Invalidate(ctx context.Context, keys ...string) {
	if !c.enabled || len(keys) == 0 {
		return
	}
	c.rdb.Del(ctx, keys...)
}

func (c *Cache) Close() {
	if c.enabled && c.rdb != nil {
		c.rdb.Close()
	}
}
