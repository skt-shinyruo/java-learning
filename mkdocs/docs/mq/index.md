# MQ Docs

This section groups MQ-oriented notes under `mq/docs`.

## Topics

- [Kafka `.log`、`.index`、`.timeindex`：`FileChannel`、`mmap` 与 page cache](content/kafka-log-index-mmap.md)
- [Kafka `VARINT`、`VARLONG` 与 `base + delta`：为什么要这样编码](content/kafka-varint-record-encoding.md)
- [Kafka Consumer offset commit：手动提交与 partition 内并发处理](content/kafka-consumer-offset-commit.md)
- [Kafka Broker 请求处理链路：Acceptor、Processor、RequestChannel 与 KafkaApis](content/kafka-request-processing.md)

## Notes

- These pages focus on message queue storage and I/O internals.
- Kafka-related notes should live here instead of under generic NIO topics when the primary subject is MQ design.
