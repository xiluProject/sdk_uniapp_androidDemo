import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 构建期处理离线 SDK 的 lib.5plus.base-release.aar：
 * 1) AndroidManifest.xml 里"广告相关配置"整块删掉
 * 2) res/xml/dcloud_gg_file_provider.xml 删掉
 * 3) --neutralize-classes：把 uni-ad 的类（io/dcloud/sdk/**、io/dcloud/feature/gg/**、
 *    io/dcloud/api/custom/**）的方法体全部清空（返回默认值），保留类名/签名/字段/构造函数，
 *    让运行时装得上、广告逻辑全哑，且不会 NoClassDefFoundError
 * 4) --strip-classes：直接删掉这些类（会崩，只在类被 stub 替换后才有意义）
 *
 * 只读原始 AAR，输出到别的路径；vendor 目录保持原样。
 */
public final class UniAdStripper {

    private static final String AAR_MANIFEST = "AndroidManifest.xml";
    private static final String AAR_CLASSES = "classes.jar";
    private static final String GG_RES = "res/xml/dcloud_gg_file_provider.xml";
    private static final String AD_BLOCK_REGEX =
            "(?s)<!--[^>]{0,80}-->\\s*<activity\\s+android:name=\"io\\.dcloud\\.sdk\\.activity\\.WebViewActivity\".*?</provider>\\s*<!--[^>]{0,80}-->";
    private static final String[] TARGET_PREFIXES = {
            "io/dcloud/sdk/", "io/dcloud/feature/gg/", "io/dcloud/api/custom/"
    };

