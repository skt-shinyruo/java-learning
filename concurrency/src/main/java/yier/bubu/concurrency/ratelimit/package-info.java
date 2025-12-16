/**
 * 单机内存版限流算法示例（rate limiting）。
 * <p>
 * 这个包的实现目标：
 * <ul>
 *   <li>尽量少依赖：不引入第三方包</li>
 *   <li>便于学习：代码结构清晰，配套测试可作为“可执行文档”</li>
 *   <li>单机实现：数据结构都在内存中（不是分布式限流）</li>
 * </ul>
 *
 * <h2>包含的算法</h2>
 * <ul>
 *   <li>固定窗口计数：{@link yier.bubu.concurrency.ratelimit.FixedWindowCounterRateLimiter}</li>
 *   <li>滑动窗口计数（桶化近似）：{@link yier.bubu.concurrency.ratelimit.SlidingWindowCounterRateLimiter}</li>
 *   <li>滑动日志（严格滑动窗口）：{@link yier.bubu.concurrency.ratelimit.SlidingLogRateLimiter}</li>
 *   <li>令牌桶：{@link yier.bubu.concurrency.ratelimit.TokenBucketRateLimiter}</li>
 *   <li>漏桶：{@link yier.bubu.concurrency.ratelimit.LeakyBucketRateLimiter}</li>
 *   <li>并发限流（Bulkhead）：{@link yier.bubu.concurrency.ratelimit.ConcurrencyLimiter}</li>
 *   <li>有界队列：{@link yier.bubu.concurrency.ratelimit.BoundedQueueLimiter}</li>
 *   <li>自适应并发（学习向 AIMD）：{@link yier.bubu.concurrency.ratelimit.AdaptiveConcurrencyLimiter}</li>
 * </ul>
 *
 * <h2>关于线程安全</h2>
 * <ul>
 *   <li>计数/桶/日志类主要用 {@code synchronized} 保证多线程下状态一致</li>
 *   <li>并发类主要用 CAS/Atomic 来做无锁的名额获取与释放</li>
 * </ul>
 */
package yier.bubu.concurrency.ratelimit;

