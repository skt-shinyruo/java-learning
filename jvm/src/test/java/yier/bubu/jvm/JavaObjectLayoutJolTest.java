package yier.bubu.jvm;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;
import org.openjdk.jol.vm.VirtualMachine;

/**
 * 用 JOL 直观打印“对象/数组在内存中的样子”。
 *
 * <p>注意：对象头大小、字段布局、对齐值等会受 JVM 参数与 JDK 版本影响（例如 CompressedOops/CompressedClassPointers、
 * -XX:ObjectAlignmentInBytes）。因此这里不做固定 offset 的强断言，只做对齐等弱断言。</p>
 *
 * <p>配套文档：jvm/docs/java-object-layout.md</p>
 */
public class JavaObjectLayoutJolTest {

    private static VirtualMachine vm;

    @BeforeClass
    public static void printVmDetails() {
        vm = VM.current();
        System.out.println(vm.details());
    }

    @Test
    public void layout_emptyObject_printLayout() {
        ClassLayout layout = ClassLayout.parseClass(Empty.class);
        System.out.println("== layout_emptyObject");
        System.out.println(layout.toPrintable());
        assertObjectSizeAligned(layout);
    }

    @Test
    public void layout_oneInt_printLayout() {
        ClassLayout layout = ClassLayout.parseClass(OneInt.class);
        System.out.println("== layout_oneInt");
        System.out.println(layout.toPrintable());
        assertObjectSizeAligned(layout);
    }

    @Test
    public void layout_oneLong_printLayout() {
        ClassLayout layout = ClassLayout.parseClass(OneLong.class);
        System.out.println("== layout_oneLong");
        System.out.println(layout.toPrintable());
        assertObjectSizeAligned(layout);
    }

    @Test
    public void layout_oneRef_printLayout() {
        ClassLayout layout = ClassLayout.parseClass(OneRef.class);
        System.out.println("== layout_oneRef");
        System.out.println(layout.toPrintable());
        assertObjectSizeAligned(layout);
    }

    @Test
    public void layout_objectArray3_printLayout() {
        Object[] arr = new Object[3];
        ClassLayout layout = ClassLayout.parseInstance(arr);
        System.out.println("== layout_objectArray3");
        System.out.println(layout.toPrintable(arr));
        assertObjectSizeAligned(layout);
    }

    @Test
    public void layout_intArray3_printLayout() {
        int[] arr = new int[3];
        ClassLayout layout = ClassLayout.parseInstance(arr);
        System.out.println("== layout_intArray3");
        System.out.println(layout.toPrintable(arr));
        assertObjectSizeAligned(layout);
    }

    private static void assertObjectSizeAligned(ClassLayout layout) {
        int alignment = vm.objectAlignment();
        Assert.assertTrue("instanceSize should be aligned to objectAlignment (" + alignment + ")",
                layout.instanceSize() % alignment == 0);
    }

    static final class Empty {
    }

    static final class OneInt {
        int x;
    }

    static final class OneLong {
        long x;
    }

    static final class OneRef {
        Object r;
    }
}

