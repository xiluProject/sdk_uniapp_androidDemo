import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** 编译期补丁：在 NativeUI 总入口按内容静默 uni-AD 噪音提示 */
public final class AdToastPatcher {

    /** App 侧的过滤器（本仓库源码，见 app/src/main/java/com/qunze/xiju/adfix/AdToastFilter.java） */
    private static final String FILTER = "com/qunze/xiju/adfix/AdToastFilter";

    /** uni.showToast 的落地方法：private void a(IApp, IWebview, String, JSONObject) */
    private static final String TOAST_CLASS = "io/dcloud/feature/ui/nativeui/NativeUIFeatureImpl.class";
    private static final String TOAST_METHOD =
            "a(Lio/dcloud/common/DHInterface/IApp;Lio/dcloud/common/DHInterface/IWebview;Ljava/lang/String;Lorg/json/JSONObject;)V";
    private static final int TOAST_TEXT_ARG = 3;              // 文案（rich text markup）
    private static final int TOAST_JSON_ARG = 4;              // 样式参数（type/duration/style）
    private static final String EXECUTE_METHOD =
            "execute(Lio/dcloud/common/DHInterface/IWebview;Ljava/lang/String;[Ljava/lang/String;)Ljava/lang/String;";
    private static final int EXECUTE_ARGS_ARG = 3;            // this=0, IWebview=1, action=2, args=3

