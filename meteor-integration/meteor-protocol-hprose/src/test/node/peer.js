'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const hprose = require('hprose');

async function callMeteor(url, scenario, tlsDirectory) {
    // 官方客户端先发现 Meteor 发布的方法，再执行当前测试指定的唯一场景。
    let settings;
    if (tlsDirectory) {
        // 通过官方客户端的 options 注入身份和信任根，保持证书与主机名校验。
        settings = {options: tlsOptions(tlsDirectory, 'client')};
    }
    const client = hprose.Client.create(url, undefined, settings);
    client.timeout = 5000;
    const service = await client.ready();

    const scenarios = {
        unicode: async () => {
            // 中文及补充字符应当完整往返。
            assert.equal(await service.sample_echo('中文🙂'), '中文🙂');
        },
        integer: async () => {
            // 整型参数经 Java 方法相加后返回整型结果。
            assert.equal(await service.sample_add(3, 4), 7);
        },
        null: async () => {
            // null 参数与返回值都使用协议中的空值表示。
            assert.equal(await service.sample_echo(null), null);
        },
        bytes: async () => {
            // 验证边界字节，包含 Java byte 为负数的 255。
            const value = await service.sample_bytes(Buffer.from([0, 127, 255]));
            assert.deepEqual(Array.from(value), [0, 127, 255]);
        },
        date: async () => {
            // 固定日期值，避免时区或毫秒转换错误。
            const value = new Date('2024-02-29T12:34:56.000Z');
            const result = await service.sample_date(value);
            assert.equal(result.toISOString(), value.toISOString());
        },
        map: async () => {
            // 不同类型的属性值应被还原为原有对象内容。
            const value = {chinese: '中文', count: 8};
            assert.deepEqual(await service.sample_map(value), value);
        },
        bean: async () => {
            // 服务只增加年龄，中文姓名在转换过程中保持不变。
            const person = await service.sample_person({name: '张三', age: 9});
            assert.equal(person.name, '张三');
            assert.equal(person.age, 10);
        },
        void: async () => {
            // Java void 方法对外返回协议空值。
            assert.equal(await service.sample_accept('ignored'), null);
        },
        byref: async () => {
            // 官方回调接收本次调用后的参数值。
            let updated;
            service.sample_mutate.byref = true;
            await service.sample_mutate(['before'], (value, args) => {
                updated = args[0];
            });
            assert.deepEqual(updated, ['before', 'after']);
        },
        batch: async () => {
            // 成功、失败、慢调用属于同一个批次隔离与顺序场景。
            client.batch.begin();
            service.sample_delay('first', 80);
            service.sample_fail().catchError(() => {
            });
            service.sample_echo('third');

            // 批次结果按提交顺序返回，失败不影响后续独立调用。
            const batch = await client.batch.end();
            assert.equal(batch[0].result, 'first');
            assert.match(batch[1].error.message, /business failure/);
            assert.equal(batch[2].result, 'third');
            assert.equal(await service.sample_echo('after error'), 'after error');
        },
        discovery: async () => {
            // GET 请求返回函数列表，正文由 Hprose 的 F 标记开始。
            const discovery = await fetch(url);
            assert.equal(discovery.status, 200);
            const functions = await discovery.text();
            assert.equal(functions[0], 'F');
        },
        cors: async () => {
            // 跨域预检应放行调用方声明的请求头。
            const headers = {
                Origin: 'https://example.test',
                'Access-Control-Request-Headers': 'x-trace'
            };
            const preflight = await fetch(url, {method: 'OPTIONS', headers});
            assert.equal(preflight.status, 204);
            assert.equal(preflight.headers.get('access-control-allow-headers'), 'x-trace');
        }
    };
    // Java 测试按名称启动一次场景，只有断言全部通过后才输出完成标记。
    assert.equal(typeof scenarios[scenario], 'function', 'Unknown interop scenario: ' + scenario);
    await scenarios[scenario]();
    console.log('OK');
}

function tlsOptions(directory, identity) {
    // PKCS12 来自同一验收 CA，服务器与客户端分别加载自己的私钥。
    return {
        pfx: fs.readFileSync(path.join(directory, identity + '.p12')),
        passphrase: 'meteor-test',
        ca: fs.readFileSync(path.join(directory, 'ca.pem')),
        rejectUnauthorized: true,
        ALPNProtocols: ['http/1.1']
    };
}

function serve(tlsDirectory) {
    // 官方 Hprose 服务端监听随机端口，为 Meteor 客户端提供独立对端。
    let options;
    if (tlsDirectory) {
        options = tlsOptions(tlsDirectory, 'server');
        options.requestCert = true;
    }
    const server = new hprose.HttpServer(0, '127.0.0.1', options);
    server.errorDelay = 0;

    // 基础类型直接回传，业务对象模拟由服务端修改字段。
    server.addFunction(value => value, 'sample_echo');
    server.addFunction((a, b) => a + b, 'sample_add');
    server.addFunction(value => value, 'sample_bytes');
    server.addFunction(value => value, 'sample_date');
    server.addFunction(value => value, 'sample_map');
    server.addFunction(value => ({name: value.name, age: value.age + 1}), 'sample_person');
    server.addFunction(values => {
        return values.map(value => ({name: value.name, age: value.age + 1}));
    }, 'sample_people');

    // 错误与无返回值方法用于验证协议终态，不模拟 Meteor 自身逻辑。
    server.addFunction(() => {
        throw new Error('business failure 中文');
    }, 'sample_fail');
    server.addFunction(() => null, 'sample_accept');

    // 监听成功后报告端口，Java 测试关闭标准输入时结束服务端。
    server.server.on('listening', () => console.log(server.server.address().port));
    server.start();
    process.stdin.resume();
    process.stdin.on('end', () => {
        server.close(() => process.exit(0));
    });
}

// 角色与场景都由对应 Java 测试指定，退出码向父进程传递结果。
if (process.argv[2] === 'server') {
    serve();
} else if (process.argv[2] === 'https-server') {
    serve(process.argv[3]);
} else {
    callMeteor(process.argv[3], process.argv[4], process.argv[5]).then(() => process.exit(0), failure => {
        console.error(failure.stack || failure);
        process.exit(1);
    });
}