    private static int neutralizedClasses = 0;
    private static int stubbedMethods = 0;
    private static int removedClasses = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: UniAdStripper <in.aar> <out.aar>"
                    + " [--neutralize-classes] [--strip-classes]");
            System.exit(2);
        }
        File in = new File(args[0]);
        File out = new File(args[1]);
        boolean neutralize = false;
        boolean stripClasses = false;
        for (String a : args) {
            if ("--neutralize-classes".equals(a)) {
                neutralize = true;
            }
            if ("--strip-classes".equals(a)) {
                stripClasses = true;
            }
        }
        if (!in.isFile()) {
            System.err.println("input aar not found: " + in);
            System.exit(3);
        }

        int removedManifestChars = 0;
        boolean droppedGgRes = false;
        boolean manifestTouched = false;

        out.getParentFile().mkdirs();
        ZipFile zip = new ZipFile(in);
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        try {
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();

                if (GG_RES.equals(name)) {
                    droppedGgRes = true;
                    continue;
                }
                if (AAR_MANIFEST.equals(name)) {
                    String text = new String(readAll(zip.getInputStream(e)), StandardCharsets.UTF_8);
                    String stripped = text.replaceAll(AD_BLOCK_REGEX, "");
                    removedManifestChars = text.length() - stripped.length();
                    manifestTouched = removedManifestChars > 0;
                    putStored(zos, name, stripped.getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                if (AAR_CLASSES.equals(name) && (stripClasses || neutralize)) {
                    byte[] jar = readAll(zip.getInputStream(e));
                    byte[] rewritten = rewriteClassesJar(jar, neutralize, stripClasses);
                    putStored(zos, name, rewritten);
                    continue;
                }
                zos.putNextEntry(new ZipEntry(name));
                copy(zip.getInputStream(e), zos);
                zos.closeEntry();
            }
        } finally {
            zos.close();
            zip.close();
        }

        System.out.println("UniAdStripper: " + in.getName() + " -> " + out.getAbsolutePath());
        System.out.println("  manifest ad block removed chars: " + removedManifestChars
                + (manifestTouched ? "" : "  (!! block not found, manifest unchanged)"));
        System.out.println("  dropped " + GG_RES + ": " + droppedGgRes);
        System.out.println("  neutralize-classes: " + neutralize + ", classes neutralized: "
                + neutralizedClasses + ", methods emptied: " + stubbedMethods);
        System.out.println("  strip-classes: " + stripClasses + ", classes removed: " + removedClasses);
        if (!manifestTouched) {
            System.exit(4);
        }
    }

    private static byte[] rewriteClassesJar(byte[] jarBytes, boolean neutralize, boolean strip)
            throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(jarBytes.length);
        ZipOutputStream jos = new ZipOutputStream(bos);
        ZipFile inner = new ZipFile(tempJar(jarBytes));
        try {
            java.util.Enumeration<? extends ZipEntry> ien = inner.entries();
            while (ien.hasMoreElements()) {
                ZipEntry ie = ien.nextElement();
                String n = ie.getName();
                if (!isTarget(n)) {
                    jos.putNextEntry(new ZipEntry(n));
                    copy(inner.getInputStream(ie), jos);
                    jos.closeEntry();
                    continue;
                }
                if (strip) {
                    removedClasses++;
                    continue;
                }
                byte[] data = readAll(inner.getInputStream(ie));
                byte[] outData = neutralize && n.endsWith(".class") ? neutralizeClass(data) : data;
                if (neutralize && n.endsWith(".class")) {
                    neutralizedClasses++;
                }
                jos.putNextEntry(new ZipEntry(n));
                jos.write(outData);
                jos.closeEntry();
            }
        } finally {
            inner.close();
            jos.close();
        }
        return bos.toByteArray();
    }

    /** 清空方法体：接口/抽象/本地方法原样保留；构造函数与静态初始化块保留（否则对象造不出来）。 */
    private static byte[] neutralizeClass(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig,
                                             String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, sig, exceptions);
                boolean keepBody = (access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
                        || "<init>".equals(name) || "<clinit>".equals(name);
                if (keepBody || mv == null) {
                    return mv;
                }
                stubbedMethods++;
                return new EmptyBodyMethodVisitor(mv, desc, access);
            }
        }, 0);
        return cw.toByteArray();
    }

    /** 丢弃原指令（含栈映射帧），只吐一个默认返回。 */
    private static final class EmptyBodyMethodVisitor extends MethodVisitor {
        private final String desc;

        EmptyBodyMethodVisitor(MethodVisitor mv, String desc, int access) {
            super(Opcodes.ASM9, mv);
            this.desc = desc;
        }

        @Override
        public void visitCode() {
            // 不转发原方法体
        }

        @Override
        public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack) {
        }

        @Override
        public void visitInsn(int opcode) {
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                                           org.objectweb.asm.Handle bsm, Object... bsmArgs) {
        }

        @Override
        public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
        }

        @Override
        public void visitLabel(org.objectweb.asm.Label label) {
        }

        @Override
        public void visitLdcInsn(Object value) {
        }

        @Override
        public void visitIincInsn(int varIndex, int increment) {
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, org.objectweb.asm.Label dflt,
                                         org.objectweb.asm.Label... labels) {
        }

        @Override
        public void visitLookupSwitchInsn(org.objectweb.asm.Label dflt, int[] keys,
                                          org.objectweb.asm.Label[] labels) {
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
        }

        @Override
        public void visitTryCatchBlock(org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                       org.objectweb.asm.Label handler, String type) {
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       org.objectweb.asm.Label start, org.objectweb.asm.Label end,
                                       int index) {
        }

        @Override
        public void visitLineNumber(int line, org.objectweb.asm.Label start) {
        }

        @Override
        public void visitEnd() {
            mv.visitCode();
            Type ret = Type.getReturnType(desc);
            switch (ret.getSort()) {
                case Type.VOID:
                    mv.visitInsn(Opcodes.RETURN);
                    break;
                case Type.LONG:
                    mv.visitInsn(Opcodes.LCONST_0);
                    mv.visitInsn(Opcodes.LRETURN);
                    break;
                case Type.DOUBLE:
                    mv.visitInsn(Opcodes.DCONST_0);
                    mv.visitInsn(Opcodes.DRETURN);
                    break;
                case Type.FLOAT:
                    mv.visitInsn(Opcodes.FCONST_0);
                    mv.visitInsn(Opcodes.FRETURN);
                    break;
                case Type.BOOLEAN:
                case Type.BYTE:
                case Type.CHAR:
                case Type.SHORT:
                case Type.INT:
                    mv.visitInsn(Opcodes.ICONST_0);
                    mv.visitInsn(Opcodes.IRETURN);
                    break;
                default:
                    mv.visitInsn(Opcodes.ACONST_NULL);
                    mv.visitInsn(Opcodes.ARETURN);
                    break;
            }
            int args = 1;
            for (Type t : Type.getArgumentTypes(desc)) {
                args += t.getSize();
            }
            mv.visitMaxs(Math.max(args, 2), args);
            mv.visitEnd();
        }
    }

    private static boolean isTarget(String entry) {
        for (String p : TARGET_PREFIXES) {
            if (entry.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static File tempJar(byte[] jar) throws IOException {
        File f = File.createTempFile("uniad-inner-", ".jar");
        f.deleteOnExit();
        try (OutputStream os = new FileOutputStream(f)) {
            os.write(jar);
        }
        return f;
    }

    private static void putStored(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry ne = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(data);
        ne.setMethod(ZipEntry.STORED);
        ne.setSize(data.length);
        ne.setCompressedSize(data.length);
        ne.setCrc(crc.getValue());
        zos.putNextEntry(ne);
        zos.write(data);
        zos.closeEntry();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        copy(is, bos);
        return bos.toByteArray();
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
        is.close();
    }

    private UniAdStripper() {
    }
}
