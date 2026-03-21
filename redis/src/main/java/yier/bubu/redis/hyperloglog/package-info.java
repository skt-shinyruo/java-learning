/**
 * 学习向、单机内存版 HyperLogLog 示例。
 * <p>
 * 这个包当前包含：
 * <ul>
 *   <li>{@link yier.bubu.redis.hyperloglog.HyperLogLog}：近似 distinct 计数</li>
 *   <li>{@link yier.bubu.redis.hyperloglog.DailyActiveUserCounter}：用 HyperLogLog 做单日 UV 估计的业务化示例</li>
 * </ul>
 * <p>
 * 这里的实现刻意保持为“学习向、单节点、纯内存”版本，不涉及 Redis 网络协议、序列化或分布式同步。
 */
package yier.bubu.redis.hyperloglog;
