/**
 * 单例（Singleton）示例。
 * <p>
 * 这里提供几种主流写法（均为线程安全），并补充一些常见变体/扩展：
 * <ul>
 *   <li>{@link yier.bubu.base.singleton.EagerSingleton}：饿汉式（类加载即初始化）</li>
 *   <li>{@link yier.bubu.base.singleton.LazySynchronizedSingleton}：懒汉式 + 同步方法</li>
 *   <li>{@link yier.bubu.base.singleton.DoubleCheckedLockingSingleton}：双重检查锁（DCL）</li>
 *   <li>{@link yier.bubu.base.singleton.HolderSingleton}：静态内部类（Initialization-on-demand holder）</li>
 *   <li>{@link yier.bubu.base.singleton.EnumSingleton}：枚举单例（推荐）</li>
 *   <li>{@link yier.bubu.base.singleton.AtomicReferenceSingleton}：CAS（AtomicReference）懒加载</li>
 *   <li>{@link yier.bubu.base.singleton.FieldUpdaterSingleton}：CAS（AtomicReferenceFieldUpdater）懒加载</li>
 *   <li>{@link yier.bubu.base.singleton.VarHandleSingleton}：CAS（VarHandle）懒加载（JDK 9+）</li>
 *   <li>{@link yier.bubu.base.singleton.UnsafeSingleton}：CAS（sun.misc.Unsafe）懒加载（内部 API）</li>
 *   <li>{@link yier.bubu.base.singleton.FutureTaskSingleton}：FutureTask/SingleFlight 懒加载</li>
 *   <li>{@link yier.bubu.base.singleton.StaticBlockSingleton}：静态代码块（饿汉变体）</li>
 *   <li>{@link yier.bubu.base.singleton.SerializableSingleton}：可序列化单例（readResolve）</li>
 *   <li>{@link yier.bubu.base.singleton.ThreadLocalSingleton}：线程内单例（每线程一个实例）</li>
 *   <li>{@link yier.bubu.base.singleton.KeyedMultiton}：Multiton（按 key 一个实例）</li>
 *   <li>{@link yier.bubu.base.singleton.Lazy}：通用 Lazy / 记忆化 Supplier（单例只是其特例）</li>
 * </ul>
 */
package yier.bubu.base.singleton;
