package order

import (
	"encoding/json"
	"fmt"
	"os"
	"sync"

	"google.golang.org/protobuf/proto"
	orders "meteor.test/orderpeer/gen/orderpb"
)

// Store owns the orders used by the independent fixed-address process scenarios.
type Store struct {
	mu     sync.RWMutex
	values map[int32]*orders.Order
}

func NewStore() *Store {
	return &Store{values: make(map[int32]*orders.Order)}
}

func (s *Store) Save(order *orders.Order) *orders.Order {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.values[order.Id] = proto.Clone(order).(*orders.Order)
	return order
}

func (s *Store) Find(id int32) (*orders.Order, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	order, found := s.values[id]
	if !found {
		return nil, fmt.Errorf("Order not found: %d", id)
	}
	return proto.Clone(order).(*orders.Order), nil
}

func Sample() *orders.Order {
	return &orders.Order{Id: 1001, Description: "Meteor order", Attachment: []byte("hello Meteor")}
}

func Print(saved, found *orders.Order) error {
	return json.NewEncoder(os.Stdout).Encode(map[string]*orders.Order{"saved": saved, "found": found})
}
