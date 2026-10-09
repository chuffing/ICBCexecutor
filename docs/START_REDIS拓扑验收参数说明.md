# START_REDIS 拓扑验收参数说明

## 1. 修改目的

Redis Sentinel 故障切换后，旧主库重新启动时可能短暂显示为 MASTER，随后才被 Sentinel 改为新主库的副本。只检查一次 PING 和 ROLE 会过早返回成功。

新版 START_REDIS 支持平台声明期望拓扑，并要求连续三次检查一致后才返回成功。检查间隔为 1 秒，最长等待 20 秒。

## 2. 启动主库

```json
{
  "taskNo": "TASK202609290401",
  "taskType": "START_REDIS",
  "params": {
    "expectedRole": "MASTER"
  }
}
```

成功条件：

- PING 返回 PONG。
- ROLE 连续三次为 MASTER。

## 3. 启动副本

```json
{
  "taskNo": "TASK202609290402",
  "taskType": "START_REDIS",
  "params": {
    "expectedRole": "REPLICA",
    "expectedMasterHost": "172.20.10.12",
    "expectedMasterPort": "6379"
  }
}
```

成功条件：

- PING 返回 PONG。
- ROLE 连续三次为 REPLICA。
- `master_link_status` 为 `up`。
- `master_host` 等于平台传入的 `expectedMasterHost`。
- `master_port` 等于平台传入的 `expectedMasterPort`。

## 4. 参数规则

| 参数 | MASTER | REPLICA | 说明 |
| --- | --- | --- | --- |
| `expectedRole` | 必填 | 必填 | 只允许 MASTER 或 REPLICA，不区分大小写 |
| `expectedMasterHost` | 不允许 | 必填 | 期望连接的主库地址 |
| `expectedMasterPort` | 不允许 | 必填 | 1 到 65535 |

当前暂时保留空 `params` 兼容模式。兼容模式仍要求健康状态连续三次稳定，但不会比较平台期望角色。平台正式开发时应始终传入拓扑参数。

## 5. 失败结果

- 参数格式错误：`CONFIG_INVALID`。
- 期望 REPLICA，但角色、主库地址、端口或复制链路在 20 秒内不符合要求：`REPLICATION_FAILED`。
- 期望 MASTER，但 20 秒内未稳定成为 MASTER：`START_TIMEOUT`。

实际反向验证中，第二台 Redis 的真实主库为 `172.20.10.12`，任务故意传入 `172.20.10.3`，Agent 返回：

```text
REPLICATION_FAILED
Redis在20秒内未达到期望拓扑:
Redis主库地址不符合预期: expected=172.20.10.3, actual=172.20.10.12
```

## 6. 代码流转

1. `AgentTaskServiceImpl` 把 START_REDIS 的 params 传给 `StartRedisOperation`。
2. `StartRedisTaskParameters` 校验和规范化平台参数。
3. `StartRedisOperation` 调用固定的 `start-redis.sh`。
4. `RedisHealthChecker` 执行 PING、ROLE 和 INFO replication。
5. `StartRedisOperation` 比较实际拓扑与平台期望。
6. 连续三次匹配才生成 SUCCESS；超时则生成稳定错误码和原因。

本功能没有新增 Maven 依赖或插件。
