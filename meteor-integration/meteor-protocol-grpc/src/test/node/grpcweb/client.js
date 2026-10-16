/* Copyright 2015-2026 the original author or authors. Licensed under the Apache License, Version 2.0. */
const binary = require('./generated/binary/web_grpc_web_pb.js');
const text = require('./generated/text/web_grpc_web_pb.js');
const { Message } = require('./generated/binary/web_pb.js');

function message(value, count) {
  const request = new Message();
  request.setValue(value);
  request.setCount(count);
  return request;
}

function unary(client, method, request) {
  return new Promise(resolve => {
    client[method](request, { 'custom-header': 'browser-token' }, (error, response) => {
      if (error) {
        resolve({ error: { code: error.code, message: error.message } });
      } else {
        resolve({ value: response.getValue(), count: response.getCount() });
      }
    });
  });
}

function stream(client, scene) {
  return new Promise(resolve => {
    const values = [];
    const method = scene === 'broken' ? 'broken' : 'list';
    const metadata = { 'custom-header': 'browser-token' };
    if (scene === 'deadline') {
      metadata.deadline = String(Date.now() + 700);
    }
    if (scene === 'cancel') {
      metadata.deadline = String(Date.now() + 1500);
    }
    const call = client[method](message('中文🙂', 3), metadata);
    let status;
    let error;
    call.on('data', response => {
      values.push({ value: response.getValue(), count: response.getCount() });
      if (scene === 'cancel' || scene === 'h2cancel') {
        call.cancel();
        resolve({ values, cancelled: true });
      }
    });
    call.on('status', value => {
      status = { code: value.code, details: value.details, metadata: value.metadata };
    });
    call.on('error', failure => {
      error = { code: failure.code, message: failure.message };
      // Official grpc-web does not emit end after trailers-only or XHR errors.
      resolve({ values, status, error });
    });
    call.on('end', () => resolve({ values, status, error, ended: true }));
  });
}

// These are generated official clients, running in a real browser with its CORS policy enabled.
window.grpcWebScenario = async ({ target, scene }) => {
  const options = { withCredentials: scene === 'credentials' };
  const client = scene === 'binary' ? new binary.EchoClient(target, null, options)
    : new text.EchoClient(target, null, options);
  if (['stream', 'broken', 'cancel', 'h2cancel', 'deadline'].includes(scene)) {
    return stream(client, scene);
  }
  if (scene === 'fault') {
    return unary(client, 'fail', message('拒绝访问', 7));
  }
  return unary(client, 'echo', message('中文🙂 <&>', 7));
};