    /** 全部收口类（跨 AAR，用于校验补丁是否真的插进去） */
    private static final String[] TARGET_CLASSES = { TOAST_CLASS };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AdToastPatcher <in.aar> <out.aar> [expectedPatchedClasses]");
            System.exit(2);
        }
        int expected = args.length > 2 ? Integer.parseInt(args[2]) : TARGET_CLASSES.length;
        File in = new File(args[0]);
        File out = new File(args[1]);
        File parent = out.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("cannot create " + parent);
        }

        int patched = 0;
        try (ZipFile aar = new ZipFile(in);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out))) {
            java.util.Enumeration<? extends ZipEntry> it = aar.entries();
            while (it.hasMoreElements()) {
                ZipEntry e = it.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                byte[] data = readAll(aar.getInputStream(e));
                if ("classes.jar".equals(e.getName())) {
                    byte[] jar = patchClassesJar(data);
                    patched = countHits(jar);
                    data = jar;
                }
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                zos.putNextEntry(copy);
                zos.write(data);
                zos.closeEntry();
            }
        }
        if (patched != expected) {
            throw new IllegalStateException("expected " + expected + " patched classes, got " + patched
                    + " —— DCloud AAR 版本变了？补丁需要重新确认");
        }
        System.out.println("[AdToastPatcher] patched " + patched + " points -> " + out.getAbsolutePath());
    }

    /** 重新打包 classes.jar，并在收口处插入过滤判断 */
    private static byte[] patchClassesJar(byte[] jarBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(jarBytes.length);
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jarBytes));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = readAll(zin);
                if (TOAST_CLASS.equals(e.getName())) {
                    data = patchToastEntry(data);
                }
                // 改过 class 后签名失效，直接丢弃
                if (e.getName().startsWith("META-INF/")
                        && (e.getName().endsWith(".SF") || e.getName().endsWith(".RSA")
                        || e.getName().endsWith(".DSA") || e.getName().endsWith(".EC"))) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(e.getName());
                copy.setTime(e.getTime());
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** NativeUIFeatureImpl —— NativeUI 总入口 + toast 落地方法 */
    private static byte[] patchToastEntry(byte[] classBytes) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            String key = mn.name + mn.desc;
            if (TOAST_METHOD.equals(key)) {
                // 文案不一定在 JSON 里，两个都判
                prependNote(mn, "NativeUIFeatureImpl.a(toast)");
                prependSilenceCheck(mn, TOAST_TEXT_ARG, "(Ljava/lang/String;)Z");
                prependSilenceCheck(mn, TOAST_JSON_ARG, "(Lorg/json/JSONObject;)Z");
            } else if (EXECUTE_METHOD.equals(key)) {
                // 总入口：toast / modal / alert / actionSheet / loading 全都过这里，
                // 按 JS 传参数组整体判断，覆盖 toast 以外的提示形态
                prependNote(mn, "NativeUIFeatureImpl.execute");
                prependSilenceCheck(mn, EXECUTE_ARGS_ARG, "([Ljava/lang/String;)Z");
            }
        }
        return write(cn);
    }

    /** 在方法入口插入静默检查：if (AdToastFilter.shouldSilence(arg)) return 默认值。 */
    private static void prependSilenceCheck(MethodNode mn, int argIndex, String filterDesc) {
        AbstractInsnNode first = mn.instructions.getFirst();
        if (first == null) {
            return;
        }
        LabelNode cont = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, argIndex));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FILTER, "shouldSilence", filterDesc, false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, cont));
        pre.add(returnDefault(mn));
        pre.add(cont);
        mn.instructions.insertBefore(first, pre);
    }

    /** 变体：把常量字符串作为参数传给静态方法（只记录、不分支，用于常开探针） */
    private static void prependNote(MethodNode mn, String label) {
        AbstractInsnNode first = mn.instructions.getFirst();
        if (first == null) {
            return;
        }
        InsnList pre = new InsnList();
        pre.add(new LdcInsnNode(label));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FILTER, "noteToastEvent",
                "(Ljava/lang/String;)V", false));
        mn.instructions.insertBefore(first, pre);
    }

    /** 按方法返回类型生成"空返回值"（execute 返回 String，必须 areturn null；void 才 return） */
    private static InsnList returnDefault(MethodNode mn) {
        InsnList l = new InsnList();
        switch (org.objectweb.asm.Type.getReturnType(mn.desc).getSort()) {
            case org.objectweb.asm.Type.VOID:
                l.add(new InsnNode(Opcodes.RETURN));
                break;
            case org.objectweb.asm.Type.BOOLEAN:
            case org.objectweb.asm.Type.BYTE:
            case org.objectweb.asm.Type.CHAR:
            case org.objectweb.asm.Type.SHORT:
            case org.objectweb.asm.Type.INT:
                l.add(new InsnNode(Opcodes.ICONST_0));
                l.add(new InsnNode(Opcodes.IRETURN));
                break;
            case org.objectweb.asm.Type.LONG:
                l.add(new InsnNode(Opcodes.LCONST_0));
                l.add(new InsnNode(Opcodes.LRETURN));
                break;
            case org.objectweb.asm.Type.FLOAT:
                l.add(new InsnNode(Opcodes.FCONST_0));
                l.add(new InsnNode(Opcodes.FRETURN));
                break;
            case org.objectweb.asm.Type.DOUBLE:
                l.add(new InsnNode(Opcodes.DCONST_0));
                l.add(new InsnNode(Opcodes.DRETURN));
                break;
            default:
                l.add(new InsnNode(Opcodes.ACONST_NULL));
                l.add(new InsnNode(Opcodes.ARETURN));
                break;
        }
        return l;
    }

    private static byte[] write(ClassNode cn) {
        // 插入了新分支 → 必须重算栈帧；getCommonSuperClass 返回 Object，避免 ASM 去加载
        // Android 平台类（构建期 classpath 里没有）
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return "java/lang/Object";
            }
        };
        cn.accept(cw);
        return cw.toByteArray();
    }

    /** 校验补丁是否真的插进去了（收口类含过滤器引用，按引用计数） */
    private static int countHits(byte[] jarBytes) throws Exception {
        int hits = 0;
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = readAll(zin);
                if (!isPatchTarget(e.getName())) {
                    continue;
                }
                String s = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (s.contains(FILTER)) {
                    hits++;
                }
            }
        }
        return hits;
    }

    /** 是否为参与计数的收口类 */
    private static boolean isPatchTarget(String entryName) {
        for (String target : TARGET_CLASSES) {
            if (target.equals(entryName)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private AdToastPatcher() {
    }
}
