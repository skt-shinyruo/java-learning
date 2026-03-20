/**
 * 面向缓存入口防护的单机内存示例。
 * <p>
 * 这个包里的布隆过滤器主要回答两类问题：
 * <ul>
 *   <li>返回 {@code false}：元素一定不存在，可以直接拦截</li>
 *   <li>返回 {@code true}：元素可能存在，仍需要继续查 Redis / DB</li>
 * </ul>
 * <p>
 * 这里的实现刻意保持为“学习向、单节点、纯内存”版本，不涉及 Redis 或分布式同步。
 */
package yier.bubu.concurrency.cache;
