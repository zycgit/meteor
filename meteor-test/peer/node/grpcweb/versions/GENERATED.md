# gRPC-Web 1.5 生成契约

`generated/binary` 和 `generated/text` 来自测试契约
`meteor-test/src/test/resources/grpc/proto/web.proto`，分别使用 `grpcweb`、`grpcwebtext` 模式。
生成代码保持原样，配套运行依赖为 `grpc-web@1.5.0`、`google-protobuf@3.21.4`。

## 工具来源

- `protoc 3.21.12`：既有 Native 镜像中的 Debian `protobuf-compiler 3.21.12-3+deb12u1`。
- [`protoc-gen-js 3.21.4`](https://github.com/protocolbuffers/protobuf-javascript/releases/tag/v3.21.4)：官方发布归档。
- [`protoc-gen-grpc-web 1.5.0`](https://github.com/grpc/grpc-web/releases/tag/1.5.0)：官方二进制，其 SHA-256 与官方校验文件一致。

| 官方资产 | SHA-256 |
| --- | --- |
| `protobuf-javascript-3.21.4-linux-x86_64.tar.gz` | `c57ba4130471c642462fcf98c844a3c933f6c4708b9fddc859900fd2a2e72a45` |
| `protobuf-javascript-3.21.4-linux-aarch_64.tar.gz` | `86194b1c6baee994bb06d162887b9edace6b32a8ed971eac07fdf5d2470c6937` |
| `protoc-gen-grpc-web-1.5.0-linux-x86_64` | `2e6e074497b221045a14d5a54e9fc910945bfdd1198b12b9fc23686a95671d64` |
| `protoc-gen-grpc-web-1.5.0-linux-aarch64` | `522e958568cdeabdd20ef3c97394fc067ff8e374a728c08b7410bf5de8f57783` |

AMD64 归档中的 `bin/protoc-gen-js` SHA-256：
`2731a96ccbb180b5a5142f7409a407098aa387ab1a027c6dfdca4dc0b72fb839`。

源契约 SHA-256：`f1314fde459a40a13ff9198cd3c7b870029ee97561a0edd7b380543b08b00d61`。

## 重新生成

在已有 Native 镜像的仓库根目录执行：

```bash
./meteor-test/peer/node/grpcweb/versions/regenerate.sh
```

脚本支持 Docker AMD64、ARM64，校验下载资产后在容器中生成两个模式。
工具和中间文件位于 `meteor-test/build/sdk-compatibility/grpcweb-legacy-codegen/`。
`METEOR_TEST_IMAGE_NATIVE` 可指定提供 `protoc 3.21.12` 的 Native 镜像。

## 离线验证

已在 AMD64 Node 镜像中使用原版 SDK 验证两个模式的 Unicode、负整数序列化与反序列化，
并为既有 `client.js` 构建浏览器 Bundle。
使用原 `protoc-gen-js 4.0.3` 生成结果进行反例验证时，原版 `google-protobuf 3.21.4`
可复现 `reader.readStringRequireUtf8 is not a function`；本目录生成结果通过。
ARM64 资产已下载核验，未执行 ARM64 生成或浏览器 RPC。
