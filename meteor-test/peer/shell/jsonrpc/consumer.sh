#!/usr/bin/env bash
# Copyright 2015-2026 the original author or authors.
# Licensed under the Apache License, Version 2.0. See LICENSE.txt.
set -euo pipefail

target="${METEOR_ORDER_TARGET:-http://127.0.0.1:8080/jsonrpc}"
authority_path="${target#*://}"
if [[ "$authority_path" != */* ]]; then
    target="$target/jsonrpc"
elif [[ -z "${authority_path#*/}" ]]; then
    target="${target}jsonrpc"
fi

# 保存订单 1001；附件是文本 Meteor 的 Base64 表示。
curl --fail-with-body --silent --show-error --max-time 10 \
    --header 'Content-Type: application/json' \
    --data-binary @- "$target" <<'JSON'
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "orders.save",
  "params": [{
    "id": 1001,
    "description": "Order from curl",
    "attachment": "TWV0ZW9y"
  }]
}
JSON
echo

# 查询刚保存的订单。
curl --fail-with-body --silent --show-error --max-time 10 \
    --header 'Content-Type: application/json' \
    --data-binary @- "$target" <<'JSON'
{"jsonrpc":"2.0","id":2,"method":"orders.find","params":[{"id":1001}]}
JSON
echo
