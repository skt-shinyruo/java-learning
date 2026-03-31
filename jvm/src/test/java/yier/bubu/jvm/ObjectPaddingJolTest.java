package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;
import org.openjdk.jol.vm.VirtualMachine;

/**
 * 这些测试用例不是为了“断言某个固定 offset”，而是为了用 JOL 把 padding 直观地打印出来。
 *
 * <p>注意：对象头大小、字段布局、对齐值等会受 JVM 参数与 JDK 版本影响（例如 CompressedOops/CompressedClassPointers、
 * -XX:ObjectAlignmentInBytes）。因此这里尽量只做“弱断言”（例如 losses>0、对齐倍数），避免变成不稳定单测。</p>
 *
 * <p>配套文档：jvm/docs/object-padding.md</p>
 */
public class ObjectPaddingJolTest {

    private static VirtualMachine vm;

    @BeforeClass
    public static void printVmDetails() {
        vm = VM.current();
        System.out.println(vm.details());
    }

    /**
     * 小案例：一个 byte 字段也会导致“对象总大小”向上补齐（tail/external padding）。
     */
    @Test
    public void tailPadding_singleByteField_shouldHaveExternalLosses() {
        ClassLayout layout = ClassLayout.parseClass(TailPadding1.class);
        System.out.println("== tailPadding_singleByteField");
        System.out.println(layout.toPrintable());

        Assert.assertEquals("pure byte fields should not introduce internal holes", 0L, layout.getLossesInternal());
        Assert.assertTrue("expect tail padding (external losses) > 0", layout.getLossesExternal() > 0);
        assertObjectSizeAligned(layout);
    }

    /**
     * 小案例：多个 byte 字段依然可能需要尾部补齐到对象对齐倍数。
     */
    @Test
    public void tailPadding_threeByteFields_shouldHaveExternalLosses() {
        ClassLayout layout = ClassLayout.parseClass(TailPadding3.class);
        System.out.println("== tailPadding_threeByteFields");
        System.out.println(layout.toPrintable());

        Assert.assertEquals("pure byte fields should not introduce internal holes", 0L, layout.getLossesInternal());
        Assert.assertTrue("expect tail padding (external losses) > 0", layout.getLossesExternal() > 0);
        assertObjectSizeAligned(layout);
    }

    /**
     * 小案例：byte + long 常见会触发对齐，出现 internal padding（或在不同布局策略下转化为 external padding）。
     */
    @Test
    public void padding_bytePlusLong_shouldHaveSomeLosses() {
        ClassLayout layout = ClassLayout.parseClass(InternalPaddingByteLong.class);
        System.out.println("== padding_bytePlusLong");
        System.out.println(layout.toPrintable());
        System.out.println("lossesInternal=" + layout.getLossesInternal()
                + " lossesExternal=" + layout.getLossesExternal()
                + " lossesTotal=" + layout.getLossesTotal());

        Assert.assertTrue("there should be some padding losses", layout.getLossesTotal() > 0);
        assertObjectSizeAligned(layout);
    }

    /**
     * 小案例：如果对象头是 12B（常见于压缩类指针），一个 int 字段可能“吃掉” klass gap（12..15），减少内部空洞。
     */
    @Test
    public void padding_intMayFillKlassGap_printLayout() {
        ClassLayout layout = ClassLayout.parseClass(FillKlassGapInt.class);
        System.out.println("== padding_intMayFillKlassGap");
        System.out.println(layout.toPrintable());
        System.out.println("headerSize=" + layout.headerSize()
                + " lossesInternal=" + layout.getLossesInternal()
                + " lossesExternal=" + layout.getLossesExternal()
                + " lossesTotal=" + layout.getLossesTotal());

        assertObjectSizeAligned(layout);
    }

    /**
     * 小案例：byte[3] 的元素区结束后，数组对象也会做尾部补齐（tail padding）。
     */
    @Test
    public void arrayPadding_byte3_shouldHaveExternalLosses() {
        byte[] arr = new byte[3];
        ClassLayout layout = ClassLayout.parseInstance(arr);
        System.out.println("== arrayPadding_byte3");
        System.out.println(layout.toPrintable(arr));

        Assert.assertTrue("expect tail padding (external losses) > 0", layout.getLossesExternal() > 0);
        assertObjectSizeAligned(layout);
    }

    /**
     * 小案例：long[1] 的元素区通常更“自然对齐”，但是否存在 external losses 取决于对象对齐值。
     */
    @Test
    public void arrayAlignment_long1_printLayout() {
        long[] arr = new long[1];
        ClassLayout layout = ClassLayout.parseInstance(arr);
        System.out.println("== arrayAlignment_long1");
        System.out.println(layout.toPrintable(arr));

        assertObjectSizeAligned(layout);
    }

    private static void assertObjectSizeAligned(ClassLayout layout) {
        int alignment = vm.objectAlignment();
        Assert.assertTrue("instanceSize should be aligned to objectAlignment (" + alignment + ")",
                layout.instanceSize() % alignment == 0);
    }

    static final class TailPadding1 {
        byte x;
    }

    static final class TailPadding3 {
        byte x;
        byte y;
        byte z;
    }

    static final class InternalPaddingByteLong {
        byte x;
        long y;
    }

    static final class FillKlassGapInt {
        int x;
        long y;
    }
}

