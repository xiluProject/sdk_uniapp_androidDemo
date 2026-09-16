import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
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

/** 编译期补丁：屏蔽 DCloud uni-AD 干扰 toast（含 9001/9002 等错误码文本）。 */
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

    /** 收口 2：io.dcloud.p.g0.show() —— 文本可能在字段 e，也可能在 TextView 字段 b */
    private static final String G0_CLASS = "io/dcloud/p/g0.class";
    private static final String G0_SHOW = "show()V";
    private static final String G0_TEXT_FIELD = "e";
    private static final String G0_TEXTVIEW_FIELD = "b";
    private static final String G0_VIEW_FIELD = "a";
    private static final String TEXTVIEW_DESC = "Landroid/widget/TextView;";

    /** 收口 3：io.dcloud.p.c$a.run() —— 直接 Toast.makeText(context, 字段 b, 0).show() */
    private static final String C_RUN_CLASS = "io/dcloud/p/c$a.class";
    private static final String C_RUN_METHOD = "run()V";
    private static final String C_TEXT_FIELD = "b";

    /** ToastCompat.show() —— DCloud 自有 Toast 包装类，所有提示收口于此，文案在内部 Toast 视图树中。 */
    private static final String TOAST_COMPAT_CLASS = "com/dcloud/android/widget/toast/ToastCompat.class";
    private static final String TOAST_COMPAT_SHOW = "show()V";

    /** 收口 5：io.dcloud.common.util.PdrUtil.toast(Context, String, Bitmap) */
    private static final String PDRUTIL_CLASS = "io/dcloud/common/util/PdrUtil.class";
    private static final String PDRUTIL_TOAST =
            "toast(Landroid/content/Context;Ljava/lang/String;Landroid/graphics/Bitmap;)V";

    /** WXModalUIModule 的 toast/alert/confirm/prompt —— 使用 fastjson JSONObject；-9001 该提示即源于此。 */
    private static final String WX_MODAL_CLASS = "com/taobao/weex/ui/module/WXModalUIModule.class";
    private static final String WX_FASTJSON = "Lcom/alibaba/fastjson/JSONObject;";
    private static final String WX_FILTER_DESC = "(Lcom/alibaba/fastjson/JSONObject;)Z";
    private static final String[] WX_METHODS = {
            "toast", "alert", "confirm", "prompt"
    };

    /** 所有收口类（跨 AAR，用于校验补丁是否真的插进去） */
    private static final String[] ALL_TARGET_CLASSES = {
            TOAST_CLASS, G0_CLASS, C_RUN_CLASS, TOAST_COMPAT_CLASS, PDRUTIL_CLASS, WX_MODAL_CLASS
    };

    /** lib.5plus.base 里参与计数的收口类 */
    private static final String[] PATCH_TARGET_CLASSES = {
            TOAST_CLASS, G0_CLASS, C_RUN_CLASS, TOAST_COMPAT_CLASS, PDRUTIL_CLASS
    };

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: AdToastPatcher <in.aar> <out.aar> [expectedPatchedClasses]");
            System.exit(2);
        }
        int expected = args.length > 2 ? Integer.parseInt(args[2]) : PATCH_TARGET_CLASSES.length;
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

    /** 重新打包 classes.jar，并在两个收口处插入过滤判断 */
    private static byte[] patchClassesJar(byte[] jarBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(jarBytes.length);
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jarBytes));
             ZipOutputStream zout = new ZipOutputStream(out)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = readAll(zin);
                String name = e.getName();
                if (TOAST_CLASS.equals(name)) {
                    data = patchToastEntry(data);
                } else if (G0_CLASS.equals(name)) {
                    data = patchG0Show(data);
                } else if (C_RUN_CLASS.equals(name)) {
                    data = patchCRun(data);
                } else if (TOAST_COMPAT_CLASS.equals(name)) {
                    data = patchToastCompat(data);
                } else if (PDRUTIL_CLASS.equals(name)) {
                    data = patchPdrUtil(data);
                } else if (WX_MODAL_CLASS.equals(name)) {
                    data = patchWxModal(data);
                }
                // 改动字节码后签名必然失效，直接丢弃
                if (name.startsWith("META-INF/")
                        && (name.endsWith(".SF") || name.endsWith(".RSA")
                        || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(e.getTime());
                zout.putNextEntry(copy);
                zout.write(data);
                zout.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 收口 1：NativeUIFeatureImpl —— NativeUI 总入口 + toast 落地方法 */
    private static byte[] patchToastEntry(byte[] classBytes) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            String key = mn.name + mn.desc;
            if (TOAST_METHOD.equals(key)) {
                // 文案不一定在 JSON 里（实测这条 uni-AD 提示的 options 只有 type/duration/style，
                // 正文是单独作为 String 参数传进来的），所以两个都判
                prependSilenceCheck(mn, TOAST_TEXT_ARG, "(Ljava/lang/String;)Z", null, null, null);
                prependSilenceCheck(mn, TOAST_JSON_ARG, "(Lorg/json/JSONObject;)Z", null, null, null);
            } else if (EXECUTE_METHOD.equals(key)) {
                // 总入口：toast / modal / alert / actionSheet / loading 全都过这里，
                // 按 JS 传参数组整体判断，覆盖 toast 以外的提示形态
                prependSilenceCheck(mn, EXECUTE_ARGS_ARG, "([Ljava/lang/String;)Z", null, null, null);
            }
        }
        return write(cn);
    }

    /** 收口 2：io.dcloud.p.g0.show() 入口判断消息字段/视图树 */
    private static byte[] patchG0Show(byte[] classBytes) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (G0_SHOW.equals(mn.name + mn.desc)) {
                // 文案可能在：构造参数(字段 e) / setText 进的 TextView(字段 b) /
                // Toast 视图树(字段 a) / 框架 Toast 自带的 View(getView())，全部判一遍
                prependSilenceCheck(mn, 0, "(Ljava/lang/String;)Z",
                        G0_CLASS.replace(".class", ""), G0_TEXT_FIELD, "Ljava/lang/String;");
                prependSilenceCheck(mn, 0, "(Landroid/widget/TextView;)Z",
                        G0_CLASS.replace(".class", ""), G0_TEXTVIEW_FIELD, TEXTVIEW_DESC);
                prependSilenceCheck(mn, 0, "(Landroid/view/View;)Z",
                        G0_CLASS.replace(".class", ""), G0_VIEW_FIELD, "Landroid/view/View;");
                prependCallSilenceCheck(mn, "android/widget/Toast", "getView", "()Landroid/view/View;");
            }
        }
        return write(cn);
    }

    /** 收口 3：io.dcloud.p.c$a.run() —— 直接 Toast.makeText(context, 字段 b, 0).show() */
    private static byte[] patchCRun(byte[] classBytes) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (C_RUN_METHOD.equals(mn.name + mn.desc)) {
                prependSilenceCheck(mn, 0, "(Ljava/lang/String;)Z",
                        C_RUN_CLASS.replace(".class", ""), C_TEXT_FIELD, "Ljava/lang/String;");
            }
        }
        return write(cn);
    }

    /** 收口 4：com.dcloud.android.widget.toast.ToastCompat.show() —— 按内部 Toast 的视图树判断 */
    private static byte[] patchToastCompat(byte[] classBytes) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (TOAST_COMPAT_SHOW.equals(mn.name + mn.desc)) {
                prependCallSilenceCheck(mn, TOAST_COMPAT_CLASS.replace(".class", ""),
                        "getView", "()Landroid/view/View;");
            }
        }
        return write(cn);
    }

    /** 收口 5：PdrUtil.toast(Context, String, Bitmap) —— 直接判 String 参数 */
    private static byte[] patchPdrUtil(byte[] classBytes) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (PDRUTIL_TOAST.equals(mn.name + mn.desc)) {
                prependSilenceCheck(mn, 1, "(Ljava/lang/String;)Z", null, null, null);
            }
        }
        return write(cn);
    }

    /** 在方法入口插入静默检查：if (AdToastFilter.shouldSilence(arg)) return 默认值。 */
    private static void prependSilenceCheck(MethodNode mn, int argIndex, String filterDesc,
                                            String fieldOwner, String fieldName, String fieldDesc) {
        AbstractInsnNode first = mn.instructions.getFirst();
        if (first == null) {
            return;
        }
        LabelNode cont = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, argIndex));
        if (fieldOwner != null && fieldName != null) {
            pre.add(new FieldInsnNode(Opcodes.GETFIELD, fieldOwner, fieldName, fieldDesc));
        }
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FILTER, "shouldSilence", filterDesc, false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, cont));
        pre.add(returnDefault(mn));
        pre.add(cont);
        mn.instructions.insertBefore(first, pre);
    }

    /** 收口 6：weex 模态模块的 toast/alert/confirm/prompt —— 判 fastjson 参数 */
    private static byte[] patchWxModal(byte[] classBytes) {
        ClassNode cn = new ClassNode(Opcodes.ASM9);
        new ClassReader(classBytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            for (String name : WX_METHODS) {
                if (name.equals(mn.name) && mn.desc.startsWith("(" + WX_FASTJSON)) {
                    prependSilenceCheck(mn, 1, WX_FILTER_DESC, null, null, null);
                }
            }
        }
        return write(cn);
    }

    /** 变体：将 this.<owner>.<name>() 的返回值传给过滤器判断（如 Toast.getView()）。 */
    private static void prependCallSilenceCheck(MethodNode mn, String owner, String name, String desc) {
        AbstractInsnNode first = mn.instructions.getFirst();
        if (first == null) {
            return;
        }
        LabelNode cont = new LabelNode();
        InsnList pre = new InsnList();
        pre.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pre.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, name, desc, false));
        pre.add(new MethodInsnNode(Opcodes.INVOKESTATIC, FILTER, "shouldSilence",
                "(Landroid/view/View;)Z", false));
        pre.add(new JumpInsnNode(Opcodes.IFEQ, cont));
        pre.add(returnDefault(mn));
        pre.add(cont);
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

    /** 校验补丁是否真的插进去了（数一下几个收口类里对过滤器的调用） */
    private static int countHits(byte[] jarBytes) throws Exception {
        int hits = 0;
        try (java.util.zip.ZipInputStream zin =
                     new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(jarBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                byte[] data = readAll(zin);
                if (isPatchTarget(e.getName())) {
                    String s = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (s.contains(FILTER)) {
                        hits++;
                    }
                }
            }
        }
        return hits;
    }

    /** 是否为参与计数的收口类 */
    private static boolean isPatchTarget(String entryName) {
        for (String target : ALL_TARGET_CLASSES) {
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
