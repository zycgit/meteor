package orders

import (
	"encoding/json"
	"fmt"
	"os"
	"sync"

	"google.golang.org/protobuf/proto"
)

// Store is shared order business logic; RPC publication stays in each provider.
type Store struct {
	mu     sync.RWMutex
	values map[int32]*Order
}

func NewStore() *Store {
	return &Store{values: make(map[int32]*Order)}
}

func (s *Store) Save(order *Order) *Order {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.values[order.Id] = proto.Clone(order).(*Order)
	return order
}

func (s *Store) Find(id int32) (*Order, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	order, found := s.values[id]
	if !found {
		return nil, fmt.Errorf("Order not found: %d", id)
	}
	return proto.Clone(order).(*Order), nil
}

func Sample() *Order {
	return &Order{Id: 1001, Description: "Meteor order", Attachment: []byte("hello Meteor")}
}

func Print(saved, found *Order) error {
	return json.NewEncoder(os.Stdout).Encode(map[string]*Order{"saved": saved, "found": found})
}
