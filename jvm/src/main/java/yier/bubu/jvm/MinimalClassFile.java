package yier.bubu.jvm;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class MinimalClassFile {
    private MinimalClassFile() {
    }

    static byte[] emptyClass(String fullyQualifiedClassName) {
        String internalName = fullyQualifiedClassName.replace('.', '/');
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(256);
            DataOutputStream out = new DataOutputStream(baos);

            out.writeInt(0xCAFEBABE);
            out.writeShort(0); // minor_version
            out.writeShort(52); // major_version (Java 8)

            // Constant pool:
            // 1: Utf8 thisClass
            // 2: Class #1
            // 3: Utf8 java/lang/Object
            // 4: Class #3
            // 5: Utf8 <init>
            // 6: Utf8 ()V
            // 7: NameAndType #5:#6
            // 8: Methodref #4:#7
            // 9: Utf8 Code
            out.writeShort(10); // constant_pool_count (9 entries + 1)

            writeUtf8(out, internalName); // #1
            writeClass(out, 1); // #2
            writeUtf8(out, "java/lang/Object"); // #3
            writeClass(out, 3); // #4
            writeUtf8(out, "<init>"); // #5
            writeUtf8(out, "()V"); // #6
            writeNameAndType(out, 5, 6); // #7
            writeMethodRef(out, 4, 7); // #8
            writeUtf8(out, "Code"); // #9

            out.writeShort(0x0001); // access_flags (public)
            out.writeShort(2); // this_class (#2)
            out.writeShort(4); // super_class (#4 -> java/lang/Object)

            out.writeShort(0); // interfaces_count
            out.writeShort(0); // fields_count

            out.writeShort(1); // methods_count
            out.writeShort(0x0001); // access_flags (public)
            out.writeShort(5); // name_index (<init>)
            out.writeShort(6); // descriptor_index (()V)
            out.writeShort(1); // attributes_count

            // Code attribute
            out.writeShort(9); // attribute_name_index ("Code")
            out.writeInt(17); // attribute_length
            out.writeShort(1); // max_stack
            out.writeShort(1); // max_locals
            out.writeInt(5); // code_length
            out.writeByte(0x2a); // aload_0
            out.writeByte(0xb7); // invokespecial
            out.writeShort(8); // Methodref #8
            out.writeByte(0xb1); // return
            out.writeShort(0); // exception_table_length
            out.writeShort(0); // attributes_count

            out.writeShort(0); // class attributes_count
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void writeUtf8(DataOutputStream out, String s) throws IOException {
        out.writeByte(1); // CONSTANT_Utf8
        out.writeUTF(s);
    }

    private static void writeClass(DataOutputStream out, int nameIndex) throws IOException {
        out.writeByte(7); // CONSTANT_Class
        out.writeShort(nameIndex);
    }

    private static void writeNameAndType(DataOutputStream out, int nameIndex, int descIndex) throws IOException {
        out.writeByte(12); // CONSTANT_NameAndType
        out.writeShort(nameIndex);
        out.writeShort(descIndex);
    }

    private static void writeMethodRef(DataOutputStream out, int classIndex, int nameAndTypeIndex) throws IOException {
        out.writeByte(10); // CONSTANT_Methodref
        out.writeShort(classIndex);
        out.writeShort(nameAndTypeIndex);
    }
}

