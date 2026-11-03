package order

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
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

func NxnMode() bool {
	return os.Getenv("METEOR_NXN_REQUEST_FILE") != ""
}

// Request uses only the planner's input when this process belongs to an NXN case.
func Request() (*orders.Order, error) {
	if !NxnMode() {
		return Sample(), nil
	}
	id, err := strconv.ParseInt(os.Getenv("METEOR_NXN_ORDER_ID"), 10, 32)
	if err != nil {
		return nil, fmt.Errorf("invalid NXN order id: %w", err)
	}
	description, present := os.LookupEnv("METEOR_NXN_ORDER_DESCRIPTION")
	if !present {
		return nil, fmt.Errorf("NXN order description is required")
	}
	encodedAttachment, present := os.LookupEnv("METEOR_NXN_ORDER_ATTACHMENT")
	if !present {
		return nil, fmt.Errorf("NXN order attachment is required")
	}
	attachment, err := base64.StdEncoding.DecodeString(encodedAttachment)
	if err != nil {
		return nil, fmt.Errorf("invalid NXN order attachment: %w", err)
	}
	return &orders.Order{Id: int32(id), Description: description, Attachment: attachment}, nil
}

func Ready(target string) error {
	if NxnMode() {
		return json.NewEncoder(os.Stdout).Encode(map[string]string{"event": "ready", "target": target})
	}
	fmt.Println("Order provider is ready: " + target)
	return nil
}

func Print(saved, found *orders.Order) error {
	if NxnMode() {
		expected, err := Request()
		if err != nil {
			return err
		}
		for _, actual := range []*orders.Order{saved, found} {
			if actual == nil || expected.Id != actual.Id || expected.Description != actual.Description ||
				!bytes.Equal(expected.Attachment, actual.Attachment) {
				return fmt.Errorf("order differs: expected %v, actual %v", expected, actual)
			}
		}
		// A separate result shape preserves zero and empty values omitted by generated protobuf JSON tags.
		type resultOrder struct {
			ID          int32  `json:"id"`
			Description string `json:"description"`
			Attachment  string `json:"attachment"`
		}
		toResult := func(value *orders.Order) resultOrder {
			return resultOrder{
				ID: value.Id, Description: value.Description,
				Attachment: base64.StdEncoding.EncodeToString(value.Attachment),
			}
		}
		return json.NewEncoder(os.Stdout).Encode(map[string]any{
			"event": "result", "saved": toResult(saved), "order": toResult(found),
		})
	}
	return json.NewEncoder(os.Stdout).Encode(map[string]*orders.Order{"saved": saved, "found": found})
}
